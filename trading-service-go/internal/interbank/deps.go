package interbank

import (
	"context"
	"time"

	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/portfolio"

	gpdb "banka1/go-platform/db"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/shopspring/decimal"
)

// interbankRepo abstracts *Repository for unit testing.
type interbankRepo interface {
	Pool() *pgxpool.Pool
	InsertStockReservation(ctx context.Context, q Querier, reservationID string, txRouting int, txLocal string, portfolioID int64, ticker string, quantity int) error
	FindStockReservationByReservationID(ctx context.Context, q Querier, reservationID string) (*StockReservation, error)
	FinalizeStockReservation(ctx context.Context, q Querier, reservationID, status string) error
	FindOptionReservationByNegotiationID(ctx context.Context, q Querier, negotiationID string) (*OptionReservation, error)
	InsertOptionReservation(ctx context.Context, q Querier, negotiationID, reservationID, status string, sellerUserID int64, ticker string, quantity int) error
	UpdateOptionReservationStatus(ctx context.Context, q Querier, negotiationID, status string) error
}

// interbankPortfolio abstracts *portfolio.Repository for unit testing.
type interbankPortfolio interface {
	Pool() *pgxpool.Pool
	FindByUserID(ctx context.Context, q portfolio.Querier, userID int64) ([]portfolio.Portfolio, error)
	FindByUserIDAndListingIDForUpdate(ctx context.Context, q portfolio.Querier, userID, listingID int64) (*portfolio.Portfolio, error)
	FindByIDForUpdate(ctx context.Context, q portfolio.Querier, id int64) (*portfolio.Portfolio, error)
	UpdateReservedQuantity(ctx context.Context, q portfolio.Querier, id int64, reserved int) error
	UpdateQuantityAndReserved(ctx context.Context, q portfolio.Querier, id int64, quantity, reserved int) error
	UpdateQuantityAndAvg(ctx context.Context, q portfolio.Querier, id int64, quantity int, avg decimal.Decimal) error
	Insert(ctx context.Context, q portfolio.Querier, userID, listingID int64, listingType string, quantity int, avg decimal.Decimal) error
	FindAllPublicStocks(ctx context.Context, q portfolio.Querier) ([]portfolio.Portfolio, error)
}

// interbankMarket abstracts the MarketClient methods used by Service.
type interbankMarket interface {
	GetListing(ctx context.Context, id int64) (*clients.StockListing, error)
	// ResolveStockListing maps a ticker to its STOCK listing (id + current price).
	// Used by CreditStock (FIX 1) to key the buyer's portfolio row when the buyer
	// has no existing position for that ticker.
	ResolveStockListing(ctx context.Context, ticker string) (*clients.StockListingSummary, error)
}

// txRunner runs fn inside a single transaction.
type txRunner func(ctx context.Context, fn func(pgx.Tx) error) error

func poolTxRunner(pool *pgxpool.Pool) txRunner {
	return func(ctx context.Context, fn func(pgx.Tx) error) error {
		return gpdb.RunInTx(ctx, pool, pgx.TxOptions{}, fn)
	}
}

// suppress unused import
var _ = time.Now
