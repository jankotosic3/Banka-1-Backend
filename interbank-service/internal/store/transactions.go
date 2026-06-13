package store

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Transaction status constants — mirror Java TxStatus enum.
const (
	TxStatusPrepared   = "PREPARED"
	TxStatusCommitted  = "COMMITTED"
	TxStatusRolledBack = "ROLLED_BACK"
	TxStatusFailed     = "FAILED"
)

// Reservation ref kind constants — what downstream system was reserved.
const (
	RefKindMonas  = "MONAS"
	RefKindStock  = "STOCK"
	RefKindOption = "OPTION"
	// RefKindOptionExercise marks the seller-side delivery of a cross-bank OTC option
	// EXERCISE (the negative STOCK leg on our option pseudo-account, §2.7.2). On commit it
	// drives ExerciseOption — committing the seller's already-held accept-time stock
	// reservation (quantity & reserved_quantity decrement) and flipping the option to
	// EXERCISED. It reserves NOTHING new at prepare (the shares were reserved at accept),
	// so releaseRef is a deliberate no-op: an exercise abort must leave the accept-time
	// reservation intact for a retry — releasing it (as a plain OPTION ref would) would
	// strand the option without its backing shares.
	RefKindOptionExercise = "OPTION_EXERCISE"
	// RefKindMonasCredit marks an INCOMING money posting to one of our accounts.
	// Unlike the others it is not a reservation: nothing is held at prepare; the
	// recipient is credited on commit, and releaseRef is a no-op (no debit to undo).
	RefKindMonasCredit = "MONAS_CREDIT"
	// RefKindStockCredit marks an INCOMING stock posting to one of our local buyers
	// (the buyer-side STOCK leg of a cross-bank OTC option exercise). Like
	// MONAS_CREDIT it reserves nothing at prepare: the shares are credited to the
	// buyer's portfolio on commit (via trading-service), and releaseRef is a no-op.
	// Without this the exercise debited the buyer's strike cash but never delivered
	// the shares (FIX 1: asset-conservation).
	RefKindStockCredit = "STOCK_CREDIT"
)

// ReservationRef captures a downstream reservation made during prepareLocal.
// On COMMIT_TX we commit each ref; on ROLLBACK_TX we release each ref (LIFO).
// Stored as JSONB array in interbank_transactions.reservation_refs.
type ReservationRef struct {
	Kind               string  `json:"kind"`                         // MONAS | STOCK | OPTION | MONAS_CREDIT
	ReservationID      string  `json:"reservationId,omitempty"`      // banking-core / trading UUID
	NegotiationRouting *int    `json:"negotiationRouting,omitempty"` // OPTION refs only
	NegotiationID      *string `json:"negotiationId,omitempty"`      // OPTION refs only

	// MONAS_CREDIT refs only — an incoming credit applied (not reserved) on commit.
	CreditAccountNum string `json:"creditAccountNum,omitempty"`
	CreditAmount     string `json:"creditAmount,omitempty"` // decimal serialized as a plain string
	CreditClientID   int64  `json:"creditClientId,omitempty"`
	// CreditCurrency and CreditFromAccount carry the audit metadata needed to record
	// the incoming money in this bank's transaction history on commit. CreditFromAccount
	// is the foreign sender's account (the matching negative MONAS posting), or "" when
	// the sender is a Person-only counterparty with no real account number on the wire.
	CreditCurrency    string `json:"creditCurrency,omitempty"`
	CreditFromAccount string `json:"creditFromAccount,omitempty"`

	// STOCK_CREDIT refs only — an incoming stock delivery to a local buyer, credited
	// (not reserved) on commit via trading-service. StockCreditBuyerID is the local
	// owner id; StockCreditTicker / StockCreditQuantity describe the shares; and
	// StockCreditStrike is the contract strike used as the average-purchase-price
	// fallback when trading-service has no live market price for the ticker.
	StockCreditBuyerID  int64  `json:"stockCreditBuyerId,omitempty"`
	StockCreditTicker   string `json:"stockCreditTicker,omitempty"`
	StockCreditQuantity int    `json:"stockCreditQuantity,omitempty"`
	StockCreditStrike   string `json:"stockCreditStrike,omitempty"` // decimal serialized as a plain string
}

