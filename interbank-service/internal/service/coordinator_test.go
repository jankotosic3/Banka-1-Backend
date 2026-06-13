package service

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// ---------------------------------------------------------------------------
// fakeOutboundClient
// ---------------------------------------------------------------------------

type fakeOutboundClient struct {
	vote     protocol.TransactionVote
	sendErr  error
	commitErr error
	committed bool
	rolledBack bool
}

func (f *fakeOutboundClient) SendNewTx(_ context.Context, _ int, _ protocol.InterbankTransactionPayload) (protocol.TransactionVote, error) {
	return f.vote, f.sendErr
}

func (f *fakeOutboundClient) SendCommitTx(_ context.Context, _ int, _ protocol.ForeignBankId) error {
	f.committed = true
	return f.commitErr
}

func (f *fakeOutboundClient) SendRollbackTx(_ context.Context, _ int, _ protocol.ForeignBankId) error {
	f.rolledBack = true
	return nil
}

// ---------------------------------------------------------------------------
// fakeContractStore
// ---------------------------------------------------------------------------

type fakeContractStore struct {
	inserted []*store.Contract
}

func (f *fakeContractStore) Insert(_ context.Context, c *store.Contract) error {
	cp := *c
	f.inserted = append(f.inserted, &cp)
	return nil
}

// ---------------------------------------------------------------------------
// fakeExecutorForCoordinator
// Acts as a minimal stand-in for *Executor without a real Postgres backend.
// The Coordinator requires *Executor (not an interface), so we use a real
// Executor wired with stub store/clients that return predictable results.
// ---------------------------------------------------------------------------

// coordExecStore is a minimal ExecutorStore for Coordinator tests (named to
// avoid conflict with executor_test.go's fakeExecStore).
type coordExecStore struct {
	prepared map[string]*store.Transaction
	fail     bool
}

func newCoordExecStore() *coordExecStore {
	return &coordExecStore{prepared: make(map[string]*store.Transaction)}
}

func (f *coordExecStore) PersistPrepared(_ context.Context, t *store.Transaction) error {
	if f.fail {
		return errors.New("fake: persist failed")
	}
	key := t.TransactionIdLocal
	cp := *t
	f.prepared[key] = &cp
	return nil
}

func (f *coordExecStore) FindTx(_ context.Context, _ int, id string) (*store.Transaction, error) {
	if t, ok := f.prepared[id]; ok {
		cp := *t
		return &cp, nil
	}
	return nil, nil
}

func (f *coordExecStore) UpdateTxStatus(_ context.Context, _ int, id, status string) error {
	if t, ok := f.prepared[id]; ok {
		t.Status = status
	}
	return nil
}

// fakeBCReserver satisfies BankingCoreReserver with no real reservations.
type fakeBCReserver struct{}

func (fakeBCReserver) ResolveAccount(_ context.Context, _ string) (*AccountInfo, error) {
	return &AccountInfo{Currency: "USD", AvailableBalance: decimal.NewFromFloat(99999)}, nil
}

func (fakeBCReserver) FindAccountByOwnerAndCurrency(_ context.Context, _ int64, _ string) (string, error) {
	return "111000001234567890", nil
}

func (fakeBCReserver) ReserveMonas(_ context.Context, _, _ string, _ decimal.Decimal, _, _ interface{}) (string, error) {
	return "res-monas-01", nil
}

func (fakeBCReserver) CommitMonas(_ context.Context, _ string) error { return nil }
func (fakeBCReserver) ReleaseMonas(_ context.Context, _ string) error { return nil }

// These ReserveMonas signatures need to match the interface.
// BankingCoreReserver has ReserveMonas(ctx, accountNum, currency string, amount decimal.Decimal, txIDRouting int, txIDLocal string)
// We need to match that exactly.

// fakeTDReserver satisfies TradingReserver with no-ops.
type fakeTDReserver struct{}

func (fakeTDReserver) ReserveStock(_ context.Context, _ int64, _ string, _ int, _ int, _ string) (string, error) {
	return "res-stock-01", nil
}
func (fakeTDReserver) CreditStock(_ context.Context, _ int64, _ string, _ int, _ int, _ string, _ decimal.Decimal) error {
	return nil
}
func (fakeTDReserver) CommitStock(_ context.Context, _ string) error  { return nil }
func (fakeTDReserver) ReleaseStock(_ context.Context, _ string) error { return nil }
func (fakeTDReserver) ReserveOption(_ context.Context, _ protocol.ForeignBankId, _, _ string, _ int) error {
	return nil
}
func (fakeTDReserver) ExerciseOption(_ context.Context, _ protocol.ForeignBankId) error { return nil }
func (fakeTDReserver) ReleaseOption(_ context.Context, _ protocol.ForeignBankId) error  { return nil }

