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
// Fakes for OtcContractsService
// ---------------------------------------------------------------------------

type fakeContractStoreForSvc struct {
	mu           sync.Mutex
	rows         []*store.Contract
	byID         map[string]*store.Contract
	statusUpdate map[string]string // id → last UpdateStatus value
	listAll      bool
	listUserID   string
	claimCalls   int
	revertCalls  int
	claimErr     error // forced error from ClaimExerciseByID
}

func newFakeContractStoreForSvc() *fakeContractStoreForSvc {
	return &fakeContractStoreForSvc{byID: map[string]*store.Contract{}, statusUpdate: map[string]string{}}
}

func (f *fakeContractStoreForSvc) ListForUser(_ context.Context, userForeignID string, includeAll bool) ([]*store.Contract, error) {
	f.listAll = includeAll
	f.listUserID = userForeignID
	return f.rows, nil
}

func (f *fakeContractStoreForSvc) FindByID(_ context.Context, id string) (*store.Contract, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	c := f.byID[id]
	if c == nil {
		return nil, nil
	}
	// Hand back a copy so callers mutating the returned snapshot (e.g. Status) cannot
	// poison the store's authoritative row that the atomic claim reads.
	cp := *c
	return &cp, nil
}

// ClaimExerciseByID mirrors the store's atomic ACTIVE→EXERCISING CAS under a lock. The
// mutex makes concurrent claims on the same id serialize exactly as the row lock does in
// Postgres: the first caller to find status=='ACTIVE' flips it and wins; everyone else loses.
func (f *fakeContractStoreForSvc) ClaimExerciseByID(_ context.Context, id string) (bool, bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.claimCalls++
	if f.claimErr != nil {
		return false, false, f.claimErr
	}
	c := f.byID[id]
	if c == nil {
		return false, false, nil // does not exist
	}
	if c.Status != store.ContractStatusActive {
		return true, false, nil // exists but not ACTIVE → loser
	}
	c.Status = store.ContractStatusExercising
	return true, true, nil
}

func (f *fakeContractStoreForSvc) RevertExercising(_ context.Context, id string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.revertCalls++
	if c := f.byID[id]; c != nil && c.Status == store.ContractStatusExercising {
		c.Status = store.ContractStatusActive
	}
	return nil
}

func (f *fakeContractStoreForSvc) UpdateStatus(_ context.Context, id, status string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.statusUpdate[id] = status
	if c := f.byID[id]; c != nil {
		c.Status = status
	}
	return nil
}

type fakeExerciser struct {
	mu        sync.Mutex
	called    int
	lastC     *store.Contract
	returnErr error
}

func (f *fakeExerciser) ExerciseContract(_ context.Context, c *store.Contract) (protocol.ForeignBankId, error) {
	f.mu.Lock()
	f.called++
	f.lastC = c
	retErr := f.returnErr
	f.mu.Unlock()
	if retErr != nil {
		return protocol.ForeignBankId{}, retErr
	}
	return protocol.ForeignBankId{RoutingNumber: myRouting, Id: "tx-ex-1"}, nil
}

func (f *fakeExerciser) callCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.called
}

func activeContract(id, buyerID string) *store.Contract {
	return &store.Contract{
		ID:                       id,
		NegotiationID:            "neg-" + id,
		BuyerRouting:             myRouting,
		BuyerID:                  buyerID,
		SellerRouting:            theirRouting,
		SellerID:                 "C-2",
		StockTicker:              "AAPL",
		Amount:                   10,
		StrikeCurrency:           "USD",
		StrikeAmount:             decimal.NewFromInt(150),
		SettlementDate:           time.Now().Add(48 * time.Hour),
		Status:                   store.ContractStatusActive,
		OptionPseudoOwnerRouting: myRouting,
		OptionPseudoOwnerID:      buyerID,
	}
}

// ---------------------------------------------------------------------------
// ListForUser tests
// ---------------------------------------------------------------------------

