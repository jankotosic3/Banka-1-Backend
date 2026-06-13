// Command interbank-service runs the Banka 1 inter-bank HTTP service.
// It wires all pieces — config, DB, migrations, stores, clients, services,
// handlers, retry scheduler — into a single binary with graceful shutdown.
package main

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"
	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/api"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/client"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/config"
	grpcserver "github.com/raf-si-2025/banka-1-go/interbank-service/internal/grpc"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/mock"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/scheduler"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/service"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
	"github.com/raf-si-2025/banka-1-go/shared/auth"
	sharedLog "github.com/raf-si-2025/banka-1-go/shared/log"
	pgxpoolx "github.com/raf-si-2025/banka-1-go/shared/pgxpool"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "fatal: %v\n", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("load config: %w", err)
	}

	logger := sharedLog.New(sharedLog.LogConfig{
		Level: cfg.Server.LogLevel,
		JSON:  cfg.Server.LogJSON,
	})
	logger.Info("starting interbank-service",
		"port", cfg.Server.HTTPPort,
		"myRouting", cfg.Interbank.MyRoutingNumber,
		"displayName", cfg.Interbank.MyDisplayName,
		"partners", len(cfg.Interbank.Partners),
	)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// -------------------------------------------------------------------------
	// Postgres pool
	// -------------------------------------------------------------------------
	pool, err := pgxpoolx.New(ctx, pgxpoolx.Config{
		URL:      cfg.DB.URL,
		MaxConns: cfg.DB.MaxConns,
		MinConns: cfg.DB.MinConns,
	})
	if err != nil {
		return fmt.Errorf("pgxpool: %w", err)
	}
	defer pool.Close()

	// -------------------------------------------------------------------------
	// Goose migrations (uses database/sql; pgx/v5/stdlib registers "pgx")
	// -------------------------------------------------------------------------
	sqlDB, err := sql.Open("pgx", cfg.DB.URL)
	if err != nil {
		return fmt.Errorf("sql.Open for goose: %w", err)
	}
	if err := goose.SetDialect("postgres"); err != nil {
		sqlDB.Close()
		return fmt.Errorf("goose set dialect: %w", err)
	}
	if err := goose.Up(sqlDB, cfg.Server.MigrationsPath, goose.WithAllowMissing()); err != nil {
		sqlDB.Close()
		return fmt.Errorf("goose up: %w", err)
	}
	sqlDB.Close()
	logger.Info("migrations applied")

	// -------------------------------------------------------------------------
	// Stores
	// -------------------------------------------------------------------------
	msgStore := store.NewMessageStore(pool)
	negStore := store.NewNegotiationStore(pool)
	contractStore := store.NewContractStore(pool)
	txStore := store.NewTransactionStore(pool)

	// -------------------------------------------------------------------------
	// Auth: S2S issuer for internal (service-to-service) JWT calls
	// -------------------------------------------------------------------------
	s2sIssuer := auth.NewS2SIssuer(
		cfg.JWT.Issuer,
		"interbank-service",
		[]string{"SERVICE"},
		cfg.JWT.Secret,
		cfg.JWT.TTL,
	)

	// -------------------------------------------------------------------------
	// Internal clients (calling Java banking-core / trading / user)
	// -------------------------------------------------------------------------
	bcClient := client.NewBankingCoreClient(
		cfg.Interbank.Services.BankingCoreURL,
		s2sIssuer,
		cfg.Interbank.Services.Timeout,
	)
	tdClient := client.NewTradingClient(
		cfg.Interbank.Services.TradingURL,
		s2sIssuer,
		cfg.Interbank.Services.Timeout,
	)
	userClient := client.NewUserClient(
		cfg.Interbank.Services.UserURL,
		s2sIssuer,
		cfg.Interbank.Services.Timeout,
	)

	// -------------------------------------------------------------------------
	// Partner registry — satisfies auth.PartnerStore, service.PartnerLookup,
	// and service.PartnerNameResolver through a single *partnerRegistry.
	// -------------------------------------------------------------------------
	registry := newPartnerRegistry(cfg.Interbank.Partners)

	// -------------------------------------------------------------------------
	// Outbound HTTP client (inter-bank protocol — X-Api-Key signed)
	// -------------------------------------------------------------------------
	outboundHTTP := &http.Client{Timeout: cfg.Interbank.Outbound.Timeout}
	outboundClient := service.NewInterbankClient(
		cfg.Interbank.MyRoutingNumber,
		registry, // satisfies service.PartnerLookup
		msgStore,
		outboundHTTP,
		logger,
	)

	// -------------------------------------------------------------------------
	// Executor wiring
	// -------------------------------------------------------------------------
	// TransactionStore → ExecutorStore adapter (method rename).
	execStore := &txStoreAdapter{txStore}

	// BankingCoreClient → service.BankingCoreReserver adapter.
	// service.AccountInfo and client.AccountInfo are structurally identical but
	// live in different packages — the adapter translates between them.
	bcReserver := &bcAdapter{bcClient}

	// NegotiationStore → OptionNegotiationLookup adapter.
	// NewNegotiationSellerLookup is provided by the service package; it also
	// implements NegotiationReader for Validator use (used inside NewExecutor).
	// The contract store is wired in so the Validator can tell an exercisable accepted
	// option (closed negotiation + ACTIVE contract) apart from a dead one — without it,
	// every accepted cross-bank option would fail its exercise 2PC with OPTION_USED_OR_EXPIRED.
	negLookup := service.NewNegotiationSellerLookupWithContracts(negStore, contractStore)

	executor := service.NewExecutor(
		cfg.Interbank.MyRoutingNumber,
		execStore,
		bcReserver,
		tdClient, // *client.TradingClient satisfies service.TradingReserver directly
		negLookup,
		logger,
	)
	// FIX B — per-contract exercise idempotency: gate the (non-idempotent) exercise
	// settlement legs (seller strike CreditMonas, buyer CreditStock) on the contract status
	// so a retry with a NEW txId for an already-EXERCISED contract is a no-op (no double
	// credit). The claim is an atomic ACTIVE→EXERCISED CAS on interbank_contracts.
	executor.SetContractGate(&contractGateAdapter{contractStore})

	// -------------------------------------------------------------------------
	// OTC negotiation service — needs a CoordinatorIface.
	// Coordinator and OtcNegotiationService mutually depend on each other.
	// We break the cycle by injecting a *lazyCoordinator shim into OtcNegotiationService;
	// the shim's inner pointer is filled in after Coordinator is constructed.
	// -------------------------------------------------------------------------
	coordShim := &lazyCoordinator{}

	otcSvc := service.NewOtcNegotiationService(
		cfg.Interbank.MyRoutingNumber,
		negStore, // *store.NegotiationStore satisfies service.NegotiationStoreIface
		coordShim,
		logger,
	)

	// Coordinator uses BankingCoreClient for MONAS account resolution. The
	// sellerNegResolver maps a buyer-side contract's LOCAL negotiation id to the
	// SELLER bank's authoritative id (the option pseudo-account key on exercise);
	// bcReserver doubles as the off-wire buyer-strike reserver (LocalMonasReserver).
	sellerNegResolver := &sellerNegAdapter{negStore}
	coordinator := service.NewCoordinator(
		cfg.Interbank.MyRoutingNumber,
		executor,
		outboundClient,
		negStore,
		contractStore,     // *store.ContractStore satisfies service.ContractStoreIface
		bcReserver,        // satisfies service.BankingCoreAccountResolver
		sellerNegResolver, // satisfies service.SellerNegResolver
		bcReserver,        // satisfies service.LocalMonasReserver (Reserve/Commit/ReleaseMonas)
		logger,
	)
	// Fill shim now that coordinator is ready.
	coordShim.inner = coordinator

	// -------------------------------------------------------------------------
	// OTC outbound service (FE-facing cross-bank OTC wrapper)
	// -------------------------------------------------------------------------
	otcOutbound := service.NewOtcOutboundService(
		cfg.Interbank.MyRoutingNumber,
		negStore, // satisfies NegotiationStoreForOutbound
		outboundClient,
		otcSvc,
		registry, // satisfies service.PartnerNameResolver
		logger,
	)

	// -------------------------------------------------------------------------
	// OTC contracts service (FE-facing buyer-side option contracts) — list +
	// exercise. The exerciser is the same Coordinator used by OTC accept/payment.
	// -------------------------------------------------------------------------
	otcContracts := service.NewOtcContractsService(
		cfg.Interbank.MyRoutingNumber,
		contractStore, // *store.ContractStore satisfies ContractStoreForService
		coordinator,   // *service.Coordinator satisfies service.ContractExerciser
		logger,
	)

	// BuyerContractRecorder records the buyer's local option contract on a
	// successful inbound COMMIT_TX (cross-bank OTC option buy). It reads back the
	// committed tx postings, maps the partner negotiation id → our local mirror,
	// and inserts an ACTIVE contract (idempotent).
	buyerContractRecorder := service.NewBuyerContractRecorder(
		cfg.Interbank.MyRoutingNumber,
		execStore,     // *txStoreAdapter satisfies RecorderExecutorStore (FindTx)
		contractStore, // satisfies RecorderContractStore
		negStore,      // satisfies RecorderNegotiationStore
		logger,
	)

	// -------------------------------------------------------------------------
	// API handlers
	// -------------------------------------------------------------------------
	inboundHandler := api.NewInboundHandler(executor, msgStore, buyerContractRecorder, logger)
	otcHandler := api.NewOtcHandler(otcSvc, logger)

	// PublicStockHandler needs a PublicStockService; TradingClient satisfies it
	// via an adapter (GetPublicStocks returns client.PublicStockEntry, not
	// api.PublicStockEntry — adapter translates).
	pubStockHandler := api.NewPublicStockHandler(&pubStockAdapter{tdClient}, logger)

	// UserDisplayHandler needs a UserResolver; UserClient satisfies it via adapter.
	userDisplayHandler := api.NewUserDisplayHandler(
		cfg.Interbank.MyRoutingNumber,
		cfg.Interbank.MyDisplayName,
		&userResolver{userClient},
		logger,
	)

	otcOutboundHandler := api.NewOtcOutboundHandler(otcOutbound, logger)
	otcContractsHandler := api.NewOtcContractsHandler(otcContracts, logger)

	// PaymentHandler reuses the same Coordinator instance as OTC accept; it
	// validates the sender account via banking-core (bcReserver satisfies
	// service.BankingCoreReader) before running the outbound payment 2PC.
	paymentHandler := api.NewPaymentHandler(coordinator, bcReserver, logger)

	// -------------------------------------------------------------------------
	// HTTP router
	// -------------------------------------------------------------------------
	deps := api.ServerDeps{
		Partners:       registry, // satisfies auth.PartnerStore
		InboundHandler: inboundHandler,
		OtcHandler:     otcHandler,
		PublicStock:    pubStockHandler,
		UserDisplay:    userDisplayHandler,
		OtcOutbound:    otcOutboundHandler,
		OtcContracts:   otcContractsHandler,
		Payments:       paymentHandler,
		JWTSecret:      cfg.JWT.Secret,
	}
	router := api.NewRouter(deps)

	// Mount mock routes when enabled (dev / end-to-end test without real Banka 2).
	if cfg.Interbank.MockPartner.Enabled {
		logger.Info("mock Banka 2 controller enabled — mounting /_mock/banka2/*")
		// router is an http.Handler; we need the chi.Router underneath.
		// NewRouter returns http.Handler (wraps a chi.Router). To mount mock routes
		// on the same chi mux we rebuild the router via NewRouterWithMock.
		router = api.NewRouterWithMock(deps, mock.RegisterMockRoutes)
	}

	// -------------------------------------------------------------------------
	// Retry scheduler
	// -------------------------------------------------------------------------
	retrySched := scheduler.NewRetryScheduler(
		msgStore,
		outboundClient, // satisfies scheduler.Sender (has Resend method)
		cfg.Interbank.Retry.Interval,
		cfg.Interbank.Retry.MaxRetries,
		logger,
	)
	schedDone := make(chan error, 1)
	go func() { schedDone <- retrySched.Run(ctx) }()

	// -------------------------------------------------------------------------
	// HTTP server
	// -------------------------------------------------------------------------
	httpAddr := fmt.Sprintf(":%d", cfg.Server.HTTPPort)
	httpSrv := &http.Server{
		Addr:         httpAddr,
		Handler:      router,
		ReadTimeout:  cfg.Server.ReadTimeout,
		WriteTimeout: cfg.Server.WriteTimeout,
	}
	httpDone := make(chan error, 1)
	go func() {
		logger.Info("HTTP server listening", "addr", httpAddr)
		if listenErr := httpSrv.ListenAndServe(); !errors.Is(listenErr, http.ErrServerClosed) {
			httpDone <- listenErr
		}
		close(httpDone)
	}()

	// -------------------------------------------------------------------------
	// gRPC server
	// -------------------------------------------------------------------------
	grpcSrv := grpcserver.NewServer(grpcserver.Deps{
		MyRouting:     cfg.Interbank.MyRoutingNumber,
		MyDisplayName: cfg.Interbank.MyDisplayName,
		Executor:      executor,
		OtcService:    otcSvc,
		Coordinator:   coordinator,
		MessageStore:  msgStore,
		NegStore:      negStore,
		ContractStore: contractStore,
		Trading:       tdClient,
		User:          userClient,
		Log:           logger,
	})
	grpcAddr := fmt.Sprintf(":%d", cfg.Server.GRPCPort)
	grpcGoServer, grpcLis, err := grpcSrv.Listen(grpcAddr)
	if err != nil {
		return fmt.Errorf("grpc listen: %w", err)
	}
	grpcDone := make(chan error, 1)
	go func() {
		logger.Info("gRPC server listening", "addr", grpcAddr)
		if serveErr := grpcGoServer.Serve(grpcLis); serveErr != nil {
			grpcDone <- serveErr
		}
		close(grpcDone)
	}()

	// -------------------------------------------------------------------------
	// Wait for OS signal or fatal error
	// -------------------------------------------------------------------------
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)

	select {
	case s := <-sig:
		logger.Info("shutdown signal received", "signal", s)
	case err := <-httpDone:
		if err != nil {
			cancel()
			return fmt.Errorf("http server fatal: %w", err)
		}
	case err := <-grpcDone:
		if err != nil {
			cancel()
			return fmt.Errorf("grpc server fatal: %w", err)
		}
	case err := <-schedDone:
		if err != nil && !errors.Is(err, context.Canceled) {
			cancel()
			return fmt.Errorf("retry scheduler fatal: %w", err)
		}
	}

	// -------------------------------------------------------------------------
	// Graceful shutdown
	// -------------------------------------------------------------------------
	cancel() // stop scheduler
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), cfg.Server.ShutdownTimeout)
	defer shutdownCancel()

	grpcGoServer.GracefulStop()

	if err := httpSrv.Shutdown(shutdownCtx); err != nil {
		logger.Error("http server shutdown error", "err", err)
	}

	// Wait for scheduler goroutine to exit.
	select {
	case <-schedDone:
	case <-shutdownCtx.Done():
		logger.Warn("timed out waiting for retry scheduler to stop")
	}

	logger.Info("shutdown complete")
	return nil
}

