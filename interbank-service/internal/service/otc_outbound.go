package service

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
	"github.com/raf-si-2025/banka-1-go/shared/auth"
)

// ---------------------------------------------------------------------------
// DTOs — frontend-facing request / response types
// ---------------------------------------------------------------------------

// OutboundCreateRequest is the JSON body for
// POST /api/interbank/otc/negotiations.
// Unlike OtcOfferDto (X-Api-Key path), this is received from our Angular FE.
type OutboundCreateRequest struct {
	StockTicker         string          `json:"stockTicker"`
	SettlementDate      time.Time       `json:"settlementDate"`
	PriceCurrency       string          `json:"priceCurrency"`
	PricePerUnit        decimal.Decimal `json:"pricePerUnit"`
	PremiumCurrency     string          `json:"premiumCurrency"`
	Premium             decimal.Decimal `json:"premium"`
	SellerRoutingNumber int             `json:"sellerRoutingNumber"`
	SellerForeignID     string          `json:"sellerForeignId"`
	Amount              int             `json:"amount"`
	BuyerLocalUserID    int64           `json:"buyerLocalUserId"` // our user id → "C-N" prefix
}

// OutboundCounterRequest is the JSON body for
// PUT /api/interbank/otc/negotiations/{id}/counter.
type OutboundCounterRequest struct {
	SettlementDate  time.Time       `json:"settlementDate"`
	PriceCurrency   string          `json:"priceCurrency"`
	PricePerUnit    decimal.Decimal `json:"pricePerUnit"`
	PremiumCurrency string          `json:"premiumCurrency"`
	Premium         decimal.Decimal `json:"premium"`
	Amount          int             `json:"amount"`
}

// NegotiationView is the frontend-friendly negotiation representation.
// Corresponds to Java OutboundNegotiationResponse + OtcNegotiationDto combined.
type NegotiationView struct {
	LocalID              string                 `json:"localId"`
	RemoteID             *string                `json:"remoteId,omitempty"`
	IsAuthoritative      bool                   `json:"isAuthoritative"`
	CounterpartyBankCode int                    `json:"counterpartyBankCode"`
	CounterpartyBankName string                 `json:"counterpartyBankName,omitempty"`
	BuyerID              protocol.ForeignBankId `json:"buyerId"`
	SellerID             protocol.ForeignBankId `json:"sellerId"`
	LastModifiedBy       protocol.ForeignBankId `json:"lastModifiedBy"`
	StockTicker          string                 `json:"stockTicker"`
	PriceCurrency        string                 `json:"priceCurrency"`
	PricePerUnit         decimal.Decimal        `json:"pricePerUnit"`
	Amount               int                    `json:"amount"`
	SettlementDate       time.Time              `json:"settlementDate"`
	PremiumCurrency      string                 `json:"premiumCurrency"`
	Premium              decimal.Decimal        `json:"premium"`
	IsOngoing            bool                   `json:"isOngoing"`
	CreatedAt            time.Time              `json:"createdAt"`
	LastModifiedAt       time.Time              `json:"lastModifiedAt"`
}

// CounterResult is returned by CounterOutbound so the handler can propagate
// the partner's status code (204 / 4xx) back to the frontend.
type CounterResult struct {
	StatusCode int
	View       *NegotiationView // non-nil when partner returned 2xx
}

// ---------------------------------------------------------------------------
// Interface seams for the service layer
// ---------------------------------------------------------------------------

// NegotiationStoreForOutbound is the persistence seam for OtcOutboundService.
// The concrete *store.NegotiationStore satisfies this at wiring time; tests use fakes.
type NegotiationStoreForOutbound interface {
	Insert(ctx context.Context, n *store.Negotiation) error
	FindByID(ctx context.Context, id string) (*store.Negotiation, error)
	FindByAuthoritativeRef(ctx context.Context, routing int, id string) (*store.Negotiation, error)
	UpdateCounter(ctx context.Context, n *store.Negotiation) error
	MarkClosed(ctx context.Context, id string) error
	ListForUser(ctx context.Context, userForeignID string, includeAll bool) ([]*store.Negotiation, error)
}

