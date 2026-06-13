package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// ErrAlreadyTerminal indicates a commit/rollback was attempted on a tx whose
// status is already terminal (COMMITTED, ROLLED_BACK, or FAILED).
var ErrAlreadyTerminal = errors.New("service: transaction already terminal")

// ---------------------------------------------------------------------------
// Interfaces
// ---------------------------------------------------------------------------

// ExecutorStore is the persistence seam used by Executor. Production wiring
// adapts *store.TransactionStore; tests use an in-memory fake.
type ExecutorStore interface {
	// PersistPrepared inserts a new PREPARED transaction row.
	// Populates t.ID and t.CreatedAt on success.
	PersistPrepared(ctx context.Context, t *store.Transaction) error

	// FindTx returns (nil, nil) when the transaction is not found.
	FindTx(ctx context.Context, routing int, id string) (*store.Transaction, error)

	// UpdateTxStatus flips the status column for the given (routing, id) pair.
	UpdateTxStatus(ctx context.Context, routing int, id, status string) error
}

// BankingCoreReserver extends BankingCoreReader with reservation actions toward
// banking-core's internal interbank endpoints.
type BankingCoreReserver interface {
	BankingCoreReader // ResolveAccount, FindAccountByOwnerAndCurrency
	// ReserveMonas places an amount reservation on the given account.
	// Returns the reservation UUID on success.
	ReserveMonas(ctx context.Context, accountNum, currency string, amount decimal.Decimal, txIDRouting int, txIDLocal string) (string, error)
	// CommitMonas permanently debits a previously reserved amount.
	CommitMonas(ctx context.Context, reservationID string) error
	// CreditMonas credits an account for an incoming inter-bank transfer (money
	// arriving at one of our accounts). clientID must be the recipient account owner.
	CreditMonas(ctx context.Context, accountNum string, amount decimal.Decimal, clientID int64) error
	// ReleaseMonas frees a previously reserved amount back to available balance.
	ReleaseMonas(ctx context.Context, reservationID string) error
	// RecordInterbankPayment records a COMPLETED payment_table row for a cross-bank
	// money movement so it shows in this bank's transaction history. orderNumber is the
	// idempotency key. Best-effort: callers log and continue on failure.
	RecordInterbankPayment(ctx context.Context, orderNumber, fromAccount, toAccount string, amount decimal.Decimal, currency, recipientName, paymentPurpose string) error
}

// TradingReserver provides stock and option reservation actions toward
// trading-service's internal interbank endpoints.
type TradingReserver interface {
	// ReserveStock creates a stock reservation for a pending inter-bank transaction.
	// Returns the reservation UUID on success.
	ReserveStock(ctx context.Context, sellerUserID int64, ticker string, quantity int, txIDRouting int, txIDLocal string) (string, error)
	// CreditStock credits an incoming stock delivery to a LOCAL buyer's portfolio
	// (the buyer-side STOCK leg of a cross-bank OTC option exercise). Like an incoming
	// MONAS credit it is applied on commit only — no reservation at prepare. strike is
	// the contract strike used as the average-purchase-price fallback when no live
	// market price exists for the ticker (FIX 1: asset-conservation).
	CreditStock(ctx context.Context, buyerUserID int64, ticker string, quantity int, txIDRouting int, txIDLocal string, strike decimal.Decimal) error
	// CommitStock permanently transfers the reserved stock.
	CommitStock(ctx context.Context, reservationID string) error
	// ReleaseStock frees the stock reservation.
	ReleaseStock(ctx context.Context, reservationID string) error
	// ReserveOption marks an option contract as reserved (idempotent per spec §3.6).
	ReserveOption(ctx context.Context, negotiationID protocol.ForeignBankId, sellerForeignID, ticker string, quantity int) error
	// ExerciseOption marks an option contract as exercised (spec §2.7.2).
	ExerciseOption(ctx context.Context, negotiationID protocol.ForeignBankId) error
	// ReleaseOption frees an option reservation.
	ReleaseOption(ctx context.Context, negotiationID protocol.ForeignBankId) error
}

// OptionNegotiationLookup resolves an option negotiation to its seller foreign-id.
// Used during reservePosting for the OPTION branch. In production this is
// implemented by a thin adapter over *store.NegotiationStore.
type OptionNegotiationLookup interface {
	// FindSellerID returns the seller's foreign-id string for the given negotiation id.
	// Returns a non-nil error (and empty string) when the negotiation is not found.
	FindSellerID(ctx context.Context, negID string) (string, error)
}

// ContractGate provides per-CONTRACT idempotency for cross-bank OTC option exercise
// settlement (FIX B). The desync it closes: B1's exercise settlement (seller strike
// CreditMonas, seller stock delivery via ExerciseOption, and — when we are the buyer —
// the buyer STOCK CreditStock) is keyed per-txId / per-negotiation, NOT per-contract.
// A coordinator retry with a NEW txId for the SAME contract re-runs CommitLocal and
// re-applies the non-idempotent legs (a second strike credit creates money; a second
// stock credit creates assets). ExerciseOption alone is per-negotiation idempotent, but
// CreditMonas/CreditStock are not.
//
// ClaimExercise serializes different-txId exercises of the same contract: it atomically
// transitions the contract from ACTIVE to EXERCISED (single conditional UPDATE — the
// WHERE status='ACTIVE' guard is the serialization point) and reports whether THIS call
// won the claim. The settlement runs ONLY when claimed; a contract already EXERCISED
// (or absent / terminal) yields claimed=false so the whole exercise settlement is a no-op.
// On a settlement failure AFTER a successful claim, ReleaseClaim flips the contract back
// to ACTIVE so a retransmit (§2.9) can re-settle — preserving conservation under partial
// failure. Optional: when nil, CommitLocal behaves exactly as before (per-txId only).
type ContractGate interface {
	// ClaimExercise atomically transitions the contract identified by negotiationID from
	// ACTIVE to EXERCISED. claimed=true means THIS call won the transition and must run
	// settlement; claimed=false means it is already settled (or not exercisable) and the
	// caller must skip settlement. exists=false means there is no local contract for the
	// negotiation (defensive — proceed without gating, e.g. a partner-hosted option).
	ClaimExercise(ctx context.Context, negotiationID string) (exists bool, claimed bool, err error)
	// ReleaseClaim reverts a contract previously claimed by ClaimExercise back to ACTIVE
	// (settlement failed after the claim). Best-effort and idempotent.
	ReleaseClaim(ctx context.Context, negotiationID string) error
}