// Transaction is one row in interbank_transactions.
// Column notes (verified against migration 20260524000001):
//   - PK is id BIGSERIAL (auto); the canonical business key is
//     (transaction_id_routing, transaction_id_local) with a UNIQUE constraint.
//   - postings_json, reservation_refs, and message_meta are all JSONB columns.
//     Java stores them as raw JSON strings; we do the same to keep exact round-trip.
//   - reservation_refs is scanned into []ReservationRef via json.Unmarshal.
//   - message_meta stores the audit fields (message/callNumber/paymentCode/paymentPurpose)
//     as a raw JSON string — callers compose/parse it themselves.
type Transaction struct {
	ID                   int64  // BIGSERIAL PK
	TransactionIdRouting int    // composite business key — routing number of originating bank
	TransactionIdLocal   string // composite business key — originating bank's local tx id
	Status               string
	PostingsJSON         string           // raw JSON string (JSONB column)
	ReservationRefs      []ReservationRef // deserialized from reservation_refs JSONB
	MessageMeta          string           // raw JSON string (JSONB column), audit fields
	CreatedAt            time.Time
	FinalizedAt          *time.Time
}

type TransactionStore struct{ pool querier }

func NewTransactionStore(pool *pgxpool.Pool) *TransactionStore {
	return &TransactionStore{pool: pool}
}

// PersistPrepared inserts a new row with status=PREPARED, populating t.ID and t.CreatedAt.
// Returns IsUniqueViolation on duplicate (transaction_id_routing, transaction_id_local).
func (s *TransactionStore) PersistPrepared(ctx context.Context, t *Transaction) error {
	refsJSON, err := json.Marshal(t.ReservationRefs)
	if err != nil {
		return err
	}
	// Use empty JSON array string if refs are nil/empty
	if t.ReservationRefs == nil {
		refsJSON = []byte("[]")
	}
	return s.pool.QueryRow(ctx, `
		INSERT INTO interbank_transactions
			(transaction_id_routing, transaction_id_local, status, postings_json, reservation_refs, message_meta)
		VALUES ($1, $2, $3, $4, $5, $6)
		RETURNING id, created_at`,
		t.TransactionIdRouting, t.TransactionIdLocal, TxStatusPrepared,
		t.PostingsJSON, string(refsJSON), t.MessageMeta,
	).Scan(&t.ID, &t.CreatedAt)
}

// FindByID returns (nil, nil) when not found. Keyed by the composite business
// key (transactionIdRouting, transactionIdLocal), not the BIGSERIAL id.
func (s *TransactionStore) FindByID(ctx context.Context, routing int, localID string) (*Transaction, error) {
	var t Transaction
	var refsJSON []byte
	err := s.pool.QueryRow(ctx, `
		SELECT id, transaction_id_routing, transaction_id_local, status,
		       postings_json, reservation_refs, message_meta, created_at, finalized_at
		FROM interbank_transactions
		WHERE transaction_id_routing = $1 AND transaction_id_local = $2`,
		routing, localID).Scan(
		&t.ID, &t.TransactionIdRouting, &t.TransactionIdLocal, &t.Status,
		&t.PostingsJSON, &refsJSON, &t.MessageMeta, &t.CreatedAt, &t.FinalizedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if len(refsJSON) > 0 {
		if err := json.Unmarshal(refsJSON, &t.ReservationRefs); err != nil {
			return nil, err
		}
	}
	return &t, nil
}

// UpdateStatus flips status and sets finalized_at to now(). Keyed by the
// composite business key (routing, localID).
func (s *TransactionStore) UpdateStatus(ctx context.Context, routing int, localID, status string) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE interbank_transactions
		SET status = $1, finalized_at = now()
		WHERE transaction_id_routing = $2 AND transaction_id_local = $3`,
		status, routing, localID)
	return err
}
