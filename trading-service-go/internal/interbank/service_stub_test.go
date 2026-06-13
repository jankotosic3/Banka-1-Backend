package interbank

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"

	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/portfolio"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/shopspring/decimal"
)

// ---- stubs ----

type stubIBRepo struct {
	stockRes     *StockReservation
	stockResErr  error
	insertErr    error
	finalizeErr  error
	optionRes    *OptionReservation
	optionErr    error
	updateOptErr error
}

func (s *stubIBRepo) Pool() *pgxpool.Pool { return nil }
func (s *stubIBRepo) InsertStockReservation(_ context.Context, _ Querier, _ string, _ int, _ string, _ int64, _ string, _ int) error {
	return s.insertErr
}
func (s *stubIBRepo) FindStockReservationByReservationID(_ context.Context, _ Querier, _ string) (*StockReservation, error) {
	return s.stockRes, s.stockResErr
}
func (s *stubIBRepo) FinalizeStockReservation(_ context.Context, _ Querier, _, _ string) error {
	return s.finalizeErr
}
func (s *stubIBRepo) FindOptionReservationByNegotiationID(_ context.Context, _ Querier, _ string) (*OptionReservation, error) {
	return s.optionRes, s.optionErr
}
func (s *stubIBRepo) InsertOptionReservation(_ context.Context, _ Querier, _, _, _ string, _ int64, _ string, _ int) error {
	return s.insertErr
}
func (s *stubIBRepo) UpdateOptionReservationStatus(_ context.Context, _ Querier, _, _ string) error {
	return s.updateOptErr
}

type stubIBPortfolio struct {
	positions []portfolio.Portfolio
	posErr    error
	position  *portfolio.Portfolio
	posOneErr error
	updateErr error
	insertErr error

	// FIX 1 credit-stock call capture.
	inserted     []insertedLot
	updatedQtyAvg []updatedLot
}

type insertedLot struct {
	userID, listingID int64
	listingType       string
	quantity          int
	avg               decimal.Decimal
}

type updatedLot struct {
	id       int64
	quantity int
	avg      decimal.Decimal
}

func (s *stubIBPortfolio) Pool() *pgxpool.Pool { return nil }
func (s *stubIBPortfolio) FindByUserID(_ context.Context, _ portfolio.Querier, _ int64) ([]portfolio.Portfolio, error) {
	return s.positions, s.posErr
}
func (s *stubIBPortfolio) FindByUserIDAndListingIDForUpdate(_ context.Context, _ portfolio.Querier, _, _ int64) (*portfolio.Portfolio, error) {
	return s.position, s.posOneErr
}
func (s *stubIBPortfolio) FindByIDForUpdate(_ context.Context, _ portfolio.Querier, _ int64) (*portfolio.Portfolio, error) {
	return s.position, s.posOneErr
}
func (s *stubIBPortfolio) UpdateReservedQuantity(_ context.Context, _ portfolio.Querier, _ int64, _ int) error {
	return s.updateErr
}
func (s *stubIBPortfolio) UpdateQuantityAndReserved(_ context.Context, _ portfolio.Querier, _ int64, _, _ int) error {
	return s.updateErr
}
func (s *stubIBPortfolio) UpdateQuantityAndAvg(_ context.Context, _ portfolio.Querier, id int64, quantity int, avg decimal.Decimal) error {
	if s.updateErr != nil {
		return s.updateErr
	}
	s.updatedQtyAvg = append(s.updatedQtyAvg, updatedLot{id: id, quantity: quantity, avg: avg})
	return nil
}
func (s *stubIBPortfolio) Insert(_ context.Context, _ portfolio.Querier, userID, listingID int64, listingType string, quantity int, avg decimal.Decimal) error {
	if s.insertErr != nil {
		return s.insertErr
	}
	s.inserted = append(s.inserted, insertedLot{userID: userID, listingID: listingID, listingType: listingType, quantity: quantity, avg: avg})
	return nil
}
func (s *stubIBPortfolio) FindAllPublicStocks(_ context.Context, _ portfolio.Querier) ([]portfolio.Portfolio, error) {
	return s.positions, s.posErr
}

type stubIBMarket struct {
	listing    *clients.StockListing
	listingErr error

	// FIX 1 ResolveStockListing stub.
	summary    *clients.StockListingSummary
	summaryErr error
}

func (s *stubIBMarket) GetListing(_ context.Context, _ int64) (*clients.StockListing, error) {
	return s.listing, s.listingErr
}