// ---------------------------------------------------------------------------
// Executor struct
// ---------------------------------------------------------------------------

// Executor orchestrates the 2PC prepare/commit/rollback lifecycle for
// incoming inter-bank transactions per spec §7.
//
// Critical invariant: Spring @Transactional (Java) maps to our per-method
// approach: all outbound REST reservation calls are NOT inside the same DB
// transaction as PersistPrepared. We use saga-style LIFO compensation instead.
type Executor struct {
	myRouting int
	store     ExecutorStore
	bc        BankingCoreReserver
	td        TradingReserver
	negLookup OptionNegotiationLookup
	gate      ContractGate // optional — per-contract exercise idempotency (FIX B); nil = per-txId only
	validator *Validator
	log       *slog.Logger
}

// SetContractGate wires the per-contract exercise idempotency gate (FIX B). Kept as a
// setter rather than a constructor parameter so the many existing NewExecutor call sites
// (production + tests) are unaffected; production wires it in main.go.
func (e *Executor) SetContractGate(g ContractGate) { e.gate = g }

// NewExecutor constructs an Executor. negLookup is used only during
// reservePosting for the OPTION branch (looking up the seller's foreign-id).
// A nil negLookup means no OPTION reservations will succeed; pass a real
// adapter in production.
func NewExecutor(
	myRouting int,
	s ExecutorStore,
	bc BankingCoreReserver,
	td TradingReserver,
	negLookup OptionNegotiationLookup,
	log *slog.Logger,
) *Executor {
	if log == nil {
		log = slog.Default()
	}
	// The Validator uses negLookup (which implements NegotiationReader) for
	// option validation checks. bc provides BankingCoreReader.
	var negReader NegotiationReader
	if negLookup != nil {
		if nr, ok := negLookup.(NegotiationReader); ok {
			negReader = nr
		}
	}
	return &Executor{
		myRouting: myRouting,
		store:     s,
		bc:        bc,
		td:        td,
		negLookup: negLookup,
		validator: NewValidator(myRouting, negReader, bc, td),
		log:       log,
	}
}

// ---------------------------------------------------------------------------
// PrepareLocal
// ---------------------------------------------------------------------------

