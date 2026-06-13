package service

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
)

// ---------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------

type fakeAccountInfo struct {
	OwnerType        string
	OwnerID          int64
	Currency         string
	AvailableBalance decimal.Decimal
}

type fakeBC struct {
	byNum           map[string]fakeAccountInfo
	byOwnerCurrency map[string]string // key "ownerID:currency" → account num
}

func (f *fakeBC) ResolveAccount(_ context.Context, num string) (*AccountInfo, error) {
	a, ok := f.byNum[num]
	if !ok {
		return nil, ErrAccountNotFound
	}
	return &AccountInfo{
		OwnerType:        a.OwnerType,
		OwnerID:          a.OwnerID,
		Currency:         a.Currency,
		AvailableBalance: a.AvailableBalance,
	}, nil
}

func (f *fakeBC) FindAccountByOwnerAndCurrency(_ context.Context, ownerID int64, currency string) (string, error) {
	key := fmt.Sprintf("%d:%s", ownerID, currency)
	num, ok := f.byOwnerCurrency[key]
	if !ok {
		return "", ErrAccountNotFound
	}
	return num, nil
}

type fakeNegInfo struct {
	isOngoing    bool
	settlement   time.Time
	amount       int
	pricePerUnit decimal.Decimal
	// contractStatus / contractSettlement model the backing option contract (empty =
	// no contract yet, i.e. accept-time). Used to exercise the EXERCISE-time path where
	// the negotiation is closed but the contract is still ACTIVE.
	contractStatus     string
	contractSettlement time.Time
}

type fakeTD struct {
	negs map[string]fakeNegInfo
}

