package service

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// ---------------------------------------------------------------------------
// In-memory fakes
// ---------------------------------------------------------------------------

type fakeOutboundNegStore struct {
	mu   sync.Mutex
	rows map[string]*store.Negotiation
}

func newFakeOutboundNegStore() *fakeOutboundNegStore {
	return &fakeOutboundNegStore{rows: make(map[string]*store.Negotiation)}
}

func (f *fakeOutboundNegStore) Insert(_ context.Context, n *store.Negotiation) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	cp := *n
	cp.CreatedAt = time.Now()
	cp.LastModifiedAt = time.Now()
	f.rows[n.ID] = &cp
	return nil
}

func (f *fakeOutboundNegStore) FindByID(_ context.Context, id string) (*store.Negotiation, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	n, ok := f.rows[id]
	if !ok {
		return nil, nil
	}
	cp := *n
	return &cp, nil
}

func (f *fakeOutboundNegStore) FindByAuthoritativeRef(_ context.Context, _ int, id string) (*store.Negotiation, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	// Check local id first.
	if n, ok := f.rows[id]; ok {
		cp := *n
		return &cp, nil
	}
	// Then scan remote_negotiation_id.
	for _, n := range f.rows {
		if n.RemoteNegotiationID != nil && *n.RemoteNegotiationID == id {
			cp := *n
			return &cp, nil
		}
	}
	return nil, nil
}

func (f *fakeOutboundNegStore) UpdateCounter(_ context.Context, n *store.Negotiation) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	existing, ok := f.rows[n.ID]
	if !ok {
		return store.ErrNotFound
	}
	existing.Amount = n.Amount
	existing.PriceAmount = n.PriceAmount
	existing.PriceCurrency = n.PriceCurrency
	existing.PremiumAmount = n.PremiumAmount
	existing.PremiumCurrency = n.PremiumCurrency
	existing.SettlementDate = n.SettlementDate
	existing.LastModifiedByRouting = n.LastModifiedByRouting
	existing.LastModifiedByID = n.LastModifiedByID
	existing.Version++
	return nil
}

func (f *fakeOutboundNegStore) MarkClosed(_ context.Context, id string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	n, ok := f.rows[id]
	if !ok {
		return nil // idempotent
	}
	n.IsOngoing = false
	return nil
}

func (f *fakeOutboundNegStore) ListForUser(_ context.Context, userForeignID string, includeAll bool) ([]*store.Negotiation, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []*store.Negotiation
	for _, n := range f.rows {
		if includeAll || n.BuyerID == userForeignID || n.SellerID == userForeignID {
			cp := *n
			out = append(out, &cp)
		}
	}
	return out, nil
}

// ---------------------------------------------------------------------------
// fake OtcOutboundClient
// ---------------------------------------------------------------------------

type fakeOtcOutboundClient struct {
	createID  *protocol.ForeignBankId
	createErr error

	counterStatus int
	counterErr    error

	acceptStatus int
	acceptErr    error

	deleteErr error

	publicStock []protocol.PublicStockEntry
	publicErr   error
}

func (f *fakeOtcOutboundClient) OutboundCreateNegotiation(_ context.Context, _ int, _ OtcOfferDto) (*protocol.ForeignBankId, error) {
	return f.createID, f.createErr
}

func (f *fakeOtcOutboundClient) OutboundPutCounter(_ context.Context, _ int, _ protocol.ForeignBankId, _ OtcOfferDto) (int, error) {
	return f.counterStatus, f.counterErr
}

func (f *fakeOtcOutboundClient) OutboundAccept(_ context.Context, _ int, _ protocol.ForeignBankId) (int, error) {
	return f.acceptStatus, f.acceptErr
}

func (f *fakeOtcOutboundClient) OutboundDelete(_ context.Context, _ int, _ protocol.ForeignBankId) error {
	return f.deleteErr
}

