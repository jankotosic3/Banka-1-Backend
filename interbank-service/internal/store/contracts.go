package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/shopspring/decimal"
)

// Contract status constants — mirror Java NegotiationContractStatus enum.
const (
	ContractStatusPendingPremium = "PENDING_PREMIUM" // created; premium 2PC not yet committed
	ContractStatusActive         = "ACTIVE"          // premium received; option is live
	ContractStatusExercising     = "EXERCISING"      // buyer exercise 2PC in flight (entry-level claim held)
	ContractStatusExercised      = "EXERCISED"       // option exercised successfully
	ContractStatusExpired        = "EXPIRED"         // settlement_date passed without exercise
	ContractStatusReleased       = "RELEASED"        // cancelled before expiry
)

// Contract mirrors the interbank_contracts row.
// Column notes (verified against migration 20260524000001):
//   - Price columns are strike_currency / strike_amount (NOT price_currency/price_amount)
//     because the SQL DDL uses the options-domain terminology "strike price".
//   - There are NO premium_* columns in this table — premiums are tracked in the
//     negotiation row and via the 2PC transaction.
//   - option_pseudo_owner_routing/id record which party conceptually "owns" the option
//     (the buyer holds the right; stored so the 2PC executor knows who to credit on exercise).
//   - No updated_at; exercised_at and expired_at are set on status transitions.
type Contract struct {
	ID                     string
	NegotiationID          string
	BuyerRouting           int
	BuyerID                string
	SellerRouting          int
	SellerID               string
	StockTicker            string
	Amount                 int
	StrikeCurrency         string
	StrikeAmount           decimal.Decimal
	SettlementDate         time.Time
	Status                 string
	OptionPseudoOwnerRouting int
	OptionPseudoOwnerID    string
	Version                int64
	CreatedAt              time.Time
	ExercisedAt            *time.Time
	ExpiredAt              *time.Time
}

type ContractStore struct{ pool querier }

func NewContractStore(pool *pgxpool.Pool) *ContractStore { return &ContractStore{pool: pool} }

const contractSelectCols = `
	id, negotiation_id, buyer_routing_number, buyer_id, seller_routing_number, seller_id,
	stock_ticker, amount, strike_currency, strike_amount, settlement_date, status,
	option_pseudo_owner_routing, option_pseudo_owner_id,
	version, created_at, exercised_at, expired_at`