func (f *fakeTD) FindNegotiation(_ context.Context, id protocol.ForeignBankId) (*NegotiationLite, error) {
	key := fmt.Sprintf("%d:%s", id.RoutingNumber, id.Id)
	n, ok := f.negs[key]
	if !ok {
		return nil, errors.New("not found")
	}
	return &NegotiationLite{
		IsOngoing:          n.isOngoing,
		SettlementDate:     n.settlement,
		Amount:             n.amount,
		PricePerUnit:       n.pricePerUnit,
		ContractStatus:     n.contractStatus,
		ContractSettlement: n.contractSettlement,
	}, nil
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

func mkRealAccountMonas(num string, amt decimal.Decimal, currency string) protocol.Posting {
	return protocol.Posting{
		Account: &protocol.RealAccount{Num: num},
		Amount:  amt,
		Asset:   &protocol.MonasAsset{Currency: currency},
	}
}

// ---------------------------------------------------------------------------
// BalanceCheck tests
// ---------------------------------------------------------------------------

func TestBalanceCheck_Balanced(t *testing.T) {
	v := NewValidator(111, nil, nil, nil)
	postings := []protocol.Posting{
		mkRealAccountMonas("222000000000000001", decimal.NewFromInt(-100), "USD"),
		mkRealAccountMonas("111000000000000001", decimal.NewFromInt(100), "USD"),
	}
	if reasons := v.BalanceCheck(postings); len(reasons) != 0 {
		t.Errorf("expected balanced, got reasons=%+v", reasons)
	}
}

func TestBalanceCheck_Unbalanced(t *testing.T) {
	v := NewValidator(111, nil, nil, nil)
	postings := []protocol.Posting{
		mkRealAccountMonas("222000000000000001", decimal.NewFromInt(-100), "USD"),
		mkRealAccountMonas("111000000000000001", decimal.NewFromInt(99), "USD"),
	}
	reasons := v.BalanceCheck(postings)
	if len(reasons) != 1 {
		t.Fatalf("expected 1 reason, got %d: %+v", len(reasons), reasons)
	}
	if reasons[0].Reason != protocol.ReasonUnbalancedTx {
		t.Errorf("expected UNBALANCED_TX, got %q", reasons[0].Reason)
	}
}

func TestBalanceCheck_MultiAsset_Balanced(t *testing.T) {
	v := NewValidator(111, nil, nil, nil)
	postings := []protocol.Posting{
		mkRealAccountMonas("222000000000000001", decimal.NewFromInt(-100), "USD"),
		mkRealAccountMonas("111000000000000001", decimal.NewFromInt(100), "USD"),
		{
			Account: &protocol.RealAccount{Num: "222000000000000002"},
			Amount:  decimal.NewFromInt(-5),
			Asset:   &protocol.StockAsset{Ticker: "AAPL"},
		},
		{
			Account: &protocol.RealAccount{Num: "111000000000000002"},
			Amount:  decimal.NewFromInt(5),
			Asset:   &protocol.StockAsset{Ticker: "AAPL"},
		},
	}
	if reasons := v.BalanceCheck(postings); len(reasons) != 0 {
		t.Errorf("expected balanced, got reasons=%+v", reasons)
	}
}

// ---------------------------------------------------------------------------
// ValidatePosting — partner side (should be skipped)
// ---------------------------------------------------------------------------

func TestValidatePosting_PartnerAccount_Skipped(t *testing.T) {
	v := NewValidator(111, nil, nil, nil)
	// Account prefix 222 = partner, not ours
	p := mkRealAccountMonas("222000000000000001", decimal.NewFromInt(-100), "USD")
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r != nil {
		t.Errorf("expected nil reason for partner account, got %+v", r)
	}
}

// ---------------------------------------------------------------------------
// ValidatePosting — NO_SUCH_ACCOUNT
// ---------------------------------------------------------------------------

func TestValidatePosting_NoSuchAccount(t *testing.T) {
	bc := &fakeBC{byNum: map[string]fakeAccountInfo{}}
	v := NewValidator(111, nil, bc, nil)
	p := mkRealAccountMonas("111000000000000099", decimal.NewFromInt(-100), "USD")
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r == nil || r.Reason != protocol.ReasonNoSuchAccount {
		t.Errorf("expected NO_SUCH_ACCOUNT, got %+v", r)
	}
}

func TestValidatePosting_PersonAccount_NoSuchAccount(t *testing.T) {
	bc := &fakeBC{
		byNum:           map[string]fakeAccountInfo{},
		byOwnerCurrency: map[string]string{},
	}
	v := NewValidator(111, nil, bc, nil)
	p := protocol.Posting{
		Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "C-7"}},
		Amount:  decimal.NewFromInt(-50),
		Asset:   &protocol.MonasAsset{Currency: "USD"},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r == nil || r.Reason != protocol.ReasonNoSuchAccount {
		t.Errorf("expected NO_SUCH_ACCOUNT for unknown person, got %+v", r)
	}
}

// ---------------------------------------------------------------------------
// ValidatePosting — NO_SUCH_ASSET (currency mismatch)
// ---------------------------------------------------------------------------

func TestValidatePosting_NoSuchAsset_CurrencyMismatch(t *testing.T) {
	bc := &fakeBC{
		byNum: map[string]fakeAccountInfo{
			"111000000000000001": {Currency: "EUR", AvailableBalance: decimal.NewFromInt(1000)},
		},
	}
	v := NewValidator(111, nil, bc, nil)
	// Posting in USD but the account is denominated in EUR
	p := mkRealAccountMonas("111000000000000001", decimal.NewFromInt(-100), "USD")
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r == nil || r.Reason != protocol.ReasonNoSuchAsset {
		t.Errorf("expected NO_SUCH_ASSET, got %+v", r)
	}
}

// ---------------------------------------------------------------------------
// ValidatePosting — INSUFFICIENT_ASSET
// ---------------------------------------------------------------------------

