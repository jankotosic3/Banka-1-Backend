package service

import (
	"context"
	"errors"
	"log/slog"
	"testing"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

func TestMapOutboundErrorStatus(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want int
	}{
		{"not found", ErrOutboundNotFound, 404},
		{"conflict", ErrOutboundConflict, 409},
		{"auth", ErrOutboundAuth, 401},
		{"other", errors.New("boom"), 500},
		{"nil", nil, 500},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := mapOutboundErrorStatus(c.err); got != c.want {
				t.Errorf("mapOutboundErrorStatus(%v) = %d, want %d", c.err, got, c.want)
			}
		})
	}
}

func TestIsErrOutboundHelpers(t *testing.T) {
	if !isErrOutboundNotFound(ErrOutboundNotFound) {
		t.Error("isErrOutboundNotFound(ErrOutboundNotFound) should be true")
	}
	if isErrOutboundNotFound(nil) {
		t.Error("isErrOutboundNotFound(nil) should be false")
	}
	if isErrOutboundNotFound(errors.New("x")) {
		t.Error("isErrOutboundNotFound(other) should be false")
	}

	if !isErrOutboundConflict(ErrOutboundConflict) {
		t.Error("isErrOutboundConflict(ErrOutboundConflict) should be true")
	}
	if isErrOutboundConflict(nil) {
		t.Error("isErrOutboundConflict(nil) should be false")
	}

	if !isErrOutboundAuth(ErrOutboundAuth) {
		t.Error("isErrOutboundAuth(ErrOutboundAuth) should be true")
	}
	if isErrOutboundAuth(nil) {
		t.Error("isErrOutboundAuth(nil) should be false")
	}
}

// ---------------------------------------------------------------------------
// Executor reservePosting branches (internal — exercises the saga reservation
// dispatch for MONAS / STOCK / OPTION assets without a database).
// ---------------------------------------------------------------------------

func newExecutorForReserve(bc BankingCoreReserver, td TradingReserver, negLookup OptionNegotiationLookup) *Executor {
	return NewExecutor(111, &fakeExecStore{}, bc, td, negLookup, slog.Default())
}

func TestReservePosting_MonasRealAccount(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{})
	e := newExecutorForReserve(bc, &fakeTradingReserver{}, nil)
	p := protocol.Posting{
		Account: &protocol.RealAccount{Num: "111000000000000011"},
		Amount:  decimal.NewFromFloat(-100),
		Asset:   &protocol.MonasAsset{Currency: "USD"},
	}
	ref, err := e.reservePosting(context.Background(), p, protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-1"})
	if err != nil {
		t.Fatalf("reservePosting MONAS: %v", err)
	}
	if ref.Kind != store.RefKindMonas {
		t.Errorf("expected MONAS ref, got %q", ref.Kind)
	}
}

func TestReservePosting_MonasPersonResolvesAccount(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(15, "USD"): "111000000000000011"}
	e := newExecutorForReserve(bc, &fakeTradingReserver{}, nil)
	p := protocol.Posting{
		Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "C-15"}},
		Amount:  decimal.NewFromFloat(-50),
		Asset:   &protocol.MonasAsset{Currency: "USD"},
	}
	ref, err := e.reservePosting(context.Background(), p, protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-2"})
	if err != nil {
		t.Fatalf("reservePosting MONAS person: %v", err)
	}
	if ref.Kind != store.RefKindMonas {
		t.Errorf("expected MONAS ref, got %q", ref.Kind)
	}
}

func TestReservePosting_MonasPartnerPersonSkipped(t *testing.T) {
	bc := newFakeBC(map[string]*AccountInfo{})
	e := newExecutorForReserve(bc, &fakeTradingReserver{}, nil)
	p := protocol.Posting{
		Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: 222, Id: "C-2"}},
		Amount:  decimal.NewFromFloat(-50),
		Asset:   &protocol.MonasAsset{Currency: "USD"},
	}
	ref, err := e.reservePosting(context.Background(), p, protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-3"})
	if err != nil {
		t.Fatalf("expected skip (nil err) for partner person, got %v", err)
	}
	if ref.Kind != "" {
		t.Errorf("expected empty ref for skipped partner person, got %q", ref.Kind)
	}
}

func TestReservePosting_StockOurs(t *testing.T) {
	td := &fakeTradingReserver{}
	e := newExecutorForReserve(newFakeBC(nil), td, nil)
	p := protocol.Posting{
		Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "C-15"}},
		Amount:  decimal.NewFromFloat(-10),
		Asset:   &protocol.StockAsset{Ticker: "AAPL"},
	}
	ref, err := e.reservePosting(context.Background(), p, protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-4"})
	if err != nil {
		t.Fatalf("reservePosting STOCK: %v", err)
	}
	if ref.Kind != store.RefKindStock {
		t.Errorf("expected STOCK ref, got %q", ref.Kind)
	}
}

