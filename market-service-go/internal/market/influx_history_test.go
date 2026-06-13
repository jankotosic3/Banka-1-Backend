package market

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestListingPricePointLineProtocol(t *testing.T) {
	point := ListingPricePoint{
		ListingID:       10,
		Ticker:          "BRK B",
		ListingType:     ListingTypeStock,
		ExchangeMICCode: "XNYS",
		Date:            time.Date(2026, 6, 9, 12, 30, 0, 0, time.UTC),
		Price:           "123.45000000",
		Ask:             "124.00000000",
		Bid:             "123.00000000",
		Change:          "1.25000000",
		Volume:          99,
	}

	line := point.toLineProtocol()

	if !strings.Contains(line, "listing_price,listing_id=10,ticker=BRK\\ B,listing_type=STOCK,exchange_code=XNYS") {
		t.Fatalf("unexpected tags: %s", line)
	}
	if !strings.Contains(line, "price=123.45000000,ask=124.00000000,bid=123.00000000,change=1.25000000,volume=99i") {
		t.Fatalf("unexpected fields: %s", line)
	}
	if !strings.HasSuffix(line, " 1780963200000000000") {
		t.Fatalf("unexpected timestamp: %s", line)
	}
}

func TestInfluxStoreWritesAndQueries(t *testing.T) {
	queryCSV := `#group,false,false,true,true,false,false
#datatype,string,long,dateTime:RFC3339,double,double,double,double,long
#default,_result,,,,,,
,result,table,_time,ask,bid,change,price,volume
,,0,2026-06-09T00:00:00Z,294.00000000,292.50000000,1.00000000,293.32000000,52000000
`
	var wroteBody string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/write":
			raw, _ := io.ReadAll(r.Body)
			wroteBody = string(raw)
			w.WriteHeader(http.StatusNoContent)
		case "/api/v2/query":
			w.Header().Set("Content-Type", "application/csv")
			_, _ = w.Write([]byte(queryCSV))
		default:
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
	}))
	defer server.Close()

	store := &InfluxPriceHistoryStore{
		baseURL: server.URL,
		org:     "banka1",
		bucket:  "market_data",
		token:   "token",
		client:  server.Client(),
	}
	point := ListingPricePoint{
		ListingID:       1,
		Ticker:          "AAPL",
		ListingType:     ListingTypeStock,
		ExchangeMICCode: "XNAS",
		Date:            time.Date(2026, 6, 9, 0, 0, 0, 0, time.UTC),
		Price:           "293.32000000",
		Ask:             "294.00000000",
		Bid:             "292.50000000",
		Change:          "1.00000000",
		Volume:          52000000,
	}

	if err := store.SaveDailySnapshots(context.Background(), []ListingPricePoint{point}); err != nil {
		t.Fatalf("SaveDailySnapshots error: %v", err)
	}
	if !strings.Contains(wroteBody, "listing_price,listing_id=1,ticker=AAPL") {
		t.Fatalf("unexpected write body: %s", wroteBody)
	}

	history, err := store.FindDailySnapshots(context.Background(), 1, time.Date(2026, 6, 1, 0, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatalf("FindDailySnapshots error: %v", err)
	}
	if len(history) != 1 {
		t.Fatalf("expected one history item, got %d", len(history))
	}
	if history[0].Price != "293.32000000" || history[0].Volume != 52000000 {
		t.Fatalf("unexpected history item: %+v", history[0])
	}
}
