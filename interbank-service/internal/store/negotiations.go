package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/shopspring/decimal"
)

// Negotiation mirrors the interbank_negotiations row.
// Column notes (verified against migration 20260524000001):
//   - Timestamp columns are TIMESTAMP WITH TIME ZONE → time.Time
//   - price_amount, premium_amount are NUMERIC(20,4) → decimal.Decimal
//   - last_modified_at (NOT updated_at) is the mutable timestamp column
//   - linked_local_offer_id is an optional BIGINT FK to the intra-bank offer
type Negotiation struct {
	ID                    string
	BuyerRouting          int
	BuyerID               string
	SellerRouting         int
	SellerID              string
	StockTicker           string
	Amount                int
	PriceCurrency         string
	PriceAmount           decimal.Decimal
	PremiumCurrency       string
	PremiumAmount         decimal.Decimal
	SettlementDate        time.Time
	LastModifiedByRouting int
	LastModifiedByID      string
	IsOngoing             bool
	IsAuthoritative       bool
	RemoteNegotiationID   *string
	LinkedLocalOfferID    *int64 // optional FK to intra-bank otc offer
	Version               int64
	CreatedAt             time.Time
	LastModifiedAt        time.Time
}

type NegotiationStore struct {
	pool querier
}

func NewNegotiationStore(pool *pgxpool.Pool) *NegotiationStore {
	return &NegotiationStore{pool: pool}
}

// Insert writes a new negotiation row, populating CreatedAt and LastModifiedAt.
func (s *NegotiationStore) Insert(ctx context.Context, n *Negotiation) error {
	return s.pool.QueryRow(ctx, `
		INSERT INTO interbank_negotiations
			(id, buyer_routing_number, buyer_id, seller_routing_number, seller_id,
			 stock_ticker, amount, price_currency, price_amount,
			 premium_currency, premium_amount, settlement_date,
			 last_modified_by_routing, last_modified_by_id,
			 is_ongoing, is_authoritative, remote_negotiation_id, linked_local_offer_id, version)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,0)
		RETURNING created_at, last_modified_at`,
		n.ID, n.BuyerRouting, n.BuyerID, n.SellerRouting, n.SellerID,
		n.StockTicker, n.Amount, n.PriceCurrency, n.PriceAmount,
		n.PremiumCurrency, n.PremiumAmount, n.SettlementDate,
		n.LastModifiedByRouting, n.LastModifiedByID,
		n.IsOngoing, n.IsAuthoritative, n.RemoteNegotiationID, n.LinkedLocalOfferID,
	).Scan(&n.CreatedAt, &n.LastModifiedAt)
}

const negotiationSelectCols = `
	id, buyer_routing_number, buyer_id, seller_routing_number, seller_id,
	stock_ticker, amount, price_currency, price_amount,
	premium_currency, premium_amount, settlement_date,
	last_modified_by_routing, last_modified_by_id,
	is_ongoing, is_authoritative, remote_negotiation_id, linked_local_offer_id,
	version, created_at, last_modified_at`