func TestReservePosting_StockPartnerSkipped(t *testing.T) {
	e := newExecutorForReserve(newFakeBC(nil), &fakeTradingReserver{}, nil)
	p := protocol.Posting{
		Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: 222, Id: "C-2"}},
		Amount:  decimal.NewFromFloat(-10),
		Asset:   &protocol.StockAsset{Ticker: "AAPL"},
	}
	ref, err := e.reservePosting(context.Background(), p, protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-5"})
	if err != nil {
		t.Fatalf("expected skip for partner stock, got %v", err)
	}
	if ref.Kind != "" {
		t.Errorf("expected empty ref, got %q", ref.Kind)
	}
}

func TestReservePosting_StockWrongAccountType(t *testing.T) {
	e := newExecutorForReserve(newFakeBC(nil), &fakeTradingReserver{}, nil)
	p := protocol.Posting{
		Account: &protocol.RealAccount{Num: "111000000000000011"},
		Amount:  decimal.NewFromFloat(-10),
		Asset:   &protocol.StockAsset{Ticker: "AAPL"},
	}
	if _, err := e.reservePosting(context.Background(), p, protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-6"}); err == nil {
		t.Error("expected error for STOCK with non-Person account")
	}
}

func TestCommitRef_AllKinds(t *testing.T) {
	bc := newFakeBC(nil)
	td := &fakeTradingReserver{}
	e := newExecutorForReserve(bc, td, nil)
	negRouting := 111
	negID := "neg-1"
	refs := []store.ReservationRef{
		{Kind: store.RefKindMonas, ReservationID: "m-1"},
		{Kind: store.RefKindStock, ReservationID: "s-1"},
		{Kind: store.RefKindOption, NegotiationRouting: &negRouting, NegotiationID: &negID},
	}
	for _, ref := range refs {
		if err := e.commitRef(context.Background(), ref, protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-commit"}); err != nil {
			t.Errorf("commitRef %s: %v", ref.Kind, err)
		}
	}
	if len(bc.committed) != 1 || len(td.committed) != 2 {
		t.Errorf("expected 1 monas + 2 trading commits, got bc=%d td=%d", len(bc.committed), len(td.committed))
	}
}

func TestReleaseRef_AllKinds(t *testing.T) {
	bc := newFakeBC(nil)
	td := &fakeTradingReserver{}
	e := newExecutorForReserve(bc, td, nil)
	negRouting := 111
	negID := "neg-1"
	refs := []store.ReservationRef{
		{Kind: store.RefKindMonas, ReservationID: "m-1"},
		{Kind: store.RefKindStock, ReservationID: "s-1"},
		{Kind: store.RefKindOption, NegotiationRouting: &negRouting, NegotiationID: &negID},
	}
	e.compensate(context.Background(), refs)
	if len(bc.released) != 1 || len(td.released) != 2 {
		t.Errorf("expected 1 monas + 2 trading releases, got bc=%d td=%d", len(bc.released), len(td.released))
	}
}

// ---------------------------------------------------------------------------
// NegotiationSellerLookup adapter
// ---------------------------------------------------------------------------

func TestNegotiationSellerLookup_FindSellerID(t *testing.T) {
	ns := newFakeNegotiationStore()
	ns.rows["neg-1"] = &store.Negotiation{ID: "neg-1", SellerID: "C-99", IsOngoing: true}
	lk := NewNegotiationSellerLookup(ns)

	sid, err := lk.FindSellerID(context.Background(), "neg-1")
	if err != nil || sid != "C-99" {
		t.Fatalf("FindSellerID → %q %v", sid, err)
	}
	if _, err := lk.FindSellerID(context.Background(), "ghost"); err == nil {
		t.Error("expected not-found error")
	}
}

func TestNegotiationSellerLookup_FindNegotiation(t *testing.T) {
	ns := newFakeNegotiationStore()
	ns.rows["neg-1"] = &store.Negotiation{
		ID: "neg-1", IsOngoing: true, Amount: 10, PriceAmount: decimal.NewFromFloat(150),
	}
	lk := NewNegotiationSellerLookup(ns)

	lite, err := lk.FindNegotiation(context.Background(), protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-1"})
	if err != nil || lite == nil || lite.Amount != 10 {
		t.Fatalf("FindNegotiation → %+v %v", lite, err)
	}
	if _, err := lk.FindNegotiation(context.Background(), protocol.ForeignBankId{Id: "ghost"}); err == nil {
		t.Error("expected not-found error")
	}
}

// ---------------------------------------------------------------------------
// parseOwnerID error branches
// ---------------------------------------------------------------------------

func TestParseOwnerID(t *testing.T) {
	if id, err := parseOwnerID("C-15"); err != nil || id != 15 {
		t.Errorf("C-15 → %d %v", id, err)
	}
	if id, err := parseOwnerID("E-42"); err != nil || id != 42 {
		t.Errorf("E-42 → %d %v", id, err)
	}
	if _, err := parseOwnerID("ab"); err == nil {
		t.Error("short id should error")
	}
	if _, err := parseOwnerID("X-1"); err == nil {
		t.Error("bad prefix should error")
	}
	if _, err := parseOwnerID("C-notanumber"); err == nil {
		t.Error("non-numeric suffix should error")
	}
}