// ---------------------------------------------------------------------------
// partnerRegistry — satisfies auth.PartnerStore + service.PartnerLookup
//                   + service.PartnerNameResolver
// ---------------------------------------------------------------------------

// partnerRegistry holds a static slice of partners built from config.
// It satisfies three interfaces needed by different packages:
//   - auth.PartnerStore  (Partners() []auth.Partner)
//   - service.PartnerLookup (FindByRouting(int) (*auth.Partner, error))
//   - service.PartnerNameResolver (DisplayName(int) string)
type partnerRegistry struct {
	partners []auth.Partner
}

func newPartnerRegistry(cfgPartners []config.Partner) *partnerRegistry {
	out := make([]auth.Partner, len(cfgPartners))
	for i, p := range cfgPartners {
		out[i] = auth.Partner{
			Routing:       p.Routing,
			DisplayName:   p.DisplayName,
			BaseURL:       p.BaseURL,
			InboundToken:  p.InboundToken,
			OutboundToken: p.OutboundToken,
		}
	}
	return &partnerRegistry{partners: out}
}

// Partners implements auth.PartnerStore.
func (r *partnerRegistry) Partners() []auth.Partner { return r.partners }

// FindByRouting implements service.PartnerLookup.
func (r *partnerRegistry) FindByRouting(routing int) (*auth.Partner, error) {
	for i := range r.partners {
		if r.partners[i].Routing == routing {
			cp := r.partners[i]
			return &cp, nil
		}
	}
	return nil, fmt.Errorf("%w: routing=%d", service.ErrPartnerNotFound, routing)
}

