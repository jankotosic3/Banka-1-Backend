package service

import (
	"context"
	"testing"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// ---------------------------------------------------------------------------
// FIX A — reverse-direction (B1-buyer / B2-seller) exercise canonical shape.
//
// These tests pin the CANONICAL §2.7.2 exercise wire shape the buyer-side
// coordinator must emit so the seller bank recognises the exercise from the
// SHAPE (Stock+Option signature) and settles its seller internally. They also
// verify money/asset conservation: the buyer strike is reserved+committed
// OFF-WIRE (not a posting) and the MONAS/STOCK groups each net to zero.
// ---------------------------------------------------------------------------

// fakeSellerNegResolver maps a local negotiation id to a seller-bank id.
type fakeSellerNegResolver struct {
	bySellerNeg map[string]string
}

func (f *fakeSellerNegResolver) SellerNegotiationID(_ context.Context, localNegotiationID string) string {
	return f.bySellerNeg[localNegotiationID]
}

// buildBuyerExerciseContract returns a B1-buyer / B2-seller contract ready to
// exercise (mirrors the live DB row otc-…/neg-… with remote seller neg).
func buildBuyerExerciseContract() *store.Contract {
	return &store.Contract{
		ID:             "otc-rev-001",
		NegotiationID:  "neg-local-rev-001", // our LOCAL mirror id
		BuyerRouting:   myRouting,           // 111 — we are the buyer
		BuyerID:        "C-100",
		SellerRouting:  theirRouting, // 222 — partner is the seller
		SellerID:       "C-8",
		StockTicker:    "MSFT",
		Amount:         3,
		StrikeCurrency: "USD",
		StrikeAmount:   decimal.NewFromInt(123),
		SettlementDate: time.Now().Add(48 * time.Hour),
		Status:         store.ContractStatusActive,
	}
}

// TestExerciseContract_ReverseDirection_CanonicalShape asserts the buyer-side
// coordinator emits the canonical exercise legs with the seller-bank routing and
// authoritative negId on the OPTION pseudo-account, the correct signs, and the
// buyer strike kept OFF the wire (reserved + committed locally instead).
func TestExerciseContract_ReverseDirection_CanonicalShape(t *testing.T) {
	const sellerBankNegID = "66b10b51-420e-41a3-9d8e-f2a0ee565933"

	ns := newFakeNegotiationStore()
	cs := &fakeContractStore{}
	contract := buildBuyerExerciseContract()

	// The buyer's USD account is ours, with plenty of funds.
	bc := newFakeBC(map[string]*AccountInfo{
		"111000000000000900": {OwnerID: 100, Currency: "USD", AvailableBalance: decimal.NewFromInt(100000)},
	})
	bc.byOwnerCurrency = map[string]string{
		formatOwnerKey(100, "USD"): "111000000000000900",
	}
	td := &fakeTradingReserver{}
	sellerNeg := &fakeSellerNegResolver{bySellerNeg: map[string]string{
		contract.NegotiationID: sellerBankNegID,
	}}

	outbound := &capturingOutboundClient{vote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	coord := buildCoordinatorForExercise(outbound, ns, cs, bc, sellerNeg, td)

	txID, err := coord.ExerciseContract(context.Background(), contract)
	if err != nil {
		t.Fatalf("ExerciseContract returned error: %v", err)
	}
	if txID.Id == "" {
		t.Fatalf("expected a non-empty tx id")
	}

	// ---- Wire shape ----
	ps := outbound.lastTx.Postings
	if len(ps) != 4 {
		t.Fatalf("expected 4 wire postings, got %d: %+v", len(ps), ps)
	}
	if outbound.sentRouting != theirRouting {
		t.Errorf("expected NEW_TX sent to seller routing %d, got %d", theirRouting, outbound.sentRouting)
	}

	totalStrike := decimal.NewFromInt(123 * 3)
	qty := decimal.NewFromInt(3)

	var sawOptionMonas, sawSellerCash, sawOptionStock, sawBuyerStock bool
	monasSum := decimal.Zero
	stockSum := decimal.Zero
	for _, p := range ps {
		switch a := p.Asset.(type) {
		case *protocol.MonasAsset:
			monasSum = monasSum.Add(p.Amount)
			switch acc := p.Account.(type) {
			case *protocol.OptionPseudoAccount:
				// p1: OPTION pseudo (seller routing + seller-bank negId), +strike.
				sawOptionMonas = true
				if acc.Id.RoutingNumber != theirRouting {
					t.Errorf("p1 OPTION routing = %d, want %d", acc.Id.RoutingNumber, theirRouting)
				}
				if acc.Id.Id != sellerBankNegID {
					t.Errorf("p1 OPTION negId = %q, want seller-bank id %q", acc.Id.Id, sellerBankNegID)
				}
				if !p.Amount.Equal(totalStrike) {
					t.Errorf("p1 OPTION MONAS amount = %s, want +%s", p.Amount, totalStrike)
				}
			case *protocol.PersonAccount:
				// p2: seller PERSON@222, −strike (seller is CREDITED — negative sign).
				sawSellerCash = true
				if acc.Id.RoutingNumber != theirRouting {
					t.Errorf("p2 seller PERSON routing = %d, want %d", acc.Id.RoutingNumber, theirRouting)
				}
				if !p.Amount.Equal(totalStrike.Neg()) {
					t.Errorf("p2 seller cash amount = %s, want −%s (credit-seller sign)", p.Amount, totalStrike)
				}
			default:
				t.Errorf("unexpected MONAS account type %T", acc)
			}
		case *protocol.StockAsset:
			stockSum = stockSum.Add(p.Amount)
			switch acc := p.Account.(type) {
			case *protocol.OptionPseudoAccount:
				// p3: OPTION pseudo (seller routing), −k STOCK — the exercise signature.
				sawOptionStock = true
				if acc.Id.RoutingNumber != theirRouting {
					t.Errorf("p3 OPTION-stock routing = %d, want %d", acc.Id.RoutingNumber, theirRouting)
				}
				if acc.Id.Id != sellerBankNegID {
					t.Errorf("p3 OPTION-stock negId = %q, want %q", acc.Id.Id, sellerBankNegID)
				}
				if !p.Amount.Equal(qty.Neg()) {
					t.Errorf("p3 OPTION stock amount = %s, want −%s", p.Amount, qty)
				}
				if a.Ticker != "MSFT" {
					t.Errorf("p3 ticker = %q, want MSFT", a.Ticker)
				}
			case *protocol.PersonAccount:
				// p4: buyer PERSON@111, +k STOCK.
				sawBuyerStock = true
				if acc.Id.RoutingNumber != myRouting {
					t.Errorf("p4 buyer PERSON routing = %d, want %d", acc.Id.RoutingNumber, myRouting)
				}
				if !p.Amount.Equal(qty) {
					t.Errorf("p4 buyer stock amount = %s, want +%s", p.Amount, qty)
				}
			default:
				t.Errorf("unexpected STOCK account type %T", acc)
			}
		default:
			t.Errorf("unexpected wire asset type %T", a)
		}
	}

	if !sawOptionMonas || !sawSellerCash || !sawOptionStock || !sawBuyerStock {
		t.Fatalf("missing canonical legs: optMonas=%v sellerCash=%v optStock=%v buyerStock=%v",
			sawOptionMonas, sawSellerCash, sawOptionStock, sawBuyerStock)
	}

	// ---- Conservation: each asset group nets to zero on the wire ----
	if !monasSum.IsZero() {
		t.Errorf("MONAS group does not balance: sum = %s (want 0)", monasSum)
	}
	if !stockSum.IsZero() {
		t.Errorf("STOCK group does not balance: sum = %s (want 0)", stockSum)
	}

	// ---- Buyer strike is OFF the wire (no buyer-side negative MONAS posting) ----
	for _, p := range ps {
		if _, isMonas := p.Asset.(*protocol.MonasAsset); !isMonas {
			continue
		}
		if person, ok := p.Account.(*protocol.PersonAccount); ok &&
			person.Id.RoutingNumber == myRouting {
			t.Errorf("buyer strike must NOT be a wire posting; found PERSON@%d MONAS %s", myRouting, p.Amount)
		}
		if real, ok := p.Account.(*protocol.RealAccount); ok {
			t.Errorf("buyer strike must NOT be a wire RealAccount posting; found %s %s", real.Num, p.Amount)
		}
	}

	// ---- Buyer strike was reserved AND committed off-wire (debit applied once) ----
	if len(bc.reserved) != 1 {
		t.Fatalf("expected exactly 1 off-wire buyer-strike reservation, got %d: %+v", len(bc.reserved), bc.reserved)
	}
	if len(bc.committed) != 1 {
		t.Fatalf("expected exactly 1 off-wire buyer-strike commit, got %d: %+v", len(bc.committed), bc.committed)
	}
	if len(bc.released) != 0 {
		t.Errorf("expected no buyer-strike release on the happy path, got %d: %+v", len(bc.released), bc.released)
	}
	if !outbound.committed {
		t.Errorf("expected SendCommitTx to the partner on the happy path")
	}
}

// TestExerciseContract_ReverseDirection_PartnerRejects_ReleasesStrike asserts that
// when the seller bank votes NO the buyer strike reservation is RELEASED (no money
// moves) and never committed.
func TestExerciseContract_ReverseDirection_PartnerRejects_ReleasesStrike(t *testing.T) {
	ns := newFakeNegotiationStore()
	cs := &fakeContractStore{}
	contract := buildBuyerExerciseContract()

	bc := newFakeBC(map[string]*AccountInfo{
		"111000000000000900": {OwnerID: 100, Currency: "USD", AvailableBalance: decimal.NewFromInt(100000)},
	})
	bc.byOwnerCurrency = map[string]string{formatOwnerKey(100, "USD"): "111000000000000900"}
	td := &fakeTradingReserver{}
	sellerNeg := &fakeSellerNegResolver{bySellerNeg: map[string]string{
		contract.NegotiationID: "seller-neg-xyz",
	}}

	outbound := &capturingOutboundClient{vote: protocol.TransactionVote{
		Vote: protocol.VoteNo, Reasons: []protocol.NoVoteReason{{Reason: protocol.ReasonInsufficientAsset}},
	}}
	coord := buildCoordinatorForExercise(outbound, ns, cs, bc, sellerNeg, td)

	if _, err := coord.ExerciseContract(context.Background(), contract); err == nil {
		t.Fatalf("expected error when partner rejects, got nil")
	}

	if len(bc.reserved) != 1 {
		t.Fatalf("expected 1 reservation attempt, got %d", len(bc.reserved))
	}
	if len(bc.committed) != 0 {
		t.Errorf("buyer strike must NOT be committed on partner-reject, got %d: %+v", len(bc.committed), bc.committed)
	}
	if len(bc.released) != 1 {
		t.Errorf("buyer strike must be RELEASED on partner-reject, got %d: %+v", len(bc.released), bc.released)
	}
	if !outbound.rolledBack {
		t.Errorf("expected a partner rollback on reject")
	}
}