// OtcOutboundClient is the subset of *InterbankClient consumed by OtcOutboundService.
type OtcOutboundClient interface {
	OutboundCreateNegotiation(ctx context.Context, partnerRouting int, offer OtcOfferDto) (*protocol.ForeignBankId, error)
	OutboundPutCounter(ctx context.Context, partnerRouting int, negID protocol.ForeignBankId, offer OtcOfferDto) (int, error)
	OutboundAccept(ctx context.Context, partnerRouting int, negID protocol.ForeignBankId) (int, error)
	OutboundDelete(ctx context.Context, partnerRouting int, negID protocol.ForeignBankId) error
	OutboundFetchPublicStock(ctx context.Context, partnerRouting int) ([]protocol.PublicStockEntry, error)
}

// PartnerNameResolver resolves a routing number to a display name.
// Implemented by a thin wrapper around auth.PartnerStore.
type PartnerNameResolver interface {
	DisplayName(routing int) string
}

// ---------------------------------------------------------------------------
// OtcOutboundService
// ---------------------------------------------------------------------------

// OtcOutboundService implements the FE-facing /api/interbank/otc/* wrapper.
// Corresponds to Java InterbankOtcOutboundService (PR_33 Phase A).
//
// Responsibility split:
//   - This service translates frontend DTOs ↔ protocol OtcOfferDto.
//   - LocalAuthoritative negotiations (we received them from a partner) can be
//     counter-offered locally; the partner will pick up changes on its next GET.
//   - Mirror negotiations (we initiated them) route outbound through InterbankClient.
type OtcOutboundService struct {
	myRouting int
	store     NegotiationStoreForOutbound
	client    OtcOutboundClient
	otcSvc    *OtcNegotiationService // for local authoritative counter-offer path
	partners  PartnerNameResolver
	log       *slog.Logger
}

// NewOtcOutboundService constructs the service.
// partners may be nil; in that case CounterpartyBankName will be empty.
func NewOtcOutboundService(
	myRouting int,
	store NegotiationStoreForOutbound,
	client OtcOutboundClient,
	otcSvc *OtcNegotiationService,
	partners PartnerNameResolver,
	log *slog.Logger,
) *OtcOutboundService {
	if log == nil {
		log = slog.Default()
	}
	return &OtcOutboundService{
		myRouting: myRouting,
		store:     store,
		client:    client,
		otcSvc:    otcSvc,
		partners:  partners,
		log:       log,
	}
}

// ---------------------------------------------------------------------------
// CreateOutbound — POST /api/interbank/otc/negotiations
// ---------------------------------------------------------------------------