// DisplayName implements service.PartnerNameResolver.
func (r *partnerRegistry) DisplayName(routing int) string {
	for _, p := range r.partners {
		if p.Routing == routing {
			return p.DisplayName
		}
	}
	return fmt.Sprintf("Bank %d", routing)
}

// ---------------------------------------------------------------------------
// txStoreAdapter — adapts *store.TransactionStore to service.ExecutorStore
// ---------------------------------------------------------------------------

// ExecutorStore requires FindTx and UpdateTxStatus; TransactionStore uses
// FindByID and UpdateStatus. This adapter bridges the rename.
type txStoreAdapter struct{ s *store.TransactionStore }

func (a *txStoreAdapter) PersistPrepared(ctx context.Context, t *store.Transaction) error {
	return a.s.PersistPrepared(ctx, t)
}

func (a *txStoreAdapter) FindTx(ctx context.Context, routing int, id string) (*store.Transaction, error) {
	return a.s.FindByID(ctx, routing, id)
}

func (a *txStoreAdapter) UpdateTxStatus(ctx context.Context, routing int, id, status string) error {
	return a.s.UpdateStatus(ctx, routing, id, status)
}

// ---------------------------------------------------------------------------
// contractGateAdapter — adapts *store.ContractStore to service.ContractGate
// ---------------------------------------------------------------------------