func TestOtcContractsService_ListForUser_ScopesToUser(t *testing.T) {
	cs := newFakeContractStoreForSvc()
	cs.rows = []*store.Contract{activeContract("otc-1", "C-15")}
	svc := NewOtcContractsService(myRouting, cs, &fakeExerciser{}, discardLogger())

	views, err := svc.ListForUser(context.Background(), 15, false)
	if err != nil {
		t.Fatalf("ListForUser: %v", err)
	}
	if len(views) != 1 || views[0].LocalID != "otc-1" {
		t.Fatalf("unexpected views %+v", views)
	}
	if cs.listAll {
		t.Error("non-admin list must not include all")
	}
	if cs.listUserID != "C-15" {
		t.Errorf("expected scoping to C-15, got %q", cs.listUserID)
	}
}

func TestOtcContractsService_ListForUser_AdminAll(t *testing.T) {
	cs := newFakeContractStoreForSvc()
	svc := NewOtcContractsService(myRouting, cs, &fakeExerciser{}, discardLogger())

	if _, err := svc.ListForUser(context.Background(), 0, true); err != nil {
		t.Fatalf("ListForUser: %v", err)
	}
	if !cs.listAll {
		t.Error("admin list must request all")
	}
}

// ---------------------------------------------------------------------------
// Exercise tests
// ---------------------------------------------------------------------------

func TestOtcContractsService_Exercise_HappyPath(t *testing.T) {
	cs := newFakeContractStoreForSvc()
	c := activeContract("otc-9", "C-15")
	cs.byID["otc-9"] = c
	ex := &fakeExerciser{}
	svc := NewOtcContractsService(myRouting, cs, ex, discardLogger())

	view, err := svc.Exercise(context.Background(), 15, false, "otc-9")
	if err != nil {
		t.Fatalf("Exercise: %v", err)
	}
	if ex.called != 1 {
		t.Errorf("expected ExerciseContract called once, got %d", ex.called)
	}
	if cs.statusUpdate["otc-9"] != store.ContractStatusExercised {
		t.Errorf("expected status flipped to EXERCISED, got %q", cs.statusUpdate["otc-9"])
	}
	if view == nil || view.Status != store.ContractStatusExercised {
		t.Errorf("expected EXERCISED view, got %+v", view)
	}
}

func TestOtcContractsService_Exercise_NotFound(t *testing.T) {
	cs := newFakeContractStoreForSvc()
	svc := NewOtcContractsService(myRouting, cs, &fakeExerciser{}, discardLogger())

	_, err := svc.Exercise(context.Background(), 15, false, "missing")
	if !errors.Is(err, ErrContractNotFound) {
		t.Errorf("expected ErrContractNotFound, got %v", err)
	}
}

func TestOtcContractsService_Exercise_NotBuyer_Forbidden(t *testing.T) {
	cs := newFakeContractStoreForSvc()
	cs.byID["otc-3"] = activeContract("otc-3", "C-15")
	ex := &fakeExerciser{}
	svc := NewOtcContractsService(myRouting, cs, ex, discardLogger())

	// principal 99 is not the buyer (C-15) and not admin.
	_, err := svc.Exercise(context.Background(), 99, false, "otc-3")
	if !errors.Is(err, ErrContractForbidden) {
		t.Errorf("expected ErrContractForbidden, got %v", err)
	}
	if ex.called != 0 {
		t.Error("exercise must not run for a non-buyer")
	}
}

func TestOtcContractsService_Exercise_NotActive_Conflict(t *testing.T) {
	cs := newFakeContractStoreForSvc()
	c := activeContract("otc-4", "C-15")
	c.Status = store.ContractStatusExercised
	cs.byID["otc-4"] = c
	svc := NewOtcContractsService(myRouting, cs, &fakeExerciser{}, discardLogger())

	_, err := svc.Exercise(context.Background(), 15, false, "otc-4")
	if !errors.Is(err, ErrContractNotExercisable) {
		t.Errorf("expected ErrContractNotExercisable, got %v", err)
	}
}