// CreateOutbound initiates a cross-bank OTC negotiation.
// We are always the buyer-bank; the seller is on the partner side.
//
// Flow:
//  1. Validate inputs.
//  2. Build OtcOfferDto with buyerId = {myRouting, "C-<principalUserID>"}.
//  3. POST to partner via client.OutboundCreateNegotiation.
//  4. Persist a local mirror row (is_authoritative=false, remote_negotiation_id=partner_id).
//  5. Return the partner's ForeignBankId.
func (s *OtcOutboundService) CreateOutbound(
	ctx context.Context,
	principalUserID int64,
	req OutboundCreateRequest,
) (*protocol.ForeignBankId, error) {
	// Resolve buyer user id: request body overrides principal if set.
	buyerLocalID := req.BuyerLocalUserID
	if buyerLocalID == 0 {
		buyerLocalID = principalUserID
	}
	if buyerLocalID == 0 {
		return nil, fmt.Errorf("%w: buyerLocalUserId is zero — cannot determine buyer", ErrNegotiationInvalid)
	}

	if req.SellerRoutingNumber == s.myRouting {
		return nil, fmt.Errorf("%w: sellerRoutingNumber must not be our own routing (%d) — use /otc for intra-bank",
			ErrNegotiationInvalid, s.myRouting)
	}
	if !req.SettlementDate.After(time.Now()) {
		return nil, fmt.Errorf("%w: settlementDate must be in the future", ErrNegotiationInvalid)
	}
	if req.Amount <= 0 {
		return nil, fmt.Errorf("%w: amount must be positive", ErrNegotiationInvalid)
	}
	if req.PricePerUnit.IsZero() || req.PricePerUnit.IsNegative() {
		return nil, fmt.Errorf("%w: pricePerUnit must be positive", ErrNegotiationInvalid)
	}
	if req.Premium.IsNegative() {
		return nil, fmt.Errorf("%w: premium must be non-negative", ErrNegotiationInvalid)
	}

	buyerIDStr := fmt.Sprintf("C-%d", buyerLocalID)
	buyer := protocol.ForeignBankId{RoutingNumber: s.myRouting, Id: buyerIDStr}
	seller := protocol.ForeignBankId{RoutingNumber: req.SellerRoutingNumber, Id: req.SellerForeignID}

	offer := OtcOfferDto{
		Stock:          protocol.StockDescription{Ticker: req.StockTicker},
		SettlementDate: req.SettlementDate,
		PricePerUnit:   protocol.MonetaryValue{Currency: req.PriceCurrency, Amount: req.PricePerUnit},
		Premium:        protocol.MonetaryValue{Currency: req.PremiumCurrency, Amount: req.Premium},
		BuyerID:        buyer,
		SellerID:       seller,
		Amount:         IntAmount(req.Amount),
		LastModifiedBy: buyer, // we initiated, so we are last modifier
	}

	remoteID, err := s.client.OutboundCreateNegotiation(ctx, req.SellerRoutingNumber, offer)
	if err != nil {
		return nil, fmt.Errorf("outbound: POST /negotiations: %w", err)
	}
	if remoteID == nil || remoteID.Id == "" {
		return nil, fmt.Errorf("%w: partner returned blank negotiation id", ErrInterbankProtocol)
	}

	// Persist local mirror row.
	localID := generateNegotiationID()
	n := &store.Negotiation{
		ID:                    localID,
		BuyerRouting:          s.myRouting,
		BuyerID:               buyerIDStr,
		SellerRouting:         req.SellerRoutingNumber,
		SellerID:              req.SellerForeignID,
		StockTicker:           req.StockTicker,
		Amount:                req.Amount,
		PriceCurrency:         req.PriceCurrency,
		PriceAmount:           req.PricePerUnit,
		PremiumCurrency:       req.PremiumCurrency,
		PremiumAmount:         req.Premium,
		SettlementDate:        req.SettlementDate,
		LastModifiedByRouting: s.myRouting,
		LastModifiedByID:      buyerIDStr,
		IsOngoing:             true,
		IsAuthoritative:       false, // partner is authoritative
		RemoteNegotiationID:   &remoteID.Id,
	}
	if err := s.store.Insert(ctx, n); err != nil {
		return nil, fmt.Errorf("outbound: persist mirror: %w", err)
	}

	s.log.InfoContext(ctx, "created outbound negotiation mirror",
		"localId", localID,
		"remoteId", fmt.Sprintf("%d/%s", remoteID.RoutingNumber, remoteID.Id),
		"buyer", buyerIDStr,
		"seller", req.SellerForeignID,
	)
	return remoteID, nil
}

// ---------------------------------------------------------------------------
// ListForUser — GET /api/interbank/otc/negotiations
// ---------------------------------------------------------------------------

// ListForUser returns negotiations involving principalUserID.
// Admin / supervisor callers set isAdmin=true to receive all negotiations.
func (s *OtcOutboundService) ListForUser(
	ctx context.Context,
	principalUserID int64,
	isAdmin bool,
) ([]NegotiationView, error) {
	var userForeignID string
	if !isAdmin {
		if principalUserID == 0 {
			return nil, fmt.Errorf("%w: principalUserID is zero and isAdmin is false", ErrNegotiationInvalid)
		}
		userForeignID = fmt.Sprintf("C-%d", principalUserID)
	}

	rows, err := s.store.ListForUser(ctx, userForeignID, isAdmin)
	if err != nil {
		return nil, fmt.Errorf("outbound: list negotiations: %w", err)
	}

	views := make([]NegotiationView, 0, len(rows))
	for _, n := range rows {
		views = append(views, s.toView(n))
	}
	return views, nil
}

// ---------------------------------------------------------------------------
// GetOne — GET /api/interbank/otc/negotiations/{id}
// ---------------------------------------------------------------------------