// fakeBCReserverFull implements BankingCoreReserver with correct signatures.
type fakeBCReserverFull struct{}

func (f fakeBCReserverFull) ResolveAccount(ctx context.Context, num string) (*AccountInfo, error) {
	return &AccountInfo{Currency: "USD", AvailableBalance: decimal.NewFromFloat(99999)}, nil
}

func (f fakeBCReserverFull) FindAccountByOwnerAndCurrency(ctx context.Context, ownerID int64, currency string) (string, error) {
	return "111000001234567890", nil
}

func (f fakeBCReserverFull) ReserveMonas(ctx context.Context, accountNum, currency string, amount decimal.Decimal, txIDRouting int, txIDLocal string) (string, error) {
	return "res-monas-01", nil
}

func (f fakeBCReserverFull) CommitMonas(ctx context.Context, reservationID string) error { return nil }
func (f fakeBCReserverFull) CreditMonas(ctx context.Context, accountNum string, amount decimal.Decimal, clientID int64) error {
	return nil
}
func (f fakeBCReserverFull) ReleaseMonas(ctx context.Context, reservationID string) error { return nil }
func (f fakeBCReserverFull) RecordInterbankPayment(ctx context.Context, orderNumber, fromAccount, toAccount string, amount decimal.Decimal, currency, recipientName, paymentPurpose string) error {
	return nil
}

// ---------------------------------------------------------------------------
// build helpers
// ---------------------------------------------------------------------------

func buildNeg(ongoing bool, settlementFuture bool) *store.Negotiation {
	sd := time.Now().Add(24 * time.Hour)
	if !settlementFuture {
		sd = time.Now().Add(-24 * time.Hour)
	}
	return &store.Negotiation{
		ID:                    "neg-test-001",
		BuyerRouting:          theirRouting,
		BuyerID:               "C-2",
		SellerRouting:         myRouting,
		SellerID:              "C-15",
		StockTicker:           "AAPL",
		Amount:                10,
		PriceCurrency:         "USD",
		PriceAmount:           decimal.NewFromFloat(150),
		PremiumCurrency:       "USD",
		PremiumAmount:         decimal.NewFromFloat(5),
		SettlementDate:        sd,
		LastModifiedByRouting: myRouting, // last mod by us → their turn to accept
		LastModifiedByID:      "sys",
		IsOngoing:             ongoing,
		IsAuthoritative:       true,
	}
}

func buildCoordinator(outbound OutboundClient, negStore NegotiationStoreIface, contractSt ContractStoreIface) *Coordinator {
	es := newCoordExecStore()
	// Wire NegotiationSellerLookup so the Validator can resolve option negotiations.
	negLookup := NewNegotiationSellerLookup(negStore)
	exec := NewExecutor(myRouting, es, fakeBCReserverFull{}, fakeTDReserver{}, negLookup, nil)
	return NewCoordinator(myRouting, exec, outbound, negStore, contractSt, fakeBCReserverFull{}, nil, nil, nil)
}

// buildCoordinatorWithBC builds a Coordinator backed by the supplied
// *fakeBankingCoreReserver so reservation/commit tracking can be asserted. The same
// reserver doubles as the off-wire buyer-strike LocalMonasReserver.
func buildCoordinatorWithBC(outbound OutboundClient, negStore NegotiationStoreIface, contractSt ContractStoreIface, bc *fakeBankingCoreReserver) *Coordinator {
	es := newCoordExecStore()
	negLookup := NewNegotiationSellerLookup(negStore)
	exec := NewExecutor(myRouting, es, bc, fakeTDReserver{}, negLookup, nil)
	return NewCoordinator(myRouting, exec, outbound, negStore, contractSt, bc, nil, bc, nil)
}

// buildCoordinatorForExercise builds a Coordinator wired for a buyer-side reverse
// exercise: the supplied BC reserver tracks the off-wire buyer-strike reservation, and
// sellerNeg maps the local negotiation id to the seller-bank authoritative id (so the
// emitted OPTION pseudo-account carries the seller-bank negId).
func buildCoordinatorForExercise(outbound OutboundClient, negStore NegotiationStoreIface, contractSt ContractStoreIface, bc *fakeBankingCoreReserver, sellerNeg SellerNegResolver, td TradingReserver) *Coordinator {
	es := newCoordExecStore()
	negLookup := NewNegotiationSellerLookup(negStore)
	exec := NewExecutor(myRouting, es, bc, td, negLookup, nil)
	return NewCoordinator(myRouting, exec, outbound, negStore, contractSt, bc, sellerNeg, bc, nil)
}