func scanNegotiation(row pgx.Row) (*Negotiation, error) {
	var n Negotiation
	err := row.Scan(
		&n.ID, &n.BuyerRouting, &n.BuyerID, &n.SellerRouting, &n.SellerID,
		&n.StockTicker, &n.Amount, &n.PriceCurrency, &n.PriceAmount,
		&n.PremiumCurrency, &n.PremiumAmount, &n.SettlementDate,
		&n.LastModifiedByRouting, &n.LastModifiedByID,
		&n.IsOngoing, &n.IsAuthoritative, &n.RemoteNegotiationID, &n.LinkedLocalOfferID,
		&n.Version, &n.CreatedAt, &n.LastModifiedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &n, nil
}

// FindByID returns (nil, nil) if not found.
func (s *NegotiationStore) FindByID(ctx context.Context, id string) (*Negotiation, error) {
	row := s.pool.QueryRow(ctx,
		`SELECT `+negotiationSelectCols+` FROM interbank_negotiations WHERE id = $1`, id)
	return scanNegotiation(row)
}

// FindByAuthoritativeRef matches either local id (when we are the authoritative side)
// or remote_negotiation_id (when we hold a mirror of a remote-authoritative negotiation).
// The routing parameter is kept for future defense-in-depth filtering (cross-bank ID
// collision protection); for now it is used as a $1::int hint so pgx can infer the
// parameter type — postgres returns SQLSTATE 42P18 if a $N placeholder is never
// referenced in the query body, even when supplied in the parameter slice.
// Used by PUT/GET/DELETE handlers that accept the {routing, id} pair from the partner.
func (s *NegotiationStore) FindByAuthoritativeRef(ctx context.Context, routing int, id string) (*Negotiation, error) {
	row := s.pool.QueryRow(ctx,
		`SELECT `+negotiationSelectCols+` FROM interbank_negotiations
		 WHERE ($1::int IS NOT NULL)
		   AND ((is_authoritative = true AND id = $2)
		     OR (is_authoritative = false AND remote_negotiation_id = $2))`,
		routing, id)
	return scanNegotiation(row)
}

// FindByRoutingAndID is a strict lookup for cases where we know exactly which bank
// owns the negotiation (routing==our routing → local id, else → remote_negotiation_id).
func (s *NegotiationStore) FindByRoutingAndID(ctx context.Context, routing int, id string) (*Negotiation, error) {
	row := s.pool.QueryRow(ctx,
		`SELECT `+negotiationSelectCols+` FROM interbank_negotiations WHERE id = $1`, id)
	return scanNegotiation(row)
}

// UpdateCounter applies a counter-offer with optimistic locking.
// All economically-relevant fields (price, amount, settlement, premium,
// last_modified_by) are updated. Returns ErrOptimisticLockConflict when the
// version predicate fails (concurrent write detected).
func (s *NegotiationStore) UpdateCounter(ctx context.Context, n *Negotiation) error {
	tag, err := s.pool.Exec(ctx, `
		UPDATE interbank_negotiations
		SET stock_ticker              = $1,
		    price_currency            = $2,
		    price_amount              = $3,
		    amount                    = $4,
		    settlement_date           = $5,
		    premium_currency          = $6,
		    premium_amount            = $7,
		    last_modified_by_routing  = $8,
		    last_modified_by_id       = $9,
		    last_modified_at          = now(),
		    version                   = version + 1
		WHERE id = $10 AND version = $11`,
		n.StockTicker, n.PriceCurrency, n.PriceAmount, n.Amount,
		n.SettlementDate, n.PremiumCurrency, n.PremiumAmount,
		n.LastModifiedByRouting, n.LastModifiedByID,
		n.ID, n.Version)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrOptimisticLockConflict
	}
	n.Version++
	return nil
}

// MarkClosed flips is_ongoing=false (e.g., after DELETE or successful accept).
// Idempotent — returns nil even if already closed (WHERE is_ongoing=true filters).
func (s *NegotiationStore) MarkClosed(ctx context.Context, id string) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE interbank_negotiations
		SET is_ongoing      = false,
		    last_modified_at = now(),
		    version         = version + 1
		WHERE id = $1 AND is_ongoing = true`, id)
	return err
}

// ListForUser returns negotiations where the given user is buyer or seller
// (matching by foreign-id string, e.g. "C-15").
//
// When includeAll is true (admin/supervisor scope), userForeignID is ignored and ALL
// rows — open and closed — are returned for oversight/history, ordered by
// last_modified_at DESC.
//
// For a non-admin user the feed is the ACTIVE list only: closed negotiations
// (is_ongoing = false) are filtered out (FIX 4). Previously closed mirrors lingered in
// the user's active list because the FE maps is_ongoing=false → 'ACCEPTED' and could
// not tell a partner-rejected/closed mirror from a settled one, so a stale "open"
// negotiation kept showing an actionable Accept button.
func (s *NegotiationStore) ListForUser(ctx context.Context, userForeignID string, includeAll bool) ([]*Negotiation, error) {
	var rows interface {
		Next() bool
		Scan(...any) error
		Err() error
		Close()
	}
	var err error
	if includeAll {
		rows, err = s.pool.Query(ctx,
			`SELECT `+negotiationSelectCols+` FROM interbank_negotiations
			 ORDER BY last_modified_at DESC`)
	} else {
		rows, err = s.pool.Query(ctx,
			`SELECT `+negotiationSelectCols+` FROM interbank_negotiations
			 WHERE (buyer_id = $1 OR seller_id = $1) AND is_ongoing = true
			 ORDER BY last_modified_at DESC`,
			userForeignID)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []*Negotiation
	for rows.Next() {
		var n Negotiation
		if scanErr := rows.Scan(
			&n.ID, &n.BuyerRouting, &n.BuyerID, &n.SellerRouting, &n.SellerID,
			&n.StockTicker, &n.Amount, &n.PriceCurrency, &n.PriceAmount,
			&n.PremiumCurrency, &n.PremiumAmount, &n.SettlementDate,
			&n.LastModifiedByRouting, &n.LastModifiedByID,
			&n.IsOngoing, &n.IsAuthoritative, &n.RemoteNegotiationID, &n.LinkedLocalOfferID,
			&n.Version, &n.CreatedAt, &n.LastModifiedAt,
		); scanErr != nil {
			return nil, scanErr
		}
		cp := n
		out = append(out, &cp)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return out, nil
}
