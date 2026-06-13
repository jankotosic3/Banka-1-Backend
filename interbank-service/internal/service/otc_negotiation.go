package service

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// IntAmount is a whole-share-count OTC amount that tolerates a JSON value
// written with trailing-zero scale (e.g. 10.00) or as a quoted string ("10").
// Banka 2 serializes OTC amounts from BigDecimal and may emit a scaled form; a
// plain Go int cannot decode 10.00 ("cannot unmarshal number 10.00 into Go
// value of type int") and would 400 the offer. IntAmount decodes any integral
// decimal (rejecting a genuinely fractional value) and always marshals back as
// a bare integer (10), so Banka 1's own outbound offers stay int-shaped.
type IntAmount int

func (a *IntAmount) UnmarshalJSON(b []byte) error {
	s := strings.Trim(string(b), `"`)
	if s == "" || s == "null" {
		*a = 0
		return nil
	}
	d, err := decimal.NewFromString(s)
	if err != nil {
		return fmt.Errorf("amount %q is not a number: %w", s, err)
	}
	if !d.Equal(d.Truncate(0)) {
		return fmt.Errorf("amount %s must be a whole number of shares", s)
	}
	*a = IntAmount(d.IntPart())
	return nil
}

func (a IntAmount) MarshalJSON() ([]byte, error) {
	return []byte(strconv.Itoa(int(a))), nil
}

// ---------------------------------------------------------------------------
// Store interface consumed by OtcNegotiationService
// ---------------------------------------------------------------------------

// NegotiationStoreIface is the persistence seam used by OtcNegotiationService.
// Production wiring uses *store.NegotiationStore; tests use an in-memory fake.
type NegotiationStoreIface interface {
	Insert(ctx context.Context, n *store.Negotiation) error
	FindByAuthoritativeRef(ctx context.Context, routing int, id string) (*store.Negotiation, error)
	UpdateCounter(ctx context.Context, n *store.Negotiation) error
	MarkClosed(ctx context.Context, id string) error
}

// ---------------------------------------------------------------------------
// OTC DTO types (wire format for the 7 §3 routes)
// ---------------------------------------------------------------------------

// OtcOfferDto is the body for POST /negotiations and PUT /negotiations/{rn}/{id}.
// Mirrors Java OtcOfferDto record.
type OtcOfferDto struct {
	Stock          protocol.StockDescription `json:"stock"`
	SettlementDate time.Time                 `json:"settlementDate"`
	PricePerUnit   protocol.MonetaryValue    `json:"pricePerUnit"`
	Premium        protocol.MonetaryValue    `json:"premium"`
	BuyerID        protocol.ForeignBankId    `json:"buyerId"`
	SellerID       protocol.ForeignBankId    `json:"sellerId"`
	Amount         IntAmount                 `json:"amount"`
	LastModifiedBy protocol.ForeignBankId    `json:"lastModifiedBy"`
}

// OtcNegotiationDto is the response body for GET /negotiations/{rn}/{id}.
// Mirrors Java OtcNegotiationDto record.
type OtcNegotiationDto struct {
	Stock          protocol.StockDescription `json:"stock"`
	SettlementDate time.Time                 `json:"settlementDate"`
	PricePerUnit   protocol.MonetaryValue    `json:"pricePerUnit"`
	Premium        protocol.MonetaryValue    `json:"premium"`
	BuyerID        protocol.ForeignBankId    `json:"buyerId"`
	SellerID       protocol.ForeignBankId    `json:"sellerId"`
	Amount         IntAmount                 `json:"amount"`
	LastModifiedBy protocol.ForeignBankId    `json:"lastModifiedBy"`
	IsOngoing      bool                      `json:"isOngoing"`
}

// ---------------------------------------------------------------------------
// OtcNegotiationService
// ---------------------------------------------------------------------------

// OtcNegotiationService implements OTC §3 CRUD + turn-logic per Tim 2 spec.
// Corresponds to Java OtcNegotiationService.
type OtcNegotiationService struct {
	myRouting   int
	store       NegotiationStoreIface
	coordinator CoordinatorIface
	log         *slog.Logger
}

