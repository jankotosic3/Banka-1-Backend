package interbank

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"banka1/trading-service-go/internal/api"
	"banka1/trading-service-go/internal/portfolio"

	"github.com/jackc/pgx/v5"
	"github.com/shopspring/decimal"
)

// Service exposes the interbank 2PC stock primitives + the option lifecycle +
// the public-stocks listing. It mirrors com.banka1.tradingservice.interbank
// (InterbankStockReservationService + the two transactional controllers) over
// interbank_stock_reservations, interbank_option_reservations, and the shared
// `portfolio` table. Every mutation is one gpdb.RunInTx (Java @Transactional).
//
// Portfolio rows are taken FOR UPDATE on reserve (by user+listing) and on
// commit/release (by id, via portfolio.FindByIDForUpdate) — a Go-port hardening;
// Java fetches the row by id without a lock in commit/release. Each interbank
// operation locks exactly one portfolio row, so there is no lock-ordering hazard.
type Service struct {
	repo          interbankRepo
	portfolio     interbankPortfolio
	market        interbankMarket
	runTx         txRunner
	routingNumber int
	logger        *slog.Logger
}

// NewService wires the interbank service over its repository + the shared
// portfolio repo + the market client (ticker resolution). routingNumber is this
// bank's interbank routing number (BANKA1_ROUTING_NUMBER, default 111), advertised
// in the public-stocks foreign-bank id.
func NewService(repo *Repository, portfolioRepo *portfolio.Repository, market interbankMarket, routingNumber int, logger *slog.Logger) *Service {
	return &Service{
		repo:          repo,
		portfolio:     portfolioRepo,
		market:        market,
		runTx:         poolTxRunner(repo.Pool()),
		routingNumber: routingNumber,
		logger:        logger,
	}
}

// ============================ stock 2PC (public) ===========================

// ReserveStock mirrors InterbankStockReservationService.reserveStock: reserves
// `quantity` of the owner's `ticker` shares (reserved_quantity += quantity,
// quantity untouched) and records a HELD interbank_stock_reservations row,
// returning the new reservation UUID. One transaction.
func (s *Service) ReserveStock(ctx context.Context, ownerUserID int64, ticker string, quantity, transactionIDRouting int, transactionIDLocal string) (string, error) {
	var reservationID string
	err := s.runTx(ctx, func(tx pgx.Tx) error {
		id, e := s.reserveStockTx(ctx, tx, ownerUserID, ticker, quantity, transactionIDRouting, transactionIDLocal)
		reservationID = id
		return e
	})
	if err != nil {
		return "", err
	}
	return reservationID, nil
}

// CommitStock mirrors InterbankStockReservationService.commitStock: the 2PC commit
// (quantity -= qty AND reserved_quantity -= qty, both floored at 0). Idempotent —
// an already-COMMITTED reservation is a no-op.
func (s *Service) CommitStock(ctx context.Context, reservationID string) error {
	return s.runTx(ctx, func(tx pgx.Tx) error {
		return s.commitStockTx(ctx, tx, reservationID)
	})
}

// ReleaseStock mirrors InterbankStockReservationService.releaseStock: the 2PC abort
// (reserved_quantity -= qty only; quantity untouched). Idempotent — an
// already-RELEASED reservation is a no-op.
func (s *Service) ReleaseStock(ctx context.Context, reservationID string) error {
	return s.runTx(ctx, func(tx pgx.Tx) error {
		return s.releaseStockTx(ctx, tx, reservationID)
	})
}

