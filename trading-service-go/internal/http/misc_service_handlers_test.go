package http

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/dividend"
	"banka1/trading-service-go/internal/interbank"
	"banka1/trading-service-go/internal/portfolio"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/shopspring/decimal"
	"github.com/stretchr/testify/assert"
)

func noTx(ctx context.Context, fn func(pgx.Tx) error) error { return fn(nil) }

// ===================== portfolio =====================

type portRepoStub struct{}

func (portRepoStub) Pool() *pgxpool.Pool { return nil }
func (portRepoStub) FindByUserID(context.Context, portfolio.Querier, int64) ([]portfolio.Portfolio, error) {
	return nil, nil
}
func (portRepoStub) FindByID(context.Context, portfolio.Querier, int64) (*portfolio.Portfolio, error) {
	return nil, nil
}
func (portRepoStub) FindByUserIDAndListingID(context.Context, portfolio.Querier, int64, int64) (*portfolio.Portfolio, error) {
	return nil, nil
}
func (portRepoStub) UpdatePublic(context.Context, portfolio.Querier, int64, int, bool) error {
	return nil
}
func (portRepoStub) Insert(context.Context, portfolio.Querier, int64, int64, string, int, decimal.Decimal) error {
	return nil
}
func (portRepoStub) UpdateQuantityAndAvg(context.Context, portfolio.Querier, int64, int, decimal.Decimal) error {
	return nil
}
func (portRepoStub) UpdateQuantity(context.Context, portfolio.Querier, int64, int) error { return nil }
func (portRepoStub) Delete(context.Context, portfolio.Querier, int64) error              { return nil }

type portMktStub struct{}

func (portMktStub) GetListing(context.Context, int64) (*clients.StockListing, error) {
	return nil, nil
}
func (portMktStub) Calculate(context.Context, string, string, decimal.Decimal) (*clients.ExchangeRate, error) {
	return nil, nil
}

type portAccStub struct{}

func (portAccStub) GetBankAccount(context.Context, string) (*clients.BankAccount, error) {
	return &clients.BankAccount{}, nil
}
func (portAccStub) GetAccountDetailsByID(context.Context, int64) (*clients.AccountDetails, error) {
	return nil, nil
}
func (portAccStub) GetGovernmentBankAccountRsd(context.Context) (*clients.AccountDetails, error) {
	return nil, nil
}
func (portAccStub) Transaction(context.Context, clients.Payment) error { return nil }

type portTaxStub struct{}

func (portTaxStub) CurrentYearPaidTax(context.Context, int64) (decimal.Decimal, error) {
	return decimal.Zero, nil
}
func (portTaxStub) CurrentMonthUnpaidTax(context.Context, int64) (decimal.Decimal, error) {
	return decimal.Zero, nil
}

func newPortfolioHandlers() *Handlers {
	svc := portfolio.NewServiceForTest(portRepoStub{}, portMktStub{}, portAccStub{}, portTaxStub{}, noTx)
	return &Handlers{app: &App{Portfolio: svc}}
}