// CoordinatorIface is the seam between OtcNegotiationService and Coordinator.
// Defined here to break the circular dependency and enable test fakes.
type CoordinatorIface interface {
	AcceptNegotiation(ctx context.Context, neg *store.Negotiation) error
}

// NewOtcNegotiationService constructs the service.
func NewOtcNegotiationService(
	myRouting int,
	s NegotiationStoreIface,
	coordinator CoordinatorIface,
	log *slog.Logger,
) *OtcNegotiationService {
	if log == nil {
		log = slog.Default()
	}
	return &OtcNegotiationService{
		myRouting:   myRouting,
		store:       s,
		coordinator: coordinator,
		log:         log,
	}
}

// CreateNegotiation handles §3.2 POST /negotiations.
// Returns the ForeignBankId we assigned to the new negotiation.
func (s *OtcNegotiationService) CreateNegotiation(ctx context.Context, offer OtcOfferDto, senderRouting int) (protocol.ForeignBankId, error) {
	if offer.SellerID.RoutingNumber != s.myRouting {
		return protocol.ForeignBankId{}, fmt.Errorf("%w: sellerId.routingNumber must be %d (this bank)",
			ErrNegotiationInvalid, s.myRouting)
	}
	if offer.BuyerID.RoutingNumber != senderRouting {
		return protocol.ForeignBankId{}, fmt.Errorf("%w: buyerId.routingNumber must match X-Api-Key sender (%d)",
			ErrNegotiationInvalid, senderRouting)
	}
	if offer.LastModifiedBy.RoutingNumber != senderRouting {
		return protocol.ForeignBankId{}, fmt.Errorf("%w: lastModifiedBy.routingNumber must match X-Api-Key sender (%d)",
			ErrNegotiationInvalid, senderRouting)
	}
	if !offer.SettlementDate.After(time.Now()) {
		return protocol.ForeignBankId{}, fmt.Errorf("%w: settlementDate must be in the future",
			ErrNegotiationInvalid)
	}
	if offer.Amount <= 0 {
		return protocol.ForeignBankId{}, fmt.Errorf("%w: amount must be positive",
			ErrNegotiationInvalid)
	}
	if offer.PricePerUnit.Amount.IsZero() || offer.PricePerUnit.Amount.IsNegative() {
		return protocol.ForeignBankId{}, fmt.Errorf("%w: pricePerUnit.amount must be positive",
			ErrNegotiationInvalid)
	}
	if offer.Premium.Amount.IsNegative() {
		return protocol.ForeignBankId{}, fmt.Errorf("%w: premium.amount must be non-negative",
			ErrNegotiationInvalid)
	}

	id := generateNegotiationID()
	n := &store.Negotiation{
		ID:                    id,
		BuyerRouting:          offer.BuyerID.RoutingNumber,
		BuyerID:               offer.BuyerID.Id,
		SellerRouting:         offer.SellerID.RoutingNumber,
		SellerID:              offer.SellerID.Id,
		StockTicker:           offer.Stock.Ticker,
		Amount:                int(offer.Amount),
		PriceCurrency:         offer.PricePerUnit.Currency,
		PriceAmount:           offer.PricePerUnit.Amount,
		PremiumCurrency:       offer.Premium.Currency,
		PremiumAmount:         offer.Premium.Amount,
		SettlementDate:        offer.SettlementDate,
		LastModifiedByRouting: offer.LastModifiedBy.RoutingNumber,
		LastModifiedByID:      offer.LastModifiedBy.Id,
		IsOngoing:             true,
		IsAuthoritative:       true,
	}
	if err := s.store.Insert(ctx, n); err != nil {
		return protocol.ForeignBankId{}, fmt.Errorf("otc: insert negotiation: %w", err)
	}
	s.log.InfoContext(ctx, "created interbank negotiation",
		"id", id,
		"buyer", fmt.Sprintf("%d/%s", offer.BuyerID.RoutingNumber, offer.BuyerID.Id),
		"seller", fmt.Sprintf("%d/%s", offer.SellerID.RoutingNumber, offer.SellerID.Id),
	)
	return protocol.ForeignBankId{RoutingNumber: s.myRouting, Id: id}, nil
}