// CreditStock credits `quantity` of `ticker` shares to the local buyer's portfolio
// (FIX 1: asset-conservation on a cross-bank OTC option exercise). On the exercise
// 2PC our buyer pays the strike (legs a/b, reserved+committed via banking-core) and
// the partner-side seller delivers the shares (legs c/d). The buyer's incoming STOCK
// credit (leg d) has no raw-posting path on our side, so interbank-service routes it
// here on commit: without this the cash was debited but the shares never landed.
//
// The ticker is resolved to its listing id via market-service (the buyer may hold no
// prior position for it, so we cannot key off an existing portfolio row). The buyer
// position is upserted as a new lot at the listing's current price (falling back to
// the strike when the market price is unavailable), mirroring TransferOwnership's
// buyer-side merge. One transaction.
//
// Idempotency note: interbank-service only invokes this once per committed tx —
// Executor.CommitLocal flips the transaction to COMMITTED after running every ref and
// short-circuits on a re-commit, and the retry scheduler re-sends the COMMIT only to
// the partner, never re-running our local refs. So no extra dedup table is needed.
func (s *Service) CreditStock(ctx context.Context, buyerUserID int64, ticker string, quantity, transactionIDRouting int, transactionIDLocal string, strikePrice decimal.Decimal) error {
	if quantity <= 0 {
		return api.NewOtcError(http.StatusNotFound, "Quantity must be positive: "+itoa(int64(quantity)))
	}
	if strings.TrimSpace(ticker) == "" {
		return api.NewOtcError(http.StatusNotFound, "Ticker must not be blank")
	}

	listing, err := s.market.ResolveStockListing(ctx, ticker)
	if err != nil {
		return err
	}
	if listing == nil {
		return api.NewOtcError(http.StatusNotFound, "No STOCK listing found for ticker "+ticker)
	}
	// Average purchase price for the new lot: prefer the listing's current price; fall
	// back to the contract strike when the market price is missing or non-positive.
	avg := listing.Price
	if avg.Sign() <= 0 {
		avg = strikePrice
	}

	return s.runTx(ctx, func(tx pgx.Tx) error {
		existing, err := s.portfolio.FindByUserIDAndListingIDForUpdate(ctx, tx, buyerUserID, listing.ListingID)
		if err != nil {
			return err
		}
		if existing == nil {
			if err := s.portfolio.Insert(ctx, tx, buyerUserID, listing.ListingID, "STOCK", quantity, avg); err != nil {
				return err
			}
			s.logger.Info("interbank creditStock (new lot)", "buyer", buyerUserID, "ticker", ticker,
				"qty", quantity, "listing", listing.ListingID, "avg", avg.String(),
				"txRouting", transactionIDRouting, "txLocal", transactionIDLocal)
			return nil
		}
		// Merge into the existing lot at a quantity-weighted average price.
		newQty := existing.Quantity + quantity
		mergedAvg := weightedAvg(existing.Quantity, existing.AveragePurchasePrice, quantity, avg)
		if err := s.portfolio.UpdateQuantityAndAvg(ctx, tx, existing.ID, newQty, mergedAvg); err != nil {
			return err
		}
		s.logger.Info("interbank creditStock (merge)", "buyer", buyerUserID, "ticker", ticker,
			"qty", quantity, "listing", listing.ListingID, "newQty", newQty, "avg", mergedAvg.String(),
			"txRouting", transactionIDRouting, "txLocal", transactionIDLocal)
		return nil
	})
}

// weightedAvg computes the quantity-weighted average price when merging an incoming
// lot (qtyB @ priceB) into an existing position (qtyA @ priceA). Degenerates to
// priceB when the combined quantity is non-positive (defensive; should not happen).
func weightedAvg(qtyA int, priceA decimal.Decimal, qtyB int, priceB decimal.Decimal) decimal.Decimal {
	total := qtyA + qtyB
	if total <= 0 {
		return priceB
	}
	sum := priceA.Mul(decimal.NewFromInt(int64(qtyA))).Add(priceB.Mul(decimal.NewFromInt(int64(qtyB))))
	return sum.Div(decimal.NewFromInt(int64(total)))
}