// contractGateAdapter bridges the per-contract exercise idempotency gate (FIX B) to the
// contract store's atomic ACTIVE→EXERCISED claim. The method names differ only to keep the
// service-side interface name short.
type contractGateAdapter struct{ s *store.ContractStore }

func (a *contractGateAdapter) ClaimExercise(ctx context.Context, negotiationID string) (bool, bool, error) {
	return a.s.ClaimExerciseByNegotiation(ctx, negotiationID)
}

func (a *contractGateAdapter) ReleaseClaim(ctx context.Context, negotiationID string) error {
	return a.s.ReleaseExerciseClaimByNegotiation(ctx, negotiationID)
}

// ---------------------------------------------------------------------------
// sellerNegAdapter — adapts *store.NegotiationStore to service.SellerNegResolver
// ---------------------------------------------------------------------------

// sellerNegAdapter maps a buyer-side contract's LOCAL negotiation id to the SELLER
// bank's AUTHORITATIVE negotiation id (the option pseudo-account key on a cross-bank
// exercise). It reads our mirror negotiation row: when we are NOT authoritative we hold
// a non-authoritative mirror whose remote_negotiation_id is the seller-bank id. When we
// ARE authoritative (the option was issued here) there is no remote id; returning ""
// makes the Coordinator fall back to the local id (correct — we host the pseudo-account).
type sellerNegAdapter struct{ s *store.NegotiationStore }