// GetNegotiation handles §3.4 GET /negotiations/{rn}/{id}.
func (s *OtcNegotiationService) GetNegotiation(ctx context.Context, rn int, id string) (OtcNegotiationDto, error) {
	neg, err := s.store.FindByAuthoritativeRef(ctx, rn, id)
	if err != nil {
		return OtcNegotiationDto{}, fmt.Errorf("otc: lookup %d/%s: %w", rn, id, err)
	}
	if neg == nil {
		return OtcNegotiationDto{}, fmt.Errorf("%w: %d/%s", ErrNegotiationNotFound, rn, id)
	}
	return toDto(neg), nil
}

// UpdateCounter handles §3.3 PUT /negotiations/{rn}/{id} counter-offer.
// Per Tim 2 §6.3: 204 / 404 / 409 (turn or closed) / 400 (malformed).
func (s *OtcNegotiationService) UpdateCounter(ctx context.Context, rn int, id string, offer OtcOfferDto, senderRouting int) error {
	neg, err := s.store.FindByAuthoritativeRef(ctx, rn, id)
	if err != nil {
		return fmt.Errorf("otc: lookup %d/%s: %w", rn, id, err)
	}
	if neg == nil {
		return fmt.Errorf("%w: %d/%s", ErrNegotiationNotFound, rn, id)
	}
	if !neg.IsOngoing {
		return fmt.Errorf("%w: negotiation %s is closed", ErrNegotiationClosed, id)
	}
	if !neg.SettlementDate.IsZero() && neg.SettlementDate.Before(time.Now()) {
		return fmt.Errorf("%w: negotiation %s settlement date passed", ErrNegotiationClosed, id)
	}
	// lastModifiedBy.routingNumber must be the X-Api-Key sender.
	if offer.LastModifiedBy.RoutingNumber != senderRouting {
		return fmt.Errorf("%w: lastModifiedBy.routingNumber must match X-Api-Key sender",
			ErrNegotiationInvalid)
	}
	// Turn check — CRITICAL: 409, not 400.
	if neg.LastModifiedByRouting == senderRouting {
		return fmt.Errorf("%w: last modification was from your bank (routing=%d)",
			ErrTurnViolation, senderRouting)
	}
	// Payload validations.
	if !offer.SettlementDate.After(time.Now()) {
		return fmt.Errorf("%w: settlementDate must be in the future", ErrNegotiationInvalid)
	}
	if offer.Amount <= 0 {
		return fmt.Errorf("%w: amount must be positive", ErrNegotiationInvalid)
	}
	if offer.PricePerUnit.Amount.IsZero() || offer.PricePerUnit.Amount.IsNegative() {
		return fmt.Errorf("%w: pricePerUnit.amount must be positive", ErrNegotiationInvalid)
	}
	if offer.Premium.Amount.IsNegative() {
		return fmt.Errorf("%w: premium.amount must be non-negative", ErrNegotiationInvalid)
	}

	neg.Amount = int(offer.Amount)
	neg.PriceAmount = offer.PricePerUnit.Amount
	neg.PriceCurrency = offer.PricePerUnit.Currency
	neg.PremiumAmount = offer.Premium.Amount
	neg.PremiumCurrency = offer.Premium.Currency
	neg.SettlementDate = offer.SettlementDate
	neg.LastModifiedByRouting = offer.LastModifiedBy.RoutingNumber
	neg.LastModifiedByID = offer.LastModifiedBy.Id

	if err := s.store.UpdateCounter(ctx, neg); err != nil {
		return fmt.Errorf("otc: update counter %s: %w", id, err)
	}
	s.log.InfoContext(ctx, "updated negotiation counter-offer", "id", id, "senderRouting", senderRouting)
	return nil
}