// GetOne looks up a negotiation by local id or remote id.
// Returns ErrNegotiationNotFound if neither is found.
func (s *OtcOutboundService) GetOne(ctx context.Context, id string) (*NegotiationView, error) {
	n, err := s.findByLocalOrRemote(ctx, id)
	if err != nil {
		return nil, err
	}
	v := s.toView(n)
	return &v, nil
}

// ---------------------------------------------------------------------------
// CounterOutbound — PUT /api/interbank/otc/negotiations/{id}/counter
// ---------------------------------------------------------------------------

// CounterOutbound sends a counter-offer.
//   - Authoritative rows: update locally (partner picks up via GET).
//   - Mirror rows: PUT to partner, update locally on 2xx.
//
// Returns CounterResult.StatusCode so the HTTP handler can propagate the exact
// partner status (204 / 400 / 404 / 409) back to the frontend.
func (s *OtcOutboundService) CounterOutbound(
	ctx context.Context,
	principalUserID int64,
	id string,
	req OutboundCounterRequest,
) (CounterResult, error) {
	if principalUserID == 0 {
		return CounterResult{}, fmt.Errorf("%w: principalUserID is zero", ErrNegotiationInvalid)
	}

	n, err := s.findByLocalOrRemote(ctx, id)
	if err != nil {
		return CounterResult{}, err
	}
	if !n.IsOngoing {
		return CounterResult{}, fmt.Errorf("%w: negotiation %s is closed", ErrNegotiationClosed, id)
	}
	if !n.SettlementDate.IsZero() && n.SettlementDate.Before(time.Now()) {
		return CounterResult{}, fmt.Errorf("%w: negotiation %s settlement date passed", ErrNegotiationClosed, id)
	}
	if n.LastModifiedByRouting == s.myRouting {
		return CounterResult{}, fmt.Errorf("%w: last modification was from our bank — not our turn", ErrTurnViolation)
	}
	if !req.SettlementDate.After(time.Now()) {
		return CounterResult{}, fmt.Errorf("%w: settlementDate must be in the future", ErrNegotiationInvalid)
	}
	if req.Amount <= 0 {
		return CounterResult{}, fmt.Errorf("%w: amount must be positive", ErrNegotiationInvalid)
	}
	if req.PricePerUnit.IsZero() || req.PricePerUnit.IsNegative() {
		return CounterResult{}, fmt.Errorf("%w: pricePerUnit must be positive", ErrNegotiationInvalid)
	}
	if req.Premium.IsNegative() {
		return CounterResult{}, fmt.Errorf("%w: premium must be non-negative", ErrNegotiationInvalid)
	}

	myUserIDStr := fmt.Sprintf("C-%d", principalUserID)
	modifier := protocol.ForeignBankId{RoutingNumber: s.myRouting, Id: myUserIDStr}
	buyer := protocol.ForeignBankId{RoutingNumber: n.BuyerRouting, Id: n.BuyerID}
	seller := protocol.ForeignBankId{RoutingNumber: n.SellerRouting, Id: n.SellerID}

	offer := OtcOfferDto{
		Stock:          protocol.StockDescription{Ticker: n.StockTicker},
		SettlementDate: req.SettlementDate,
		PricePerUnit:   protocol.MonetaryValue{Currency: req.PriceCurrency, Amount: req.PricePerUnit},
		Premium:        protocol.MonetaryValue{Currency: req.PremiumCurrency, Amount: req.Premium},
		BuyerID:        buyer,
		SellerID:       seller,
		Amount:         IntAmount(req.Amount),
		LastModifiedBy: modifier,
	}

	if n.IsAuthoritative {
		// Local update — partner will pick up on its next GET.
		if err := s.otcSvc.UpdateCounter(ctx, n.BuyerRouting, n.ID, offer, s.myRouting); err != nil {
			return CounterResult{}, err
		}
		s.log.InfoContext(ctx, "counter-offer local (authoritative)", "id", n.ID, "modifier", myUserIDStr)
		// Re-fetch fresh state.
		refreshed, ferr := s.store.FindByID(ctx, n.ID)
		if ferr != nil || refreshed == nil {
			refreshed = n // fallback to pre-update snapshot
		}
		v := s.toView(refreshed)
		return CounterResult{StatusCode: 204, View: &v}, nil
	}

	// Mirror path — outbound PUT to partner.
	partnerRouting := s.partnerRoutingOf(n)
	remoteRef := s.remoteForeignBankIDOf(n)

	statusCode, err := s.client.OutboundPutCounter(ctx, partnerRouting, remoteRef, offer)
	if err != nil {
		// Typed errors (ErrOutboundNotFound → 404, ErrOutboundConflict → 409) need
		// to be re-expressed as HTTP status codes for the handler to propagate.
		statusCode = mapOutboundErrorStatus(err)
		s.log.WarnContext(ctx, "counter-offer outbound failed",
			"id", id, "partnerRouting", partnerRouting, "status", statusCode, "err", err)
		return CounterResult{StatusCode: statusCode}, nil
	}

	if statusCode >= 200 && statusCode < 300 {
		// Mirror local state.
		n.Amount = req.Amount
		n.PriceCurrency = req.PriceCurrency
		n.PriceAmount = req.PricePerUnit
		n.PremiumCurrency = req.PremiumCurrency
		n.PremiumAmount = req.Premium
		n.SettlementDate = req.SettlementDate
		n.LastModifiedByRouting = s.myRouting
		n.LastModifiedByID = myUserIDStr
		if updateErr := s.store.UpdateCounter(ctx, n); updateErr != nil {
			s.log.WarnContext(ctx, "counter-offer: local mirror update failed (non-fatal — partner accepted)",
				"id", id, "err", updateErr)
		}
		v := s.toView(n)
		return CounterResult{StatusCode: statusCode, View: &v}, nil
	}
	return CounterResult{StatusCode: statusCode}, nil
}