func (f *fakeOtcOutboundClient) OutboundFetchPublicStock(_ context.Context, _ int) ([]protocol.PublicStockEntry, error) {
	return f.publicStock, f.publicErr
}

// ---------------------------------------------------------------------------
// static partner name resolver
// ---------------------------------------------------------------------------

type staticPartnerNames struct {
	names map[int]string
}

func (s *staticPartnerNames) DisplayName(routing int) string {
	return s.names[routing]
}

// ---------------------------------------------------------------------------
// builder helpers
// ---------------------------------------------------------------------------

const (
	testOutboundMyRouting   = 111
	testOutboundPartnerRN   = 222
)

func newOutboundSvc(
	store NegotiationStoreForOutbound,
	client OtcOutboundClient,
) *OtcOutboundService {
	partners := &staticPartnerNames{names: map[int]string{testOutboundPartnerRN: "Banka 2"}}
	// otcSvc is nil — tests that need the local-authoritative path build their own.
	return NewOtcOutboundService(testOutboundMyRouting, store, client, nil, partners, nil)
}

func validCreateReq() OutboundCreateRequest {
	return OutboundCreateRequest{
		StockTicker:         "AAPL",
		SettlementDate:      time.Now().Add(48 * time.Hour),
		PriceCurrency:       "USD",
		PricePerUnit:        decimal.NewFromFloat(150.00),
		PremiumCurrency:     "USD",
		Premium:             decimal.NewFromFloat(5.00),
		SellerRoutingNumber: testOutboundPartnerRN,
		SellerForeignID:     "C-2",
		Amount:              10,
		BuyerLocalUserID:    15,
	}
}

// ---------------------------------------------------------------------------
// TestCreateOutbound — happy path
// ---------------------------------------------------------------------------

func TestCreateOutbound_HappyPath(t *testing.T) {
	ns := newFakeOutboundNegStore()
	partnerID := &protocol.ForeignBankId{RoutingNumber: testOutboundPartnerRN, Id: "neg-remote-abc"}
	client := &fakeOtcOutboundClient{createID: partnerID}
	svc := newOutboundSvc(ns, client)

	remoteID, err := svc.CreateOutbound(context.Background(), 15, validCreateReq())
	if err != nil {
		t.Fatalf("CreateOutbound error: %v", err)
	}
	if remoteID == nil || remoteID.Id != "neg-remote-abc" {
		t.Errorf("expected remoteId=neg-remote-abc, got %v", remoteID)
	}

	// Local mirror must have been persisted.
	ns.mu.Lock()
	defer ns.mu.Unlock()
	if len(ns.rows) != 1 {
		t.Fatalf("expected 1 mirror row, got %d", len(ns.rows))
	}
	for _, n := range ns.rows {
		if n.IsAuthoritative {
			t.Error("mirror row must NOT be authoritative")
		}
		if n.RemoteNegotiationID == nil || *n.RemoteNegotiationID != "neg-remote-abc" {
			t.Errorf("expected remoteNegotiationId=neg-remote-abc, got %v", n.RemoteNegotiationID)
		}
		if n.BuyerID != "C-15" {
			t.Errorf("expected buyerId=C-15, got %s", n.BuyerID)
		}
	}
}

// ---------------------------------------------------------------------------
// TestCreateOutbound_PartnerFails — partner returns error, no mirror persisted
// ---------------------------------------------------------------------------

func TestCreateOutbound_PartnerFails(t *testing.T) {
	ns := newFakeOutboundNegStore()
	client := &fakeOtcOutboundClient{createErr: errors.New("upstream error")}
	svc := newOutboundSvc(ns, client)

	_, err := svc.CreateOutbound(context.Background(), 15, validCreateReq())
	if err == nil {
		t.Fatal("expected error when partner fails")
	}

	ns.mu.Lock()
	defer ns.mu.Unlock()
	if len(ns.rows) != 0 {
		t.Errorf("expected no mirror rows when partner failed, got %d", len(ns.rows))
	}
}