// PrepareLocal validates and reserves resources for an incoming NEW_TX message.
// Returns a YES vote and persists a PREPARED row on success.
// Returns a NO vote (never an error) when any validation rule is violated.
// Returns an error (not a vote) on unexpected infrastructure failures; in that
// case compensation has already been attempted for any partial reservations.
func (e *Executor) PrepareLocal(ctx context.Context, tx protocol.InterbankTransactionPayload) (protocol.TransactionVote, error) {
	// 1. Balance check — pure local computation.
	if reasons := e.validator.BalanceCheck(tx.Postings); len(reasons) > 0 {
		return protocol.TransactionVote{Vote: protocol.VoteNo, Reasons: reasons}, nil
	}

	// 2. Filter postings that belong to our side.
	var ours []protocol.Posting
	for _, p := range tx.Postings {
		if e.isOurs(p) {
			ours = append(ours, p)
		}
	}

	// Detect the cross-bank EXERCISE shape: an OPTION pseudo-account leg on OUR routing
	// carrying a negative STOCK asset ("Credit option pseudo-account for k stocks", §2.7.2).
	// In an exercise, our option pseudo-account is swept internally (stock delivered +
	// seller credited the strike via the option lifecycle), so the wire MONAS legs are
	// balance-only and the seller's PERSON+MONAS leg is a CREDIT to the seller, not a debit.
	exercise := e.isExerciseTx(ours)

	// 3. Validate each of our postings (no side effects; NO vote on any failure).
	var reasons []protocol.NoVoteReason
	for _, p := range ours {
		// In an exercise, the seller's PERSON+MONAS leg is the strike the seller RECEIVES
		// (the option pseudo-account is swept on our side). It is not a debit, so it must
		// not be balance-checked against the seller's available funds — doing so wrongly
		// rejected the legitimate exercise with INSUFFICIENT_ASSET. Existence is still
		// implied by the credit applied on commit.
		if exercise && e.isExerciseSellerCashLeg(p) {
			continue
		}
		r, err := e.validator.ValidatePosting(ctx, p)
		if err != nil {
			return protocol.TransactionVote{}, fmt.Errorf("executor: validate posting: %w", err)
		}
		if r != nil {
			reasons = append(reasons, *r)
		}
	}
	if len(reasons) > 0 {
		return protocol.TransactionVote{Vote: protocol.VoteNo, Reasons: reasons}, nil
	}

	// 4. Reserve outgoing (debit) postings + record incoming (credit) postings —
	// saga-style, LIFO compensation on failure. Debits are reserved now and
	// committed/released later; incoming MONAS credits are applied on commit only
	// (incoming money needs no reservation), so we just record a credit ref here.
	var refs []store.ReservationRef
	for _, p := range ours {
		// EXERCISE delivery leg (cross-bank §2.7.2, B2-buyer / B1-seller): the buyer's bank
		// places the seller's stock-delivery on the OPTION pseudo-account as a NEGATIVE
		// StockAsset leg ("Credit option pseudo-account for k stocks"). Unlike the accept-shape
		// side legs, this one MUST settle on our side: the seller's k reserved shares have to
		// be delivered. We record a RefKindOption so commit drives ExerciseOption (which
		// commits the seller's stock reservation: quantity & reserved_quantity decrement, option
		// flips EXERCISED). Without this the seller's shares stayed reserved forever — broken
		// asset conservation. The seller's strike CREDIT is handled by the exercise seller-cash
		// branch below (creditSellerExerciseStrike); the OPTION-pseudo MONAS leg stays a no-op skip.
		if ref, ok := e.optionExerciseDeliveryRef(p); ok {
			refs = append(refs, ref)
			continue
		}
		// FIX 3: a MONAS/STOCK leg riding the OPTION pseudo-account (the premium/share
		// legs a partner-coordinated accept hangs off the pseudo-account) carries no raw
		// money/stock movement on our side — the seller's stock is delivered through the
		// OPTION reservation lifecycle (the OPTION-unit leg), and the premium lands on the
		// seller's real PERSON/ACCOUNT leg. Skip it here so it is not (wrongly) reserved or
		// credited as a standalone money/stock posting. The validator has already accepted
		// it against the live negotiation.
		if isOptionPseudoSideLeg(p) {
			continue
		}
		// EXERCISE seller-cash leg: the seller RECEIVES the strike (k·π). On B2's wire the
		// leg is signed negative (their "credit seller" convention), but per the exercise
		// settlement the seller's bank credits the seller from the swept option pseudo-account.
		// Record a MONAS_CREDIT for the ABSOLUTE strike amount so the seller is paid on commit
		// — never reserved/debited. This is what conserves money on the seller side (seller
		// +k·π, buyer −k·π at the other bank).
		if exercise && e.isExerciseSellerCashLeg(p) {
			ref, ok, err := e.creditSellerExerciseStrike(ctx, p, tx.Postings)
			if err != nil {
				e.compensate(ctx, refs)
				return protocol.TransactionVote{}, fmt.Errorf("executor: prepare exercise seller credit: %w", err)
			}
			if ok {
				refs = append(refs, ref)
			}
			continue
		}
		if p.Amount.IsNegative() {
			ref, err := e.reservePosting(ctx, p, tx.TransactionId)
			if err != nil {
				e.compensate(ctx, refs)
				return protocol.TransactionVote{}, fmt.Errorf("executor: reserve posting: %w", err)
			}
			refs = append(refs, ref)
			continue
		}
		// Incoming (credit) posting. Two kinds land here:
		//   - MONAS credit → recorded as a MONAS_CREDIT ref (recipient credited on commit).
		//   - STOCK credit to a local buyer (the buyer-side leg of a cross-bank OTC
		//     option exercise) → recorded as a STOCK_CREDIT ref (shares delivered on
		//     commit via trading-service). Without this the buyer's strike cash was
		//     debited but the shares never arrived (FIX 1).
		// Option-receipt postings are recorded as contracts elsewhere, not credited.
		if ref, ok, err := e.creditStockPosting(p, tx.Postings); err != nil {
			e.compensate(ctx, refs)
			return protocol.TransactionVote{}, fmt.Errorf("executor: prepare stock credit posting: %w", err)
		} else if ok {
			refs = append(refs, ref)
			continue
		}
		ref, ok, err := e.creditMonasPosting(ctx, p, tx.Postings)
		if err != nil {
			e.compensate(ctx, refs)
			return protocol.TransactionVote{}, fmt.Errorf("executor: prepare credit posting: %w", err)
		}
		if ok {
			refs = append(refs, ref)
		}
	}

	// 5. Build message meta JSON.
	metaJSON, _ := json.Marshal(map[string]any{
		"message":        tx.Message,
		"callNumber":     tx.CallNumber,
		"paymentCode":    tx.PaymentCode,
		"paymentPurpose": tx.PaymentPurpose,
	})

	// 6. Marshal postings for storage.
	postingsJSON, _ := json.Marshal(tx.Postings)

	// 7. Persist PREPARED row.
	row := &store.Transaction{
		TransactionIdRouting: tx.TransactionId.RoutingNumber,
		TransactionIdLocal:   tx.TransactionId.Id,
		Status:               store.TxStatusPrepared,
		PostingsJSON:         string(postingsJSON),
		ReservationRefs:      refs,
		MessageMeta:          string(metaJSON),
	}
	if err := e.store.PersistPrepared(ctx, row); err != nil {
		e.compensate(ctx, refs)
		return protocol.TransactionVote{}, fmt.Errorf("executor: persist prepared: %w", err)
	}

	return protocol.TransactionVote{Vote: protocol.VoteYes}, nil
}

// ---------------------------------------------------------------------------
// CommitLocal
// ---------------------------------------------------------------------------

