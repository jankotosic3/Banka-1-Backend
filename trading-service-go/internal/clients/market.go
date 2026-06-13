package clients

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"github.com/shopspring/decimal"
)

// MarketClient calls market-service. It covers both the stock listing lookups
// (StockClient in Java) and the FX conversion (ExchangeClient) — both live on
// market-service in this stack (services.stock.url / services.exchange.url).
type MarketClient struct {
	base *baseClient
}

// NewMarketClient builds a MarketClient over baseURL (SERVICES_MARKET_URL).
func NewMarketClient(baseURL string, tokens *ServiceTokenProvider, doer HTTPDoer) *MarketClient {
	return &MarketClient{base: newBaseClient(baseURL, tokens, doer)}
}

// GetListing mirrors StockClient.getListing: GET /api/listings/{id}?period=DAY.
func (c *MarketClient) GetListing(ctx context.Context, id int64) (*StockListing, error) {
	var out StockListing
	q := url.Values{"period": {"DAY"}}
	if err := c.base.doJSON(ctx, http.MethodGet, fmt.Sprintf("/api/listings/%d", id), q, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// StockListingSummary is the subset of market-service's ListingSummaryResponse
// (GET /api/listings/stocks) that the interbank credit-stock path consumes when
// resolving a ticker to its listing id + current price.
type StockListingSummary struct {
	ListingID int64           `json:"listingId"`
	Ticker    string          `json:"ticker"`
	Price     decimal.Decimal `json:"price"`
	Currency  string          `json:"currency"`
}

// stockListingPage mirrors the Spring-style PageListingSummaryResponse envelope
// (only the content slice is consumed).
type stockListingPage struct {
	Content []StockListingSummary `json:"content"`
}

// ResolveStockListing resolves a ticker to its STOCK listing (id + current price)
// via market-service's paginated stock-listing endpoint
// (GET /api/listings/stocks?search=TICKER), filtering for an exact case-insensitive
// ticker match. Returns (nil, nil) when no listing matches. Used by the interbank
// credit-stock path (FIX 1: crediting a local buyer the shares delivered on a
// cross-bank OTC option exercise), which needs the listing id to key the portfolio
// row and a sensible average purchase price.
func (c *MarketClient) ResolveStockListing(ctx context.Context, ticker string) (*StockListingSummary, error) {
	wanted := strings.TrimSpace(ticker)
	if wanted == "" {
		return nil, nil
	}
	q := url.Values{}
	q.Set("search", wanted)
	q.Set("size", "200")
	var page stockListingPage
	if err := c.base.doJSON(ctx, http.MethodGet, "/api/listings/stocks", q, nil, &page); err != nil {
		return nil, err
	}
	for i := range page.Content {
		if strings.EqualFold(strings.TrimSpace(page.Content[i].Ticker), wanted) {
			return &page.Content[i], nil
		}
	}
	return nil, nil
}

// Calculate mirrors ExchangeClient.calculate: GET /calculate?fromCurrency&toCurrency&amount.
func (c *MarketClient) Calculate(ctx context.Context, from, to string, amount decimal.Decimal) (*ExchangeRate, error) {
	q := url.Values{}
	q.Set("fromCurrency", from)
	q.Set("toCurrency", to)
	q.Set("amount", amount.String())
	var out ExchangeRate
	if err := c.base.doJSON(ctx, http.MethodGet, "/calculate", q, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// CalculateWithoutCommission mirrors ExchangeClient.calculateWithoutCommission:
// GET /internal/calculate/no-commission?fromCurrency&toCurrency&amount.
func (c *MarketClient) CalculateWithoutCommission(ctx context.Context, from, to string, amount decimal.Decimal) (*ExchangeRate, error) {
	q := url.Values{}
	q.Set("fromCurrency", from)
	q.Set("toCurrency", to)
	q.Set("amount", amount.String())
	var out ExchangeRate
	if err := c.base.doJSON(ctx, http.MethodGet, "/internal/calculate/no-commission", q, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// GetExchangeStatus mirrors StockClient.getExchangeStatus:
// GET /api/stock-exchanges/{id}/status.
func (c *MarketClient) GetExchangeStatus(ctx context.Context, exchangeID int64) (*ExchangeStatus, error) {
	var out ExchangeStatus
	if err := c.base.doJSON(ctx, http.MethodGet, fmt.Sprintf("/api/stock-exchanges/%d/status", exchangeID), nil, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// RefreshListing mirrors StockClient.refreshListing: POST
// /api/internal/listings/{id}/refresh. Upstream errors are tolerated (logged at
// the call site is unnecessary — the order proceeds on whatever quote data is
// persisted, and the next scheduled refresh recovers), so this never returns an
// error — matching Java swallowing RestClientException.
func (c *MarketClient) RefreshListing(ctx context.Context, id int64) {
	_ = c.base.doJSON(ctx, http.MethodPost, fmt.Sprintf("/api/internal/listings/%d/refresh", id), nil, nil, nil)
}

// StockPriceSnapshot mirrors the funds MarketPriceClient.StockPrice (subset of
// market-service StockPriceSnapshotDto): the four fields the funds layer
// consumes (current price, change percent, volume).
type StockPriceSnapshot struct {
	Ticker        string           `json:"ticker"`
	CurrentPrice  *decimal.Decimal `json:"currentPrice"`
	ChangePercent *decimal.Decimal `json:"changePercent"`
	Volume        *int64           `json:"volume"`
}

// FetchSnapshots mirrors MarketPriceClient.fetchSnapshots: GET
// /stocks/price-feed/current?tickers=AAPL,MSFT. Tolerant — returns empty on
// upstream failure (caller falls back to avgUnitPrice, mirroring Java).
func (c *MarketClient) FetchSnapshots(ctx context.Context, tickers []string) map[string]StockPriceSnapshot {
	if len(tickers) == 0 {
		return map[string]StockPriceSnapshot{}
	}
	q := url.Values{"tickers": {joinCSV(tickers)}}
	var out []StockPriceSnapshot
	if err := c.base.doJSON(ctx, http.MethodGet, "/stocks/price-feed/current", q, nil, &out); err != nil {
		return map[string]StockPriceSnapshot{}
	}
	result := make(map[string]StockPriceSnapshot, len(out))
	for _, p := range out {
		if p.Ticker == "" || p.CurrentPrice == nil {
			continue
		}
		if _, exists := result[p.Ticker]; exists {
			continue
		}
		result[p.Ticker] = p
	}
	return result
}

// CurrentPrices mirrors MarketPriceClient.currentPrices: project snapshots into
// {ticker -> currentPrice}.
func (c *MarketClient) CurrentPrices(ctx context.Context, tickers []string) map[string]decimal.Decimal {
	snaps := c.FetchSnapshots(ctx, tickers)
	out := make(map[string]decimal.Decimal, len(snaps))
	for ticker, snap := range snaps {
		if snap.CurrentPrice != nil {
			out[ticker] = *snap.CurrentPrice
		}
	}
	return out
}

// CurrentPrice mirrors MarketPriceClient.currentPrice: single-ticker convenience
// wrapper. Returns (price, true) on success, (zero, false) when missing.
func (c *MarketClient) CurrentPrice(ctx context.Context, ticker string) (decimal.Decimal, bool) {
	prices := c.CurrentPrices(ctx, []string{ticker})
	p, ok := prices[ticker]
	return p, ok
}

// ConvertNoCommission mirrors MarketPriceClient.convertNoCommission: wraps
// CalculateWithoutCommission and returns (amount, true) when the upstream
// answered, (zero, false) on failure. Same-currency or zero amount returns the
// input unchanged. Funds (NAV, holdings, dividend, liquidation) fall back to the
// original amount when this signals failure.
func (c *MarketClient) ConvertNoCommission(ctx context.Context, amount decimal.Decimal, from, to string) (decimal.Decimal, bool) {
	if amount.Sign() == 0 || from == "" || to == "" || strings.EqualFold(from, to) {
		return amount, true
	}
	rate, err := c.CalculateWithoutCommission(ctx, from, to, amount)
	if err != nil {
		return decimal.Zero, false
	}
	conv := rate.Converted()
	if conv == nil {
		return decimal.Zero, false
	}
	return *conv, true
}

func joinCSV(items []string) string {
	return strings.Join(items, ",")
}

// DividendData mirrors DividendDataClient.DividendData — one row per STOCK
// listing with the as-of price, exchange currency, and dividend yield (WP-14).
type DividendData struct {
	ListingID     int64            `json:"listingId"`
	Ticker        string           `json:"ticker"`
	Price         *decimal.Decimal `json:"price"`
	Currency      *string          `json:"currency"`
	DividendYield *decimal.Decimal `json:"dividendYield"`
}

// FetchDividendData mirrors DividendDataClient.fetchAll: GET
// /stocks/internal/dividend-data (SERVICE token). Tolerant — returns an empty
// slice on upstream failure, so a quarterly run with no data gracefully pays
// nothing instead of erroring.
func (c *MarketClient) FetchDividendData(ctx context.Context) []DividendData {
	var out []DividendData
	if err := c.base.doJSON(ctx, http.MethodGet, "/stocks/internal/dividend-data", nil, nil, &out); err != nil {
		return []DividendData{}
	}
	return out
}
