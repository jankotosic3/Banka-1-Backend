package service

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// ---------------------------------------------------------------------------
// Fakes for BuyerContractRecorder
// ---------------------------------------------------------------------------

// recorderTxStore is a minimal RecorderExecutorStore returning a fixed tx.
type recorderTxStore struct {
	tx *store.Transaction
}

func (f *recorderTxStore) FindTx(_ context.Context, _ int, _ string) (*store.Transaction, error) {
	return f.tx, nil
}

// recorderContractStore is a richer fake than coordinator_test.go's fakeContractStore:
// it supports FindByNegotiationID + Insert for idempotency testing, plus UpdateStatus for
// the seller-side EXERCISED flip.
type recorderContractStore struct {
	byNeg         map[string]*store.Contract
	byID          map[string]*store.Contract
	inserted      []*store.Contract
	statusUpdates map[string]string // contract id → last status set
}

func newRecorderContractStore() *recorderContractStore {
	return &recorderContractStore{
		byNeg:         map[string]*store.Contract{},
		byID:          map[string]*store.Contract{},
		statusUpdates: map[string]string{},
	}
}

func (f *recorderContractStore) FindByNegotiationID(_ context.Context, negID string) (*store.Contract, error) {
	if c, ok := f.byNeg[negID]; ok {
		return c, nil
	}
	return nil, nil
}

func (f *recorderContractStore) Insert(_ context.Context, c *store.Contract) error {
	cp := *c
	f.inserted = append(f.inserted, &cp)
	f.byNeg[c.NegotiationID] = &cp
	if c.ID != "" {
		f.byID[c.ID] = &cp
	}
	return nil
}

func (f *recorderContractStore) UpdateStatus(_ context.Context, id, status string) error {
	f.statusUpdates[id] = status
	if c, ok := f.byID[id]; ok {
		c.Status = status
	}
	return nil
}

// recorderNegStore is a minimal RecorderNegotiationStore. It matches the partner's
// remote negotiation id to a local mirror via FindByAuthoritativeRef.
type recorderNegStore struct {
	byRemote map[string]*store.Negotiation // remote_negotiation_id → local mirror
	byID     map[string]*store.Negotiation // local id → row
}

func (f *recorderNegStore) FindByID(_ context.Context, id string) (*store.Negotiation, error) {
	if f.byID == nil {
		return nil, nil
	}
	return f.byID[id], nil
}

func (f *recorderNegStore) FindByAuthoritativeRef(_ context.Context, _ int, id string) (*store.Negotiation, error) {
	if f.byRemote == nil {
		return nil, nil
	}
	return f.byRemote[id], nil
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

// buyerOptionReceiptTx builds a committed-transaction row whose postings include
// a buyer-side OPTION receipt (PersonAccount on our routing, +1, OptionAsset),
// plus the seller OptionPseudoAccount debit — exactly the shape the partner
// coordinator sends to us when we BUY the option.
func buyerOptionReceiptTx(t *testing.T, sellerRouting int, remoteNegID, ticker string, amount int, strike decimal.Decimal) *store.Transaction {
	t.Helper()
	optDesc := protocol.OptionDescription{
		NegotiationId:  protocol.ForeignBankId{RoutingNumber: sellerRouting, Id: remoteNegID},
		Stock:          protocol.StockDescription{Ticker: ticker},
		PricePerUnit:   protocol.MonetaryValue{Currency: "USD", Amount: strike},
		SettlementDate: time.Now().Add(48 * time.Hour).UTC().Format(time.RFC3339),
		Amount:         amount,
	}
	postings := []protocol.Posting{
		// Seller option pseudo-account credit (-1) — option-issuing side (seller bank).
		{
			Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: sellerRouting, Id: remoteNegID}},
			Amount:  decimal.NewFromInt(-1),
			Asset:   &protocol.OptionAsset{OptionDescription: optDesc},
		},
		// Buyer person option receipt (+1) — OUR client receives the option.
		{
			Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: myRouting, Id: "C-15"}},
			Amount:  decimal.NewFromInt(1),
			Asset:   &protocol.OptionAsset{OptionDescription: optDesc},
		},
	}
	pj, err := json.Marshal(postings)
	if err != nil {
		t.Fatalf("marshal postings: %v", err)
	}
	return &store.Transaction{
		TransactionIdRouting: sellerRouting,
		TransactionIdLocal:   "tx-buy-opt",
		Status:               store.TxStatusCommitted,
		PostingsJSON:         string(pj),
	}
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