func TestPortfolioSummary_Empty(t *testing.T) {
	h := newPortfolioHandlers()
	w := httptest.NewRecorder()
	h.PortfolioSummary(w, fundsReq(http.MethodGet, "/portfolio", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestPortfolioSetPublic_BadID(t *testing.T) {
	h := &Handlers{app: &App{}}
	w := httptest.NewRecorder()
	h.PortfolioSetPublic(w, withID(fundsReq(http.MethodPut, "/portfolio/x/set-public", ""), "id", "x"))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestPortfolioSetPublic_NotFound(t *testing.T) {
	h := newPortfolioHandlers()
	w := httptest.NewRecorder()
	h.PortfolioSetPublic(w, withID(fundsReq(http.MethodPut, "/portfolio/1/set-public", `{"publicQuantity":5}`), "id", "1"))
	assert.NotEqual(t, http.StatusOK, w.Code) // position not found
}

func TestPortfolioExerciseOption_BadID(t *testing.T) {
	h := &Handlers{app: &App{}}
	w := httptest.NewRecorder()
	h.PortfolioExerciseOption(w, withID(fundsReq(http.MethodPost, "/portfolio/x/exercise-option", ""), "id", "x"))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestPortfolioExerciseOption_NotFound(t *testing.T) {
	h := newPortfolioHandlers()
	w := httptest.NewRecorder()
	h.PortfolioExerciseOption(w, withID(fundsReq(http.MethodPost, "/portfolio/1/exercise-option", ""), "id", "1"))
	assert.NotEqual(t, http.StatusOK, w.Code)
}

// ===================== interbank =====================

type ibRepoStub struct{}

func (ibRepoStub) Pool() *pgxpool.Pool { return nil }
func (ibRepoStub) InsertStockReservation(context.Context, interbank.Querier, string, int, string, int64, string, int) error {
	return nil
}
func (ibRepoStub) FindStockReservationByReservationID(context.Context, interbank.Querier, string) (*interbank.StockReservation, error) {
	return nil, nil
}
func (ibRepoStub) FinalizeStockReservation(context.Context, interbank.Querier, string, string) error {
	return nil
}
func (ibRepoStub) FindOptionReservationByNegotiationID(context.Context, interbank.Querier, string) (*interbank.OptionReservation, error) {
	return nil, nil
}
func (ibRepoStub) InsertOptionReservation(context.Context, interbank.Querier, string, string, string, int64, string, int) error {
	return nil
}
func (ibRepoStub) UpdateOptionReservationStatus(context.Context, interbank.Querier, string, string) error {
	return nil
}

type ibPortStub struct{}

func (ibPortStub) Pool() *pgxpool.Pool { return nil }
func (ibPortStub) FindByUserID(context.Context, portfolio.Querier, int64) ([]portfolio.Portfolio, error) {
	return nil, nil
}
func (ibPortStub) FindByUserIDAndListingIDForUpdate(context.Context, portfolio.Querier, int64, int64) (*portfolio.Portfolio, error) {
	return nil, nil
}
func (ibPortStub) FindByIDForUpdate(context.Context, portfolio.Querier, int64) (*portfolio.Portfolio, error) {
	return nil, nil
}
func (ibPortStub) UpdateReservedQuantity(context.Context, portfolio.Querier, int64, int) error {
	return nil
}
func (ibPortStub) UpdateQuantityAndReserved(context.Context, portfolio.Querier, int64, int, int) error {
	return nil
}
func (ibPortStub) UpdateQuantityAndAvg(context.Context, portfolio.Querier, int64, int, decimal.Decimal) error {
	return nil
}
func (ibPortStub) Insert(context.Context, portfolio.Querier, int64, int64, string, int, decimal.Decimal) error {
	return nil
}
func (ibPortStub) FindAllPublicStocks(context.Context, portfolio.Querier) ([]portfolio.Portfolio, error) {
	return nil, nil
}

type ibMktStub struct{}

func (ibMktStub) GetListing(context.Context, int64) (*clients.StockListing, error) { return nil, nil }
func (ibMktStub) ResolveStockListing(context.Context, string) (*clients.StockListingSummary, error) {
	return nil, nil
}

func newInterbankHandlers() *Handlers {
	svc := interbank.NewServiceForTest(ibRepoStub{}, ibPortStub{}, ibMktStub{}, noTx, 111, nil)
	return &Handlers{app: &App{Interbank: svc}}
}

func TestInterbankReserveStock_NoPosition(t *testing.T) {
	h := newInterbankHandlers()
	w := httptest.NewRecorder()
	h.InterbankReserveStock(w, fundsReq(http.MethodPost, "/internal/interbank/reserve-stock",
		`{"sellerUserId":1,"ticker":"AAPL","quantity":5,"transactionIdRouting":111,"transactionIdLocal":"t1"}`))
	assert.NotEqual(t, http.StatusOK, w.Code)
}

func TestInterbankCommitStock_NotFound(t *testing.T) {
	h := newInterbankHandlers()
	r := fundsReq(http.MethodPost, "/internal/interbank/reservations/abc/commit-stock", "")
	r.SetPathValue("id", "abc")
	w := httptest.NewRecorder()
	h.InterbankCommitStock(w, r)
	assert.NotEqual(t, http.StatusNoContent, w.Code)
}

func TestInterbankReleaseStock_NotFound(t *testing.T) {
	h := newInterbankHandlers()
	r := fundsReq(http.MethodDelete, "/internal/interbank/reservations/abc", "")
	r.SetPathValue("id", "abc")
	w := httptest.NewRecorder()
	h.InterbankReleaseStock(w, r)
	assert.NotEqual(t, http.StatusNoContent, w.Code)
}

func TestInterbankReserveOption_Idempotent(t *testing.T) {
	h := newInterbankHandlers()
	r := fundsReq(http.MethodPost, "/internal/interbank/options/n1/reserve",
		`{"sellerForeignId":2,"ticker":"AAPL","quantity":5}`)
	r.SetPathValue("negotiationId", "n1")
	w := httptest.NewRecorder()
	h.InterbankReserveOption(w, r)
	assert.NotEqual(t, http.StatusInternalServerError, w.Code)
}

func TestInterbankExerciseOption_Idempotent(t *testing.T) {
	h := newInterbankHandlers()
	r := fundsReq(http.MethodPost, "/internal/interbank/options/n1/exercise", "")
	r.SetPathValue("negotiationId", "n1")
	w := httptest.NewRecorder()
	h.InterbankExerciseOption(w, r)
	assert.NotEqual(t, http.StatusInternalServerError, w.Code)
}

func TestInterbankReleaseOption_Idempotent(t *testing.T) {
	h := newInterbankHandlers()
	r := fundsReq(http.MethodDelete, "/internal/interbank/options/n1/release", "")
	r.SetPathValue("negotiationId", "n1")
	w := httptest.NewRecorder()
	h.InterbankReleaseOption(w, r)
	assert.NotEqual(t, http.StatusInternalServerError, w.Code)
}

func TestInterbankPublicStocks_Empty(t *testing.T) {
	h := newInterbankHandlers()
	w := httptest.NewRecorder()
	h.InterbankPublicStocks(w, fundsReq(http.MethodGet, "/internal/interbank/public-stocks", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

// ===================== dividend (WP-14) =====================

type divRepoStub struct{}

func (divRepoStub) Pool() *pgxpool.Pool { return nil }
func (divRepoStub) Insert(context.Context, dividend.Querier, *dividend.Payout) error { return nil }
func (divRepoStub) ExistsForDate(context.Context, dividend.Querier, int64, int64, time.Time, bool) (bool, error) {
	return false, nil
}
func (divRepoStub) FindByUserID(context.Context, dividend.Querier, int64) ([]dividend.Payout, error) {
	return nil, nil
}
func (divRepoStub) FindByUserIDAndListingID(context.Context, dividend.Querier, int64, int64) ([]dividend.Payout, error) {
	return nil, nil
}

type divPortStub struct{}

func (divPortStub) Pool() *pgxpool.Pool { return nil }
func (divPortStub) FindStockHoldersByListingID(context.Context, portfolio.Querier, int64) ([]portfolio.Portfolio, error) {
	return nil, nil
}

type divMktStub struct{}

func (divMktStub) FetchDividendData(context.Context) []clients.DividendData { return nil }
func (divMktStub) ConvertNoCommission(context.Context, decimal.Decimal, string, string) (decimal.Decimal, bool) {
	return decimal.Zero, false
}

type divAccStub struct{}

func (divAccStub) GetBankRsdOwnerAccount(context.Context) *clients.OwnerAccount  { return nil }
func (divAccStub) GetStateRsdOwnerAccount(context.Context) *clients.OwnerAccount { return nil }
func (divAccStub) GetAccountInCurrency(context.Context, int64, string) *clients.OwnerAccount {
	return nil
}
func (divAccStub) GetDefaultRsdAccountNumberForOwner(context.Context, int64) string { return "" }
func (divAccStub) CreditAccount(context.Context, string, decimal.Decimal, int64) error {
	return nil
}

func newDividendHandlers() *Handlers {
	svc := dividend.NewServiceForTest(divRepoStub{}, divPortStub{}, divMktStub{}, divAccStub{},
		func(ctx context.Context, q dividend.Querier, userID, listingID int64) (int64, error) { return 0, nil },
		noTx, decimal.RequireFromString("0.15"), nil)
	return &Handlers{app: &App{DividendPayout: svc}}
}

func TestMyDividends_Empty(t *testing.T) {
	h := newDividendHandlers()
	w := httptest.NewRecorder()
	h.MyDividends(w, fundsReq(http.MethodGet, "/dividends", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestMyDividends_BadListingID(t *testing.T) {
	h := newDividendHandlers()
	w := httptest.NewRecorder()
	h.MyDividends(w, fundsReq(http.MethodGet, "/dividends?listingId=x", ""))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestMyDividends_WithListingID(t *testing.T) {
	h := newDividendHandlers()
	w := httptest.NewRecorder()
	h.MyDividends(w, fundsReq(http.MethodGet, "/dividends?listingId=5", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestDividendTrigger_BadAsOf(t *testing.T) {
	h := newDividendHandlers()
	w := httptest.NewRecorder()
	h.DividendTrigger(w, fundsReq(http.MethodPost, "/dividends/trigger?asOf=bad", ""))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestDividendTrigger_Success(t *testing.T) {
	h := newDividendHandlers()
	w := httptest.NewRecorder()
	h.DividendTrigger(w, fundsReq(http.MethodPost, "/dividends/trigger", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}