func (a *sellerNegAdapter) SellerNegotiationID(ctx context.Context, localNegotiationID string) string {
	neg, err := a.s.FindByID(ctx, localNegotiationID)
	if err != nil || neg == nil {
		return ""
	}
	if neg.RemoteNegotiationID != nil && *neg.RemoteNegotiationID != "" {
		return *neg.RemoteNegotiationID
	}
	return ""
}

// ---------------------------------------------------------------------------
// bcAdapter — adapts *client.BankingCoreClient to service.BankingCoreReserver
// ---------------------------------------------------------------------------

// service.BankingCoreReader.ResolveAccount uses *service.AccountInfo but
// client.BankingCoreClient.ResolveAccount returns *client.AccountInfo.
// The two types are structurally identical; this adapter translates between them.
type bcAdapter struct{ c *client.BankingCoreClient }

func (a *bcAdapter) ResolveAccount(ctx context.Context, num string) (*service.AccountInfo, error) {
	info, err := a.c.ResolveAccount(ctx, num)
	if err != nil {
		return nil, err
	}
	return &service.AccountInfo{
		OwnerType:        info.OwnerType,
		OwnerID:          info.OwnerID,
		Currency:         info.Currency,
		AvailableBalance: info.AvailableBalance,
	}, nil
}

func (a *bcAdapter) FindAccountByOwnerAndCurrency(ctx context.Context, ownerID int64, currency string) (string, error) {
	return a.c.FindAccountByOwnerAndCurrency(ctx, ownerID, currency)
}