// ---------------------------------------------------------------------------
// AcceptOutbound — POST /api/interbank/otc/negotiations/{id}/accept
// ---------------------------------------------------------------------------

// AcceptOutbound accepts the current offer.
// We must be the buyer-bank for the mirror path.
// Returns the partner's HTTP status code.
func (s *OtcOutboundService) AcceptOutbound(
	ctx context.Context,
	id string,
) (int, error) {
	n, err := s.findByLocalOrRemote(ctx, id)
	if err != nil {
		return 0, err
	}
	if !n.IsOngoing {
		return 0, fmt.Errorf("%w: negotiation %s is closed", ErrNegotiationClosed, id)
	}
	if !n.SettlementDate.IsZero() && n.SettlementDate.Before(time.Now()) {
		return 0, fmt.Errorf("%w: negotiation %s settlement date passed", ErrNegotiationClosed, id)
	}
	if n.LastModifiedByRouting == s.myRouting {
		return 0, fmt.Errorf("%w: our bank made the last modification — cannot accept", ErrTurnViolation)
	}

	if n.IsAuthoritative {
		// Partner initiated: we are the seller (locally authoritative).
		// AcceptNegotiation runs 2PC as coordinator — senderRouting is us.
		if err := s.otcSvc.AcceptNegotiation(ctx, n.BuyerRouting, n.ID, s.myRouting); err != nil {
			return 500, err
		}
		return 204, nil
	}

	// Mirror: we are buyer-bank. Route to partner as coordinator.
	if n.BuyerRouting != s.myRouting {
		return 0, fmt.Errorf("%w: cannot accept — we are not buyer-bank for this negotiation",
			ErrNegotiationInvalid)
	}
	partnerRouting := n.SellerRouting
	remoteRef := s.remoteForeignBankIDOf(n)

	statusCode, clientErr := s.client.OutboundAccept(ctx, partnerRouting, remoteRef)
	if clientErr != nil {
		s.log.WarnContext(ctx, "accept outbound failed",
			"id", id, "partnerRouting", partnerRouting, "status", statusCode, "err", clientErr)
		return mapOutboundErrorStatus(clientErr), nil
	}
	// FIX 2: on partner success the negotiation is settled — close our local mirror so
	// it leaves the active feed and the FE no longer offers "Prihvati". Without this the
	// mirror's is_ongoing stayed true and a second accept click hit the partner's now-
	// closed negotiation → 409. The authoritative branch already closes via the
	// coordinator; only the mirror path was leaking. Non-fatal: a close failure is logged.
	if statusCode >= 200 && statusCode < 300 && n.IsOngoing {
		if closeErr := s.store.MarkClosed(ctx, n.ID); closeErr != nil {
			s.log.WarnContext(ctx, "accept outbound: MarkClosed mirror failed (non-fatal)",
				"id", n.ID, "err", closeErr)
		}
	}
	s.log.InfoContext(ctx, "accept outbound partner returned", "id", id, "status", statusCode)
	return statusCode, nil
}