func TestBuyerContractRecorder_RecordsBuyerContract(t *testing.T) {
	tx := buyerOptionReceiptTx(t, theirRouting, "neg-remote-1", "AAPL", 10, decimal.NewFromInt(150))
	txStore := &recorderTxStore{tx: tx}
	cs := newRecorderContractStore()
	localNeg := &store.Negotiation{
		ID:             "neg-local-1",
		BuyerRouting:   myRouting,
		BuyerID:        "C-15",
		SellerRouting:  theirRouting,
		SellerID:       "C-2",
		StockTicker:    "AAPL",
		Amount:         10,
		PriceCurrency:  "USD",
		PriceAmount:    decimal.NewFromInt(150),
		SettlementDate: time.Now().Add(48 * time.Hour),
	}
	ns := &recorderNegStore{byRemote: map[string]*store.Negotiation{"neg-remote-1": localNeg}}

	rec := NewBuyerContractRecorder(myRouting, txStore, cs, ns, discardLogger())
	rec.RecordOnCommit(context.Background(), protocol.ForeignBankId{RoutingNumber: theirRouting, Id: "tx-buy-opt"})

	if len(cs.inserted) != 1 {
		t.Fatalf("expected exactly one contract inserted, got %d", len(cs.inserted))
	}
	c := cs.inserted[0]
	if c.NegotiationID != "neg-local-1" {
		t.Errorf("expected FK to local negotiation id, got %q", c.NegotiationID)
	}
	if c.BuyerRouting != myRouting || c.BuyerID != "C-15" {
		t.Errorf("unexpected buyer %d/%s", c.BuyerRouting, c.BuyerID)
	}
	if c.SellerRouting != theirRouting {
		t.Errorf("expected seller routing %d, got %d", theirRouting, c.SellerRouting)
	}
	if c.StockTicker != "AAPL" || c.Amount != 10 {
		t.Errorf("unexpected ticker/amount %s/%d", c.StockTicker, c.Amount)
	}
	if c.StrikeCurrency != "USD" || !c.StrikeAmount.Equal(decimal.NewFromInt(150)) {
		t.Errorf("unexpected strike %s/%s", c.StrikeCurrency, c.StrikeAmount)
	}
	if c.Status != store.ContractStatusActive {
		t.Errorf("expected ACTIVE, got %q", c.Status)
	}
	if c.OptionPseudoOwnerRouting != myRouting || c.OptionPseudoOwnerID != "C-15" {
		t.Errorf("buyer should hold the option, got %d/%s", c.OptionPseudoOwnerRouting, c.OptionPseudoOwnerID)
	}
}

func TestBuyerContractRecorder_IdempotentOnSecondCommit(t *testing.T) {
	tx := buyerOptionReceiptTx(t, theirRouting, "neg-remote-2", "MSFT", 5, decimal.NewFromInt(300))
	txStore := &recorderTxStore{tx: tx}
	cs := newRecorderContractStore()
	localNeg := &store.Negotiation{
		ID:             "neg-local-2",
		BuyerRouting:   myRouting,
		BuyerID:        "C-15",
		SellerRouting:  theirRouting,
		SellerID:       "C-2",
		StockTicker:    "MSFT",
		Amount:         5,
		PriceCurrency:  "USD",
		PriceAmount:    decimal.NewFromInt(300),
		SettlementDate: time.Now().Add(48 * time.Hour),
	}
	ns := &recorderNegStore{byRemote: map[string]*store.Negotiation{"neg-remote-2": localNeg}}

	rec := NewBuyerContractRecorder(myRouting, txStore, cs, ns, discardLogger())
	ctx := context.Background()
	txID := protocol.ForeignBankId{RoutingNumber: theirRouting, Id: "tx-buy-opt"}

	rec.RecordOnCommit(ctx, txID)
	rec.RecordOnCommit(ctx, txID) // idempotent replay

	if len(cs.inserted) != 1 {
		t.Fatalf("expected exactly one contract after duplicate commits, got %d", len(cs.inserted))
	}
}

func TestBuyerContractRecorder_NonOptionTx_NoContract(t *testing.T) {
	// A plain MONAS-only transaction must not produce a contract.
	postings := balancedMonasPosting("222000000000000099", "111000000000000001", 100, "USD")
	pj, _ := json.Marshal(postings)
	txStore := &recorderTxStore{tx: &store.Transaction{
		TransactionIdRouting: theirRouting,
		TransactionIdLocal:   "tx-money",
		Status:               store.TxStatusCommitted,
		PostingsJSON:         string(pj),
	}}
	cs := newRecorderContractStore()
	ns := &recorderNegStore{}

	rec := NewBuyerContractRecorder(myRouting, txStore, cs, ns, discardLogger())
	rec.RecordOnCommit(context.Background(), protocol.ForeignBankId{RoutingNumber: theirRouting, Id: "tx-money"})

	if len(cs.inserted) != 0 {
		t.Fatalf("expected no contract for a money-only tx, got %d", len(cs.inserted))
	}
}