// ---------------------------------------------------------------------------
// Coordinator tests
// ---------------------------------------------------------------------------

func TestCoordinator_AcceptNegotiation_HappyPath(t *testing.T) {
	ns := newFakeNegotiationStore()
	neg := buildNeg(true, true)
	ns.mu.Lock()
	ns.rows[neg.ID] = neg
	ns.mu.Unlock()
	cs := &fakeContractStore{}
	outbound := &fakeOutboundClient{
		vote: protocol.TransactionVote{Vote: protocol.VoteYes},
	}
	coord := buildCoordinator(outbound, ns, cs)
	err := coord.AcceptNegotiation(context.Background(), neg)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !outbound.committed {
		t.Error("expected SendCommitTx to be called")
	}
	if len(cs.inserted) == 0 {
		t.Error("expected contract to be inserted")
	}
}

func TestCoordinator_AcceptNegotiation_PartnerRejects(t *testing.T) {
	ns := newFakeNegotiationStore()
	neg := buildNeg(true, true)
	ns.mu.Lock()
	ns.rows[neg.ID] = neg
	ns.mu.Unlock()
	cs := &fakeContractStore{}
	outbound := &fakeOutboundClient{
		vote: protocol.TransactionVote{
			Vote:    protocol.VoteNo,
			Reasons: []protocol.NoVoteReason{{Reason: protocol.ReasonInsufficientAsset}},
		},
	}
	coord := buildCoordinator(outbound, ns, cs)
	err := coord.AcceptNegotiation(context.Background(), neg)
	if err == nil {
		t.Fatal("expected error when partner rejects")
	}
	if !errors.Is(err, ErrInterbankProtocol) {
		t.Errorf("expected ErrInterbankProtocol, got %v", err)
	}
	// Partner rollback should have been sent.
	if !outbound.rolledBack {
		t.Error("expected SendRollbackTx to be called on partner reject")
	}
}

func TestCoordinator_AcceptNegotiation_PartnerSendError(t *testing.T) {
	ns := newFakeNegotiationStore()
	neg := buildNeg(true, true)
	ns.mu.Lock()
	ns.rows[neg.ID] = neg
	ns.mu.Unlock()
	cs := &fakeContractStore{}
	outbound := &fakeOutboundClient{
		sendErr: errors.New("network timeout"),
	}
	coord := buildCoordinator(outbound, ns, cs)
	err := coord.AcceptNegotiation(context.Background(), neg)
	if err == nil {
		t.Fatal("expected error on send failure")
	}
	if !errors.Is(err, ErrInterbankProtocol) {
		t.Errorf("expected ErrInterbankProtocol, got %v", err)
	}
}

func TestCoordinator_CommitTxSendFailure_NonFatal(t *testing.T) {
	// If SendCommitTx fails, AcceptNegotiation should still return nil
	// (retry scheduler handles it). Contract must be inserted.
	ns := newFakeNegotiationStore()
	neg := buildNeg(true, true)
	ns.mu.Lock()
	ns.rows[neg.ID] = neg
	ns.mu.Unlock()
	cs := &fakeContractStore{}
	outbound := &fakeOutboundClient{
		vote:      protocol.TransactionVote{Vote: protocol.VoteYes},
		commitErr: errors.New("partner unreachable"),
	}
	coord := buildCoordinator(outbound, ns, cs)
	err := coord.AcceptNegotiation(context.Background(), neg)
	if err != nil {
		t.Fatalf("expected nil (best-effort commit); got %v", err)
	}
	if len(cs.inserted) == 0 {
		t.Error("contract must be inserted even if SendCommitTx fails")
	}
}

// ---------------------------------------------------------------------------
// capturingOutboundClient — records the last NEW_TX so payment tests can
// assert the postings sent to the partner.
// ---------------------------------------------------------------------------

type capturingOutboundClient struct {
	vote           protocol.TransactionVote
	sendErr        error
	lastTx         protocol.InterbankTransactionPayload
	sentRouting    int
	committed      bool
	rolledBack     bool
}

func (f *capturingOutboundClient) SendNewTx(_ context.Context, routing int, tx protocol.InterbankTransactionPayload) (protocol.TransactionVote, error) {
	f.sentRouting = routing
	f.lastTx = tx
	return f.vote, f.sendErr
}

func (f *capturingOutboundClient) SendCommitTx(_ context.Context, _ int, _ protocol.ForeignBankId) error {
	f.committed = true
	return nil
}