// ---------------------------------------------------------------------------
// DeleteOutbound — DELETE /api/interbank/otc/negotiations/{id}
// ---------------------------------------------------------------------------

// DeleteOutbound closes a negotiation. Idempotent — 204 even if not found.
func (s *OtcOutboundService) DeleteOutbound(ctx context.Context, id string) error {
	n, err := s.store.FindByID(ctx, id)
	if err != nil {
		return fmt.Errorf("outbound: find negotiation: %w", err)
	}
	if n == nil {
		// Try remote id lookup.
		n, err = s.findByRemoteID(ctx, id)
		if err != nil {
			return err
		}
	}
	if n == nil {
		// Already gone — idempotent no-op.
		s.log.DebugContext(ctx, "DELETE outbound — negotiation not found, idempotent", "id", id)
		return nil
	}

	partnerRouting := s.partnerRoutingOf(n)
	remoteRef := s.remoteForeignBankIDOf(n)

	if err := s.client.OutboundDelete(ctx, partnerRouting, remoteRef); err != nil {
		// OutboundDelete treats 404 as idempotent internally, so a real error means 5xx.
		return fmt.Errorf("outbound: DELETE /negotiations: %w", err)
	}
	if n.IsOngoing {
		if closeErr := s.store.MarkClosed(ctx, n.ID); closeErr != nil {
			s.log.WarnContext(ctx, "DELETE outbound: MarkClosed failed (non-fatal)", "id", n.ID, "err", closeErr)
		}
	}
	s.log.InfoContext(ctx, "deleted outbound negotiation", "id", n.ID)
	return nil
}

// ---------------------------------------------------------------------------
// FetchPartnerPublicStock — GET /api/interbank/otc/public-stock?bankCode=222
// ---------------------------------------------------------------------------