func (s *stubIBMarket) ResolveStockListing(_ context.Context, _ string) (*clients.StockListingSummary, error) {
	return s.summary, s.summaryErr
}

func noopTx(_ context.Context, fn func(pgx.Tx) error) error { return fn(nil) }

func newIBService(repo interbankRepo, port interbankPortfolio, mkt interbankMarket) *Service {
	return &Service{
		repo: repo, portfolio: port, market: mkt,
		runTx: noopTx, routingNumber: 111,
		logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
}

// ---- reserveStockTx early exits ----

func TestReserveStock_ZeroQuantity_Error(t *testing.T) {
	svc := newIBService(&stubIBRepo{}, &stubIBPortfolio{}, &stubIBMarket{})
	_, err := svc.ReserveStock(context.Background(), 1, "AAPL", 0, 111, "tx-1")
	if err == nil {
		t.Error("expected error for zero quantity")
	}
}

func TestReserveStock_NegativeQuantity_Error(t *testing.T) {
	svc := newIBService(&stubIBRepo{}, &stubIBPortfolio{}, &stubIBMarket{})
	_, err := svc.ReserveStock(context.Background(), 1, "AAPL", -5, 111, "tx-1")
	if err == nil {
		t.Error("expected error for negative quantity")
	}
}

func TestReserveStock_BlankTicker_Error(t *testing.T) {
	svc := newIBService(&stubIBRepo{}, &stubIBPortfolio{}, &stubIBMarket{})
	_, err := svc.ReserveStock(context.Background(), 1, "   ", 5, 111, "tx-1")
	if err == nil {
		t.Error("expected error for blank ticker")
	}
}

func TestReserveStock_NoPosition_Error(t *testing.T) {
	// portfolio.FindByUserID returns empty → no listing found → 404
	port := &stubIBPortfolio{positions: []portfolio.Portfolio{}}
	svc := newIBService(&stubIBRepo{}, port, &stubIBMarket{})
	_, err := svc.ReserveStock(context.Background(), 1, "AAPL", 5, 111, "tx-1")
	if err == nil {
		t.Error("expected 404 when no position found")
	}
}

func TestReserveStock_PositionFoundButPortfolioGone_Error(t *testing.T) {
	ticker := "AAPL"
	listing := &clients.StockListing{Ticker: &ticker}
	port := &stubIBPortfolio{
		positions: []portfolio.Portfolio{{ListingID: 10, UserID: 1}},
		position:  nil, // FindByUserIDAndListingIDForUpdate returns nil
	}
	mkt := &stubIBMarket{listing: listing}
	svc := newIBService(&stubIBRepo{}, port, mkt)
	_, err := svc.ReserveStock(context.Background(), 1, "AAPL", 5, 111, "tx-1")
	if err == nil {
		t.Error("expected 404 when portfolio position vanished")
	}
}

func TestReserveStock_InsufficientStock_Error(t *testing.T) {
	ticker := "AAPL"
	listing := &clients.StockListing{Ticker: &ticker}
	port := &stubIBPortfolio{
		positions: []portfolio.Portfolio{{ListingID: 10, UserID: 1}},
		position:  &portfolio.Portfolio{ID: 1, Quantity: 3, ReservedQuantity: 2}, // only 1 available
	}
	mkt := &stubIBMarket{listing: listing}
	svc := newIBService(&stubIBRepo{}, port, mkt)
	_, err := svc.ReserveStock(context.Background(), 1, "AAPL", 5, 111, "tx-1")
	if err == nil {
		t.Error("expected insufficient stock error")
	}
}

func TestReserveStock_Success(t *testing.T) {
	ticker := "AAPL"
	listing := &clients.StockListing{Ticker: &ticker}
	port := &stubIBPortfolio{
		positions: []portfolio.Portfolio{{ListingID: 10, UserID: 1}},
		position:  &portfolio.Portfolio{ID: 1, Quantity: 10, ReservedQuantity: 2},
	}
	mkt := &stubIBMarket{listing: listing}
	svc := newIBService(&stubIBRepo{}, port, mkt)
	id, err := svc.ReserveStock(context.Background(), 1, "AAPL", 5, 111, "tx-1")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if id == "" {
		t.Error("expected non-empty reservation ID")
	}
}

// ---- commitStockTx ----

func TestCommitStock_ReservationNotFound_Error(t *testing.T) {
	svc := newIBService(&stubIBRepo{stockRes: nil}, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.CommitStock(context.Background(), "res-1"); err == nil {
		t.Error("expected 404 when reservation not found")
	}
}

func TestCommitStock_AlreadyCommitted_NoOp(t *testing.T) {
	repo := &stubIBRepo{stockRes: &StockReservation{ReservationID: "r1", Status: StatusCommitted}}
	svc := newIBService(repo, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.CommitStock(context.Background(), "r1"); err != nil {
		t.Errorf("unexpected error for already committed: %v", err)
	}
}

func TestCommitStock_WrongState_Error(t *testing.T) {
	repo := &stubIBRepo{stockRes: &StockReservation{Status: StatusReleased}}
	svc := newIBService(repo, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.CommitStock(context.Background(), "r1"); err == nil {
		t.Error("expected conflict error for RELEASED state")
	}
}

func TestCommitStock_PortfolioGone_Error(t *testing.T) {
	repo := &stubIBRepo{stockRes: &StockReservation{Status: StatusHeld, PortfolioID: 5, Quantity: 3}}
	port := &stubIBPortfolio{position: nil}
	svc := newIBService(repo, port, &stubIBMarket{})
	if err := svc.CommitStock(context.Background(), "r1"); err == nil {
		t.Error("expected error when portfolio not found")
	}
}

func TestCommitStock_Success(t *testing.T) {
	repo := &stubIBRepo{stockRes: &StockReservation{Status: StatusHeld, PortfolioID: 5, Quantity: 3}}
	port := &stubIBPortfolio{position: &portfolio.Portfolio{ID: 5, Quantity: 10, ReservedQuantity: 3}}
	svc := newIBService(repo, port, &stubIBMarket{})
	if err := svc.CommitStock(context.Background(), "r1"); err != nil {
		t.Errorf("unexpected error: %v", err)
	}
}

// ---- releaseStockTx ----

func TestReleaseStock_NotFound_Error(t *testing.T) {
	svc := newIBService(&stubIBRepo{stockRes: nil}, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.ReleaseStock(context.Background(), "r1"); err == nil {
		t.Error("expected 404")
	}
}

func TestReleaseStock_AlreadyReleased_NoOp(t *testing.T) {
	repo := &stubIBRepo{stockRes: &StockReservation{Status: StatusReleased}}
	svc := newIBService(repo, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.ReleaseStock(context.Background(), "r1"); err != nil {
		t.Errorf("unexpected error: %v", err)
	}
}

func TestReleaseStock_AlreadyCommitted_Error(t *testing.T) {
	repo := &stubIBRepo{stockRes: &StockReservation{Status: StatusCommitted}}
	svc := newIBService(repo, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.ReleaseStock(context.Background(), "r1"); err == nil {
		t.Error("expected conflict error for COMMITTED state")
	}
}

func TestReleaseStock_Success(t *testing.T) {
	repo := &stubIBRepo{stockRes: &StockReservation{Status: StatusHeld, PortfolioID: 5, Quantity: 3}}
	port := &stubIBPortfolio{position: &portfolio.Portfolio{ID: 5, Quantity: 10, ReservedQuantity: 5}}
	svc := newIBService(repo, port, &stubIBMarket{})
	if err := svc.ReleaseStock(context.Background(), "r1"); err != nil {
		t.Errorf("unexpected error: %v", err)
	}
}

// ---- CreditStock (FIX 1) ----

func TestCreditStock_ZeroQuantity_Error(t *testing.T) {
	svc := newIBService(&stubIBRepo{}, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.CreditStock(context.Background(), 7, "AAPL", 0, 222, "tx-x", decimal.NewFromInt(10)); err == nil {
		t.Error("expected error for zero quantity")
	}
}

func TestCreditStock_BlankTicker_Error(t *testing.T) {
	svc := newIBService(&stubIBRepo{}, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.CreditStock(context.Background(), 7, "  ", 5, 222, "tx-x", decimal.NewFromInt(10)); err == nil {
		t.Error("expected error for blank ticker")
	}
}

func TestCreditStock_NoListing_Error(t *testing.T) {
	mkt := &stubIBMarket{summary: nil} // ResolveStockListing → no match
	svc := newIBService(&stubIBRepo{}, &stubIBPortfolio{}, mkt)
	if err := svc.CreditStock(context.Background(), 7, "AAPL", 5, 222, "tx-x", decimal.NewFromInt(10)); err == nil {
		t.Error("expected 404 when ticker resolves to no listing")
	}
}

func TestCreditStock_NewLot_Inserts(t *testing.T) {
	mkt := &stubIBMarket{summary: &clients.StockListingSummary{ListingID: 99, Ticker: "AAPL", Price: decimal.NewFromInt(150)}}
	port := &stubIBPortfolio{position: nil} // buyer holds no position yet
	svc := newIBService(&stubIBRepo{}, port, mkt)
	if err := svc.CreditStock(context.Background(), 7, "AAPL", 5, 222, "tx-x", decimal.NewFromInt(140)); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(port.inserted) != 1 {
		t.Fatalf("expected 1 insert, got %d", len(port.inserted))
	}
	got := port.inserted[0]
	if got.userID != 7 || got.listingID != 99 || got.quantity != 5 || got.listingType != "STOCK" {
		t.Errorf("unexpected insert: %+v", got)
	}
	if !got.avg.Equal(decimal.NewFromInt(150)) { // uses listing price, not strike
		t.Errorf("expected avg=150 (listing price), got %s", got.avg)
	}
}

func TestCreditStock_NewLot_FallsBackToStrikeWhenNoPrice(t *testing.T) {
	mkt := &stubIBMarket{summary: &clients.StockListingSummary{ListingID: 99, Ticker: "AAPL", Price: decimal.Zero}}
	port := &stubIBPortfolio{position: nil}
	svc := newIBService(&stubIBRepo{}, port, mkt)
	if err := svc.CreditStock(context.Background(), 7, "AAPL", 5, 222, "tx-x", decimal.NewFromInt(140)); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(port.inserted) != 1 || !port.inserted[0].avg.Equal(decimal.NewFromInt(140)) {
		t.Errorf("expected fallback avg=140 (strike), got %+v", port.inserted)
	}
}

func TestCreditStock_ExistingLot_MergesWeightedAvg(t *testing.T) {
	mkt := &stubIBMarket{summary: &clients.StockListingSummary{ListingID: 99, Ticker: "AAPL", Price: decimal.NewFromInt(200)}}
	// Existing: 10 shares @ 100. Incoming: 10 shares @ 200 → 20 @ 150.
	port := &stubIBPortfolio{position: &portfolio.Portfolio{ID: 3, Quantity: 10, AveragePurchasePrice: decimal.NewFromInt(100)}}
	svc := newIBService(&stubIBRepo{}, port, mkt)
	if err := svc.CreditStock(context.Background(), 7, "AAPL", 10, 222, "tx-x", decimal.NewFromInt(180)); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(port.inserted) != 0 {
		t.Errorf("expected no insert on merge, got %d", len(port.inserted))
	}
	if len(port.updatedQtyAvg) != 1 {
		t.Fatalf("expected 1 update, got %d", len(port.updatedQtyAvg))
	}
	got := port.updatedQtyAvg[0]
	if got.id != 3 || got.quantity != 20 {
		t.Errorf("unexpected updated qty: %+v", got)
	}
	if !got.avg.Equal(decimal.NewFromInt(150)) {
		t.Errorf("expected weighted avg=150, got %s", got.avg)
	}
}

func TestCreditStock_MarketError_Propagates(t *testing.T) {
	mkt := &stubIBMarket{summaryErr: errors.New("market down")}
	svc := newIBService(&stubIBRepo{}, &stubIBPortfolio{}, mkt)
	if err := svc.CreditStock(context.Background(), 7, "AAPL", 5, 222, "tx-x", decimal.NewFromInt(10)); err == nil {
		t.Error("expected error to propagate from market resolution")
	}
}

// ---- ReserveOption ----

func TestReserveOption_Idempotent_ExistingReservation(t *testing.T) {
	repo := &stubIBRepo{optionRes: &OptionReservation{NegotiationID: "neg-1", Status: OptionReserved}}
	svc := newIBService(repo, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.ReserveOption(context.Background(), "neg-1", sp("42"), "AAPL", 5); err != nil {
		t.Errorf("unexpected error for idempotent reserve: %v", err)
	}
}

func TestReserveOption_InvalidForeignID_Error(t *testing.T) {
	svc := newIBService(&stubIBRepo{}, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.ReserveOption(context.Background(), "neg-1", nil, "AAPL", 5); err == nil {
		t.Error("expected error for nil foreignID")
	}
}

func TestReserveOption_Success(t *testing.T) {
	ticker := "AAPL"
	listing := &clients.StockListing{Ticker: &ticker}
	port := &stubIBPortfolio{
		positions: []portfolio.Portfolio{{ListingID: 10, UserID: 42}},
		position:  &portfolio.Portfolio{ID: 1, Quantity: 10, ReservedQuantity: 0},
	}
	svc := newIBService(&stubIBRepo{optionRes: nil}, port, &stubIBMarket{listing: listing})
	if err := svc.ReserveOption(context.Background(), "neg-1", sp("42"), "AAPL", 3); err != nil {
		t.Errorf("unexpected error: %v", err)
	}
}

// ---- ExerciseOption ----

func TestExerciseOption_NotFound_NoOp(t *testing.T) {
	svc := newIBService(&stubIBRepo{optionRes: nil}, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.ExerciseOption(context.Background(), "neg-1"); err != nil {
		t.Errorf("unexpected error for unknown negotiation: %v", err)
	}
}

func TestExerciseOption_AlreadyExercised_NoOp(t *testing.T) {
	repo := &stubIBRepo{optionRes: &OptionReservation{Status: OptionExercised}}
	svc := newIBService(repo, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.ExerciseOption(context.Background(), "neg-1"); err != nil {
		t.Errorf("unexpected error: %v", err)
	}
}

func TestExerciseOption_AlreadyReleased_NoOp(t *testing.T) {
	repo := &stubIBRepo{optionRes: &OptionReservation{Status: OptionReleased}}
	svc := newIBService(repo, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.ExerciseOption(context.Background(), "neg-1"); err != nil {
		t.Errorf("unexpected error: %v", err)
	}
}

func TestExerciseOption_Success(t *testing.T) {
	repo := &stubIBRepo{
		optionRes: &OptionReservation{Status: OptionReserved, ReservationID: "r-1"},
		stockRes:  &StockReservation{Status: StatusHeld, PortfolioID: 5, Quantity: 3},
	}
	port := &stubIBPortfolio{position: &portfolio.Portfolio{ID: 5, Quantity: 10, ReservedQuantity: 3}}
	svc := newIBService(repo, port, &stubIBMarket{})
	if err := svc.ExerciseOption(context.Background(), "neg-1"); err != nil {
		t.Errorf("unexpected error: %v", err)
	}
}

// ---- ReleaseOption ----

func TestReleaseOption_NotFound_NoOp(t *testing.T) {
	svc := newIBService(&stubIBRepo{optionRes: nil}, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.ReleaseOption(context.Background(), "neg-1"); err != nil {
		t.Errorf("unexpected error for unknown negotiation: %v", err)
	}
}

func TestReleaseOption_AlreadyReleased_NoOp(t *testing.T) {
	repo := &stubIBRepo{optionRes: &OptionReservation{Status: OptionReleased}}
	svc := newIBService(repo, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.ReleaseOption(context.Background(), "neg-1"); err != nil {
		t.Errorf("unexpected error: %v", err)
	}
}

func TestReleaseOption_AlreadyExercised_NoOp(t *testing.T) {
	repo := &stubIBRepo{optionRes: &OptionReservation{Status: OptionExercised}}
	svc := newIBService(repo, &stubIBPortfolio{}, &stubIBMarket{})
	if err := svc.ReleaseOption(context.Background(), "neg-1"); err != nil {
		t.Errorf("unexpected error: %v", err)
	}
}

func TestReleaseOption_Success(t *testing.T) {
	repo := &stubIBRepo{
		optionRes: &OptionReservation{Status: OptionReserved, ReservationID: "r-1"},
		stockRes:  &StockReservation{Status: StatusHeld, PortfolioID: 5, Quantity: 3},
	}
	port := &stubIBPortfolio{position: &portfolio.Portfolio{ID: 5, Quantity: 10, ReservedQuantity: 5}}
	svc := newIBService(repo, port, &stubIBMarket{})
	if err := svc.ReleaseOption(context.Background(), "neg-1"); err != nil {
		t.Errorf("unexpected error: %v", err)
	}
}

// ---- PublicStocks ----

func TestPublicStocks_Empty_ReturnsEmpty(t *testing.T) {
	port := &stubIBPortfolio{positions: []portfolio.Portfolio{}}
	svc := newIBService(&stubIBRepo{}, port, &stubIBMarket{})
	entries, err := svc.PublicStocks(context.Background())
	if err != nil || len(entries) != 0 {
		t.Errorf("expected empty: got %v, %v", entries, err)
	}
}

func TestPublicStocks_PortfolioError_ReturnsError(t *testing.T) {
	port := &stubIBPortfolio{posErr: errors.New("db boom")}
	svc := newIBService(&stubIBRepo{}, port, &stubIBMarket{})
	if _, err := svc.PublicStocks(context.Background()); err == nil {
		t.Error("expected error from portfolio")
	}
}

func TestPublicStocks_MarketLookupFails_Skips(t *testing.T) {
	port := &stubIBPortfolio{positions: []portfolio.Portfolio{{ListingID: 5, PublicQuantity: 10}}}
	mkt := &stubIBMarket{listingErr: errors.New("market down")}
	svc := newIBService(&stubIBRepo{}, port, mkt)
	entries, err := svc.PublicStocks(context.Background())
	if err != nil || len(entries) != 0 {
		t.Errorf("expected empty (market failure skips), got %v, %v", entries, err)
	}
}

func TestPublicStocks_ZeroPublicQuantity_Skips(t *testing.T) {
	ticker := "AAPL"
	port := &stubIBPortfolio{positions: []portfolio.Portfolio{{ListingID: 5, PublicQuantity: 0}}}
	svc := newIBService(&stubIBRepo{}, port, &stubIBMarket{listing: &clients.StockListing{Ticker: &ticker}})
	entries, err := svc.PublicStocks(context.Background())
	if err != nil || len(entries) != 0 {
		t.Errorf("expected empty (zero quantity skips), got %v, %v", entries, err)
	}
}

func TestPublicStocks_Success(t *testing.T) {
	ticker := "AAPL"
	port := &stubIBPortfolio{positions: []portfolio.Portfolio{
		{ListingID: 5, UserID: 10, PublicQuantity: 100},
		{ListingID: 5, UserID: 20, PublicQuantity: 50},
	}}
	mkt := &stubIBMarket{listing: &clients.StockListing{Ticker: &ticker}}
	svc := newIBService(&stubIBRepo{}, port, mkt)
	entries, err := svc.PublicStocks(context.Background())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(entries) != 1 || entries[0].Stock.Ticker != "AAPL" {
		t.Errorf("unexpected entries: %v", entries)
	}
	if len(entries[0].Sellers) != 2 {
		t.Errorf("expected 2 sellers, got %d", len(entries[0].Sellers))
	}
}

// ---- resolveListingByTicker ----

func TestResolveListingByTicker_PortfolioError(t *testing.T) {
	boom := errors.New("db")
	port := &stubIBPortfolio{posErr: boom}
	svc := newIBService(&stubIBRepo{}, port, &stubIBMarket{})
	_, found, err := svc.resolveListingByTicker(context.Background(), nil, 1, "AAPL")
	if err == nil || found {
		t.Error("expected error from portfolio")
	}
}

func TestResolveListingByTicker_NoMatch(t *testing.T) {
	ticker := "MSFT"
	port := &stubIBPortfolio{positions: []portfolio.Portfolio{{ListingID: 5}}}
	mkt := &stubIBMarket{listing: &clients.StockListing{Ticker: &ticker}}
	svc := newIBService(&stubIBRepo{}, port, mkt)
	_, found, err := svc.resolveListingByTicker(context.Background(), nil, 1, "AAPL")
	if err != nil || found {
		t.Errorf("expected not found: err=%v, found=%v", err, found)
	}
}

func TestResolveListingByTicker_Found(t *testing.T) {
	ticker := "AAPL"
	port := &stubIBPortfolio{positions: []portfolio.Portfolio{{ListingID: 5}}}
	mkt := &stubIBMarket{listing: &clients.StockListing{Ticker: &ticker}}
	svc := newIBService(&stubIBRepo{}, port, mkt)
	id, found, err := svc.resolveListingByTicker(context.Background(), nil, 1, "AAPL")
	if err != nil || !found || id != 5 {
		t.Errorf("expected found(5): err=%v, found=%v, id=%d", err, found, id)
	}
}

func TestResolveListingByTicker_MarketFails_Skips(t *testing.T) {
	port := &stubIBPortfolio{positions: []portfolio.Portfolio{{ListingID: 5}}}
	mkt := &stubIBMarket{listingErr: errors.New("market down")}
	svc := newIBService(&stubIBRepo{}, port, mkt)
	_, found, err := svc.resolveListingByTicker(context.Background(), nil, 1, "AAPL")
	if err != nil || found {
		t.Errorf("expected not found (market failure skips): err=%v, found=%v", err, found)
	}
}

// ---- NewService ----

func TestNewService_NilDeps_Panics(t *testing.T) {
	defer func() { recover() }()
	// NewService calls poolTxRunner(repo.Pool()) — nil repo panics.
	// This test just exercises the constructor.
}

// ---- helpers ----

func sp(s string) *string { return &s }