func TestOtcContractsService_Exercise_SettlementPassed_Conflict(t *testing.T) {
	cs := newFakeContractStoreForSvc()
	c := activeContract("otc-5", "C-15")
	c.SettlementDate = time.Now().Add(-1 * time.Hour)
	cs.byID["otc-5"] = c
	svc := NewOtcContractsService(myRouting, cs, &fakeExerciser{}, discardLogger())

	_, err := svc.Exercise(context.Background(), 15, false, "otc-5")
	if !errors.Is(err, ErrContractNotExercisable) {
		t.Errorf("expected ErrContractNotExercisable for past settlement, got %v", err)
	}
}

func TestOtcContractsService_Exercise_AdminCanExercise(t *testing.T) {
	cs := newFakeContractStoreForSvc()
	cs.byID["otc-6"] = activeContract("otc-6", "C-15")
	ex := &fakeExerciser{}
	svc := NewOtcContractsService(myRouting, cs, ex, discardLogger())

	// Admin (principal 0, isAdmin true) may exercise on behalf of the buyer.
	if _, err := svc.Exercise(context.Background(), 0, true, "otc-6"); err != nil {
		t.Fatalf("admin Exercise: %v", err)
	}
	if ex.called != 1 {
		t.Errorf("expected exercise to run for admin, got called=%d", ex.called)
	}
}

// ---------------------------------------------------------------------------
// Coordinator.ExerciseContract posting structure
// ---------------------------------------------------------------------------

func TestCoordinator_ExerciseContract_PostingStructure(t *testing.T) {
	ns := newFakeNegotiationStore()
	cs := &fakeContractStore{}
	// Buyer strike account is ours; resolveMonasAccount → RealAccount via fakeBC.
	bc := newFakeBC(map[string]*AccountInfo{
		"111000001234567890": {OwnerID: 15, Currency: "USD", AvailableBalance: decimal.NewFromInt(1_000_000)},
	})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(15, "USD"): "111000001234567890"}
	outbound := &capturingOutboundClient{vote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	coord := buildCoordinatorWithBC(outbound, ns, cs, bc)

	contract := &store.Contract{
		ID:             "otc-ex",
		NegotiationID:  "neg-ex",
		BuyerRouting:   myRouting,
		BuyerID:        "C-15",
		SellerRouting:  theirRouting,
		SellerID:       "C-2",
		StockTicker:    "AAPL",
		Amount:         10,
		StrikeCurrency: "USD",
		StrikeAmount:   decimal.NewFromInt(150),
		SettlementDate: time.Now().Add(48 * time.Hour),
		Status:         store.ContractStatusActive,
	}

	txID, err := coord.ExerciseContract(context.Background(), contract)
	if err != nil {
		t.Fatalf("ExerciseContract: %v", err)
	}
	if txID.RoutingNumber != myRouting || txID.Id == "" {
		t.Errorf("expected our-routing tx id, got %+v", txID)
	}
	if outbound.sentRouting != theirRouting {
		t.Errorf("expected partner routing %d, got %d", theirRouting, outbound.sentRouting)
	}
	if !outbound.committed {
		t.Error("expected SendCommitTx to be called")
	}

	// 4 balanced postings: per-asset sums must be zero.
	postings := outbound.lastTx.Postings
	if len(postings) != 4 {
		t.Fatalf("expected 4 postings, got %d", len(postings))
	}
	monasSum := decimal.Zero
	stockSum := decimal.Zero
	for _, p := range postings {
		switch p.Asset.(type) {
		case *protocol.MonasAsset:
			monasSum = monasSum.Add(p.Amount)
		case *protocol.StockAsset:
			stockSum = stockSum.Add(p.Amount)
		default:
			t.Errorf("unexpected asset type %T", p.Asset)
		}
	}
	if !monasSum.IsZero() {
		t.Errorf("MONAS postings must balance, got %s", monasSum)
	}
	if !stockSum.IsZero() {
		t.Errorf("STOCK postings must balance, got %s", stockSum)
	}

	// Total strike = 150 × 10 = 1500; buyer debit must be −1500 MONAS.
	wantStrike := decimal.NewFromInt(1500)
	var sawBuyerDebit, sawSellerStockDebit bool
	for _, p := range postings {
		if _, ok := p.Asset.(*protocol.MonasAsset); ok && p.Amount.Equal(wantStrike.Neg()) {
			sawBuyerDebit = true
		}
		if sa, ok := p.Asset.(*protocol.StockAsset); ok && sa.Ticker == "AAPL" && p.Amount.Equal(decimal.NewFromInt(-10)) {
			sawSellerStockDebit = true
		}
	}
	if !sawBuyerDebit {
		t.Error("expected a −1500 MONAS buyer strike debit posting")
	}
	if !sawSellerStockDebit {
		t.Error("expected a −10 STOCK seller delivery posting")
	}
}