func TestBuyerContractRecorder_NoLocalNegotiation_Skips(t *testing.T) {
	// No local mirror negotiation → cannot satisfy FK → skip (no insert, no panic).
	tx := buyerOptionReceiptTx(t, theirRouting, "neg-missing", "TSLA", 3, decimal.NewFromInt(200))
	txStore := &recorderTxStore{tx: tx}
	cs := newRecorderContractStore()
	ns := &recorderNegStore{} // empty — nothing resolves

	rec := NewBuyerContractRecorder(myRouting, txStore, cs, ns, discardLogger())
	rec.RecordOnCommit(context.Background(), protocol.ForeignBankId{RoutingNumber: theirRouting, Id: "tx-buy-opt"})

	if len(cs.inserted) != 0 {
		t.Fatalf("expected no contract when local negotiation is missing, got %d", len(cs.inserted))
	}
}

// sellerExerciseCommitTx builds the committed exercise tx that B1 (seller, myRouting)
// receives from the buyer's bank: our OPTION pseudo-account (id = our local negotiation id)
// is credited -k stocks and +k·π monas, the buyer (partner) receives the stock. This is the
// shape the seller-side EXERCISED flip keys off.
func sellerExerciseCommitTx(t *testing.T, localNegID, ticker string) *store.Transaction {
	t.Helper()
	neg := protocol.ForeignBankId{RoutingNumber: myRouting, Id: localNegID}
	seller := protocol.ForeignBankId{RoutingNumber: myRouting, Id: "C-5"}
	buyer := protocol.ForeignBankId{RoutingNumber: theirRouting, Id: "C-8"}
	postings := []protocol.Posting{
		{Account: &protocol.OptionPseudoAccount{Id: neg}, Amount: decimal.NewFromInt(200), Asset: &protocol.MonasAsset{Currency: "USD"}},
		{Account: &protocol.PersonAccount{Id: seller}, Amount: decimal.NewFromInt(-200), Asset: &protocol.MonasAsset{Currency: "USD"}},
		{Account: &protocol.OptionPseudoAccount{Id: neg}, Amount: decimal.NewFromInt(-1), Asset: &protocol.StockAsset{Ticker: ticker}},
		{Account: &protocol.PersonAccount{Id: buyer}, Amount: decimal.NewFromInt(1), Asset: &protocol.StockAsset{Ticker: ticker}},
	}
	pj, err := json.Marshal(postings)
	if err != nil {
		t.Fatalf("marshal postings: %v", err)
	}
	return &store.Transaction{
		TransactionIdRouting: theirRouting,
		TransactionIdLocal:   "tx-ex-seller",
		Status:               store.TxStatusCommitted,
		PostingsJSON:         string(pj),
	}
}

// On an inbound exercise commit, the seller's local contract is flipped ACTIVE→EXERCISED,
// and no buyer contract is inserted.
func TestBuyerContractRecorder_SellerExercise_FlipsContractExercised(t *testing.T) {
	tx := sellerExerciseCommitTx(t, "neg-local-ex", "AAPL")
	txStore := &recorderTxStore{tx: tx}
	cs := newRecorderContractStore()
	cs.byNeg["neg-local-ex"] = &store.Contract{ID: "ctr-1", NegotiationID: "neg-local-ex", Status: store.ContractStatusActive}
	ns := &recorderNegStore{}

	rec := NewBuyerContractRecorder(myRouting, txStore, cs, ns, discardLogger())
	rec.RecordOnCommit(context.Background(), protocol.ForeignBankId{RoutingNumber: theirRouting, Id: "tx-ex-seller"})

	if got := cs.statusUpdates["ctr-1"]; got != store.ContractStatusExercised {
		t.Fatalf("expected seller contract flipped to EXERCISED, got %q", got)
	}
	if len(cs.inserted) != 0 {
		t.Fatalf("exercise must not insert a buyer contract, got %d", len(cs.inserted))
	}
}

// Idempotent: a duplicate exercise COMMIT_TX (already EXERCISED) does not re-update.
func TestBuyerContractRecorder_SellerExercise_IdempotentWhenAlreadyExercised(t *testing.T) {
	tx := sellerExerciseCommitTx(t, "neg-local-ex2", "AAPL")
	txStore := &recorderTxStore{tx: tx}
	cs := newRecorderContractStore()
	cs.byNeg["neg-local-ex2"] = &store.Contract{ID: "ctr-2", NegotiationID: "neg-local-ex2", Status: store.ContractStatusExercised}
	ns := &recorderNegStore{}

	rec := NewBuyerContractRecorder(myRouting, txStore, cs, ns, discardLogger())
	rec.RecordOnCommit(context.Background(), protocol.ForeignBankId{RoutingNumber: theirRouting, Id: "tx-ex-seller"})

	if _, updated := cs.statusUpdates["ctr-2"]; updated {
		t.Fatalf("already-EXERCISED contract must not be re-updated")
	}
}