// Delete handles §3.5 DELETE /negotiations/{rn}/{id}. Idempotent.
func (s *OtcNegotiationService) Delete(ctx context.Context, rn int, id string, senderRouting int) error {
	neg, err := s.store.FindByAuthoritativeRef(ctx, rn, id)
	if err != nil {
		return fmt.Errorf("otc: lookup %d/%s: %w", rn, id, err)
	}
	if neg == nil {
		// Idempotent: no-op.
		s.log.InfoContext(ctx, "DELETE negotiation — not found, idempotent no-op", "rn", rn, "id", id)
		return nil
	}
	// Sender must be a party (buyer or seller).
	if neg.BuyerRouting != senderRouting && neg.SellerRouting != senderRouting {
		return fmt.Errorf("%w: sender routing=%d is not buyer (%d) or seller (%d)",
			ErrSenderNotParty, senderRouting, neg.BuyerRouting, neg.SellerRouting)
	}
	if neg.IsOngoing {
		if err := s.store.MarkClosed(ctx, id); err != nil {
			return fmt.Errorf("otc: mark closed %s: %w", id, err)
		}
		s.log.InfoContext(ctx, "closed negotiation via DELETE", "id", id, "senderRouting", senderRouting)
	} else {
		s.log.DebugContext(ctx, "DELETE negotiation — already closed, idempotent no-op", "id", id)
	}
	return nil
}

// AcceptNegotiation handles §3.6 GET /negotiations/{rn}/{id}/accept.
// Validates pre-conditions, then delegates the synchronous 2PC to Coordinator.
func (s *OtcNegotiationService) AcceptNegotiation(ctx context.Context, rn int, id string, senderRouting int) error {
	neg, err := s.store.FindByAuthoritativeRef(ctx, rn, id)
	if err != nil {
		return fmt.Errorf("otc: lookup %d/%s: %w", rn, id, err)
	}
	if neg == nil {
		return fmt.Errorf("%w: %d/%s", ErrNegotiationNotFound, rn, id)
	}
	// Sender must be a party.
	if neg.BuyerRouting != senderRouting && neg.SellerRouting != senderRouting {
		return fmt.Errorf("%w: sender routing=%d", ErrSenderNotParty, senderRouting)
	}
	if !neg.IsOngoing {
		return fmt.Errorf("%w: negotiation %s is closed", ErrNegotiationClosed, id)
	}
	if !neg.SettlementDate.IsZero() && neg.SettlementDate.Before(time.Now()) {
		return fmt.Errorf("%w: negotiation %s settlement date passed", ErrNegotiationClosed, id)
	}
	// Turn check for accept: acceptor must not be the last modifier.
	if neg.LastModifiedByRouting == senderRouting {
		return fmt.Errorf("%w: your bank made the last modification", ErrTurnViolation)
	}
	return s.coordinator.AcceptNegotiation(ctx, neg)
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

func toDto(n *store.Negotiation) OtcNegotiationDto {
	return OtcNegotiationDto{
		Stock:          protocol.StockDescription{Ticker: n.StockTicker},
		SettlementDate: n.SettlementDate,
		PricePerUnit: protocol.MonetaryValue{
			Currency: n.PriceCurrency,
			Amount:   n.PriceAmount,
		},
		Premium: protocol.MonetaryValue{
			Currency: n.PremiumCurrency,
			Amount:   n.PremiumAmount,
		},
		BuyerID:  protocol.ForeignBankId{RoutingNumber: n.BuyerRouting, Id: n.BuyerID},
		SellerID: protocol.ForeignBankId{RoutingNumber: n.SellerRouting, Id: n.SellerID},
		Amount:   IntAmount(n.Amount),
		LastModifiedBy: protocol.ForeignBankId{
			RoutingNumber: n.LastModifiedByRouting,
			Id:            n.LastModifiedByID,
		},
		IsOngoing: n.IsOngoing,
	}
}

func generateNegotiationID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return "neg-" + hex.EncodeToString(b)
}