func (a *bcAdapter) ReserveMonas(ctx context.Context, accountNum, currency string, amount decimal.Decimal, txIDRouting int, txIDLocal string) (string, error) {
	return a.c.ReserveMonas(ctx, accountNum, currency, amount, txIDRouting, txIDLocal)
}

func (a *bcAdapter) CommitMonas(ctx context.Context, reservationID string) error {
	return a.c.CommitMonas(ctx, reservationID)
}

func (a *bcAdapter) CreditMonas(ctx context.Context, accountNum string, amount decimal.Decimal, clientID int64) error {
	return a.c.CreditMonas(ctx, accountNum, amount, clientID)
}

func (a *bcAdapter) ReleaseMonas(ctx context.Context, reservationID string) error {
	return a.c.ReleaseMonas(ctx, reservationID)
}

func (a *bcAdapter) RecordInterbankPayment(ctx context.Context, orderNumber, fromAccount, toAccount string, amount decimal.Decimal, currency, recipientName, paymentPurpose string) error {
	return a.c.RecordInterbankPayment(ctx, orderNumber, fromAccount, toAccount, amount, currency, recipientName, paymentPurpose)
}

// ---------------------------------------------------------------------------
// lazyCoordinator — breaks the OtcNegotiationService ↔ Coordinator cycle
// ---------------------------------------------------------------------------

// OtcNegotiationService needs a CoordinatorIface at construction time, but
// Coordinator needs OtcNegotiationService. We break the cycle by injecting a
// shim whose inner pointer is filled in after Coordinator is constructed.
type lazyCoordinator struct {
	inner service.CoordinatorIface
}

func (l *lazyCoordinator) AcceptNegotiation(ctx context.Context, neg *store.Negotiation) error {
	if l.inner == nil {
		return errors.New("lazyCoordinator: inner coordinator not set")
	}
	return l.inner.AcceptNegotiation(ctx, neg)
}

// ---------------------------------------------------------------------------
// pubStockAdapter — adapts *client.TradingClient to api.PublicStockService
// ---------------------------------------------------------------------------

type pubStockAdapter struct{ c *client.TradingClient }

func (a *pubStockAdapter) GetPublicStocks(ctx context.Context) ([]api.PublicStockEntry, error) {
	entries, err := a.c.GetPublicStocks(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]api.PublicStockEntry, 0, len(entries))
	for _, e := range entries {
		sellers := make([]api.SellerRow, len(e.Sellers))
		for i, s := range e.Sellers {
			sellers[i] = api.SellerRow{
				Seller: api.SellerID{RoutingNumber: s.Seller.RoutingNumber, ID: s.Seller.ID},
				Amount: s.Amount,
			}
		}
		out = append(out, api.PublicStockEntry{
			Stock:   api.StockRef{Ticker: e.Stock.Ticker},
			Sellers: sellers,
		})
	}
	return out, nil
}

// ---------------------------------------------------------------------------
// userResolver — adapts *client.UserClient to api.UserResolver
// ---------------------------------------------------------------------------

type userResolver struct{ c *client.UserClient }

func (u *userResolver) ResolveUser(ctx context.Context, userType string, id int64) (*api.UserDisplayInfo, error) {
	dto, err := u.c.ResolveUser(ctx, userType, id)
	if err != nil {
		return nil, err
	}
	displayName := dto.DisplayName
	if displayName == "" {
		displayName = dto.FirstName + " " + dto.LastName
	}
	return &api.UserDisplayInfo{DisplayName: displayName}, nil
}