// ---------------------------------------------------------------------------
// TestCreateOutbound_SameRoutingRejected
// ---------------------------------------------------------------------------

func TestCreateOutbound_SameRoutingRejected(t *testing.T) {
	ns := newFakeOutboundNegStore()
	client := &fakeOtcOutboundClient{}
	svc := newOutboundSvc(ns, client)

	req := validCreateReq()
	req.SellerRoutingNumber = testOutboundMyRouting // our own routing

	_, err := svc.CreateOutbound(context.Background(), 15, req)
	if !errors.Is(err, ErrNegotiationInvalid) {
		t.Errorf("expected ErrNegotiationInvalid for same routing, got %v", err)
	}
}

// ---------------------------------------------------------------------------
// TestListForUser — user scope vs admin scope
// ---------------------------------------------------------------------------

func TestListForUser_UserSeesOwnRows(t *testing.T) {
	ns := newFakeOutboundNegStore()
	remoteID := "neg-partner-1"
	ns.rows["neg-local-1"] = &store.Negotiation{
		ID:            "neg-local-1",
		BuyerRouting:  testOutboundMyRouting,
		BuyerID:       "C-15",
		SellerRouting: testOutboundPartnerRN,
		SellerID:      "C-2",
		IsOngoing:     true,
		RemoteNegotiationID: &remoteID,
		LastModifiedByRouting: testOutboundPartnerRN,
		LastModifiedByID: "C-2",
		SettlementDate: time.Now().Add(24 * time.Hour),
	}
	ns.rows["neg-local-2"] = &store.Negotiation{
		ID:            "neg-local-2",
		BuyerRouting:  testOutboundPartnerRN,
		BuyerID:       "C-99",
		SellerRouting: testOutboundMyRouting,
		SellerID:      "C-7",  // different user
		IsOngoing:     true,
		LastModifiedByRouting: testOutboundPartnerRN,
		LastModifiedByID: "C-99",
		SettlementDate: time.Now().Add(24 * time.Hour),
	}

	client := &fakeOtcOutboundClient{}
	svc := newOutboundSvc(ns, client)

	// User 15 should see only neg-local-1.
	views, err := svc.ListForUser(context.Background(), 15, false)
	if err != nil {
		t.Fatalf("ListForUser error: %v", err)
	}
	if len(views) != 1 || views[0].LocalID != "neg-local-1" {
		t.Errorf("expected 1 view for user 15, got %d: %v", len(views), views)
	}
}

func TestListForUser_AdminSeesAll(t *testing.T) {
	ns := newFakeOutboundNegStore()
	ns.rows["neg-a"] = &store.Negotiation{ID: "neg-a", BuyerID: "C-1", SellerID: "C-2",
		SettlementDate: time.Now().Add(24 * time.Hour)}
	ns.rows["neg-b"] = &store.Negotiation{ID: "neg-b", BuyerID: "C-3", SellerID: "C-4",
		SettlementDate: time.Now().Add(24 * time.Hour)}

	client := &fakeOtcOutboundClient{}
	svc := newOutboundSvc(ns, client)

	views, err := svc.ListForUser(context.Background(), 0, true)
	if err != nil {
		t.Fatalf("ListForUser admin error: %v", err)
	}
	if len(views) != 2 {
		t.Errorf("admin should see 2 rows, got %d", len(views))
	}
}

// ---------------------------------------------------------------------------
// TestCounterOutbound_Mirror — routes to client.OutboundPutCounter
// ---------------------------------------------------------------------------