// FetchPartnerPublicStock fetches the partner's public-stock list.
// Gracefully returns empty slice when bankCode == myRouting.
func (s *OtcOutboundService) FetchPartnerPublicStock(ctx context.Context, bankCode int) ([]protocol.PublicStockEntry, error) {
	if bankCode == s.myRouting {
		return []protocol.PublicStockEntry{}, nil
	}
	return s.client.OutboundFetchPublicStock(ctx, bankCode)
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

// findByLocalOrRemote looks up a negotiation by local id first, then
// falls back to remote_negotiation_id scan via FindByAuthoritativeRef.
func (s *OtcOutboundService) findByLocalOrRemote(ctx context.Context, id string) (*store.Negotiation, error) {
	n, err := s.store.FindByID(ctx, id)
	if err != nil {
		return nil, fmt.Errorf("outbound: find by id: %w", err)
	}
	if n != nil {
		return n, nil
	}
	// Fallback: treat id as a remote_negotiation_id.
	n, err = s.store.FindByAuthoritativeRef(ctx, 0, id)
	if err != nil {
		return nil, fmt.Errorf("outbound: find by remote id: %w", err)
	}
	if n == nil {
		return nil, fmt.Errorf("%w: %s", ErrNegotiationNotFound, id)
	}
	return n, nil
}

// findByRemoteID looks for a non-authoritative mirror row whose remote_negotiation_id equals id.
func (s *OtcOutboundService) findByRemoteID(ctx context.Context, id string) (*store.Negotiation, error) {
	n, err := s.store.FindByAuthoritativeRef(ctx, 0, id)
	if err != nil {
		return nil, fmt.Errorf("outbound: find by remote id: %w", err)
	}
	return n, nil
}

// partnerRoutingOf returns the routing number of the OTHER party.
func (s *OtcOutboundService) partnerRoutingOf(n *store.Negotiation) int {
	if n.BuyerRouting == s.myRouting {
		return n.SellerRouting
	}
	return n.BuyerRouting
}

// remoteForeignBankIDOf builds the ForeignBankId that the partner uses to identify
// this negotiation. For mirror rows: use remote_negotiation_id + partner routing.
// For authoritative rows: use our local id (partner has a mirror that references it).
func (s *OtcOutboundService) remoteForeignBankIDOf(n *store.Negotiation) protocol.ForeignBankId {
	partnerRouting := s.partnerRoutingOf(n)
	remoteID := n.ID // authoritative: partner mirrors us by our id
	if n.RemoteNegotiationID != nil && *n.RemoteNegotiationID != "" {
		remoteID = *n.RemoteNegotiationID
	}
	return protocol.ForeignBankId{RoutingNumber: partnerRouting, Id: remoteID}
}

// toView converts a store.Negotiation to a NegotiationView.
func (s *OtcOutboundService) toView(n *store.Negotiation) NegotiationView {
	counterpartyRouting := s.partnerRoutingOf(n)
	counterpartyName := ""
	if s.partners != nil {
		counterpartyName = s.partners.DisplayName(counterpartyRouting)
	}
	var remoteID *string
	if n.RemoteNegotiationID != nil && *n.RemoteNegotiationID != "" {
		remoteID = n.RemoteNegotiationID
	}
	return NegotiationView{
		LocalID:              n.ID,
		RemoteID:             remoteID,
		IsAuthoritative:      n.IsAuthoritative,
		CounterpartyBankCode: counterpartyRouting,
		CounterpartyBankName: counterpartyName,
		BuyerID:              protocol.ForeignBankId{RoutingNumber: n.BuyerRouting, Id: n.BuyerID},
		SellerID:             protocol.ForeignBankId{RoutingNumber: n.SellerRouting, Id: n.SellerID},
		LastModifiedBy:       protocol.ForeignBankId{RoutingNumber: n.LastModifiedByRouting, Id: n.LastModifiedByID},
		StockTicker:          n.StockTicker,
		PriceCurrency:        n.PriceCurrency,
		PricePerUnit:         n.PriceAmount,
		Amount:               n.Amount,
		SettlementDate:       n.SettlementDate,
		PremiumCurrency:      n.PremiumCurrency,
		Premium:              n.PremiumAmount,
		IsOngoing:            n.IsOngoing,
		CreatedAt:            n.CreatedAt,
		LastModifiedAt:       n.LastModifiedAt,
	}
}

// mapOutboundErrorStatus converts typed outbound errors to HTTP status codes
// for propagating partner responses back to the frontend.
func mapOutboundErrorStatus(err error) int {
	switch {
	case isErrOutboundNotFound(err):
		return 404
	case isErrOutboundConflict(err):
		return 409
	case isErrOutboundAuth(err):
		return 401
	default:
		return 500
	}
}

// isErr* helpers use string matching on error messages to avoid importing
// the concrete sentinel vars from outbound.go (same package).
func isErrOutboundNotFound(err error) bool {
	return err != nil && err.Error() == ErrOutboundNotFound.Error()
}

func isErrOutboundConflict(err error) bool {
	return err != nil && err.Error() == ErrOutboundConflict.Error()
}

func isErrOutboundAuth(err error) bool {
	return err != nil && err.Error() == ErrOutboundAuth.Error()
}

// ---------------------------------------------------------------------------
// partnerStoreAdapter for PartnerNameResolver
// ---------------------------------------------------------------------------

// PartnerStoreAdapter adapts auth.PartnerStore to PartnerNameResolver.
type PartnerStoreAdapter struct {
	ps auth.PartnerStore
}

// NewPartnerStoreAdapter constructs the adapter.
func NewPartnerStoreAdapter(ps auth.PartnerStore) *PartnerStoreAdapter {
	return &PartnerStoreAdapter{ps: ps}
}

// DisplayName returns the display name for the given routing number,
// or an empty string if no partner matches.
func (a *PartnerStoreAdapter) DisplayName(routing int) string {
	for _, p := range a.ps.Partners() {
		if p.Routing == routing {
			return p.DisplayName
		}
	}
	return ""
}