// ContractByNegotiationReader is the minimal contract-store seam the seller lookup needs to
// resolve a negotiation id → its option contract lifecycle (status + settlement). Satisfied
// by *store.ContractStore; tests use a fake. It is optional: a nil reader degrades to the
// pre-exercise behaviour (contract fields left empty → only accept-time legs pass).
type ContractByNegotiationReader interface {
	FindByNegotiationID(ctx context.Context, negotiationID string) (*store.Contract, error)
}

// NegotiationSellerLookup adapts NegotiationStoreIface to the OptionNegotiationLookup
// interface required by Executor. It resolves a negotiation id → seller foreign id string,
// and (for the Validator's NegotiationReader role) enriches the negotiation view with the
// backing option contract's lifecycle so EXERCISE-time legs on a closed negotiation pass.
type NegotiationSellerLookup struct {
	store     NegotiationStoreIface
	contracts ContractByNegotiationReader // optional — nil is tolerated
}

func NewNegotiationSellerLookup(s NegotiationStoreIface) *NegotiationSellerLookup {
	return &NegotiationSellerLookup{store: s}
}

// NewNegotiationSellerLookupWithContracts wires the contract store so the Validator can tell
// an exercisable accepted option (closed negotiation + ACTIVE contract) apart from a
// genuinely dead one. Used by production wiring; the plain constructor stays for callers
// that only need seller resolution.
func NewNegotiationSellerLookupWithContracts(s NegotiationStoreIface, c ContractByNegotiationReader) *NegotiationSellerLookup {
	return &NegotiationSellerLookup{store: s, contracts: c}
}

// FindSellerID returns the seller's foreign-id string (e.g. "C-15") for the given negotiation id.
func (l *NegotiationSellerLookup) FindSellerID(ctx context.Context, negID string) (string, error) {
	neg, err := l.store.FindByAuthoritativeRef(ctx, 0, negID)
	if err != nil {
		return "", err
	}
	if neg == nil {
		return "", fmt.Errorf("%w: %s", ErrNegotiationNotFound, negID)
	}
	return neg.SellerID, nil
}

// FindNegotiation adapts to service.NegotiationReader for Validator use.
//
// It additionally resolves the backing option contract (when a contract store is wired) so
// the Validator can distinguish an EXERCISE of an accepted option (closed negotiation but
// ACTIVE contract) from a genuinely dead negotiation. The contract lookup is best-effort:
// any error is swallowed and the contract fields are left empty — the validator then falls
// back to accept-time semantics, which is the safe (more-restrictive) default.
func (l *NegotiationSellerLookup) FindNegotiation(ctx context.Context, id protocol.ForeignBankId) (*NegotiationLite, error) {
	neg, err := l.store.FindByAuthoritativeRef(ctx, id.RoutingNumber, id.Id)
	if err != nil {
		return nil, err
	}
	if neg == nil {
		return nil, fmt.Errorf("%w: %v", ErrNegotiationNotFound, id)
	}
	lite := &NegotiationLite{
		IsOngoing:      neg.IsOngoing,
		SettlementDate: neg.SettlementDate,
		Amount:         neg.Amount,
		PricePerUnit:   neg.PriceAmount,
	}
	if l.contracts != nil {
		// neg.ID is OUR local negotiation id; interbank_contracts.negotiation_id references it.
		if c, cerr := l.contracts.FindByNegotiationID(ctx, neg.ID); cerr == nil && c != nil {
			lite.ContractStatus = c.Status
			lite.ContractSettlement = c.SettlementDate
		}
	}
	return lite, nil
}

// ensure NegotiationSellerLookup implements OptionNegotiationLookup and NegotiationReader
var _ OptionNegotiationLookup = (*NegotiationSellerLookup)(nil)
var _ NegotiationReader = (*NegotiationSellerLookup)(nil)

// ensure decimal zero comparison works (used in validation)
var _ = decimal.Zero