func TestCounterOutbound_Mirror(t *testing.T) {
	ns := newFakeOutboundNegStore()
	remoteID := "neg-partner-42"
	ns.rows["neg-local-42"] = &store.Negotiation{
		ID:            "neg-local-42",
		BuyerRouting:  testOutboundMyRouting,
		BuyerID:       "C-15",
		SellerRouting: testOutboundPartnerRN,
		SellerID:      "C-2",
		StockTicker:   "AAPL",
		Amount:        10,
		PriceCurrency: "USD",
		PriceAmount:   decimal.NewFromFloat(150),
		PremiumCurrency: "USD",
		PremiumAmount: decimal.NewFromFloat(5),
		IsOngoing:     true,
		IsAuthoritative: false,
		RemoteNegotiationID: &remoteID,
		LastModifiedByRouting: testOutboundPartnerRN, // partner's turn → we can counter
		LastModifiedByID:      "C-2",
		SettlementDate: time.Now().Add(48 * time.Hour),
	}

	client := &fakeOtcOutboundClient{counterStatus: 204}
	svc := newOutboundSvc(ns, client)

	counterReq := OutboundCounterRequest{
		SettlementDate:  time.Now().Add(72 * time.Hour),
		PriceCurrency:   "USD",
		PricePerUnit:    decimal.NewFromFloat(155),
		PremiumCurrency: "USD",
		Premium:         decimal.NewFromFloat(6),
		Amount:          10,
	}

	result, err := svc.CounterOutbound(context.Background(), 15, "neg-local-42", counterReq)
	if err != nil {
		t.Fatalf("CounterOutbound error: %v", err)
	}
	if result.StatusCode != 204 {
		t.Errorf("expected status 204, got %d", result.StatusCode)
	}
}

// ---------------------------------------------------------------------------
// TestCounterOutbound_TurnViolation — we are last modifier → ErrTurnViolation
// ---------------------------------------------------------------------------

func TestCounterOutbound_TurnViolation(t *testing.T) {
	ns := newFakeOutboundNegStore()
	remoteID := "neg-partner-tv"
	ns.rows["neg-tv"] = &store.Negotiation{
		ID:            "neg-tv",
		BuyerRouting:  testOutboundMyRouting,
		BuyerID:       "C-15",
		SellerRouting: testOutboundPartnerRN,
		SellerID:      "C-2",
		IsOngoing:     true,
		IsAuthoritative: false,
		RemoteNegotiationID: &remoteID,
		LastModifiedByRouting: testOutboundMyRouting, // OUR turn violation — WE were last
		LastModifiedByID:      "C-15",
		SettlementDate: time.Now().Add(48 * time.Hour),
	}

	client := &fakeOtcOutboundClient{counterStatus: 204}
	svc := newOutboundSvc(ns, client)

	counterReq := OutboundCounterRequest{
		SettlementDate: time.Now().Add(72 * time.Hour),
		PriceCurrency:  "USD",
		PricePerUnit:   decimal.NewFromFloat(155),
		Amount:         10,
	}
	_, err := svc.CounterOutbound(context.Background(), 15, "neg-tv", counterReq)
	if !errors.Is(err, ErrTurnViolation) {
		t.Errorf("expected ErrTurnViolation, got %v", err)
	}
}

// ---------------------------------------------------------------------------
// TestAcceptOutbound_Mirror — routes to client.OutboundAccept
// ---------------------------------------------------------------------------

func TestAcceptOutbound_Mirror(t *testing.T) {
	ns := newFakeOutboundNegStore()
	remoteID := "neg-partner-acc"
	ns.rows["neg-acc"] = &store.Negotiation{
		ID:            "neg-acc",
		BuyerRouting:  testOutboundMyRouting,
		BuyerID:       "C-15",
		SellerRouting: testOutboundPartnerRN,
		SellerID:      "C-2",
		IsOngoing:     true,
		IsAuthoritative: false,
		RemoteNegotiationID: &remoteID,
		LastModifiedByRouting: testOutboundPartnerRN, // partner was last — it's our turn to accept
		LastModifiedByID:      "C-2",
		SettlementDate: time.Now().Add(48 * time.Hour),
	}

	client := &fakeOtcOutboundClient{acceptStatus: 204}
	svc := newOutboundSvc(ns, client)

	status, err := svc.AcceptOutbound(context.Background(), "neg-acc")
	if err != nil {
		t.Fatalf("AcceptOutbound error: %v", err)
	}
	if status != 204 {
		t.Errorf("expected status 204, got %d", status)
	}
}