// CommitLocal finalizes a PREPARED transaction. Idempotent.
// Returns nil when:
//   - tx is already COMMITTED (no-op)
//   - tx is not found (no-op; partner is protocol master)
//
// Returns ErrAlreadyTerminal when tx is in ROLLED_BACK or FAILED state.
func (e *Executor) CommitLocal(ctx context.Context, txID protocol.ForeignBankId) error {
	tx, err := e.store.FindTx(ctx, txID.RoutingNumber, txID.Id)
	if err != nil {
		return fmt.Errorf("executor: find tx for commit: %w", err)
	}
	if tx == nil {
		e.log.WarnContext(ctx, "commitLocal: tx not found — partner is master, no-op",
			"txID", txID)
		return nil
	}
	if tx.Status == store.TxStatusCommitted {
		e.log.DebugContext(ctx, "commitLocal: already committed — idempotent no-op",
			"txID", txID)
		return nil
	}
	if tx.Status != store.TxStatusPrepared {
		return fmt.Errorf("%w: status=%s txID=%v", ErrAlreadyTerminal, tx.Status, txID)
	}

	// FIX B — per-CONTRACT exercise idempotency. The per-txId COMMITTED guard above makes a
	// re-commit of the SAME txId a no-op, but a coordinator retry with a NEW txId for the
	// SAME contract would otherwise re-run the non-idempotent exercise settlement legs
	// (a second seller-strike CreditMonas creates money; a second buyer CreditStock creates
	// assets). We claim the contract ONCE: when this tx is an exercise and the contract is
	// already EXERCISED (claimed=false), the exercise settlement refs are skipped; only the
	// claiming tx applies them. The negotiation id is read from the tx's OPTION pseudo-account
	// leg, present in both directions (seller hosts OPTION@ours; buyer addresses OPTION@seller).
	exerciseNegID := exerciseNegotiationIDFromPostings(tx.PostingsJSON)
	skipExercise := false
	claimedNegID := ""
	if exerciseNegID != "" && e.gate != nil {
		exists, claimed, gerr := e.gate.ClaimExercise(ctx, exerciseNegID)
		if gerr != nil {
			if updateErr := e.store.UpdateTxStatus(ctx, txID.RoutingNumber, txID.Id, store.TxStatusFailed); updateErr != nil {
				e.log.ErrorContext(ctx, "commitLocal: failed to flip status to FAILED after gate error",
					"txID", txID, "err", updateErr)
			}
			return fmt.Errorf("executor: claim exercise contract neg=%s: %w", exerciseNegID, gerr)
		}
		if exists && !claimed {
			// Contract already EXERCISED (or terminal): a prior/concurrent tx settled it.
			// Skip ALL exercise settlement refs — exactly-once per contract. Plain refs
			// (none expected on an exercise tx) still run.
			skipExercise = true
			e.log.InfoContext(ctx, "commitLocal: exercise already settled for contract — skipping settlement (idempotent)",
				"txID", txID, "neg", exerciseNegID)
		} else if exists && claimed {
			claimedNegID = exerciseNegID // we own the claim; revert on settlement failure
		}
	}

	for _, ref := range tx.ReservationRefs {
		if skipExercise && isExerciseSettlementRef(ref) {
			continue
		}
		if err := e.commitRef(ctx, ref, txID); err != nil {
			// Settlement failed AFTER claiming the contract — revert the claim so a §2.9
			// retransmit can re-settle (preserves conservation under partial failure).
			if claimedNegID != "" {
				if relErr := e.gate.ReleaseClaim(ctx, claimedNegID); relErr != nil {
					e.log.ErrorContext(ctx, "commitLocal: failed to release exercise claim after settlement failure",
						"txID", txID, "neg", claimedNegID, "err", relErr)
				}
			}
			// Best-effort status flip to FAILED; log and propagate.
			if updateErr := e.store.UpdateTxStatus(ctx, txID.RoutingNumber, txID.Id, store.TxStatusFailed); updateErr != nil {
				e.log.ErrorContext(ctx, "commitLocal: failed to flip status to FAILED",
					"txID", txID, "err", updateErr)
			}
			return fmt.Errorf("executor: commit ref %+v: %w", ref, err)
		}
	}

	return e.store.UpdateTxStatus(ctx, txID.RoutingNumber, txID.Id, store.TxStatusCommitted)
}

// isExerciseSettlementRef reports whether a reservation ref is part of the cross-bank OTC
// option EXERCISE settlement that must be gated per-contract (FIX B): the seller stock
// delivery (RefKindOptionExercise), the buyer stock credit (RefKindStockCredit), and the
// seller strike credit (RefKindMonasCredit). On an exercise-shaped tx every credit/exercise
// ref belongs to that exercise; plain MONAS/STOCK/OPTION reservation refs do not appear on
// an exercise tx, so this gate never suppresses an unrelated leg.
func isExerciseSettlementRef(ref store.ReservationRef) bool {
	switch ref.Kind {
	case store.RefKindOptionExercise, store.RefKindStockCredit, store.RefKindMonasCredit:
		return true
	default:
		return false
	}
}

// exerciseNegotiationIDFromPostings extracts the option negotiation id from a committed
// transaction's stored postings when the tx is an exercise (a STOCK asset riding an OPTION
// pseudo-account — the §2.7.2 "Credit option pseudo-account for k stocks" signature). The
// pseudo-account id IS the negotiation id (keyed at the bank that issued the option). Works
// in both exercise directions: seller-host (OPTION@ours) and buyer (OPTION@seller). Returns
// "" when the tx is not an exercise or the postings cannot be parsed (gate then no-ops).
func exerciseNegotiationIDFromPostings(postingsJSON string) string {
	if postingsJSON == "" {
		return ""
	}
	var postings []protocol.Posting
	if err := json.Unmarshal([]byte(postingsJSON), &postings); err != nil {
		return ""
	}
	for i := range postings {
		pseudo, isPseudo := postings[i].Account.(*protocol.OptionPseudoAccount)
		if !isPseudo {
			continue
		}
		if _, isStock := postings[i].Asset.(*protocol.StockAsset); !isStock {
			continue
		}
		return pseudo.Id.Id
	}
	return ""
}

// ---------------------------------------------------------------------------
// RollbackLocal
// ---------------------------------------------------------------------------