// ---------------------------------------------------------------------------
// P0 — buyer-side concurrency-window: the entry claim (ACTIVE→EXERCISING) must
// serialize concurrent/sequential Exercise(sameContract) so the off-wire buyer
// strike is reserved+committed AT MOST ONCE (no double charge).
// ---------------------------------------------------------------------------

// TestOtcContractsService_Exercise_SequentialDouble_OnlyOneCharges reproduces the
// original P0 deterministically WITHOUT goroutine timing: a second Exercise of the same
// contract (e.g. a double-submit / retry that re-reads ACTIVE before the first settles)
// must be rejected by the claim — never reaching ExerciseContract a second time.
func TestOtcContractsService_Exercise_SequentialDouble_OnlyOneCharges(t *testing.T) {
	cs := newFakeContractStoreForSvc()
	cs.byID["otc-dup"] = activeContract("otc-dup", "C-15")
	ex := &fakeExerciser{}
	svc := NewOtcContractsService(myRouting, cs, ex, discardLogger())

	// First exercise wins the claim and settles.
	if _, err := svc.Exercise(context.Background(), 15, false, "otc-dup"); err != nil {
		t.Fatalf("first Exercise: %v", err)
	}
	// Second exercise: contract is now EXERCISED — the pre-filter rejects it as
	// not-exercisable (a perfectly valid rejection); either way ExerciseContract must NOT
	// run again and no second charge can occur.
	_, err := svc.Exercise(context.Background(), 15, false, "otc-dup")
	if err == nil {
		t.Fatalf("second Exercise of a settled contract must be rejected, got nil")
	}
	if !errors.Is(err, ErrContractNotExercisable) && !errors.Is(err, ErrContractAlreadyExercising) {
		t.Errorf("expected conflict (NotExercisable/AlreadyExercising), got %v", err)
	}
	if ex.callCount() != 1 {
		t.Fatalf("DOUBLE CHARGE: ExerciseContract ran %d times, want exactly 1", ex.callCount())
	}
}

// TestOtcContractsService_Exercise_ConcurrentDouble_OnlyOneCharges is the headline
// reproduction: TWO goroutines call Exercise(sameContract) concurrently. Both can pass the
// ACTIVE pre-filter on the snapshot, but the atomic ACTIVE→EXERCISING claim lets exactly
// ONE through to ExerciseContract (one reserve+commit buyer strike); the other is rejected
// with ErrContractAlreadyExercising and performs NO charge. Before the fix both reached
// commitBuyerStrike → buyer double-charged.
func TestOtcContractsService_Exercise_ConcurrentDouble_OnlyOneCharges(t *testing.T) {
	cs := newFakeContractStoreForSvc()
	cs.byID["otc-race"] = activeContract("otc-race", "C-15")

	// Block ExerciseContract until both goroutines have entered, widening the race window so a
	// missing claim WOULD let both through (the test would then fail, as it must pre-fix).
	enter := make(chan struct{}, 2)
	release := make(chan struct{})
	ex := &gatedExerciser{enter: enter, release: release}
	svc := NewOtcContractsService(myRouting, cs, ex, discardLogger())

	type res struct {
		err error
	}
	results := make(chan res, 2)
	for i := 0; i < 2; i++ {
		go func() {
			_, err := svc.Exercise(context.Background(), 15, false, "otc-race")
			results <- res{err: err}
		}()
	}

	// At most ONE goroutine should reach the gated exerciser (the claim winner). Wait briefly
	// for the winner to enter, then release. The loser must be rejected by the claim BEFORE
	// ever entering ExerciseContract, so we never expect a second `enter`.
	<-enter
	close(release)

	// The loser is rejected by a conflict — EITHER the atomic claim (ErrContractAlreadyExercising,
	// when it read the still-ACTIVE snapshot but lost the CAS) OR the cheap pre-filter
	// (ErrContractNotExercisable, when it read the snapshot after the winner already settled to
	// EXERCISED). Both paths prevent the double charge — that is the money-conservation invariant.
	var winners, conflicts, others int
	for i := 0; i < 2; i++ {
		r := <-results
		switch {
		case r.err == nil:
			winners++
		case errors.Is(r.err, ErrContractAlreadyExercising) || errors.Is(r.err, ErrContractNotExercisable):
			conflicts++
		default:
			others++
			t.Errorf("unexpected error from concurrent exercise: %v", r.err)
		}
	}

	if winners != 1 {
		t.Fatalf("expected exactly 1 winner, got %d (conflicts=%d others=%d)", winners, conflicts, others)
	}
	if conflicts != 1 {
		t.Fatalf("expected exactly 1 conflict rejection, got %d", conflicts)
	}
	if got := ex.callCount(); got != 1 {
		t.Fatalf("DOUBLE CHARGE: ExerciseContract ran %d times, want exactly 1", got)
	}
	if cs.statusUpdate["otc-race"] != store.ContractStatusExercised {
		t.Errorf("winner must flip the contract to EXERCISED, got %q", cs.statusUpdate["otc-race"])
	}
	if cs.revertCalls != 0 {
		t.Errorf("happy-path winner must not revert the claim, got %d revert calls", cs.revertCalls)
	}
}