// FIX 2: a successful mirror accept must close the local mirror row so it leaves the
// active feed and a second accept cannot fire (which would hit the partner's now-closed
// negotiation → 409).
func TestAcceptOutbound_Mirror_ClosesLocalMirror(t *testing.T) {
	ns := newFakeOutboundNegStore()
	remoteID := "neg-partner-acc2"
	ns.rows["neg-acc2"] = &store.Negotiation{
		ID:                    "neg-acc2",
		BuyerRouting:          testOutboundMyRouting,
		BuyerID:               "C-15",
		SellerRouting:         testOutboundPartnerRN,
		SellerID:              "C-2",
		IsOngoing:             true,
		IsAuthoritative:       false,
		RemoteNegotiationID:   &remoteID,
		LastModifiedByRouting: testOutboundPartnerRN,
		LastModifiedByID:      "C-2",
		SettlementDate:        time.Now().Add(48 * time.Hour),
	}

	client := &fakeOtcOutboundClient{acceptStatus: 204}
	svc := newOutboundSvc(ns, client)

	if _, err := svc.AcceptOutbound(context.Background(), "neg-acc2"); err != nil {
		t.Fatalf("AcceptOutbound error: %v", err)
	}
	row, _ := ns.FindByID(context.Background(), "neg-acc2")
	if row == nil || row.IsOngoing {
		t.Fatalf("expected mirror closed (is_ongoing=false) after successful accept, got %+v", row)
	}
}

// FIX 2 guard: a non-2xx partner accept must NOT close the local mirror (the
// negotiation is still open and the user may retry once the partner recovers).
func TestAcceptOutbound_Mirror_DoesNotCloseOnNon2xx(t *testing.T) {
	ns := newFakeOutboundNegStore()
	remoteID := "neg-partner-acc3"
	ns.rows["neg-acc3"] = &store.Negotiation{
		ID:                    "neg-acc3",
		BuyerRouting:          testOutboundMyRouting,
		BuyerID:               "C-15",
		SellerRouting:         testOutboundPartnerRN,
		SellerID:              "C-2",
		IsOngoing:             true,
		IsAuthoritative:       false,
		RemoteNegotiationID:   &remoteID,
		LastModifiedByRouting: testOutboundPartnerRN,
		LastModifiedByID:      "C-2",
		SettlementDate:        time.Now().Add(48 * time.Hour),
	}

	client := &fakeOtcOutboundClient{acceptStatus: 409}
	svc := newOutboundSvc(ns, client)

	if _, err := svc.AcceptOutbound(context.Background(), "neg-acc3"); err != nil {
		t.Fatalf("AcceptOutbound error: %v", err)
	}
	row, _ := ns.FindByID(context.Background(), "neg-acc3")
	if row == nil || !row.IsOngoing {
		t.Fatalf("expected mirror to stay open after non-2xx accept, got %+v", row)
	}
}

// ---------------------------------------------------------------------------
// TestDeleteOutbound_Idempotent — DELETE non-existent: no error
// ---------------------------------------------------------------------------

func TestDeleteOutbound_Idempotent(t *testing.T) {
	ns := newFakeOutboundNegStore()
	client := &fakeOtcOutboundClient{}
	svc := newOutboundSvc(ns, client)

	// No rows in store — should be idempotent no-op.
	err := svc.DeleteOutbound(context.Background(), "neg-nonexistent")
	if err != nil {
		t.Errorf("expected nil for non-existent negotiation, got %v", err)
	}
}

// ---------------------------------------------------------------------------
// TestDeleteOutbound_ExistingRow — closes local mirror on success
// ---------------------------------------------------------------------------