// RollbackLocal releases all reservations for a PREPARED transaction. Idempotent.
// Best-effort: logs warnings but does not return errors for individual release
// failures (partner is protocol master).
func (e *Executor) RollbackLocal(ctx context.Context, txID protocol.ForeignBankId) error {
	tx, err := e.store.FindTx(ctx, txID.RoutingNumber, txID.Id)
	if err != nil {
		return fmt.Errorf("executor: find tx for rollback: %w", err)
	}
	if tx == nil {
		e.log.WarnContext(ctx, "rollbackLocal: tx not found — best-effort no-op",
			"txID", txID)
		return nil
	}
	if tx.Status != store.TxStatusPrepared {
		e.log.WarnContext(ctx, "rollbackLocal: tx not in PREPARED state — no-op",
			"txID", txID, "status", tx.Status)
		return nil
	}

	// Release LIFO (reverse order).
	e.compensate(ctx, tx.ReservationRefs)

	return e.store.UpdateTxStatus(ctx, txID.RoutingNumber, txID.Id, store.TxStatusRolledBack)
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

// isOurs returns true when a posting's account side belongs to our routing number.
// "Ours" means:
//   - RealAccount: prefix of 18-digit number matches our routing (e.g. "111...")
//   - PersonAccount: id.RoutingNumber == myRouting
//   - OptionPseudoAccount: id.RoutingNumber == myRouting
func (e *Executor) isOurs(p protocol.Posting) bool {
	switch acc := p.Account.(type) {
	case *protocol.RealAccount:
		return e.validator.accountIsOurs(acc.Num)
	case *protocol.PersonAccount:
		return acc.Id.RoutingNumber == e.myRouting
	case *protocol.OptionPseudoAccount:
		return acc.Id.RoutingNumber == e.myRouting
	}
	return false
}

// isOptionPseudoSideLeg reports whether a posting is a MONAS/STOCK leg sitting on an
// OPTION pseudo-account — the premium/share legs a partner-coordinated accept hangs off
// the option pseudo-account (FIX 3). Such legs are settled via the OPTION reservation
// lifecycle (the OPTION-unit leg) and the seller's real-account postings, not as
// standalone money/stock movements, so PrepareLocal skips them. The OPTION-unit leg
// itself (OptionAsset on the pseudo-account) is NOT a side leg — it is the reservation.
func isOptionPseudoSideLeg(p protocol.Posting) bool {
	if _, ok := p.Account.(*protocol.OptionPseudoAccount); !ok {
		return false
	}
	switch p.Asset.(type) {
	case *protocol.MonasAsset, *protocol.StockAsset:
		return true
	default:
		return false
	}
}

// optionExerciseDeliveryRef recognises the cross-bank EXERCISE delivery leg and, when it
// targets one of OUR option pseudo-accounts, returns a RefKindOptionExercise reservation ref
// that drives ExerciseOption on commit (delivering the seller's k reserved shares).
//
// Shape (B2-buyer / B1-seller, §2.7.2 "Credit option pseudo-account for k stocks"):
//
//	{Account: OPTION{ours, negId}, Amount: -k, Asset: STOCK{ticker}}
//
// This is distinct from the accept-shape pseudo side legs (which are MONAS premium legs or
// the OPTION-unit reservation), and from the strike-into-option MONAS leg of the same
// exercise tx (which is settled via the seller's real PERSON/MONAS credit leg). Only the
// negative STOCK leg on our pseudo-account represents the seller's stock delivery, so only
// it produces an exercise ref. Returns ok=false for anything else.
func (e *Executor) optionExerciseDeliveryRef(p protocol.Posting) (store.ReservationRef, bool) {
	pseudo, ok := p.Account.(*protocol.OptionPseudoAccount)
	if !ok || pseudo.Id.RoutingNumber != e.myRouting {
		return store.ReservationRef{}, false
	}
	if _, ok := p.Asset.(*protocol.StockAsset); !ok {
		return store.ReservationRef{}, false
	}
	// The seller DELIVERS shares: the option pseudo-account is CREDITED (negative amount).
	// A positive STOCK leg on the pseudo-account is not a delivery and is left to the
	// skip path (defensive — the protocol does not emit one).
	if !p.Amount.IsNegative() {
		return store.ReservationRef{}, false
	}
	negRouting := pseudo.Id.RoutingNumber
	negID := pseudo.Id.Id
	return store.ReservationRef{
		Kind:               store.RefKindOptionExercise,
		NegotiationRouting: &negRouting,
		NegotiationID:      &negID,
	}, true
}

// isExerciseTx reports whether any of OUR postings is the exercise delivery leg (an OPTION
// pseudo-account on our routing carrying a negative STOCK asset). When true, the tx is a
// cross-bank OTC option exercise where WE are the seller bank: our option pseudo-account is
// swept internally (stock delivered + seller credited), and the wire MONAS legs are
// balance-only. `ours` is the already-filtered slice of postings on our side.
func (e *Executor) isExerciseTx(ours []protocol.Posting) bool {
	for _, p := range ours {
		if _, ok := e.optionExerciseDeliveryRef(p); ok {
			return true
		}
	}
	return false
}

// isExerciseSellerCashLeg reports whether a posting is the seller's strike-cash leg of an
// exercise tx: a MONAS asset on a PERSON account belonging to OUR routing. (The buyer cash
// leg lives at the buyer's bank and never reaches us; the option pseudo MONAS leg is an
// OptionPseudoAccount, not a PersonAccount, so it is excluded here and skipped separately.)
func (e *Executor) isExerciseSellerCashLeg(p protocol.Posting) bool {
	if _, ok := p.Asset.(*protocol.MonasAsset); !ok {
		return false
	}
	person, ok := p.Account.(*protocol.PersonAccount)
	if !ok {
		return false
	}
	return person.Id.RoutingNumber == e.myRouting
}

// creditSellerExerciseStrike builds a MONAS_CREDIT ref that pays the seller the strike on
// commit. The seller's PERSON id resolves to their account in the leg's currency; the
// amount credited is the ABSOLUTE wire amount (the seller always receives, regardless of
// the wire sign convention the buyer's bank used). Returns ok=false only if the seller
// person cannot be parsed (defensive — the validator/recorder already vetted it).
func (e *Executor) creditSellerExerciseStrike(ctx context.Context, p protocol.Posting, allPostings []protocol.Posting) (store.ReservationRef, bool, error) {
	asset, ok := p.Asset.(*protocol.MonasAsset)
	if !ok {
		return store.ReservationRef{}, false, nil
	}
	person, ok := p.Account.(*protocol.PersonAccount)
	if !ok {
		return store.ReservationRef{}, false, nil
	}
	if e.bc == nil {
		return store.ReservationRef{}, false, errors.New("executor: BankingCoreReserver is nil for exercise seller credit")
	}
	ownerID, err := parseOwnerID(person.Id.Id)
	if err != nil {
		return store.ReservationRef{}, false, fmt.Errorf("executor: parse exercise seller id %q: %w", person.Id.Id, err)
	}
	num, err := e.bc.FindAccountByOwnerAndCurrency(ctx, ownerID, asset.Currency)
	if err != nil || num == "" {
		return store.ReservationRef{}, false, fmt.Errorf("executor: resolve exercise seller %d %s account: %w", ownerID, asset.Currency, err)
	}
	return store.ReservationRef{
		Kind:              store.RefKindMonasCredit,
		CreditAccountNum:  num,
		CreditAmount:      p.Amount.Abs().String(), // seller RECEIVES the strike — absolute amount
		CreditClientID:    ownerID,
		CreditCurrency:    asset.Currency,
		CreditFromAccount: foreignSenderAccount(p, allPostings),
	}, true, nil
}

// reservePosting dispatches to the appropriate downstream reservation call
// based on the asset type. Returns the ReservationRef for bookkeeping.
func (e *Executor) reservePosting(ctx context.Context, p protocol.Posting, txID protocol.ForeignBankId) (store.ReservationRef, error) {
	switch asset := p.Asset.(type) {

	case *protocol.MonasAsset:
		return e.reserveMonasPosting(ctx, p, asset, txID)

	case *protocol.StockAsset:
		return e.reserveStockPosting(ctx, p, asset, txID)

	case *protocol.OptionAsset:
		return e.reserveOptionPosting(ctx, p, asset, txID)

	default:
		return store.ReservationRef{}, fmt.Errorf("executor: unknown asset type %T", p.Asset)
	}
}

// reserveMonasPosting handles MONAS (monetary asset) reservation.
// Both RealAccount and PersonAccount are supported (Person → resolve to account number first).
func (e *Executor) reserveMonasPosting(ctx context.Context, p protocol.Posting, asset *protocol.MonasAsset, txID protocol.ForeignBankId) (store.ReservationRef, error) {
	if e.bc == nil {
		return store.ReservationRef{}, errors.New("executor: BankingCoreReserver is nil")
	}

	var accountNum string
	switch acc := p.Account.(type) {
	case *protocol.RealAccount:
		accountNum = acc.Num

	case *protocol.PersonAccount:
		// Person+MONAS: spec §2.6 allows opaque person id; resolve to real account.
		if acc.Id.RoutingNumber != e.myRouting {
			// Partner-side person — skip reservation (partner reserves their own side).
			return store.ReservationRef{}, nil
		}
		ownerID, err := parseOwnerID(acc.Id.Id)
		if err != nil {
			return store.ReservationRef{}, fmt.Errorf("executor: parse owner id %q: %w", acc.Id.Id, err)
		}
		num, err := e.bc.FindAccountByOwnerAndCurrency(ctx, ownerID, asset.Currency)
		if err != nil || num == "" {
			return store.ReservationRef{}, fmt.Errorf("executor: resolve person %d to %s account: %w", ownerID, asset.Currency, err)
		}
		accountNum = num

	default:
		return store.ReservationRef{}, fmt.Errorf("executor: MONAS posting with unexpected account type %T", p.Account)
	}

	resID, err := e.bc.ReserveMonas(ctx, accountNum, asset.Currency, p.Amount.Abs(), txID.RoutingNumber, txID.Id)
	if err != nil {
		return store.ReservationRef{}, fmt.Errorf("executor: ReserveMonas account=%s: %w", accountNum, err)
	}
	return store.ReservationRef{Kind: store.RefKindMonas, ReservationID: resID}, nil
}

// creditMonasPosting records an INCOMING MONAS credit to one of our accounts so it
// can be applied on commit. It performs NO balance mutation at prepare (incoming money
// needs no reservation). Returns ok=false for non-MONAS postings (e.g. option receipt),
// which are recorded as contracts elsewhere, not credited.
func (e *Executor) creditMonasPosting(ctx context.Context, p protocol.Posting, allPostings []protocol.Posting) (store.ReservationRef, bool, error) {
	asset, ok := p.Asset.(*protocol.MonasAsset)
	if !ok {
		return store.ReservationRef{}, false, nil
	}
	if e.bc == nil {
		return store.ReservationRef{}, false, errors.New("executor: BankingCoreReserver is nil")
	}

	var accountNum string
	var clientID int64
	switch acc := p.Account.(type) {
	case *protocol.RealAccount:
		info, err := e.bc.ResolveAccount(ctx, acc.Num)
		if err != nil || info == nil {
			return store.ReservationRef{}, false, fmt.Errorf("executor: resolve credit account %s: %w", acc.Num, err)
		}
		accountNum = acc.Num
		clientID = info.OwnerID

	case *protocol.PersonAccount:
		ownerID, err := parseOwnerID(acc.Id.Id)
		if err != nil {
			return store.ReservationRef{}, false, fmt.Errorf("executor: parse credit owner id %q: %w", acc.Id.Id, err)
		}
		num, err := e.bc.FindAccountByOwnerAndCurrency(ctx, ownerID, asset.Currency)
		if err != nil || num == "" {
			return store.ReservationRef{}, false, fmt.Errorf("executor: resolve person %d %s account: %w", ownerID, asset.Currency, err)
		}
		accountNum = num
		clientID = ownerID

	default:
		return store.ReservationRef{}, false, fmt.Errorf("executor: MONAS credit with unexpected account type %T", p.Account)
	}

	return store.ReservationRef{
		Kind:              store.RefKindMonasCredit,
		CreditAccountNum:  accountNum,
		CreditAmount:      p.Amount.String(),
		CreditClientID:    clientID,
		CreditCurrency:    asset.Currency,
		CreditFromAccount: foreignSenderAccount(p, allPostings),
	}, true, nil
}

// creditStockPosting records an INCOMING STOCK delivery to one of our LOCAL buyers
// (the buyer-side STOCK leg of a cross-bank OTC option exercise). It performs NO
// mutation at prepare (like an incoming MONAS credit); the shares are credited to the
// buyer's portfolio on commit via trading-service. Returns ok=false for any posting
// that is not a positive STOCK leg on one of our PersonAccounts, so the caller falls
// through to the MONAS-credit path.
//
// The per-unit strike (used by trading-service only as the average-purchase-price
// fallback when no live market price exists) is derived from the matching negative
// MONAS posting (the buyer's strike debit: |totalStrike| ÷ quantity).
func (e *Executor) creditStockPosting(p protocol.Posting, allPostings []protocol.Posting) (store.ReservationRef, bool, error) {
	if !p.Amount.IsPositive() {
		return store.ReservationRef{}, false, nil
	}
	asset, ok := p.Asset.(*protocol.StockAsset)
	if !ok {
		return store.ReservationRef{}, false, nil
	}
	person, ok := p.Account.(*protocol.PersonAccount)
	if !ok || person.Id.RoutingNumber != e.myRouting {
		// Not a local buyer — partner handles their own side.
		return store.ReservationRef{}, false, nil
	}
	buyerID, err := parseOwnerID(person.Id.Id)
	if err != nil {
		return store.ReservationRef{}, false, fmt.Errorf("executor: parse stock-credit buyer id %q: %w", person.Id.Id, err)
	}
	quantity := int(p.Amount.IntPart())
	strike := perUnitStrike(allPostings, quantity)
	return store.ReservationRef{
		Kind:                store.RefKindStockCredit,
		StockCreditBuyerID:  buyerID,
		StockCreditTicker:   asset.Ticker,
		StockCreditQuantity: quantity,
		StockCreditStrike:   strike.String(),
	}, true, nil
}

// perUnitStrike derives the per-unit strike price for a stock-credit leg from the
// transaction's negative MONAS posting (the buyer's strike debit): |amount| ÷ quantity.
// Returns zero when there is no negative MONAS leg or quantity is non-positive — in
// that case trading-service falls back to the live market price.
func perUnitStrike(allPostings []protocol.Posting, quantity int) decimal.Decimal {
	if quantity <= 0 {
		return decimal.Zero
	}
	for _, p := range allPostings {
		if !p.Amount.IsNegative() {
			continue
		}
		if _, ok := p.Asset.(*protocol.MonasAsset); !ok {
			continue
		}
		return p.Amount.Abs().Div(decimal.NewFromInt(int64(quantity)))
	}
	return decimal.Zero
}

// foreignSenderAccount returns the 18-digit account number of the matching negative
// MONAS posting (same currency) — i.e. the foreign sender that funds this incoming
// credit. Returns "" when the sender side is not a RealAccount (e.g. a Person-only
// counterparty), in which case the recorded payment leaves from_account blank-resolved
// by banking-core to a sentinel. Used only for transaction-history audit metadata.
func foreignSenderAccount(credit protocol.Posting, allPostings []protocol.Posting) string {
	creditAsset, ok := credit.Asset.(*protocol.MonasAsset)
	if !ok {
		return ""
	}
	for _, p := range allPostings {
		if !p.Amount.IsNegative() {
			continue
		}
		asset, ok := p.Asset.(*protocol.MonasAsset)
		if !ok || asset.Currency != creditAsset.Currency {
			continue
		}
		if real, ok := p.Account.(*protocol.RealAccount); ok {
			return real.Num
		}
	}
	return ""
}

// reserveStockPosting handles STOCK reservation (only PersonAccount sellers are valid here).
func (e *Executor) reserveStockPosting(ctx context.Context, p protocol.Posting, asset *protocol.StockAsset, txID protocol.ForeignBankId) (store.ReservationRef, error) {
	if e.td == nil {
		return store.ReservationRef{}, errors.New("executor: TradingReserver is nil")
	}

	person, ok := p.Account.(*protocol.PersonAccount)
	if !ok {
		return store.ReservationRef{}, fmt.Errorf("executor: STOCK posting requires PersonAccount, got %T", p.Account)
	}
	if person.Id.RoutingNumber != e.myRouting {
		// Partner-side stock seller — partner reserves their side, skip.
		return store.ReservationRef{}, nil
	}

	sellerUserID, err := parseOwnerID(person.Id.Id)
	if err != nil {
		return store.ReservationRef{}, fmt.Errorf("executor: parse stock seller id %q: %w", person.Id.Id, err)
	}

	resID, err := e.td.ReserveStock(ctx, sellerUserID, asset.Ticker, int(p.Amount.Abs().IntPart()), txID.RoutingNumber, txID.Id)
	if err != nil {
		return store.ReservationRef{}, fmt.Errorf("executor: ReserveStock seller=%d ticker=%s: %w", sellerUserID, asset.Ticker, err)
	}
	return store.ReservationRef{Kind: store.RefKindStock, ReservationID: resID}, nil
}

// reserveOptionPosting handles OPTION reservation (OptionPseudoAccount only).
// The negotiation id IS the reservation key — there is no separate reservation UUID.
// Per spec §3.6: the option pseudo-account.id is the negotiationId; the seller is
// looked up from our negotiation store (pseudo.id() is NOT the seller).
func (e *Executor) reserveOptionPosting(ctx context.Context, p protocol.Posting, asset *protocol.OptionAsset, txID protocol.ForeignBankId) (store.ReservationRef, error) {
	if e.td == nil {
		return store.ReservationRef{}, errors.New("executor: TradingReserver is nil")
	}

	pseudo, ok := p.Account.(*protocol.OptionPseudoAccount)
	if !ok {
		return store.ReservationRef{}, fmt.Errorf("executor: OPTION posting requires OptionPseudoAccount, got %T", p.Account)
	}
	if pseudo.Id.RoutingNumber != e.myRouting {
		// Partner-side option pseudo-account — skip.
		return store.ReservationRef{}, nil
	}

	negID := asset.NegotiationId.Id

	// Resolve seller from our negotiation mirror.
	// pseudo.Id.Id holds the negotiationId (NOT the seller); per Java comment in reservePosting.
	sellerForeignID := ""
	if e.negLookup != nil {
		sid, err := e.negLookup.FindSellerID(ctx, negID)
		if err != nil {
			return store.ReservationRef{}, fmt.Errorf("executor: resolve option seller for neg %q: %w", negID, err)
		}
		sellerForeignID = sid
	}

	err := e.td.ReserveOption(ctx, asset.NegotiationId, sellerForeignID, asset.Stock.Ticker, asset.Amount)
	if err != nil {
		return store.ReservationRef{}, fmt.Errorf("executor: ReserveOption neg=%s: %w", negID, err)
	}

	negRouting := pseudo.Id.RoutingNumber
	return store.ReservationRef{
		Kind:               store.RefKindOption,
		NegotiationRouting: &negRouting,
		NegotiationID:      &negID,
	}, nil
}

// commitRef finalizes a single reservation ref. txID identifies the inter-bank
// transaction and is used to derive the idempotent order_number for the
// transaction-history audit record on incoming credits.
func (e *Executor) commitRef(ctx context.Context, ref store.ReservationRef, txID protocol.ForeignBankId) error {
	switch ref.Kind {
	case store.RefKindMonas:
		if e.bc == nil {
			return errors.New("executor: BankingCoreReserver is nil for MONAS commit")
		}
		return e.bc.CommitMonas(ctx, ref.ReservationID)

	case store.RefKindMonasCredit:
		if e.bc == nil {
			return errors.New("executor: BankingCoreReserver is nil for MONAS credit")
		}
		amt, err := decimal.NewFromString(ref.CreditAmount)
		if err != nil {
			return fmt.Errorf("executor: parse credit amount %q: %w", ref.CreditAmount, err)
		}
		if err := e.bc.CreditMonas(ctx, ref.CreditAccountNum, amt, ref.CreditClientID); err != nil {
			return err
		}
		// Best-effort: record the incoming money so it shows in transaction history.
		// A recording failure must NOT fail the (already applied) credit — log & continue.
		e.recordInboundPayment(ctx, ref, amt, txID)
		return nil

	case store.RefKindStock:
		if e.td == nil {
			return errors.New("executor: TradingReserver is nil for STOCK commit")
		}
		return e.td.CommitStock(ctx, ref.ReservationID)

	case store.RefKindStockCredit:
		if e.td == nil {
			return errors.New("executor: TradingReserver is nil for STOCK credit")
		}
		strike, err := decimal.NewFromString(ref.StockCreditStrike)
		if err != nil {
			// A blank/invalid strike just means "no fallback price" — credit at zero and
			// let trading-service prefer the live market price.
			strike = decimal.Zero
		}
		return e.td.CreditStock(ctx, ref.StockCreditBuyerID, ref.StockCreditTicker,
			ref.StockCreditQuantity, txID.RoutingNumber, txID.Id, strike)

	case store.RefKindOption, store.RefKindOptionExercise:
		if e.td == nil {
			return errors.New("executor: TradingReserver is nil for OPTION exercise")
		}
		if ref.NegotiationRouting == nil || ref.NegotiationID == nil {
			return errors.New("executor: OPTION reservation ref missing negotiation fields")
		}
		// Both kinds finalize via ExerciseOption (idempotent): RefKindOption is the
		// accept-reserved option being exercised; RefKindOptionExercise is the seller-side
		// delivery leg of a cross-bank exercise tx. ExerciseOption commits the seller's
		// held stock reservation (quantity & reserved_quantity decrement) and flips the
		// option EXERCISED — the seller's shares are delivered and conservation holds.
		return e.td.ExerciseOption(ctx, protocol.ForeignBankId{
			RoutingNumber: *ref.NegotiationRouting,
			Id:            *ref.NegotiationID,
		})

	default:
		e.log.Warn("executor: commitRef: unknown ref kind — skipping", "kind", ref.Kind)
		return nil
	}
}

// recordInboundPayment writes a transaction-history row for an incoming cross-bank
// credit (money arriving at one of our accounts). Best-effort: a failure is logged and
// swallowed so it never affects the already-applied credit. The order_number is derived
// deterministically from the inter-bank tx id, so a 2PC retry is idempotent (banking-core
// does ON CONFLICT DO NOTHING). fromAccount may be "" when the foreign sender is a
// Person-only counterparty; banking-core then resolves it to a sentinel.
func (e *Executor) recordInboundPayment(ctx context.Context, ref store.ReservationRef, amt decimal.Decimal, txID protocol.ForeignBankId) {
	orderNumber := interbankOrderNumber(txID, "IN", ref.CreditAccountNum)
	if err := e.bc.RecordInterbankPayment(ctx, orderNumber, ref.CreditFromAccount, ref.CreditAccountNum, amt, ref.CreditCurrency, "", "Interbank inbound payment"); err != nil {
		e.log.WarnContext(ctx, "executor: record inbound interbank payment failed — credit already applied, continuing",
			"txID", txID, "account", ref.CreditAccountNum, "err", err)
	}
}

// interbankOrderNumber builds a deterministic, idempotent order_number for an
// inter-bank money leg. Multiple credit legs in one tx (rare) are disambiguated by the
// recipient account suffix.
func interbankOrderNumber(txID protocol.ForeignBankId, direction, accountSuffix string) string {
	base := fmt.Sprintf("BANKA1-IB-%s-%d-%s", direction, txID.RoutingNumber, txID.Id)
	if accountSuffix != "" {
		base += "-" + accountSuffix
	}
	return base
}

// releaseRef frees a single reservation ref. Best-effort — logs failures but
// does not return them (caller continues with remaining refs).
func (e *Executor) releaseRef(ctx context.Context, ref store.ReservationRef) {
	var err error
	switch ref.Kind {
	case store.RefKindMonasCredit, store.RefKindStockCredit, store.RefKindOptionExercise:
		// Incoming credit (MONAS/STOCK) is applied only on commit — nothing was reserved at
		// prepare, so there is nothing to release. OPTION_EXERCISE likewise reserves nothing
		// new: the seller's shares were reserved at accept-time. An exercise abort MUST leave
		// that reservation intact so the buyer can retry — releasing it here would strand the
		// option without its backing shares (broken asset conservation). Deliberate no-op.
		return
	case store.RefKindMonas:
		if e.bc != nil {
			err = e.bc.ReleaseMonas(ctx, ref.ReservationID)
		}
	case store.RefKindStock:
		if e.td != nil {
			err = e.td.ReleaseStock(ctx, ref.ReservationID)
		}
	case store.RefKindOption:
		if e.td != nil && ref.NegotiationRouting != nil && ref.NegotiationID != nil {
			err = e.td.ReleaseOption(ctx, protocol.ForeignBankId{
				RoutingNumber: *ref.NegotiationRouting,
				Id:            *ref.NegotiationID,
			})
		}
	default:
		e.log.Warn("executor: releaseRef: unknown ref kind — skipping", "kind", ref.Kind)
		return
	}
	if err != nil {
		e.log.Warn("executor: releaseRef: stuck reservation",
			"kind", ref.Kind,
			"reservationID", ref.ReservationID,
			"err", err)
	}
}

// compensate releases all refs in LIFO order. Best-effort: individual release
// errors are logged but do not interrupt the sweep.
func (e *Executor) compensate(ctx context.Context, refs []store.ReservationRef) {
	for i := len(refs) - 1; i >= 0; i-- {
		e.releaseRef(ctx, refs[i])
	}
}