// reserveStockTx is the core reserve logic, run inside a caller-owned tx so the
// option lifecycle (reserveOption) can reserve within its own transaction (Java's
// @Transactional controller method joins the service's @Transactional reserveStock
// in one transaction).
func (s *Service) reserveStockTx(ctx context.Context, tx pgx.Tx, ownerUserID int64, ticker string, quantity, transactionIDRouting int, transactionIDLocal string) (string, error) {
	if quantity <= 0 {
		return "", api.NewOtcError(http.StatusNotFound, "Quantity must be positive: "+itoa(int64(quantity)))
	}
	if strings.TrimSpace(ticker) == "" {
		return "", api.NewOtcError(http.StatusNotFound, "Ticker must not be blank")
	}

	listingID, found, err := s.resolveListingByTicker(ctx, tx, ownerUserID, ticker)
	if err != nil {
		return "", err
	}
	if !found {
		return "", api.NewOtcError(http.StatusNotFound, "No portfolio position for user="+itoa(ownerUserID)+" ticker="+ticker)
	}
	p, err := s.portfolio.FindByUserIDAndListingIDForUpdate(ctx, tx, ownerUserID, listingID)
	if err != nil {
		return "", err
	}
	if p == nil {
		return "", api.NewOtcError(http.StatusNotFound, "No portfolio position for user="+itoa(ownerUserID)+" ticker="+ticker)
	}

	available := p.Quantity - p.ReservedQuantity
	if available < quantity {
		return "", api.NewOtcError(http.StatusNotFound,
			"Insufficient stock for reservation: have="+itoa(int64(available))+
				" need="+itoa(int64(quantity))+" (ticker="+ticker+")")
	}

	if err := s.portfolio.UpdateReservedQuantity(ctx, tx, p.ID, p.ReservedQuantity+quantity); err != nil {
		return "", err
	}
	reservationID := newUUIDv4()
	if err := s.repo.InsertStockReservation(ctx, tx, reservationID, transactionIDRouting, transactionIDLocal, p.ID, ticker, quantity); err != nil {
		return "", err
	}
	s.logger.Info("interbank reserveStock", "owner", ownerUserID, "ticker", ticker, "qty", quantity,
		"txRouting", transactionIDRouting, "txLocal", transactionIDLocal, "reservationId", reservationID)
	return reservationID, nil
}

// commitStockTx is the core commit logic, run inside a caller-owned tx.
func (s *Service) commitStockTx(ctx context.Context, tx pgx.Tx, reservationID string) error {
	res, err := s.repo.FindStockReservationByReservationID(ctx, tx, reservationID)
	if err != nil {
		return err
	}
	if res == nil {
		return api.NewOtcError(http.StatusNotFound, "Stock reservation not found: "+reservationID)
	}
	if res.Status == StatusCommitted {
		s.logger.Info("interbank commitStock: already COMMITTED — no-op", "reservationId", reservationID)
		return nil
	}
	if res.Status != StatusHeld {
		return api.NewOtcError(http.StatusConflict, "Cannot commit reservation "+reservationID+" in state "+res.Status)
	}

	p, err := s.portfolio.FindByIDForUpdate(ctx, tx, res.PortfolioID)
	if err != nil {
		return err
	}
	if p == nil {
		return api.NewOtcError(http.StatusNotFound, "Portfolio vanished: id="+itoa(res.PortfolioID))
	}
	qty := res.Quantity
	if err := s.portfolio.UpdateQuantityAndReserved(ctx, tx, p.ID, maxInt(0, p.Quantity-qty), maxInt(0, p.ReservedQuantity-qty)); err != nil {
		return err
	}
	if err := s.repo.FinalizeStockReservation(ctx, tx, reservationID, StatusCommitted); err != nil {
		return err
	}
	s.logger.Info("interbank commitStock", "reservationId", reservationID, "portfolio", p.ID, "qty", qty)
	return nil
}

// releaseStockTx is the core release logic, run inside a caller-owned tx.
func (s *Service) releaseStockTx(ctx context.Context, tx pgx.Tx, reservationID string) error {
	res, err := s.repo.FindStockReservationByReservationID(ctx, tx, reservationID)
	if err != nil {
		return err
	}
	if res == nil {
		return api.NewOtcError(http.StatusNotFound, "Stock reservation not found: "+reservationID)
	}
	if res.Status == StatusReleased {
		s.logger.Info("interbank releaseStock: already RELEASED — no-op", "reservationId", reservationID)
		return nil
	}
	if res.Status == StatusCommitted {
		return api.NewOtcError(http.StatusConflict, "Cannot release reservation "+reservationID+" — already COMMITTED")
	}

	p, err := s.portfolio.FindByIDForUpdate(ctx, tx, res.PortfolioID)
	if err != nil {
		return err
	}
	if p == nil {
		return api.NewOtcError(http.StatusNotFound, "Portfolio vanished: id="+itoa(res.PortfolioID))
	}
	qty := res.Quantity
	// Release returns only the reserved units — quantity is left untouched.
	if err := s.portfolio.UpdateReservedQuantity(ctx, tx, p.ID, maxInt(0, p.ReservedQuantity-qty)); err != nil {
		return err
	}
	if err := s.repo.FinalizeStockReservation(ctx, tx, reservationID, StatusReleased); err != nil {
		return err
	}
	s.logger.Info("interbank releaseStock", "reservationId", reservationID, "portfolio", p.ID, "qty", qty)
	return nil
}