func TestValidatePosting_InsufficientAsset(t *testing.T) {
	bc := &fakeBC{
		byNum: map[string]fakeAccountInfo{
			"111000000000000001": {Currency: "USD", AvailableBalance: decimal.NewFromInt(50)},
		},
	}
	v := NewValidator(111, nil, bc, nil)
	p := mkRealAccountMonas("111000000000000001", decimal.NewFromInt(-100), "USD")
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r == nil || r.Reason != protocol.ReasonInsufficientAsset {
		t.Errorf("expected INSUFFICIENT_ASSET, got %+v", r)
	}
}

func TestValidatePosting_SufficientAsset_OK(t *testing.T) {
	bc := &fakeBC{
		byNum: map[string]fakeAccountInfo{
			"111000000000000001": {Currency: "USD", AvailableBalance: decimal.NewFromInt(200)},
		},
	}
	v := NewValidator(111, nil, bc, nil)
	p := mkRealAccountMonas("111000000000000001", decimal.NewFromInt(-100), "USD")
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r != nil {
		t.Errorf("expected nil reason (valid posting), got %+v", r)
	}
}

func TestValidatePosting_CreditPosting_NotChecked(t *testing.T) {
	// Positive (credit) postings don't trigger INSUFFICIENT_ASSET even if balance is 0.
	bc := &fakeBC{
		byNum: map[string]fakeAccountInfo{
			"111000000000000001": {Currency: "USD", AvailableBalance: decimal.NewFromInt(0)},
		},
	}
	v := NewValidator(111, nil, bc, nil)
	p := mkRealAccountMonas("111000000000000001", decimal.NewFromInt(100), "USD")
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r != nil {
		t.Errorf("expected nil reason for credit posting, got %+v", r)
	}
}

// ---------------------------------------------------------------------------
// ValidatePosting — UNACCEPTABLE_ASSET
// ---------------------------------------------------------------------------

func TestValidatePosting_UnacceptableAsset_AccountPlusStock(t *testing.T) {
	// ACCOUNT + STOCK is always unacceptable (stocks don't sit on real accounts).
	v := NewValidator(111, nil, nil, nil)
	p := protocol.Posting{
		Account: &protocol.RealAccount{Num: "111000000000000001"},
		Amount:  decimal.NewFromInt(-10),
		Asset:   &protocol.StockAsset{Ticker: "AAPL"},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r == nil || r.Reason != protocol.ReasonUnacceptableAsset {
		t.Errorf("expected UNACCEPTABLE_ASSET, got %+v", r)
	}
}

func TestValidatePosting_UnacceptableAsset_PersonPlusStock(t *testing.T) {
	v := NewValidator(111, nil, nil, nil)
	p := protocol.Posting{
		Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "C-5"}},
		Amount:  decimal.NewFromInt(-10),
		Asset:   &protocol.StockAsset{Ticker: "AAPL"},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r == nil || r.Reason != protocol.ReasonUnacceptableAsset {
		t.Errorf("expected UNACCEPTABLE_ASSET for Person+Stock, got %+v", r)
	}
}

