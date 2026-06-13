package market

import (
	"bytes"
	"context"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"

	"banka1/market-service-go/internal/platform"
)

const listingPriceMeasurement = "listing_price"

type PriceHistoryStore interface {
	SaveDailySnapshots(ctx context.Context, points []ListingPricePoint) error
	FindDailySnapshots(ctx context.Context, listingID int64, from time.Time) ([]DailyPriceInfo, error)
}

type ListingPricePoint struct {
	ListingID       int64
	Ticker          string
	ListingType     ListingType
	ExchangeMICCode string
	Date            time.Time
	Price           string
	Ask             string
	Bid             string
	Change          string
	Volume          int64
}

type InfluxPriceHistoryStore struct {
	baseURL string
	org     string
	bucket  string
	token   string
	client  *http.Client
	logger  *slog.Logger
}

func NewInfluxPriceHistoryStore(cfg platform.Config, logger *slog.Logger) PriceHistoryStore {
	if !cfg.Stock.InfluxEnabled || strings.TrimSpace(cfg.Stock.InfluxURL) == "" || strings.TrimSpace(cfg.Stock.InfluxBucket) == "" {
		return nil
	}
	return &InfluxPriceHistoryStore{
		baseURL: strings.TrimRight(cfg.Stock.InfluxURL, "/"),
		org:     cfg.Stock.InfluxOrg,
		bucket:  cfg.Stock.InfluxBucket,
		token:   cfg.Stock.InfluxToken,
		client:  &http.Client{Timeout: 5 * time.Second},
		logger:  logger,
	}
}

func (s *InfluxPriceHistoryStore) SaveDailySnapshots(ctx context.Context, points []ListingPricePoint) error {
	if s == nil || len(points) == 0 {
		return nil
	}
	lines := make([]string, 0, len(points))
	for _, point := range points {
		if point.ListingID <= 0 || point.Date.IsZero() {
			continue
		}
		lines = append(lines, point.toLineProtocol())
	}
	if len(lines) == 0 {
		return nil
	}
	endpoint, err := url.Parse(s.baseURL + "/api/v2/write")
	if err != nil {
		return err
	}
	query := endpoint.Query()
	query.Set("org", s.org)
	query.Set("bucket", s.bucket)
	query.Set("precision", "ns")
	endpoint.RawQuery = query.Encode()

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint.String(), strings.NewReader(strings.Join(lines, "\n")))
	if err != nil {
		return err
	}
	s.authorize(req)
	req.Header.Set("Content-Type", "text/plain; charset=utf-8")

	resp, err := s.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
		return fmt.Errorf("influx write failed: status=%d body=%s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	return nil
}

func (s *InfluxPriceHistoryStore) FindDailySnapshots(ctx context.Context, listingID int64, from time.Time) ([]DailyPriceInfo, error) {
	if s == nil || listingID <= 0 {
		return nil, nil
	}
	endpoint, err := url.Parse(s.baseURL + "/api/v2/query")
	if err != nil {
		return nil, err
	}
	query := endpoint.Query()
	query.Set("org", s.org)
	endpoint.RawQuery = query.Encode()

	payload := map[string]string{"query": s.fluxQuery(listingID, from)}
	raw, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint.String(), bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	s.authorize(req)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/csv")

	resp, err := s.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("influx query failed: status=%d body=%s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	return parseInfluxCSV(string(body))
}

func (s *InfluxPriceHistoryStore) authorize(req *http.Request) {
	if strings.TrimSpace(s.token) != "" {
		req.Header.Set("Authorization", "Token "+s.token)
	}
}

func (s *InfluxPriceHistoryStore) fluxQuery(listingID int64, from time.Time) string {
	start := from.UTC().Format(time.RFC3339)
	return fmt.Sprintf(`from(bucket: %q)
  |> range(start: %s)
  |> filter(fn: (r) => r._measurement == %q)
  |> filter(fn: (r) => r.listing_id == %q)
  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
  |> keep(columns: ["_time", "price", "ask", "bid", "change", "volume"])
  |> sort(columns: ["_time"])`, s.bucket, start, listingPriceMeasurement, strconv.FormatInt(listingID, 10))
}

func (p ListingPricePoint) toLineProtocol() string {
	timestamp := time.Date(p.Date.Year(), p.Date.Month(), p.Date.Day(), 0, 0, 0, 0, time.UTC).UnixNano()
	return fmt.Sprintf("%s,listing_id=%d,ticker=%s,listing_type=%s,exchange_code=%s price=%s,ask=%s,bid=%s,change=%s,volume=%di %d",
		listingPriceMeasurement,
		p.ListingID,
		escapeInfluxTag(p.Ticker),
		escapeInfluxTag(string(p.ListingType)),
		escapeInfluxTag(p.ExchangeMICCode),
		normalizeInfluxDecimal(p.Price),
		normalizeInfluxDecimal(p.Ask),
		normalizeInfluxDecimal(p.Bid),
		normalizeInfluxDecimal(p.Change),
		p.Volume,
		timestamp,
	)
}

func parseInfluxCSV(raw string) ([]DailyPriceInfo, error) {
	reader := csv.NewReader(strings.NewReader(raw))
	reader.FieldsPerRecord = -1
	var header []string
	var out []DailyPriceInfo
	for {
		record, err := reader.Read()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, err
		}
		if len(record) == 0 || strings.HasPrefix(record[0], "#") {
			continue
		}
		if contains(record, "_time") {
			header = record
			continue
		}
		if header == nil {
			continue
		}
		row := mapByHeader(header, record)
		item, ok := dailyPriceFromInfluxRow(row)
		if ok {
			out = append(out, item)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Date.Before(out[j].Date) })
	return out, nil
}

func dailyPriceFromInfluxRow(row map[string]string) (DailyPriceInfo, bool) {
	rawTime := row["_time"]
	if rawTime == "" {
		return DailyPriceInfo{}, false
	}
	instant, err := time.Parse(time.RFC3339Nano, rawTime)
	if err != nil {
		return DailyPriceInfo{}, false
	}
	volume, _ := strconv.ParseInt(row["volume"], 10, 64)
	return DailyPriceInfo{
		Date:   time.Date(instant.Year(), instant.Month(), instant.Day(), 0, 0, 0, 0, time.UTC),
		Price:  nonEmpty(row["price"], "0"),
		Ask:    nonEmpty(row["ask"], row["price"]),
		Bid:    nonEmpty(row["bid"], row["price"]),
		Change: nonEmpty(row["change"], "0"),
		Volume: volume,
	}, true
}

func mapByHeader(header, record []string) map[string]string {
	out := make(map[string]string, len(header))
	for i, name := range header {
		if i < len(record) {
			out[name] = record[i]
		}
	}
	return out
}

func contains(values []string, needle string) bool {
	for _, value := range values {
		if value == needle {
			return true
		}
	}
	return false
}

func escapeInfluxTag(value string) string {
	value = strings.TrimSpace(value)
	value = strings.ReplaceAll(value, `\`, `\\`)
	value = strings.ReplaceAll(value, ",", `\,`)
	value = strings.ReplaceAll(value, " ", `\ `)
	value = strings.ReplaceAll(value, "=", `\=`)
	if value == "" {
		return "unknown"
	}
	return value
}

func normalizeInfluxDecimal(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return "0"
	}
	return value
}

func nonEmpty(value, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return value
}