// ============================ option lifecycle =============================

// ReserveOption mirrors InterbankOptionController.reserveOption (a @Transactional
// controller method): a thin wrapper over reserveStock keyed by negotiationId.
// Idempotent — an existing row for the negotiationId returns a no-op (204) without
// re-reserving or overriding the original stock reservation.
func (s *Service) ReserveOption(ctx context.Context, negotiationID string, sellerForeignID *string, ticker string, quantity int) error {
	return s.runTx(ctx, func(tx pgx.Tx) error {
		existing, err := s.repo.FindOptionReservationByNegotiationID(ctx, tx, negotiationID)
		if err != nil {
			return err
		}
		if existing != nil {
			s.logger.Info("interbank reserveOption idempotent — existing reservation",
				"negotiation", negotiationID, "status", existing.Status, "reservationId", existing.ReservationID)
			return nil
		}

		sellerUserID, err := parseUserID(sellerForeignID)
		if err != nil {
			return err
		}
		// negotiationId is used as transactionIdLocal, routing 0 (the option
		// lifecycle steps are not part of the TX 2PC routing protocol).
		reservationID, err := s.reserveStockTx(ctx, tx, sellerUserID, ticker, quantity, 0, negotiationID)
		if err != nil {
			return err
		}
		if err := s.repo.InsertOptionReservation(ctx, tx, negotiationID, reservationID, OptionReserved, sellerUserID, ticker, quantity); err != nil {
			return err
		}
		s.logger.Info("interbank reserveOption", "negotiation", negotiationID, "seller", sellerUserID,
			"ticker", ticker, "qty", quantity, "reservationId", reservationID)
		return nil
	})
}

// ExerciseOption mirrors InterbankOptionController.exerciseOption: commit the
// mapped stock reservation and flip the option to EXERCISED. Idempotent — a
// missing / already-EXERCISED / RELEASED reservation is a no-op (204).
func (s *Service) ExerciseOption(ctx context.Context, negotiationID string) error {
	return s.runTx(ctx, func(tx pgx.Tx) error {
		res, err := s.repo.FindOptionReservationByNegotiationID(ctx, tx, negotiationID)
		if err != nil {
			return err
		}
		if res == nil {
			s.logger.Warn("interbank exerciseOption: negotiation unknown — no-op", "negotiation", negotiationID)
			return nil
		}
		if res.Status == OptionExercised {
			s.logger.Info("interbank exerciseOption idempotent — already EXERCISED", "negotiation", negotiationID)
			return nil
		}
		if res.Status == OptionReleased {
			s.logger.Warn("interbank exerciseOption: negotiation in RELEASED state — no-op", "negotiation", negotiationID)
			return nil
		}
		if err := s.commitStockTx(ctx, tx, res.ReservationID); err != nil {
			return err
		}
		return s.repo.UpdateOptionReservationStatus(ctx, tx, negotiationID, OptionExercised)
	})
}

// ReleaseOption mirrors InterbankOptionController.releaseOption: release the mapped
// stock reservation and flip the option to RELEASED. Idempotent — a missing /
// already-RELEASED / EXERCISED reservation is a no-op (204).
func (s *Service) ReleaseOption(ctx context.Context, negotiationID string) error {
	return s.runTx(ctx, func(tx pgx.Tx) error {
		res, err := s.repo.FindOptionReservationByNegotiationID(ctx, tx, negotiationID)
		if err != nil {
			return err
		}
		if res == nil {
			s.logger.Warn("interbank releaseOption: negotiation unknown — no-op", "negotiation", negotiationID)
			return nil
		}
		if res.Status == OptionReleased {
			s.logger.Info("interbank releaseOption idempotent — already RELEASED", "negotiation", negotiationID)
			return nil
		}
		if res.Status == OptionExercised {
			s.logger.Warn("interbank releaseOption: negotiation already EXERCISED — no-op", "negotiation", negotiationID)
			return nil
		}
		if err := s.releaseStockTx(ctx, tx, res.ReservationID); err != nil {
			return err
		}
		return s.repo.UpdateOptionReservationStatus(ctx, tx, negotiationID, OptionReleased)
	})
}

