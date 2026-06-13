package service

import (
	"context"
	"encoding/json"
	"sync"
	"testing"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

func dec(n int64) decimal.Decimal { return decimal.NewFromInt(n) }

func mustMarshalPostings(t *testing.T, ps []protocol.Posting) string {
	t.Helper()
	b, err := json.Marshal(ps)
	if err != nil {
		t.Fatalf("marshal postings: %v", err)
	}
	return string(b)
}

// ---------------------------------------------------------------------------
// FIX B — per-CONTRACT exercise idempotency gate.
//
// The desync: B1's exercise settlement (seller strike CreditMonas, seller stock
// delivery via ExerciseOption, buyer CreditStock) is keyed per-txId / per-neg, NOT
// per-contract. A coordinator retry with a NEW txId for the SAME contract re-runs
// CommitLocal and re-applies the non-idempotent legs → double strike credit (money
// created) / double stock credit (assets created). ExerciseOption alone is per-neg
// idempotent. The ContractGate serializes different-txId exercises of one contract:
// only the claiming tx settles; an already-EXERCISED contract makes the whole
// exercise settlement a no-op.
// ---------------------------------------------------------------------------

// fakeContractGate simulates the atomic ACTIVE→EXERCISED CAS on interbank_contracts.
// The FIRST ClaimExercise for a negId wins (claimed=true) and marks it EXERCISED;
// subsequent claims for the same negId return claimed=false until ReleaseClaim reverts it.
type fakeContractGate struct {
	mu         sync.Mutex
	exercised  map[string]bool // negId → already EXERCISED
	claimCalls int
	relCalls   int
	failClaim  bool
}

func newFakeContractGate(knownNegs ...string) *fakeContractGate {
	g := &fakeContractGate{exercised: make(map[string]bool)}
	for _, n := range knownNegs {
		// register as known-but-ACTIVE (exists, not yet exercised)
		if _, ok := g.exercised[n]; !ok {
			g.exercised[n] = false
		}
	}
	return g
}

func (g *fakeContractGate) ClaimExercise(_ context.Context, negID string) (bool, bool, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.claimCalls++
	if g.failClaim {
		return false, false, context.DeadlineExceeded
	}
	already, exists := g.exercised[negID]
	if !exists {
		// No contract for this neg — proceed ungated (defensive).
		return false, false, nil
	}
	if already {
		return true, false, nil // exists but already EXERCISED → loser, skip settlement
	}
	g.exercised[negID] = true // claim it
	return true, true, nil
}

func (g *fakeContractGate) ReleaseClaim(_ context.Context, negID string) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.relCalls++
	if _, exists := g.exercised[negID]; exists {
		g.exercised[negID] = false // revert to ACTIVE
	}
	return nil
}

// TestCommitLocal_SellerExercise_NewTxId_SecondSettle_NoOp is the headline FIX B
// reproduction: two SEPARATE inter-bank transactions (different txIds) exercise the
// SAME seller contract. Without the per-contract gate the second commit re-credits the
// seller strike (money created). With the gate, the second exercise is a complete no-op:
// 0 additional CreditMonas, 0 additional ExerciseOption.
func TestCommitLocal_SellerExercise_NewTxId_SecondSettle_NoOp(t *testing.T) {
	negID := "neg-9f1830ac93f58786"
	e, _, bc, td := newSellerExerciseExecutor(t, negID)
	gate := newFakeContractGate(negID)
	e.SetContractGate(gate)

	// --- First exercise (txId #1): claims the contract, settles once. ---
	tx1 := protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-ex-1"}
	if _, err := e.PrepareLocal(context.Background(), sellerExerciseTx(tx1, negID)); err != nil {
		t.Fatalf("PrepareLocal #1: %v", err)
	}
	if err := e.CommitLocal(context.Background(), tx1); err != nil {
		t.Fatalf("CommitLocal #1: %v", err)
	}

	creditsAfter1 := len(bc.credited)
	exercisesAfter1 := countExerciseCommits(td.committed, negID)
	if creditsAfter1 != 1 {
		t.Fatalf("first exercise must credit the seller exactly once, got %d: %+v", creditsAfter1, bc.credited)
	}
	if exercisesAfter1 != 1 {
		t.Fatalf("first exercise must deliver stock exactly once, got %d", exercisesAfter1)
	}

	// --- Second exercise (txId #2, SAME contract): a coordinator retry with a fresh txId. ---
	tx2 := protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-ex-2"}
	if _, err := e.PrepareLocal(context.Background(), sellerExerciseTx(tx2, negID)); err != nil {
		t.Fatalf("PrepareLocal #2: %v", err)
	}
	if err := e.CommitLocal(context.Background(), tx2); err != nil {
		t.Fatalf("CommitLocal #2 (idempotent expected): %v", err)
	}

	// CONSERVATION: no second strike credit, no second stock delivery.
	if got := len(bc.credited); got != creditsAfter1 {
		t.Fatalf("second exercise of same contract DOUBLE-CREDITED the seller: %d → %d credits: %+v",
			creditsAfter1, got, bc.credited)
	}
	if got := countExerciseCommits(td.committed, negID); got != exercisesAfter1 {
		t.Fatalf("second exercise DOUBLE-DELIVERED stock: %d → %d ExerciseOption calls",
			exercisesAfter1, got)
	}
	if gate.claimCalls != 2 {
		t.Errorf("expected 2 claim attempts (one per txId), got %d", gate.claimCalls)
	}
	if gate.relCalls != 0 {
		t.Errorf("happy-path retries must not release the claim, got %d release calls", gate.relCalls)
	}
}