// FIX 3: with no negotiation lookup wired, a MONAS leg on our OPTION pseudo-account
// can no longer be authorised, so it is rejected as OPTION_NEGOTIATION_NOT_FOUND
// (previously a blanket UNACCEPTABLE_ASSET). The premium/share legs of a partner-
// coordinated accept are allowed only against a live negotiation (see the FIX 3 tests
// below).
func TestValidatePosting_OptionAccountPlusMonas_NoNegLookup_NotFound(t *testing.T) {
	v := NewValidator(111, nil, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-1"}},
		Amount:  decimal.NewFromInt(-100),
		Asset:   &protocol.MonasAsset{Currency: "USD"},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r == nil || r.Reason != protocol.ReasonOptionNegotiationNotFound {
		t.Errorf("expected OPTION_NEGOTIATION_NOT_FOUND for OptionAccount+Monas without neg lookup, got %+v", r)
	}
}

// FIX 3: an OPTION pseudo-account with a genuinely unsupported asset type stays
// UNACCEPTABLE_ASSET. (OptionAsset/MONAS/STOCK are the only handled cases.) We cannot
// construct an unknown protocol.Asset here without an internal type, so this guard is
// covered by the default branch in validateOptionPseudoAccount; the MONAS/STOCK accept
// path is exercised by the tests below.

// FIX 3: when B2 coordinates and B1 is the seller, B2 hangs the premium MONAS leg off
// the OPTION pseudo-account. With a live negotiation on our routing, B1 must accept it
// (return nil) instead of voting UNACCEPTABLE_ASSET → 2PC abort.
func TestValidatePosting_OptionAccountPlusMonas_LiveNegotiation_Accepted(t *testing.T) {
	td := &fakeTD{negs: map[string]fakeNegInfo{
		"111:neg-live": {
			isOngoing:    true,
			settlement:   time.Now().Add(30 * 24 * time.Hour),
			amount:       10,
			pricePerUnit: decimal.NewFromInt(200),
		},
	}}
	v := NewValidator(111, td, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-live"}},
		Amount:  decimal.NewFromInt(1000), // premium credit leg
		Asset:   &protocol.MonasAsset{Currency: "USD"},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r != nil {
		t.Errorf("expected nil (accepted premium leg on live negotiation), got %+v", r)
	}
}

// FIX 3: a STOCK leg on the OPTION pseudo-account is likewise accepted against a live
// negotiation.
func TestValidatePosting_OptionAccountPlusStock_LiveNegotiation_Accepted(t *testing.T) {
	td := &fakeTD{negs: map[string]fakeNegInfo{
		"111:neg-live": {
			isOngoing:    true,
			settlement:   time.Now().Add(30 * 24 * time.Hour),
			amount:       10,
			pricePerUnit: decimal.NewFromInt(200),
		},
	}}
	v := NewValidator(111, td, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-live"}},
		Amount:  decimal.NewFromInt(-10),
		Asset:   &protocol.StockAsset{Ticker: "AAPL"},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r != nil {
		t.Errorf("expected nil (accepted stock leg on live negotiation), got %+v", r)
	}
}

// FIX 3: a MONAS/STOCK leg on a CLOSED negotiation's pseudo-account is rejected as
// OPTION_USED_OR_EXPIRED (the negotiation is no longer live).
func TestValidatePosting_OptionAccountPlusMonas_ClosedNegotiation_Rejected(t *testing.T) {
	td := &fakeTD{negs: map[string]fakeNegInfo{
		"111:neg-closed": {
			isOngoing:    false,
			settlement:   time.Now().Add(30 * 24 * time.Hour),
			amount:       10,
			pricePerUnit: decimal.NewFromInt(200),
		},
	}}
	v := NewValidator(111, td, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-closed"}},
		Amount:  decimal.NewFromInt(1000),
		Asset:   &protocol.MonasAsset{Currency: "USD"},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r == nil || r.Reason != protocol.ReasonOptionUsedOrExpired {
		t.Errorf("expected OPTION_USED_OR_EXPIRED for closed negotiation, got %+v", r)
	}
}

// EXERCISE fix — the cross-bank exercise of an ACCEPTED option (B2 buyer / B1 seller).
// After accept the negotiation is CLOSED (is_ongoing=false), but the resulting contract is
// still ACTIVE with a future settlement. The §2.7.2 exercise tx hangs the seller's
// stock-delivery (STOCK) and strike (MONAS) legs off the option pseudo-account. These MUST
// pass — pre-fix they were rejected OPTION_USED_OR_EXPIRED, making every accepted cross-bank
// option impossible to exercise.
func TestValidatePosting_OptionAccountPlusStock_ExercisableClosedNegotiation_Accepted(t *testing.T) {
	td := &fakeTD{negs: map[string]fakeNegInfo{
		"111:neg-9f1830": {
			isOngoing:          false, // closed on accept — the bug trigger
			settlement:         time.Now().Add(-1 * time.Hour),
			amount:             1,
			pricePerUnit:       decimal.NewFromInt(200),
			contractStatus:     ContractStatusActive,               // contract is still live …
			contractSettlement: time.Now().Add(7 * 24 * time.Hour), // … with a future settlement
		},
	}}
	v := NewValidator(111, td, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-9f1830"}},
		Amount:  decimal.NewFromInt(-1), // seller delivers k stocks
		Asset:   &protocol.StockAsset{Ticker: "AAPL"},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r != nil {
		t.Errorf("expected nil (exercisable closed-negotiation + ACTIVE contract), got %+v", r)
	}
}

func TestValidatePosting_OptionAccountPlusMonas_ExercisableClosedNegotiation_Accepted(t *testing.T) {
	td := &fakeTD{negs: map[string]fakeNegInfo{
		"111:neg-ex": {
			isOngoing:          false,
			settlement:         time.Now().Add(-1 * time.Hour),
			amount:             1,
			pricePerUnit:       decimal.NewFromInt(200),
			contractStatus:     ContractStatusActive,
			contractSettlement: time.Now().Add(7 * 24 * time.Hour),
		},
	}}
	v := NewValidator(111, td, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-ex"}},
		Amount:  decimal.NewFromInt(200), // strike into option pseudo-account
		Asset:   &protocol.MonasAsset{Currency: "USD"},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r != nil {
		t.Errorf("expected nil (exercisable closed-negotiation MONAS leg), got %+v", r)
	}
}

// An already-EXERCISED contract on a closed negotiation must STILL be rejected (a second
// exercise is not allowed) — exercisability requires an ACTIVE contract, not just any.
func TestValidatePosting_OptionAccountPlusStock_AlreadyExercisedContract_Rejected(t *testing.T) {
	td := &fakeTD{negs: map[string]fakeNegInfo{
		"111:neg-done": {
			isOngoing:          false,
			settlement:         time.Now().Add(-1 * time.Hour),
			amount:             1,
			pricePerUnit:       decimal.NewFromInt(200),
			contractStatus:     "EXERCISED",
			contractSettlement: time.Now().Add(7 * 24 * time.Hour),
		},
	}}
	v := NewValidator(111, td, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-done"}},
		Amount:  decimal.NewFromInt(-1),
		Asset:   &protocol.StockAsset{Ticker: "AAPL"},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r == nil || r.Reason != protocol.ReasonOptionUsedOrExpired {
		t.Errorf("expected OPTION_USED_OR_EXPIRED for already-exercised contract, got %+v", r)
	}
}

// A closed negotiation whose ACTIVE contract is PAST its settlement is not exercisable.
func TestValidatePosting_OptionAccountPlusStock_ContractPastSettlement_Rejected(t *testing.T) {
	td := &fakeTD{negs: map[string]fakeNegInfo{
		"111:neg-late": {
			isOngoing:          false,
			settlement:         time.Now().Add(-48 * time.Hour),
			amount:             1,
			pricePerUnit:       decimal.NewFromInt(200),
			contractStatus:     ContractStatusActive,
			contractSettlement: time.Now().Add(-1 * time.Hour), // contract settlement already passed
		},
	}}
	v := NewValidator(111, td, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-late"}},
		Amount:  decimal.NewFromInt(-1),
		Asset:   &protocol.StockAsset{Ticker: "AAPL"},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r == nil || r.Reason != protocol.ReasonOptionUsedOrExpired {
		t.Errorf("expected OPTION_USED_OR_EXPIRED for past-settlement contract, got %+v", r)
	}
}

// The OPTION-unit leg (OptionAsset) on a closed negotiation with an ACTIVE contract is also
// exercisable — keeps validateOption consistent with validateNegotiationOpen.
func TestValidatePosting_OptionUnit_ExercisableClosedNegotiation_Accepted(t *testing.T) {
	td := &fakeTD{negs: map[string]fakeNegInfo{
		"111:neg-unit": {
			isOngoing:          false,
			settlement:         time.Now().Add(-1 * time.Hour),
			amount:             10,
			pricePerUnit:       decimal.NewFromInt(200),
			contractStatus:     ContractStatusActive,
			contractSettlement: time.Now().Add(7 * 24 * time.Hour),
		},
	}}
	v := NewValidator(111, td, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-unit"}},
		Amount:  decimal.NewFromInt(1), // valid amount (∈ {1, k, k·π})
		Asset: &protocol.OptionAsset{OptionDescription: protocol.OptionDescription{
			NegotiationId:  protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-unit"},
			Stock:          protocol.StockDescription{Ticker: "AAPL"},
			PricePerUnit:   protocol.MonetaryValue{Currency: "USD", Amount: decimal.NewFromInt(200)},
			SettlementDate: "2026-12-01T00:00:00Z",
			Amount:         10,
		}},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r != nil {
		t.Errorf("expected nil (exercisable OPTION-unit leg), got %+v", r)
	}
}

// FIX 3: a MONAS/STOCK leg on a PARTNER-routed pseudo-account is not ours to validate.
func TestValidatePosting_OptionAccountPlusMonas_PartnerRouting_NotOurs(t *testing.T) {
	v := NewValidator(111, &fakeTD{negs: map[string]fakeNegInfo{}}, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 222, Id: "neg-partner"}},
		Amount:  decimal.NewFromInt(1000),
		Asset:   &protocol.MonasAsset{Currency: "USD"},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r != nil {
		t.Errorf("expected nil (partner-routed pseudo-account is not ours), got %+v", r)
	}
}

// ---------------------------------------------------------------------------
// ValidatePosting — OPTION_NEGOTIATION_NOT_FOUND
// ---------------------------------------------------------------------------

func TestValidatePosting_OptionNegotiationNotFound(t *testing.T) {
	td := &fakeTD{negs: map[string]fakeNegInfo{}}
	v := NewValidator(111, td, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-missing"}},
		Amount:  decimal.NewFromInt(-1),
		Asset: &protocol.OptionAsset{OptionDescription: protocol.OptionDescription{
			NegotiationId:  protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-missing"},
			Stock:          protocol.StockDescription{Ticker: "AAPL"},
			PricePerUnit:   protocol.MonetaryValue{Currency: "USD", Amount: decimal.NewFromInt(200)},
			SettlementDate: "2026-12-01T00:00:00Z",
			Amount:         10,
		}},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r == nil || r.Reason != protocol.ReasonOptionNegotiationNotFound {
		t.Errorf("expected OPTION_NEGOTIATION_NOT_FOUND, got %+v", r)
	}
}

// ---------------------------------------------------------------------------
// ValidatePosting — OPTION_USED_OR_EXPIRED
// ---------------------------------------------------------------------------

func TestValidatePosting_OptionUsedOrExpired_NotOngoing(t *testing.T) {
	td := &fakeTD{negs: map[string]fakeNegInfo{
		"111:neg-expired": {
			isOngoing:    false,
			settlement:   time.Now().Add(30 * 24 * time.Hour),
			amount:       10,
			pricePerUnit: decimal.NewFromInt(200),
		},
	}}
	v := NewValidator(111, td, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-expired"}},
		Amount:  decimal.NewFromInt(1),
		Asset: &protocol.OptionAsset{OptionDescription: protocol.OptionDescription{
			NegotiationId:  protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-expired"},
			Stock:          protocol.StockDescription{Ticker: "AAPL"},
			PricePerUnit:   protocol.MonetaryValue{Currency: "USD", Amount: decimal.NewFromInt(200)},
			SettlementDate: "2026-12-01T00:00:00Z",
			Amount:         10,
		}},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r == nil || r.Reason != protocol.ReasonOptionUsedOrExpired {
		t.Errorf("expected OPTION_USED_OR_EXPIRED (not ongoing), got %+v", r)
	}
}

func TestValidatePosting_OptionUsedOrExpired_PastSettlement(t *testing.T) {
	td := &fakeTD{negs: map[string]fakeNegInfo{
		"111:neg-past": {
			isOngoing:    true,
			settlement:   time.Now().Add(-1 * time.Hour), // settlement already passed
			amount:       10,
			pricePerUnit: decimal.NewFromInt(200),
		},
	}}
	v := NewValidator(111, td, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-past"}},
		Amount:  decimal.NewFromInt(1),
		Asset: &protocol.OptionAsset{OptionDescription: protocol.OptionDescription{
			NegotiationId:  protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-past"},
			Stock:          protocol.StockDescription{Ticker: "AAPL"},
			PricePerUnit:   protocol.MonetaryValue{Currency: "USD", Amount: decimal.NewFromInt(200)},
			SettlementDate: "2024-01-01T00:00:00Z",
			Amount:         10,
		}},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r == nil || r.Reason != protocol.ReasonOptionUsedOrExpired {
		t.Errorf("expected OPTION_USED_OR_EXPIRED (past settlement), got %+v", r)
	}
}

// ---------------------------------------------------------------------------
// ValidatePosting — OPTION_AMOUNT_INCORRECT
// ---------------------------------------------------------------------------

func TestValidatePosting_OptionAmountIncorrect(t *testing.T) {
	td := &fakeTD{negs: map[string]fakeNegInfo{
		"111:neg-1": {
			isOngoing:    true,
			settlement:   time.Now().Add(30 * 24 * time.Hour),
			amount:       10,
			pricePerUnit: decimal.NewFromInt(200),
		},
	}}
	v := NewValidator(111, td, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-1"}},
		Amount:  decimal.NewFromInt(7), // not 1, not 10 (=k), not 2000 (=k·π)
		Asset: &protocol.OptionAsset{OptionDescription: protocol.OptionDescription{
			NegotiationId:  protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-1"},
			Stock:          protocol.StockDescription{Ticker: "AAPL"},
			PricePerUnit:   protocol.MonetaryValue{Currency: "USD", Amount: decimal.NewFromInt(200)},
			SettlementDate: "2026-12-01T00:00:00Z",
			Amount:         10,
		}},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r == nil || r.Reason != protocol.ReasonOptionAmountIncorrect {
		t.Errorf("expected OPTION_AMOUNT_INCORRECT, got %+v", r)
	}
}

func TestValidatePosting_OptionAmount_One_Valid(t *testing.T) {
	// amount=1 is valid (accept flow per spec §3.6)
	td := &fakeTD{negs: map[string]fakeNegInfo{
		"111:neg-2": {
			isOngoing:    true,
			settlement:   time.Now().Add(30 * 24 * time.Hour),
			amount:       10,
			pricePerUnit: decimal.NewFromInt(200),
		},
	}}
	v := NewValidator(111, td, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-2"}},
		Amount:  decimal.NewFromInt(1),
		Asset: &protocol.OptionAsset{OptionDescription: protocol.OptionDescription{
			NegotiationId:  protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-2"},
			Stock:          protocol.StockDescription{Ticker: "AAPL"},
			PricePerUnit:   protocol.MonetaryValue{Currency: "USD", Amount: decimal.NewFromInt(200)},
			SettlementDate: "2026-12-01T00:00:00Z",
			Amount:         10,
		}},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r != nil {
		t.Errorf("expected nil (amount=1 is valid), got %+v", r)
	}
}

func TestValidatePosting_OptionAmount_K_Valid(t *testing.T) {
	// amount=k (=10) is valid (stock exercise flow)
	td := &fakeTD{negs: map[string]fakeNegInfo{
		"111:neg-3": {
			isOngoing:    true,
			settlement:   time.Now().Add(30 * 24 * time.Hour),
			amount:       10,
			pricePerUnit: decimal.NewFromInt(200),
		},
	}}
	v := NewValidator(111, td, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-3"}},
		Amount:  decimal.NewFromInt(10), // k
		Asset: &protocol.OptionAsset{OptionDescription: protocol.OptionDescription{
			NegotiationId:  protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-3"},
			Stock:          protocol.StockDescription{Ticker: "AAPL"},
			PricePerUnit:   protocol.MonetaryValue{Currency: "USD", Amount: decimal.NewFromInt(200)},
			SettlementDate: "2026-12-01T00:00:00Z",
			Amount:         10,
		}},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r != nil {
		t.Errorf("expected nil (amount=k is valid), got %+v", r)
	}
}

func TestValidatePosting_OptionAmount_KPi_Valid(t *testing.T) {
	// amount=k·π (=10*200=2000) is valid (monas exercise flow)
	td := &fakeTD{negs: map[string]fakeNegInfo{
		"111:neg-4": {
			isOngoing:    true,
			settlement:   time.Now().Add(30 * 24 * time.Hour),
			amount:       10,
			pricePerUnit: decimal.NewFromInt(200),
		},
	}}
	v := NewValidator(111, td, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-4"}},
		Amount:  decimal.NewFromInt(2000), // k·π = 10 * 200
		Asset: &protocol.OptionAsset{OptionDescription: protocol.OptionDescription{
			NegotiationId:  protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-4"},
			Stock:          protocol.StockDescription{Ticker: "AAPL"},
			PricePerUnit:   protocol.MonetaryValue{Currency: "USD", Amount: decimal.NewFromInt(200)},
			SettlementDate: "2026-12-01T00:00:00Z",
			Amount:         10,
		}},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r != nil {
		t.Errorf("expected nil (amount=k·π is valid), got %+v", r)
	}
}

// ---------------------------------------------------------------------------
// PersonAccount + Option (spec §3.6 accept leg — valid)
// ---------------------------------------------------------------------------

func TestValidatePosting_PersonPlusOption_Valid(t *testing.T) {
	v := NewValidator(111, nil, nil, nil)
	p := protocol.Posting{
		Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: 111, Id: "C-5"}},
		Amount:  decimal.NewFromInt(1),
		Asset: &protocol.OptionAsset{OptionDescription: protocol.OptionDescription{
			NegotiationId:  protocol.ForeignBankId{RoutingNumber: 111, Id: "neg-x"},
			Stock:          protocol.StockDescription{Ticker: "AAPL"},
			PricePerUnit:   protocol.MonetaryValue{Currency: "USD", Amount: decimal.NewFromInt(200)},
			SettlementDate: "2026-12-01T00:00:00Z",
			Amount:         10,
		}},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r != nil {
		t.Errorf("expected nil (Person+Option is valid accept-leg), got %+v", r)
	}
}

// ---------------------------------------------------------------------------
// OptionPseudoAccount partner side — should be skipped
// ---------------------------------------------------------------------------

func TestValidatePosting_OptionAccount_PartnerRouting_Skipped(t *testing.T) {
	v := NewValidator(111, nil, nil, nil)
	p := protocol.Posting{
		Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: 222, Id: "neg-remote"}},
		Amount:  decimal.NewFromInt(1),
		Asset: &protocol.OptionAsset{OptionDescription: protocol.OptionDescription{
			NegotiationId:  protocol.ForeignBankId{RoutingNumber: 222, Id: "neg-remote"},
			Stock:          protocol.StockDescription{Ticker: "AAPL"},
			PricePerUnit:   protocol.MonetaryValue{Currency: "USD", Amount: decimal.NewFromInt(200)},
			SettlementDate: "2026-12-01T00:00:00Z",
			Amount:         10,
		}},
	}
	r, err := v.ValidatePosting(context.Background(), p)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if r != nil {
		t.Errorf("expected nil (partner-side option account), got %+v", r)
	}
}