func TestDeleteOutbound_ExistingRow(t *testing.T) {
	ns := newFakeOutboundNegStore()
	remoteID := "neg-partner-del"
	ns.rows["neg-del"] = &store.Negotiation{
		ID:            "neg-del",
		BuyerRouting:  testOutboundMyRouting,
		BuyerID:       "C-15",
		SellerRouting: testOutboundPartnerRN,
		SellerID:      "C-2",
		IsOngoing:     true,
		IsAuthoritative: false,
		RemoteNegotiationID: &remoteID,
		LastModifiedByRouting: testOutboundPartnerRN,
	}

	client := &fakeOtcOutboundClient{deleteErr: nil}
	svc := newOutboundSvc(ns, client)

	err := svc.DeleteOutbound(context.Background(), "neg-del")
	if err != nil {
		t.Fatalf("DeleteOutbound error: %v", err)
	}

	ns.mu.Lock()
	defer ns.mu.Unlock()
	row := ns.rows["neg-del"]
	if row.IsOngoing {
		t.Error("expected IsOngoing=false after delete")
	}
}

// ---------------------------------------------------------------------------
// TestFetchPartnerPublicStock — passthrough to client
// ---------------------------------------------------------------------------

func TestFetchPartnerPublicStock_Passthrough(t *testing.T) {
	entries := []protocol.PublicStockEntry{
		{
			Stock: protocol.StockDescription{Ticker: "TSLA"},
			Sellers: []protocol.PublicStockSellerRef{
				{SellerID: protocol.ForeignBankId{RoutingNumber: 222, Id: "C-2"}, Quantity: decimal.NewFromInt(50)},
			},
		},
	}
	ns := newFakeOutboundNegStore()
	client := &fakeOtcOutboundClient{publicStock: entries}
	svc := newOutboundSvc(ns, client)

	result, err := svc.FetchPartnerPublicStock(context.Background(), testOutboundPartnerRN)
	if err != nil {
		t.Fatalf("FetchPartnerPublicStock error: %v", err)
	}
	if len(result) != 1 || result[0].Stock.Ticker != "TSLA" {
		t.Errorf("unexpected result: %+v", result)
	}
}

func TestFetchPartnerPublicStock_OwnRoutingReturnsEmpty(t *testing.T) {
	ns := newFakeOutboundNegStore()
	client := &fakeOtcOutboundClient{publicStock: []protocol.PublicStockEntry{{Stock: protocol.StockDescription{Ticker: "X"}}}}
	svc := newOutboundSvc(ns, client)

	result, err := svc.FetchPartnerPublicStock(context.Background(), testOutboundMyRouting)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(result) != 0 {
		t.Errorf("expected empty when bankCode == myRouting, got %d entries", len(result))
	}
}

// ---------------------------------------------------------------------------
// TestToView_CounterpartyBankName — name resolver wired correctly
// ---------------------------------------------------------------------------

func TestToView_CounterpartyBankName(t *testing.T) {
	remoteID := "neg-partner-view"
	n := &store.Negotiation{
		ID:            "neg-view",
		BuyerRouting:  testOutboundMyRouting,
		BuyerID:       "C-15",
		SellerRouting: testOutboundPartnerRN,
		SellerID:      "C-2",
		IsOngoing:     true,
		RemoteNegotiationID: &remoteID,
		SettlementDate: time.Now().Add(24 * time.Hour),
	}

	ns := newFakeOutboundNegStore()
	client := &fakeOtcOutboundClient{}
	svc := newOutboundSvc(ns, client)

	view := svc.toView(n)
	if view.CounterpartyBankCode != testOutboundPartnerRN {
		t.Errorf("expected counterpartyBankCode=%d, got %d", testOutboundPartnerRN, view.CounterpartyBankCode)
	}
	if view.CounterpartyBankName != "Banka 2" {
		t.Errorf("expected counterpartyBankName=Banka 2, got %q", view.CounterpartyBankName)
	}
	if view.RemoteID == nil || *view.RemoteID != remoteID {
		t.Errorf("expected remoteId=%s, got %v", remoteID, view.RemoteID)
	}
}