// TestCommitLocal_SellerExercise_SettlementFailureReleasesClaim verifies that when the
// settlement fails AFTER the contract is claimed, the gate claim is REVERTED (so a §2.9
// retransmit can re-settle) — conservation under partial failure.
func TestCommitLocal_SellerExercise_SettlementFailureReleasesClaim(t *testing.T) {
	negID := "neg-9f1830ac93f58786"
	e, _, bc, td := newSellerExerciseExecutor(t, negID)
	gate := newFakeContractGate(negID)
	e.SetContractGate(gate)

	tx1 := protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-ex-fail"}
	if _, err := e.PrepareLocal(context.Background(), sellerExerciseTx(tx1, negID)); err != nil {
		t.Fatalf("PrepareLocal: %v", err)
	}
	// Force the seller strike credit (a settlement ref) to fail on commit.
	bc.failCredit = true

	if err := e.CommitLocal(context.Background(), tx1); err == nil {
		t.Fatalf("expected CommitLocal to fail when settlement credit fails")
	}
	if gate.relCalls != 1 {
		t.Fatalf("settlement failure after claim must release the claim exactly once, got %d", gate.relCalls)
	}

	// Recovery: clear the fault, retry with a NEW txId → claim succeeds again and settles.
	bc.failCredit = false
	td.committed = nil
	bc.credited = nil
	tx2 := protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-ex-retry"}
	if _, err := e.PrepareLocal(context.Background(), sellerExerciseTx(tx2, negID)); err != nil {
		t.Fatalf("PrepareLocal retry: %v", err)
	}
	if err := e.CommitLocal(context.Background(), tx2); err != nil {
		t.Fatalf("CommitLocal retry after release: %v", err)
	}
	if len(bc.credited) != 1 {
		t.Fatalf("post-release retry must settle exactly once, got %d credits: %+v", len(bc.credited), bc.credited)
	}
	if countExerciseCommits(td.committed, negID) != 1 {
		t.Fatalf("post-release retry must deliver stock exactly once, got %v", td.committed)
	}
}

func countExerciseCommits(committed []string, negID string) int {
	want := "option-exercise-" + negID
	n := 0
	for _, c := range committed {
		if c == want {
			n++
		}
	}
	return n
}

// sanity: the exercise neg id is correctly extracted from the seller exercise postings.
func TestExerciseNegotiationIDFromPostings_SellerShape(t *testing.T) {
	negID := "neg-abc"
	tx := sellerExerciseTx(protocol.ForeignBankId{RoutingNumber: 222, Id: "tx-x"}, negID)
	body := mustMarshalPostings(t, tx.Postings)
	if got := exerciseNegotiationIDFromPostings(body); got != negID {
		t.Fatalf("exerciseNegotiationIDFromPostings = %q, want %q", got, negID)
	}
	// Non-exercise (plain MONAS transfer) → "".
	plain := []protocol.Posting{
		{Account: &protocol.RealAccount{Num: "111000000000000001"}, Amount: dec(-10), Asset: &protocol.MonasAsset{Currency: "USD"}},
		{Account: &protocol.RealAccount{Num: "222000000000000002"}, Amount: dec(10), Asset: &protocol.MonasAsset{Currency: "USD"}},
	}
	if got := exerciseNegotiationIDFromPostings(mustMarshalPostings(t, plain)); got != "" {
		t.Fatalf("plain transfer must yield empty neg id, got %q", got)
	}
	_ = store.RefKindOptionExercise // keep store import used if helpers change
}
