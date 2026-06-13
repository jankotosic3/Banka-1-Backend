package service

import (
	"context"
	"encoding/json"
	"log/slog"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// ---------------------------------------------------------------------------
// BuyerContractRecorder — records the BUYER's local option contract on commit
// ---------------------------------------------------------------------------
//
// Closes the buyer-side gap in the cross-bank OTC option lifecycle. When a
// Banka 1 user BUYS an option from a partner bank, the partner is the 2PC
// coordinator: it creates the SELLER-side contract (see coordinator.go
// AcceptNegotiation → contractSt.Insert), and B1 only processes the inbound
// NEW_TX / COMMIT_TX. The buyer pays the premium correctly, but no local
// contract was ever recorded for the option B1 received — so the buyer could
// neither see nor exercise it.
//
// This recorder runs on the inbound COMMIT path: it scans the committed
// transaction's stored postings for a buyer-side OPTION receipt (a
// PersonAccount on OUR routing, positive amount, OptionAsset) and inserts a
// matching interbank_contracts row in status ACTIVE.
//
// Idempotency: keyed by the negotiation. A second COMMIT (idempotent replay)
// must not duplicate the contract — we check FindByNegotiationID before insert
// and also tolerate a unique-violation from a concurrent insert.

// RecorderContractStore is the persistence seam BuyerContractRecorder needs from
// *store.ContractStore: look up an existing contract (idempotency), insert a new one,
// and flip a contract's status (used to mark the SELLER's contract EXERCISED on an
// inbound cross-bank exercise commit).
type RecorderContractStore interface {
	FindByNegotiationID(ctx context.Context, negotiationID string) (*store.Contract, error)
	Insert(ctx context.Context, c *store.Contract) error
	UpdateStatus(ctx context.Context, id, status string) error
}

// RecorderNegotiationStore is the lookup seam BuyerContractRecorder needs to map
// the partner's negotiation id (carried in the option posting) to OUR local
// mirror negotiation. The interbank_contracts.negotiation_id column has a FK to
// interbank_negotiations(id), so the contract must reference the LOCAL id, not
// the partner's. *store.NegotiationStore satisfies this.
type RecorderNegotiationStore interface {
	// FindByID returns (nil, nil) when not found. Matches when the partner's
	// negotiation id happens to equal one of our local ids (rare).
	FindByID(ctx context.Context, id string) (*store.Negotiation, error)
	// FindByAuthoritativeRef matches a mirror row by remote_negotiation_id (the
	// usual buyer case: we hold a non-authoritative mirror of the seller's neg).
	FindByAuthoritativeRef(ctx context.Context, routing int, id string) (*store.Negotiation, error)
}

// RecorderExecutorStore is the seam used to read back the committed transaction's
// stored postings. *store.TransactionStore is adapted to this in main.go (same
// FindTx rename adapter as ExecutorStore).
type RecorderExecutorStore interface {
	FindTx(ctx context.Context, routing int, id string) (*store.Transaction, error)
}

// BuyerContractRecorder records the buyer's local option contract on commit.
type BuyerContractRecorder struct {
	myRouting int
	txStore   RecorderExecutorStore
	contracts RecorderContractStore
	negs      RecorderNegotiationStore
	log       *slog.Logger
}

// NewBuyerContractRecorder constructs the recorder.
func NewBuyerContractRecorder(
	myRouting int,
	txStore RecorderExecutorStore,
	contracts RecorderContractStore,
	negs RecorderNegotiationStore,
	log *slog.Logger,
) *BuyerContractRecorder {
	if log == nil {
		log = slog.Default()
	}
	return &BuyerContractRecorder{
		myRouting: myRouting,
		txStore:   txStore,
		contracts: contracts,
		negs:      negs,
		log:       log,
	}
}

// RecordOnCommit scans the committed transaction's postings for a buyer-side
// OPTION receipt and, if found, inserts a local ACTIVE contract for it.
//
// It is best-effort and idempotent: it never returns an error (the 2PC commit
// has already succeeded by the time this runs, so a recording failure must not
// turn the inbound COMMIT_TX into a 5xx). Failures are logged.
func (r *BuyerContractRecorder) RecordOnCommit(ctx context.Context, txID protocol.ForeignBankId) {
	tx, err := r.txStore.FindTx(ctx, txID.RoutingNumber, txID.Id)
	if err != nil {
		r.log.WarnContext(ctx, "buyer-contract: find tx for recording failed", "txID", txID, "err", err)
		return
	}
	if tx == nil || tx.PostingsJSON == "" {
		return
	}

	var postings []protocol.Posting
	if err := json.Unmarshal([]byte(tx.PostingsJSON), &postings); err != nil {
		r.log.WarnContext(ctx, "buyer-contract: unmarshal postings failed", "txID", txID, "err", err)
		return
	}

	// SELLER-side exercise: an inbound cross-bank exercise of an option WE issued. The
	// committed tx delivered our seller's stock and credited the strike (executor); here we
	// flip our local seller contract ACTIVE→EXERCISED so the seller's view matches reality.
	// Mutually exclusive with the buyer-receipt path below (an exercise tx carries no
	// buyer-side OPTION receipt, only STOCK/MONAS legs), so handle it and return.
	if negID, ok := r.exerciseNegotiationID(postings); ok {
		r.markSellerContractExercised(ctx, txID, negID)
		return
	}

	// Locate the buyer-side OPTION receipt and the option's pseudo-account
	// (the latter carries the seller bank's routing for the seller side).
	receipt, optDesc, ok := r.findBuyerOptionReceipt(postings)
	if !ok {
		return // not a buyer-side option transaction — nothing to record
	}
	sellerRouting := r.sellerRoutingFrom(postings, optDesc)

	if err := r.insertContract(ctx, receipt, optDesc, sellerRouting); err != nil {
		r.log.ErrorContext(ctx, "buyer-contract: insert failed", "txID", txID, "neg", optDesc.NegotiationId, "err", err)
	}
}

// exerciseNegotiationID returns the negotiation id of the option pseudo-account when the
// committed tx is a cross-bank EXERCISE we host as the seller bank: an OptionPseudoAccount
// leg carrying a (negative) StockAsset ("Credit option pseudo-account for k stocks",
// §2.7.2). The pseudo-account's id IS the (our local) negotiation id.
func (r *BuyerContractRecorder) exerciseNegotiationID(postings []protocol.Posting) (string, bool) {
	for i := range postings {
		pseudo, isPseudo := postings[i].Account.(*protocol.OptionPseudoAccount)
		if !isPseudo {
			continue
		}
		if _, isStock := postings[i].Asset.(*protocol.StockAsset); !isStock {
			continue
		}
		if pseudo.Id.RoutingNumber != r.myRouting {
			continue // the option is hosted by a partner bank — not our seller contract
		}
		return pseudo.Id.Id, true
	}
	return "", false
}

// markSellerContractExercised flips our local seller contract for negID to EXERCISED.
// Best-effort and idempotent: a missing contract or an already-EXERCISED one is a no-op,
// and any error is logged (the 2PC commit and settlement already succeeded — this is only
// the contract-status view). The pseudo-account id is OUR local negotiation id, so the
// contract is found directly via FindByNegotiationID.
func (r *BuyerContractRecorder) markSellerContractExercised(ctx context.Context, txID protocol.ForeignBankId, negID string) {
	contract, err := r.contracts.FindByNegotiationID(ctx, negID)
	if err != nil {
		r.log.WarnContext(ctx, "seller-contract: lookup for exercise flip failed", "txID", txID, "neg", negID, "err", err)
		return
	}
	if contract == nil {
		r.log.WarnContext(ctx, "seller-contract: no local contract for exercised negotiation — skipping flip", "txID", txID, "neg", negID)
		return
	}
	if contract.Status == store.ContractStatusExercised {
		return // idempotent: already exercised (duplicate COMMIT_TX §2.9)
	}
	if err := r.contracts.UpdateStatus(ctx, contract.ID, store.ContractStatusExercised); err != nil {
		r.log.ErrorContext(ctx, "seller-contract: UpdateStatus EXERCISED failed", "txID", txID, "contract", contract.ID, "err", err)
		return
	}
	r.log.InfoContext(ctx, "seller-side option contract EXERCISED", "txID", txID, "contract", contract.ID, "neg", negID)
}

// findBuyerOptionReceipt returns the buyer's option-receipt posting (PersonAccount
// on our routing, positive amount, OptionAsset) and its OptionDescription.
func (r *BuyerContractRecorder) findBuyerOptionReceipt(postings []protocol.Posting) (*protocol.PersonAccount, protocol.OptionDescription, bool) {
	for i := range postings {
		p := postings[i]
		opt, isOpt := p.Asset.(*protocol.OptionAsset)
		if !isOpt {
			continue
		}
		person, isPerson := p.Account.(*protocol.PersonAccount)
		if !isPerson {
			continue
		}
		if person.Id.RoutingNumber != r.myRouting {
			continue // option received by a partner person, not ours
		}
		if !p.Amount.IsPositive() {
			continue // the buyer RECEIVES the option (+1); negatives are debits
		}
		return person, opt.OptionDescription, true
	}
	return nil, protocol.OptionDescription{}, false
}

// sellerRoutingFrom derives the seller bank's routing number. The OptionPseudoAccount
// posting (the option-issuing side) carries the seller-bank routing in its id; we
// fall back to the negotiation id's routing on the OptionDescription.
func (r *BuyerContractRecorder) sellerRoutingFrom(postings []protocol.Posting, optDesc protocol.OptionDescription) int {
	for i := range postings {
		if pseudo, ok := postings[i].Account.(*protocol.OptionPseudoAccount); ok {
			return pseudo.Id.RoutingNumber
		}
	}
	return optDesc.NegotiationId.RoutingNumber
}

// insertContract resolves the local mirror negotiation (for the FK + idempotency
// key) and inserts a buyer-side ACTIVE contract, skipping if one already exists.
func (r *BuyerContractRecorder) insertContract(
	ctx context.Context,
	buyer *protocol.PersonAccount,
	optDesc protocol.OptionDescription,
	sellerRouting int,
) error {
	// Map the partner's negotiation id → our local mirror negotiation id. The
	// interbank_contracts.negotiation_id FK references interbank_negotiations(id),
	// which on the buyer side is OUR local id, not the partner's.
	remoteNegID := optDesc.NegotiationId.Id
	localNeg := r.resolveLocalNegotiation(ctx, remoteNegID)
	if localNeg == nil {
		// Without a local negotiation row we cannot satisfy the FK. Skip rather
		// than insert an orphan (the seller bank still holds the authoritative
		// contract; the buyer view simply won't show it).
		r.log.WarnContext(ctx, "buyer-contract: no local mirror negotiation — skipping contract record",
			"remoteNeg", remoteNegID, "sellerRouting", sellerRouting)
		return nil
	}

	// Idempotency: a contract for this (local) negotiation already exists → no-op.
	existing, err := r.contracts.FindByNegotiationID(ctx, localNeg.ID)
	if err != nil {
		return err
	}
	if existing != nil {
		return nil
	}

	// Derive the seller foreign-id: prefer the local mirror's recorded seller.
	sellerID := localNeg.SellerID
	if sellerRouting == 0 {
		sellerRouting = localNeg.SellerRouting
	}

	contract := &store.Contract{
		ID:             generateContractID(),
		NegotiationID:  localNeg.ID,
		BuyerRouting:   buyer.Id.RoutingNumber,
		BuyerID:        buyer.Id.Id,
		SellerRouting:  sellerRouting,
		SellerID:       sellerID,
		StockTicker:    optDesc.Stock.Ticker,
		Amount:         optDesc.Amount,
		StrikeCurrency: optDesc.PricePerUnit.Currency,
		StrikeAmount:   optDesc.PricePerUnit.Amount,
		SettlementDate: localNeg.SettlementDate,
		Status:         store.ContractStatusActive,
		// The buyer conceptually owns (holds the right to) the option.
		OptionPseudoOwnerRouting: buyer.Id.RoutingNumber,
		OptionPseudoOwnerID:      buyer.Id.Id,
	}
	if err := r.contracts.Insert(ctx, contract); err != nil {
		if store.IsUniqueViolation(err) {
			// Concurrent commit recorded it first — idempotent success.
			return nil
		}
		return err
	}

	r.log.InfoContext(ctx, "recorded buyer-side option contract — ACTIVE",
		"contract", contract.ID, "neg", localNeg.ID, "ticker", contract.StockTicker,
		"buyer", contract.BuyerID, "sellerRouting", sellerRouting)
	return nil
}

// resolveLocalNegotiation finds OUR local negotiation row for the partner's
// negotiation id: first by remote_negotiation_id (the usual buyer mirror), then
// by direct id match (defensive — in case the ids coincide).
func (r *BuyerContractRecorder) resolveLocalNegotiation(ctx context.Context, remoteNegID string) *store.Negotiation {
	if n, err := r.negs.FindByAuthoritativeRef(ctx, 0, remoteNegID); err == nil && n != nil {
		return n
	}
	if n, err := r.negs.FindByID(ctx, remoteNegID); err == nil && n != nil {
		return n
	}
	return nil
}