func scanContract(row pgx.Row) (*Contract, error) {
	var c Contract
	err := row.Scan(
		&c.ID, &c.NegotiationID, &c.BuyerRouting, &c.BuyerID, &c.SellerRouting, &c.SellerID,
		&c.StockTicker, &c.Amount, &c.StrikeCurrency, &c.StrikeAmount, &c.SettlementDate, &c.Status,
		&c.OptionPseudoOwnerRouting, &c.OptionPseudoOwnerID,
		&c.Version, &c.CreatedAt, &c.ExercisedAt, &c.ExpiredAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &c, nil
}

// Insert writes a new contract row, populating CreatedAt.
func (s *ContractStore) Insert(ctx context.Context, c *Contract) error {
	return s.pool.QueryRow(ctx, `
		INSERT INTO interbank_contracts
			(id, negotiation_id, buyer_routing_number, buyer_id, seller_routing_number, seller_id,
			 stock_ticker, amount, strike_currency, strike_amount, settlement_date, status,
			 option_pseudo_owner_routing, option_pseudo_owner_id, version)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,0)
		RETURNING created_at`,
		c.ID, c.NegotiationID, c.BuyerRouting, c.BuyerID, c.SellerRouting, c.SellerID,
		c.StockTicker, c.Amount, c.StrikeCurrency, c.StrikeAmount, c.SettlementDate, c.Status,
		c.OptionPseudoOwnerRouting, c.OptionPseudoOwnerID,
	).Scan(&c.CreatedAt)
}

// FindByNegotiationID returns (nil, nil) if not found.
func (s *ContractStore) FindByNegotiationID(ctx context.Context, negotiationID string) (*Contract, error) {
	row := s.pool.QueryRow(ctx,
		`SELECT `+contractSelectCols+` FROM interbank_contracts WHERE negotiation_id = $1`, negotiationID)
	return scanContract(row)
}

// FindByID returns (nil, nil) if not found.
func (s *ContractStore) FindByID(ctx context.Context, id string) (*Contract, error) {
	row := s.pool.QueryRow(ctx,
		`SELECT `+contractSelectCols+` FROM interbank_contracts WHERE id = $1`, id)
	return scanContract(row)
}

// FindByIDForUpdate is FindByID under a row-level write lock (SELECT ... FOR UPDATE).
// It returns (nil, nil) when no row matches. Callers MUST run it inside a transaction
// (the lock is held until the surrounding tx commits/rolls back); outside a tx the lock is
// released immediately and offers no serialization. Used by the buyer-side exercise entry
// claim so two concurrent Exercise(sameContract) calls serialize on the row before either
// can read+flip its status.
func (s *ContractStore) FindByIDForUpdate(ctx context.Context, id string) (*Contract, error) {
	row := s.pool.QueryRow(ctx,
		`SELECT `+contractSelectCols+` FROM interbank_contracts WHERE id = $1 FOR UPDATE`, id)
	return scanContract(row)
}

// ClaimExerciseByID atomically transitions a contract ACTIVE→EXERCISING, ONLY when it is
// currently ACTIVE — the per-contract ENTRY claim for the BUYER-side off-wire exercise
// (mirrors B2's claimForExercise: findByIdForUpdate + ACTIVE→EXERCISING under the row lock).
// This single conditional UPDATE is the serialization point: two concurrent Exercise calls on
// the same contract race on the same row inside the locking CTE, exactly one observes
// status='ACTIVE' and flips it (rows-affected = 1); the loser sees 0 and is rejected BEFORE
// any strike reserve/commit, so the buyer is never double-charged. The final ACTIVE/EXERCISING
// →EXERCISED flip stays on the settlement path (UpdateStatus); this claim only guards the door.
//
// Returns (exists, claimed): exists=false when no contract row matches id at all; claimed=true
// when THIS call won the ACTIVE→EXERCISING transition; claimed=false when the row exists but was
// not ACTIVE (already EXERCISING / EXERCISED / terminal) — the caller must reject as a conflict.
func (s *ContractStore) ClaimExerciseByID(ctx context.Context, id string) (exists bool, claimed bool, err error) {
	const q = `
		WITH target AS (
			SELECT c.id, c.status
			FROM interbank_contracts c
			WHERE c.id = $1
			FOR UPDATE
		),
		upd AS (
			UPDATE interbank_contracts c
			SET status = 'EXERCISING', version = version + 1
			FROM target
			WHERE c.id = target.id AND target.status = 'ACTIVE'
			RETURNING c.id
		)
		SELECT
			(SELECT count(*) FROM target) AS existed,
			(SELECT count(*) FROM upd)    AS claimed`
	var existed, claimedCnt int
	if scanErr := s.pool.QueryRow(ctx, q, id).Scan(&existed, &claimedCnt); scanErr != nil {
		return false, false, scanErr
	}
	return existed > 0, claimedCnt > 0, nil
}

// RevertExercising reverts an EXERCISING claim (taken by ClaimExerciseByID) back to ACTIVE when
// the buyer-side 2PC/exercise failed after the claim, so the user can retry. Best-effort and
// idempotent: it only touches EXERCISING rows, never an already-EXERCISED one (settlement owns
// that final flip), so a revert that races a successful settlement is a no-op.
func (s *ContractStore) RevertExercising(ctx context.Context, id string) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE interbank_contracts
		SET status  = 'ACTIVE',
		    version = version + 1
		WHERE id = $1 AND status = 'EXERCISING'`, id)
	return err
}

// ListForUser returns all contracts where the given user is buyer or seller
// (matching by foreign-id string, e.g. "C-15"), ordered by created_at DESC.
// When includeAll is true (admin/supervisor scope), userForeignID is ignored and
// all rows are returned. Mirrors NegotiationStore.ListForUser.
func (s *ContractStore) ListForUser(ctx context.Context, userForeignID string, includeAll bool) ([]*Contract, error) {
	var rows pgx.Rows
	var err error
	if includeAll {
		rows, err = s.pool.Query(ctx,
			`SELECT `+contractSelectCols+` FROM interbank_contracts
			 ORDER BY created_at DESC`)
	} else {
		rows, err = s.pool.Query(ctx,
			`SELECT `+contractSelectCols+` FROM interbank_contracts
			 WHERE buyer_id = $1 OR seller_id = $1
			 ORDER BY created_at DESC`,
			userForeignID)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []*Contract
	for rows.Next() {
		c, scanErr := scanContract(rows)
		if scanErr != nil {
			return nil, scanErr
		}
		if c != nil {
			out = append(out, c)
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

// SumActiveBySellerAndTicker returns the total stock quantity reserved across
// ACTIVE inter-bank contracts where the seller matches (sellerRouting, sellerID)
// AND ticker matches. Used by GET /public-stock to subtract the
// committed-but-not-exercised inventory from the advertised quantity.
func (s *ContractStore) SumActiveBySellerAndTicker(ctx context.Context, sellerRouting int, sellerID, ticker string) (int, error) {
	var sum int
	err := s.pool.QueryRow(ctx, `
		SELECT COALESCE(SUM(amount), 0)
		FROM interbank_contracts
		WHERE seller_routing_number = $1
		  AND seller_id             = $2
		  AND stock_ticker          = $3
		  AND status                = 'ACTIVE'`,
		sellerRouting, sellerID, ticker).Scan(&sum)
	return sum, err
}

// ClaimExerciseByNegotiation atomically transitions to EXERCISED the contract whose
// negotiation matches negID, when it is currently ACTIVE or EXERCISING — the per-contract
// idempotency claim for cross-bank OTC exercise settlement (FIX B). The single
// conditional UPDATE is the serialization point: concurrent / different-txId exercises
// of the same contract race on the same row, and exactly one observes status IN
// ('ACTIVE','EXERCISING') and flips it (rows-affected = 1); the loser sees 0. negID may be
// EITHER our LOCAL negotiation id (seller-host case: contracts.negotiation_id == the option
// pseudo id we issued) OR the SELLER bank's authoritative id (buyer case: the option pseudo
// id is the seller-bank id, mapped to our local mirror via interbank_negotiations.remote_negotiation_id).
// Both are resolved in one statement.
//
// EXERCISING is accepted because on the BUYER-coordinator side the entry claim
// (ClaimExerciseByID) has ALREADY flipped ACTIVE→EXERCISING before CommitLocal runs. With an
// ACTIVE-only CAS this call would miss (0 rows), settlement would be skipped, and the buyer's
// stock-credit leg dropped — while the strike debit (off-wire, not under the skip gate) still
// goes through: buyer paid but never received the shares. The seller-host side has no entry
// claim, so its contract is ACTIVE and still matches.
//
// Returns (exists, claimed): exists=false when there is no matching contract row at all
// (gate then proceeds ungated); claimed=true when THIS call won the →EXERCISED transition;
// claimed=false when the contract exists but was already EXERCISED or terminal — settlement
// must then be skipped (idempotent: a second exercise sees EXERCISED and claims nothing).
func (s *ContractStore) ClaimExerciseByNegotiation(ctx context.Context, negID string) (exists bool, claimed bool, err error) {
	// Resolve the matching contract id via either keying scheme, then conditionally flip.
	// A CTE keeps it to one round-trip and makes the ACTIVE-guard atomic with the lookup.
	const q = `
		WITH target AS (
			SELECT c.id, c.status
			FROM interbank_contracts c
			WHERE c.negotiation_id = $1
			   OR c.negotiation_id IN (
					SELECT n.id FROM interbank_negotiations n
					WHERE n.remote_negotiation_id = $1
			   )
			LIMIT 1
			FOR UPDATE
		),
		upd AS (
			UPDATE interbank_contracts c
			SET status = 'EXERCISED', exercised_at = now(), version = version + 1
			FROM target
			WHERE c.id = target.id AND target.status IN ('ACTIVE', 'EXERCISING')
			RETURNING c.id
		)
		SELECT
			(SELECT count(*) FROM target)  AS existed,
			(SELECT count(*) FROM upd)     AS claimed`
	var existed, claimedCnt int
	if scanErr := s.pool.QueryRow(ctx, q, negID).Scan(&existed, &claimedCnt); scanErr != nil {
		return false, false, scanErr
	}
	return existed > 0, claimedCnt > 0, nil
}

// ReleaseExerciseClaimByNegotiation reverts an EXERCISED contract (claimed via
// ClaimExerciseByNegotiation) back to ACTIVE when settlement failed after the claim, so a
// §2.9 retransmit can re-settle. Best-effort and idempotent: it only touches EXERCISED rows
// and clears exercised_at. Uses the same dual-keying resolution as the claim.
func (s *ContractStore) ReleaseExerciseClaimByNegotiation(ctx context.Context, negID string) error {
	const q = `
		UPDATE interbank_contracts c
		SET status = 'ACTIVE', exercised_at = NULL, version = version + 1
		WHERE c.status = 'EXERCISED'
		  AND (c.negotiation_id = $1
		       OR c.negotiation_id IN (
				SELECT n.id FROM interbank_negotiations n
				WHERE n.remote_negotiation_id = $1
		       ))`
	_, err := s.pool.Exec(ctx, q, negID)
	return err
}

// UpdateStatus flips a contract's status (e.g. ACTIVE→EXERCISED or →EXPIRED).
// For EXERCISED transitions, exercised_at is set to now(); for EXPIRED, expired_at.
// Other transitions leave those columns NULL.
func (s *ContractStore) UpdateStatus(ctx context.Context, id, status string) error {
	var err error
	switch status {
	case ContractStatusExercised:
		_, err = s.pool.Exec(ctx, `
			UPDATE interbank_contracts
			SET status       = $1,
			    exercised_at = now(),
			    version      = version + 1
			WHERE id = $2`, status, id)
	case ContractStatusExpired:
		_, err = s.pool.Exec(ctx, `
			UPDATE interbank_contracts
			SET status    = $1,
			    expired_at = now(),
			    version   = version + 1
			WHERE id = $2`, status, id)
	default:
		_, err = s.pool.Exec(ctx, `
			UPDATE interbank_contracts
			SET status  = $1,
			    version = version + 1
			WHERE id = $2`, status, id)
	}
	return err
}