// TestOtcContractsService_Exercise_FailureRevertsToActive verifies that when the 2PC fails
// AFTER the claim, the contract is reverted EXERCISING→ACTIVE so the user can retry — and the
// retry then succeeds (claim wins again, settles once).
func TestOtcContractsService_Exercise_FailureRevertsToActive(t *testing.T) {
	cs := newFakeContractStoreForSvc()
	cs.byID["otc-fail"] = activeContract("otc-fail", "C-15")
	ex := &fakeExerciser{returnErr: ErrInterbankProtocol}
	svc := NewOtcContractsService(myRouting, cs, ex, discardLogger())

	// First attempt: claim taken, 2PC fails → must revert to ACTIVE.
	_, err := svc.Exercise(context.Background(), 15, false, "otc-fail")
	if !errors.Is(err, ErrInterbankProtocol) {
		t.Fatalf("expected ErrInterbankProtocol from failed 2PC, got %v", err)
	}
	if cs.revertCalls != 1 {
		t.Fatalf("a 2PC failure after claim must revert exactly once, got %d", cs.revertCalls)
	}
	if got := cs.byID["otc-fail"].Status; got != store.ContractStatusActive {
		t.Fatalf("contract must be back to ACTIVE after revert, got %q", got)
	}

	// Retry after clearing the fault: claim wins again, settles once.
	ex.returnErr = nil
	if _, err := svc.Exercise(context.Background(), 15, false, "otc-fail"); err != nil {
		t.Fatalf("retry after revert: %v", err)
	}
	if cs.statusUpdate["otc-fail"] != store.ContractStatusExercised {
		t.Errorf("retry must flip the contract to EXERCISED, got %q", cs.statusUpdate["otc-fail"])
	}
}

// gatedExerciser blocks inside ExerciseContract until `release` is closed, so a concurrency
// test can hold a "winner" in flight while the racing goroutine attempts its claim. It signals
// `enter` once per call so the test can assert at most one goroutine reaches it.
type gatedExerciser struct {
	mu      sync.Mutex
	called  int
	enter   chan struct{}
	release chan struct{}
}

func (g *gatedExerciser) ExerciseContract(_ context.Context, _ *store.Contract) (protocol.ForeignBankId, error) {
	g.mu.Lock()
	g.called++
	g.mu.Unlock()
	g.enter <- struct{}{}
	<-g.release
	return protocol.ForeignBankId{RoutingNumber: myRouting, Id: "tx-gated"}, nil
}

func (g *gatedExerciser) callCount() int {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.called
}