func (f *capturingOutboundClient) SendRollbackTx(_ context.Context, _ int, _ protocol.ForeignBankId) error {
	f.rolledBack = true
	return nil
}

// ---------------------------------------------------------------------------
// SendOutboundPayment tests
// ---------------------------------------------------------------------------

func TestCoordinator_SendOutboundPayment_HappyPath(t *testing.T) {
	ns := newFakeNegotiationStore()
	cs := &fakeContractStore{}
	// Sender account is ours (routing 111, prefix matches); recipient is at the
	// partner (routing 222, prefix 222) so PrepareLocal only reserves the debit.
	// FIX 5: plain cross-bank payments are RSD-only, so both the account and the
	// payment currency are RSD here.
	bc := newFakeBC(map[string]*AccountInfo{
		"111000000000000001": {OwnerID: 7, Currency: "RSD", AvailableBalance: decimal.NewFromInt(1000)},
	})
	outbound := &capturingOutboundClient{vote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	coord := buildCoordinatorWithBC(outbound, ns, cs, bc)

	txID, err := coord.SendOutboundPayment(
		context.Background(),
		"111000000000000001",
		"222000000000000099",
		decimal.NewFromInt(250),
		"RSD",
		"rent",
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if txID.RoutingNumber != myRouting || txID.Id == "" {
		t.Errorf("expected our-routing tx id, got %+v", txID)
	}
	// Sender debit must be reserved then committed (recipient credit is partner-side).
	if len(bc.reserved) != 1 {
		t.Errorf("expected exactly 1 reservation (sender debit), got %d", len(bc.reserved))
	}
	if len(bc.committed) != 1 {
		t.Errorf("expected exactly 1 commit (sender debit), got %d", len(bc.committed))
	}
	// Partner must have been prepared and committed.
	if !outbound.committed {
		t.Error("expected SendCommitTx to be called")
	}
	if outbound.sentRouting != theirRouting {
		t.Errorf("expected partner routing %d, got %d", theirRouting, outbound.sentRouting)
	}
	// The NEW_TX must carry exactly 2 balanced MONAS postings.
	if got := len(outbound.lastTx.Postings); got != 2 {
		t.Fatalf("expected 2 postings sent to partner, got %d", got)
	}
	sum := decimal.Zero
	for _, p := range outbound.lastTx.Postings {
		sum = sum.Add(p.Amount)
		if _, ok := p.Asset.(*protocol.MonasAsset); !ok {
			t.Errorf("expected MONAS asset, got %T", p.Asset)
		}
	}
	if !sum.IsZero() {
		t.Errorf("expected balanced postings (sum 0), got %s", sum)
	}
}

func TestCoordinator_SendOutboundPayment_IntraBankRejected(t *testing.T) {
	ns := newFakeNegotiationStore()
	cs := &fakeContractStore{}
	bc := newFakeBC(map[string]*AccountInfo{
		"111000000000000001": {OwnerID: 7, Currency: "RSD", AvailableBalance: decimal.NewFromInt(1000)},
	})
	outbound := &capturingOutboundClient{vote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	coord := buildCoordinatorWithBC(outbound, ns, cs, bc)

	// Recipient routing 111 == ours → must be rejected as an intra-bank transfer.
	// (RSD currency so the FIX 5 RSD-only gate passes and the routing rule is reached.)
	_, err := coord.SendOutboundPayment(
		context.Background(),
		"111000000000000001",
		"111000000000000002",
		decimal.NewFromInt(50),
		"RSD",
		"oops",
	)
	if err == nil {
		t.Fatal("expected error for intra-bank recipient routing")
	}
	if !errors.Is(err, ErrPaymentInvalid) {
		t.Errorf("expected ErrPaymentInvalid, got %v", err)
	}
	// Nothing should have been reserved or sent.
	if len(bc.reserved) != 0 {
		t.Errorf("expected no reservations, got %d", len(bc.reserved))
	}
}

func TestCoordinator_SendOutboundPayment_PartnerRejects(t *testing.T) {
	ns := newFakeNegotiationStore()
	cs := &fakeContractStore{}
	bc := newFakeBC(map[string]*AccountInfo{
		"111000000000000001": {OwnerID: 7, Currency: "RSD", AvailableBalance: decimal.NewFromInt(1000)},
	})
	outbound := &capturingOutboundClient{
		vote: protocol.TransactionVote{
			Vote:    protocol.VoteNo,
			Reasons: []protocol.NoVoteReason{{Reason: protocol.ReasonNoSuchAccount}},
		},
	}
	coord := buildCoordinatorWithBC(outbound, ns, cs, bc)

	_, err := coord.SendOutboundPayment(
		context.Background(),
		"111000000000000001",
		"222000000000000099",
		decimal.NewFromInt(250),
		"RSD",
		"rent",
	)
	if err == nil {
		t.Fatal("expected error when partner rejects")
	}
	if !errors.Is(err, ErrInterbankProtocol) {
		t.Errorf("expected ErrInterbankProtocol, got %v", err)
	}
	// Local reservation must be released; partner rollback must be sent.
	if len(bc.released) != 1 {
		t.Errorf("expected sender reservation to be released, got %d releases", len(bc.released))
	}
	if len(bc.committed) != 0 {
		t.Errorf("expected no commit on partner reject, got %d", len(bc.committed))
	}
	if !outbound.rolledBack {
		t.Error("expected SendRollbackTx to be called on partner reject")
	}
}

// FIX 5: plain cross-bank payments in a non-RSD currency are blocked BEFORE any 2PC —
// no reservation, no partner NEW_TX. (OTC settlement, which uses a different entry
// point, is unaffected.)
func TestCoordinator_SendOutboundPayment_NonRSD_Blocked(t *testing.T) {
	ns := newFakeNegotiationStore()
	cs := &fakeContractStore{}
	bc := newFakeBC(map[string]*AccountInfo{
		"111000000000000001": {OwnerID: 7, Currency: "USD", AvailableBalance: decimal.NewFromInt(1000)},
	})
	outbound := &capturingOutboundClient{vote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	coord := buildCoordinatorWithBC(outbound, ns, cs, bc)

	for _, ccy := range []string{"USD", "EUR", "eur", ""} {
		_, err := coord.SendOutboundPayment(
			context.Background(),
			"111000000000000001",
			"222000000000000099",
			decimal.NewFromInt(250),
			ccy,
			"rent",
		)
		if err == nil {
			t.Fatalf("expected non-RSD currency %q to be blocked", ccy)
		}
		if !errors.Is(err, ErrPaymentInvalid) {
			t.Errorf("ccy=%q: expected ErrPaymentInvalid, got %v", ccy, err)
		}
	}
	// Nothing should have been reserved or sent to the partner.
	if len(bc.reserved) != 0 {
		t.Errorf("expected no reservations for blocked non-RSD payments, got %d", len(bc.reserved))
	}
	if outbound.lastTx.TransactionId.Id != "" {
		t.Errorf("expected no NEW_TX sent to partner, got %+v", outbound.lastTx.TransactionId)
	}
}

// FIX 5: an RSD plain cross-bank payment passes the currency gate (regression guard for
// the RSD-only rule actually allowing RSD).
func TestCoordinator_SendOutboundPayment_RSD_Allowed(t *testing.T) {
	ns := newFakeNegotiationStore()
	cs := &fakeContractStore{}
	bc := newFakeBC(map[string]*AccountInfo{
		"111000000000000001": {OwnerID: 7, Currency: "RSD", AvailableBalance: decimal.NewFromInt(1000)},
	})
	outbound := &capturingOutboundClient{vote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	coord := buildCoordinatorWithBC(outbound, ns, cs, bc)

	if _, err := coord.SendOutboundPayment(
		context.Background(),
		"111000000000000001",
		"222000000000000099",
		decimal.NewFromInt(250),
		"RSD",
		"rent",
	); err != nil {
		t.Fatalf("expected RSD payment to be allowed, got %v", err)
	}
}

// FIX 5: the RSD-only gate is case-insensitive (a "rsd"/" RSD " input is accepted by the
// gate even though downstream currency matching is case-sensitive — the gate must not be
// the thing that rejects a legitimately-RSD payment).
func TestCoordinator_SendOutboundPayment_RSD_GateCaseInsensitive(t *testing.T) {
	ns := newFakeNegotiationStore()
	cs := &fakeContractStore{}
	bc := newFakeBC(map[string]*AccountInfo{})
	outbound := &capturingOutboundClient{vote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	coord := buildCoordinatorWithBC(outbound, ns, cs, bc)

	// " rsd " passes the gate, then fails LATER (no such account) — NOT with the
	// RSD-only ErrPaymentInvalid message. We only assert the gate itself didn't reject it.
	_, err := coord.SendOutboundPayment(
		context.Background(),
		"111000000000000001",
		"222000000000000099",
		decimal.NewFromInt(250),
		"  rsd  ",
		"rent",
	)
	if err != nil && errors.Is(err, ErrPaymentInvalid) && strings.Contains(err.Error(), "samo u RSD") {
		t.Fatalf("RSD gate must accept ' rsd ' case/space-insensitively, got %v", err)
	}
}