// ============================== public-stocks =============================

// PublicStocks mirrors PublicStocksInternalController.getPublicStocks: every
// publicly-advertised STOCK position grouped by ticker (insertion order, matching
// the Java LinkedHashMap over the same Postgres row order), each seller tagged with
// this bank's routing number + "C-"+userId. A position whose ticker the market
// client cannot resolve (or whose advertised amount ≤ 0) is skipped.
func (s *Service) PublicStocks(ctx context.Context) ([]PublicStockEntry, error) {
	positions, err := s.portfolio.FindAllPublicStocks(ctx, nil)
	if err != nil {
		return nil, err
	}
	order := make([]string, 0)
	byTicker := make(map[string][]PublicStockSeller)
	for _, p := range positions {
		listing, lerr := s.market.GetListing(ctx, p.ListingID)
		if lerr != nil || listing == nil || listing.Ticker == nil {
			continue
		}
		ticker := *listing.Ticker
		amount := p.PublicQuantity
		if amount <= 0 {
			continue
		}
		seller := PublicStockSeller{
			Seller: ForeignBankId{RoutingNumber: s.routingNumber, ID: "C-" + itoa(p.UserID)},
			Amount: amount,
		}
		if _, ok := byTicker[ticker]; !ok {
			order = append(order, ticker)
		}
		byTicker[ticker] = append(byTicker[ticker], seller)
	}
	entries := make([]PublicStockEntry, 0, len(order))
	for _, ticker := range order {
		entries = append(entries, PublicStockEntry{Stock: StockDescription{Ticker: ticker}, Sellers: byTicker[ticker]})
	}
	return entries, nil
}

// ================================ helpers =================================

// resolveListingByTicker mirrors findPortfolioByOwnerAndTickerForUpdate's scan:
// iterate the user's positions and return the listingId whose market listing
// ticker matches (case-insensitive). Market-lookup failures skip that position
// (defensive, matches Java's try/catch-ignore).
func (s *Service) resolveListingByTicker(ctx context.Context, q portfolio.Querier, userID int64, ticker string) (int64, bool, error) {
	positions, err := s.portfolio.FindByUserID(ctx, q, userID)
	if err != nil {
		return 0, false, err
	}
	for _, p := range positions {
		listing, lerr := s.market.GetListing(ctx, p.ListingID)
		if lerr != nil || listing == nil || listing.Ticker == nil {
			continue
		}
		if strings.EqualFold(ticker, *listing.Ticker) {
			return p.ListingID, true, nil
		}
	}
	return 0, false, nil
}

// parseUserID mirrors InterbankOptionController.parseUserId: strip the "C-"/"E-"
// foreign-bank prefix (Tim 2 §3.2) and parse the remainder as the local userId.
// A nil (absent) sellerForeignId or a non-numeric value is an IllegalArgument → 404.
func parseUserID(foreignID *string) (int64, error) {
	if foreignID == nil {
		return 0, api.NewOtcError(http.StatusNotFound, "sellerForeignId must not be null")
	}
	fid := *foreignID
	numericPart := fid
	if strings.HasPrefix(fid, "C-") || strings.HasPrefix(fid, "E-") {
		numericPart = fid[2:]
	}
	v, err := strconv.ParseInt(numericPart, 10, 64)
	if err != nil {
		return 0, api.NewOtcError(http.StatusNotFound,
			"Invalid sellerForeignId, expected numeric or 'C-N'/'E-N' format: "+fid)
	}
	return v, nil
}

// newUUIDv4 generates a random RFC 4122 v4 UUID using crypto/rand (mirrors Java
// UUID.randomUUID() → canonical lowercase string; matches the otc reservations
// helper, avoiding a google/uuid dependency).
func newUUIDv4() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return hex.EncodeToString(b[:])
	}
	b[6] = (b[6] & 0x0f) | 0x40 // version 4
	b[8] = (b[8] & 0x3f) | 0x80 // variant 10
	return hex.EncodeToString(b[0:4]) + "-" +
		hex.EncodeToString(b[4:6]) + "-" +
		hex.EncodeToString(b[6:8]) + "-" +
		hex.EncodeToString(b[8:10]) + "-" +
		hex.EncodeToString(b[10:16])
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
