package market

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sort"
	"strings"
	"time"

	"banka1/market-service-go/internal/api"
	"banka1/market-service-go/internal/clients"
	"banka1/market-service-go/internal/fx"
	"banka1/market-service-go/internal/platform"
	"github.com/jackc/pgx/v5"
	"github.com/shopspring/decimal"
)

type Service struct {
	cfg            platform.Config
	repo           *Repository
	fx             *fx.Service
	logger         *slog.Logger
	alphaClient    *clients.AlphaVantageClient
	alertPublisher PriceAlertPublisher
	historyStore   PriceHistoryStore
}

var (
	ErrNotFound   = errors.New("not found")
	ErrBadRequest = errors.New("bad request")
	ErrConflict   = errors.New("conflict")
)

type PriceAlertPublisher interface {
	PublishPriceAlertTriggered(ctx context.Context, payload PriceAlertNotificationPayload) error
}

type PriceAlertNotificationPayload struct {
	Username          string            `json:"username"`
	UserEmail         *string           `json:"userEmail"`
	TemplateVariables map[string]string `json:"templateVariables"`
	ClientID          *int64            `json:"clientId"`
}

func NewService(cfg platform.Config, repo *Repository, fxService *fx.Service, logger *slog.Logger) *Service {
	return &Service{
		cfg:         cfg,
		repo:        repo,
		fx:          fxService,
		logger:      logger,
		alphaClient: clients.NewAlphaVantageClient(cfg.Stock.MarketDataBaseURL, cfg.Stock.AlphaVantageAPIKey, nil),
	}
}

func (s *Service) SetAlphaClient(client *clients.AlphaVantageClient) {
	s.alphaClient = client
}

func (s *Service) SetPriceAlertPublisher(publisher PriceAlertPublisher) {
	s.alertPublisher = publisher
}

func (s *Service) SetPriceHistoryStore(store PriceHistoryStore) {
	s.historyStore = store
}

func (s *Service) GetExchangeStatus(ctx context.Context, id int64) (*api.StockExchangeStatusResponse, error) {
	exchange, err := s.repo.GetStockExchange(ctx, id)
	if err != nil {
		return nil, err
	}
	location, err := time.LoadLocation(exchange.TimeZone)
	if err != nil {
		location = time.UTC
	}
	now := time.Now().In(location)
	holiday := false
	workingDay := now.Weekday() != time.Saturday && now.Weekday() != time.Sunday && !holiday
	naturalPhase := resolveMarketPhase(now, exchange, workingDay)
	bypassEnabled := !exchange.IsActive
	effectivePhase := naturalPhase
	if bypassEnabled {
		effectivePhase = MarketPhaseRegularMarket
	}
	return &api.StockExchangeStatusResponse{
		ID:                    exchange.ID,
		ExchangeName:          exchange.ExchangeName,
		ExchangeAcronym:       exchange.ExchangeAcronym,
		ExchangeMICCode:       exchange.ExchangeMICCode,
		Polity:                exchange.Polity,
		TimeZone:              exchange.TimeZone,
		LocalDate:             now.Format("2006-01-02"),
		LocalTime:             now.Format("15:04:05"),
		OpenTime:              exchange.OpenTime,
		CloseTime:             exchange.CloseTime,
		PreMarketOpenTime:     exchange.PreMarketOpenTime,
		PreMarketCloseTime:    exchange.PreMarketCloseTime,
		PostMarketOpenTime:    exchange.PostMarketOpenTime,
		PostMarketCloseTime:   exchange.PostMarketCloseTime,
		IsActive:              exchange.IsActive,
		WorkingDay:            workingDay,
		Holiday:               holiday,
		Open:                  bypassEnabled || effectivePhase != MarketPhaseClosed,
		RegularMarketOpen:     effectivePhase == MarketPhaseRegularMarket,
		TestModeBypassEnabled: bypassEnabled,
		MarketPhase:           string(effectivePhase),
	}, nil
}

func (s *Service) ListStockExchanges(ctx context.Context) ([]api.StockExchangeResponse, error) {
	items, err := s.repo.ListStockExchanges(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]api.StockExchangeResponse, 0, len(items))
	for _, item := range items {
		out = append(out, api.StockExchangeResponse{
			ID:                  item.ID,
			ExchangeName:        item.ExchangeName,
			ExchangeAcronym:     item.ExchangeAcronym,
			ExchangeMICCode:     item.ExchangeMICCode,
			Polity:              item.Polity,
			Currency:            item.Currency,
			TimeZone:            item.TimeZone,
			OpenTime:            item.OpenTime,
			CloseTime:           item.CloseTime,
			PreMarketOpenTime:   item.PreMarketOpenTime,
			PreMarketCloseTime:  item.PreMarketCloseTime,
			PostMarketOpenTime:  item.PostMarketOpenTime,
			PostMarketCloseTime: item.PostMarketCloseTime,
			IsActive:            item.IsActive,
		})
	}
	return out, nil
}

func (s *Service) ToggleStockExchangeActive(ctx context.Context, id int64) (*api.StockExchangeToggleResponse, error) {
	exchange, err := s.repo.GetStockExchange(ctx, id)
	if err != nil {
		return nil, err
	}
	exchange.IsActive = !exchange.IsActive
	_, err = s.repo.db.Exec(ctx, `update stock_exchange set is_active = $2 where id=$1`, id, exchange.IsActive)
	if err != nil {
		return nil, err
	}
	return &api.StockExchangeToggleResponse{
		ID:              exchange.ID,
		ExchangeName:    exchange.ExchangeName,
		ExchangeMICCode: exchange.ExchangeMICCode,
		IsActive:        exchange.IsActive,
	}, nil
}

func (s *Service) GetListingDetails(ctx context.Context, id int64, period string) (*api.ListingDetailsResponse, error) {
	row, err := s.repo.GetListingDetailsRow(ctx, id)
	if err != nil {
		return nil, err
	}
	history, err := s.getListingHistory(ctx, id, periodStart(time.Now(), period))
	if err != nil {
		return nil, err
	}
	priceHistory := make([]api.ListingDailyPriceInfoResponse, 0, len(history))
	for _, item := range history {
		changePercent := calculateChangePercentPtr(item.Price, item.Change)
		priceHistory = append(priceHistory, api.ListingDailyPriceInfoResponse{
			Date:          item.Date.Format("2006-01-02"),
			Price:         dec(item.Price),
			Ask:           dec(item.Ask),
			Bid:           dec(item.Bid),
			Change:        dec(item.Change),
			ChangePercent: changePercent,
			Volume:        item.Volume,
			DollarVolume:  calculateDollarVolume(item.Price, item.Volume),
		})
	}
	changePercent := calculateChangePercentPtr(row.Price, row.Change)
	dollarVolume := calculateDollarVolume(row.Price, row.Volume)
	resp := &api.ListingDetailsResponse{
		ListingID:       row.ID,
		SecurityID:      row.SecurityID,
		ListingType:     string(row.ListingType),
		Ticker:          row.Ticker,
		Name:            row.Name,
		StockExchangeID: row.StockExchangeID,
		ExchangeMICCode: row.ExchangeMICCode,
		ExchangeAcronym: row.ExchangeAcronym,
		ExchangeName:    row.ExchangeName,
		LastRefresh:     formatLocalDateTime(row.LastRefresh),
		Price:           dec(row.Price),
		Ask:             dec(row.Ask),
		Bid:             dec(row.Bid),
		Change:          dec(row.Change),
		ChangePercent:   changePercent,
		Volume:          row.Volume,
		DollarVolume:    dollarVolume,
		RequestedPeriod: period,
		PriceHistory:    priceHistory,
		OptionGroups:    []api.StockOptionSettlementGroupResponse{},
		Currency:        row.Currency,
	}
	switch row.ListingType {
	case ListingTypeStock:
		maintenance := calculateInitialMargin(row.ListingType, row.Price, int32ptr(1)).Div(decimal.RequireFromString("1.10"))
		cs := int32(1)
		resp.StockDetails = &api.ListingStockDetailsResponse{
			OutstandingShares: valueInt64(row.StockOutstanding),
			DividendYield:     decDefault(valueString(row.StockDividendYield)),
			ContractSize:      cs,
		}
		resp.ContractSize = &cs
		mm := maintenance
		resp.MaintenanceMargin = &mm
		imc := maintenance.Mul(decimal.RequireFromString("1.10"))
		resp.InitialMarginCost = imc
		options, err := s.repo.ListOptionsForStock(ctx, row.SecurityID)
		if err == nil {
			resp.OptionGroups = groupOptions(options, decimal.RequireFromString(row.Price))
		}
	case ListingTypeFutures:
		maintenance := calculateInitialMargin(row.ListingType, row.Price, row.FuturesContractSize).Div(decimal.RequireFromString("1.10"))
		resp.FuturesDetails = &api.ListingFuturesDetailsResponse{
			ContractSize:   valueInt32(row.FuturesContractSize),
			ContractUnit:   valueString(row.FuturesContractUnit),
			SettlementDate: formatDatePtr(row.FuturesSettlement),
		}
		resp.ContractSize = row.FuturesContractSize
		mm := maintenance
		resp.MaintenanceMargin = &mm
		imc := maintenance.Mul(decimal.RequireFromString("1.10"))
		resp.InitialMarginCost = imc
		if row.FuturesSettlement != nil {
			value := row.FuturesSettlement.Format("2006-01-02")
			resp.SettlementDate = &value
		}
	case ListingTypeForex:
		maintenance := calculateInitialMargin(row.ListingType, row.Price, int32ptr(1000)).Div(decimal.RequireFromString("1.10"))
		cs := int32(1000)
		resp.ForexDetails = &api.ListingForexDetailsResponse{
			BaseCurrency:  valueString(row.ForexBaseCurrency),
			QuoteCurrency: valueString(row.ForexQuoteCurrency),
			ExchangeRate:  decDefault(valueString(row.ForexExchangeRate)),
			Liquidity:     valueString(row.ForexLiquidity),
			ContractSize:  cs,
		}
		resp.ContractSize = &cs
		mm := maintenance
		resp.MaintenanceMargin = &mm
		imc := maintenance.Mul(decimal.RequireFromString("1.10"))
		resp.InitialMarginCost = imc
	case "OPTION":
		maintenance := decimal.NewFromInt(100).Mul(decimal.RequireFromString(row.Price)).Mul(decimal.RequireFromString("0.50"))
		cs := int32(100)
		resp.ContractSize = &cs
		mm := maintenance
		resp.MaintenanceMargin = &mm
		imc := maintenance.Mul(decimal.RequireFromString("1.10"))
		resp.InitialMarginCost = imc
		resp.OptionType = row.OptionType
		if row.OptionStrikePrice != nil {
			value := dec(*row.OptionStrikePrice)
			resp.StrikePrice = &value
		}
		if row.OptionSettlement != nil {
			value := row.OptionSettlement.Format("2006-01-02")
			resp.SettlementDate = &value
		}
		value := dec(row.Price)
		resp.UnderlyingPrice = &value
	default:
		resp.InitialMarginCost = decimal.Zero
	}
	return resp, nil
}

func (s *Service) ListListings(ctx context.Context, listingType ListingType, filter ListingFilter, page, size int, sortBy, sortDirection string) (*api.PageListingSummaryResponse, error) {
	items, total, err := s.repo.ListListingsByType(ctx, listingType, filter, page, size, sortBy, sortDirection)
	if err != nil {
		return nil, err
	}
	content := make([]api.ListingSummaryResponse, 0, len(items))
	for _, item := range items {
		initialMargin := calculateInitialMargin(item.ListingType, item.Price, nil)
		var settlementDate *string
		if item.ListingType == ListingTypeFutures && item.SettlementDate != nil {
			value := item.SettlementDate.Format("2006-01-02")
			settlementDate = &value
		}
		content = append(content, api.ListingSummaryResponse{
			ListingID:         item.ID,
			ListingType:       string(item.ListingType),
			Ticker:            item.Ticker,
			Name:              item.Name,
			ExchangeMICCode:   item.ExchangeMICCode,
			Currency:          item.Currency,
			Price:             dec(item.Price),
			Change:            dec(item.Change),
			Volume:            item.Volume,
			InitialMarginCost: initialMargin,
			SettlementDate:    settlementDate,
		})
	}
	return &api.PageListingSummaryResponse{
		TotalElements:    total,
		TotalPages:       pageCount(total, size),
		Pageable:         api.PageableObject{Paged: true, PageSize: size, Unpaged: false, PageNumber: page, Offset: page * size, Sort: api.PageableSort{Sorted: true}},
		NumberOfElements: len(content),
		First:            page == 0,
		Last:             int64((page+1)*size) >= total,
		Size:             size,
		Content:          content,
		Number:           page,
		Sort:             api.PageableSort{Sorted: true},
		Empty:            len(content) == 0,
	}, nil
}

func (s *Service) RefreshListing(ctx context.Context, listingID int64) (*api.ListingRefreshResponse, error) {
	listing, err := s.repo.GetListing(ctx, listingID)
	if err != nil {
		return nil, err
	}
	refreshTime := time.Now().UTC()
	var snapshotDate time.Time
	switch listing.ListingType {
	case ListingTypeStock:
		quote, err := s.alphaClient.FetchQuote(ctx, listing.Ticker)
		if err != nil || quote == nil {
			return nil, fmt.Errorf("stock refresh failed")
		}
		snapshotDate = quote.LatestTradingDay
		if snapshotDate.IsZero() {
			snapshotDate = refreshTime
		}
		if err := s.repo.UpdateListingSnapshot(ctx, listing.ID, listing.Ticker, quote.Price.StringFixed(8), quote.Ask.StringFixed(8), quote.Bid.StringFixed(8), quote.Change.StringFixed(8), quote.Volume, refreshTime); err != nil {
			return nil, err
		}
		if err := s.saveDailySnapshot(ctx, *listing, snapshotDate, quote.Price.StringFixed(8), quote.Ask.StringFixed(8), quote.Bid.StringFixed(8), quote.Change.StringFixed(8), quote.Volume); err != nil {
			return nil, err
		}
	case ListingTypeForex:
		parts := strings.Split(strings.ToUpper(strings.TrimSpace(listing.Ticker)), "/")
		if len(parts) != 2 {
			return nil, fmt.Errorf("FX listing ticker must use BASE/QUOTE format")
		}
		rate, err := s.alphaClient.FetchExchangeRate(ctx, parts[0], parts[1])
		if err != nil || rate == nil {
			return nil, fmt.Errorf("forex refresh failed")
		}
		snapshotDate = rate.LastRefreshed
		if snapshotDate.IsZero() {
			snapshotDate = refreshTime
		}
		previous := decimal.RequireFromString(listing.Price)
		change := decimal.Zero
		if !previous.IsZero() {
			change = rate.ExchangeRate.Sub(previous)
		}
		if err := s.repo.UpdateForexPairRate(ctx, listing.SecurityID, rate.ExchangeRate.StringFixed(8)); err != nil {
			return nil, err
		}
		if err := s.repo.UpdateListingSnapshot(ctx, listing.ID, listing.Ticker, rate.ExchangeRate.StringFixed(8), rate.ExchangeRate.StringFixed(8), rate.ExchangeRate.StringFixed(8), change.StringFixed(8), 1000, refreshTime); err != nil {
			return nil, err
		}
		if err := s.saveDailySnapshot(ctx, *listing, snapshotDate, rate.ExchangeRate.StringFixed(8), rate.ExchangeRate.StringFixed(8), rate.ExchangeRate.StringFixed(8), change.StringFixed(8), 1000); err != nil {
			return nil, err
		}
	default:
		return nil, fmt.Errorf("Futures market data refresh is not supported — futures use static seed data")
	}
	return &api.ListingRefreshResponse{
		ListingID:         listing.ID,
		Ticker:            listing.Ticker,
		ListingType:       string(listing.ListingType),
		DailySnapshotDate: time.Date(snapshotDate.Year(), snapshotDate.Month(), snapshotDate.Day(), 0, 0, 0, 0, time.UTC).Format("2006-01-02"),
		LastRefresh:       formatLocalDateTime(refreshTime),
	}, nil
}

func (s *Service) RefreshStockByTicker(ctx context.Context, ticker string) (*api.StockMarketDataRefreshResponse, error) {
	stock, err := s.repo.GetStockByTicker(ctx, strings.ToUpper(strings.TrimSpace(ticker)))
	if err != nil {
		return nil, err
	}
	quote, err := s.alphaClient.FetchQuote(ctx, stock.Ticker)
	if err != nil || quote == nil {
		return nil, fmt.Errorf("quote fetch failed")
	}
	daily, err := s.alphaClient.FetchDaily(ctx, stock.Ticker)
	if err != nil {
		return nil, err
	}
	overview, err := s.alphaClient.FetchCompanyOverview(ctx, stock.Ticker)
	if err != nil {
		return nil, err
	}
	refreshTime := time.Now().UTC()
	if overview != nil {
		name := overview.Name
		if name == "" {
			name = stock.Name
		}
		if err := s.repo.UpdateStockFundamentals(ctx, stock.ID, name, maxInt64(overview.SharesOutstanding, stock.OutstandingShares), nonEmptyDecimal(overview.DividendYield.String(), stock.DividendYield)); err != nil {
			return nil, err
		}
	}
	if err := s.repo.UpdateListingSnapshot(ctx, stock.ListingID, stock.Ticker, quote.Price.StringFixed(8), quote.Ask.StringFixed(8), quote.Bid.StringFixed(8), quote.Change.StringFixed(8), quote.Volume, refreshTime); err != nil {
		return nil, err
	}
	listing, err := s.repo.GetListing(ctx, stock.ListingID)
	if err != nil {
		return nil, err
	}
	count := 0
	sort.Slice(daily, func(i, j int) bool { return daily[i].Date.After(daily[j].Date) })
	if len(daily) > 30 {
		daily = daily[:30]
	}
	for idx, item := range daily {
		change := decimal.Zero
		if idx+1 < len(daily) {
			change = item.ClosePrice.Sub(daily[idx+1].ClosePrice)
		}
		ask := item.ClosePrice
		bid := item.ClosePrice
		volume := item.Volume
		if sameDay(item.Date, quote.LatestTradingDay) {
			ask = quote.Ask
			bid = quote.Bid
			volume = quote.Volume
			change = quote.Change
		}
		if err := s.saveDailySnapshot(ctx, *listing, item.Date, item.ClosePrice.StringFixed(8), ask.StringFixed(8), bid.StringFixed(8), change.StringFixed(8), volume); err != nil {
			return nil, err
		}
		count++
	}
	return &api.StockMarketDataRefreshResponse{
		Ticker:                stock.Ticker,
		StockID:               stock.ID,
		ListingID:             stock.ListingID,
		RefreshedDailyEntries: count,
		LastRefresh:           formatLocalDateTime(refreshTime),
	}, nil
}

func (s *Service) getListingHistory(ctx context.Context, listingID int64, from time.Time) ([]DailyPriceInfo, error) {
	if s.historyStore != nil {
		history, err := s.historyStore.FindDailySnapshots(ctx, listingID, from)
		if err == nil && len(history) > 0 {
			return history, nil
		}
		if err != nil && s.logger != nil {
			s.logger.Warn("influx price history read failed", "listingId", listingID, "error", err.Error())
		}
	}
	return s.repo.GetListingHistory(ctx, listingID, from)
}

func (s *Service) saveDailySnapshot(ctx context.Context, listing Listing, day time.Time, price, ask, bid, change string, volume int64) error {
	if err := s.repo.UpsertDailySnapshot(ctx, listing.ID, day, price, ask, bid, change, volume); err != nil {
		return err
	}
	if s.historyStore == nil {
		return nil
	}
	point := ListingPricePoint{
		ListingID:       listing.ID,
		Ticker:          listing.Ticker,
		ListingType:     listing.ListingType,
		ExchangeMICCode: listing.ExchangeMICCode,
		Date:            day,
		Price:           price,
		Ask:             ask,
		Bid:             bid,
		Change:          change,
		Volume:          volume,
	}
	if err := s.historyStore.SaveDailySnapshots(ctx, []ListingPricePoint{point}); err != nil && s.logger != nil {
		s.logger.Warn("influx price history write failed", "listingId", listing.ID, "ticker", listing.Ticker, "error", err.Error())
	}
	return nil
}

func resolveMarketPhase(now time.Time, exchange *StockExchange, workingDay bool) MarketPhase {
	if !workingDay {
		return MarketPhaseClosed
	}
	if isWithinWindow(now, strptr(exchange.PreMarketOpenTime), strptr(exchange.PreMarketCloseTime)) {
		return MarketPhasePreMarket
	}
	if isWithinWindow(now, &exchange.OpenTime, &exchange.CloseTime) {
		return MarketPhaseRegularMarket
	}
	if isWithinWindow(now, strptr(exchange.PostMarketOpenTime), strptr(exchange.PostMarketCloseTime)) {
		return MarketPhasePostMarket
	}
	return MarketPhaseClosed
}

func isWithinWindow(now time.Time, fromText, toText *string) bool {
	if fromText == nil || toText == nil {
		return false
	}
	from, err := time.Parse("15:04:05", normalizeClock(*fromText))
	if err != nil {
		return false
	}
	to, err := time.Parse("15:04:05", normalizeClock(*toText))
	if err != nil {
		return false
	}
	if from.Equal(to) {
		return false
	}
	current := now.Hour()*3600 + now.Minute()*60 + now.Second()
	start := from.Hour()*3600 + from.Minute()*60 + from.Second()
	end := to.Hour()*3600 + to.Minute()*60 + to.Second()
	if end > start {
		return current >= start && current < end
	}
	return current >= start || current < end
}

func normalizeClock(value string) string {
	if len(value) == 5 {
		return value + ":00"
	}
	return value
}

func periodStart(now time.Time, period string) time.Time {
	switch period {
	case "WEEK":
		return now.AddDate(0, 0, -7)
	case "MONTH":
		return now.AddDate(0, -1, 0)
	case "YEAR":
		return now.AddDate(-1, 0, 0)
	case "FIVE_YEARS":
		return now.AddDate(-5, 0, 0)
	case "ALL":
		return time.Date(1970, 1, 1, 0, 0, 0, 0, time.UTC)
	default:
		return now.AddDate(0, 0, -1)
	}
}

func calculateChangePercentPtr(priceText, changeText string) *decimal.Decimal {
	value, ok := calculateChangePercent(priceText, changeText)
	if !ok {
		return nil
	}
	return &value
}

func calculateChangePercent(priceText, changeText string) (decimal.Decimal, bool) {
	price := decimal.RequireFromString(priceText)
	change := decimal.RequireFromString(changeText)
	previous := price.Sub(change)
	if previous.IsZero() {
		return decimal.Zero, false
	}
	value := change.Mul(decimal.NewFromInt(100)).DivRound(previous, 4)
	return value, true
}

func calculateDollarVolume(priceText string, volume int64) decimal.Decimal {
	return decimal.RequireFromString(priceText).Mul(decimal.NewFromInt(volume))
}

func calculateInitialMargin(listingType ListingType, priceText string, contractSize *int32) decimal.Decimal {
	price := decimal.RequireFromString(priceText)
	switch listingType {
	case ListingTypeFutures:
		size := int32(1)
		if contractSize != nil {
			size = *contractSize
		}
		return decimal.NewFromInt32(size).Mul(price).Mul(decimal.RequireFromString("0.10")).Mul(decimal.RequireFromString("1.10"))
	case ListingTypeForex:
		return decimal.NewFromInt(1000).Mul(price).Mul(decimal.RequireFromString("0.10")).Mul(decimal.RequireFromString("1.10"))
	default:
		return price.Mul(decimal.RequireFromString("0.50")).Mul(decimal.RequireFromString("1.10"))
	}
}

func groupOptions(options []OptionRow, stockPrice decimal.Decimal) []api.StockOptionSettlementGroupResponse {
	grouped := map[string]*api.StockOptionSettlementGroupResponse{}
	order := make([]string, 0)
	for _, option := range options {
		day := option.SettlementDate.Format("2006-01-02")
		group, ok := grouped[day]
		if !ok {
			group = &api.StockOptionSettlementGroupResponse{SettlementDate: day}
			grouped[day] = group
			order = append(order, day)
		}
		strike := decimal.RequireFromString(option.StrikePrice)
		inMoney := false
		switch option.OptionType {
		case "CALL":
			inMoney = stockPrice.GreaterThan(strike)
		case "PUT":
			inMoney = stockPrice.LessThan(strike)
		}
		item := api.StockOptionDetailsResponse{
			ID:                option.ID,
			Ticker:            option.Ticker,
			OptionType:        option.OptionType,
			StrikePrice:       dec(option.StrikePrice),
			ImpliedVolatility: dec(option.ImpliedVolatility),
			OpenInterest:      option.OpenInterest,
			Last:              dec(option.LastPrice),
			Bid:               dec(option.Bid),
			Ask:               dec(option.Ask),
			Volume:            option.Volume,
			InTheMoney:        inMoney,
		}
		if option.OptionType == "CALL" {
			group.Calls = append(group.Calls, item)
		} else {
			group.Puts = append(group.Puts, item)
		}
	}
	out := make([]api.StockOptionSettlementGroupResponse, 0, len(order))
	for _, day := range order {
		out = append(out, *grouped[day])
	}
	return out
}

func int32ptr(v int32) *int32 { return &v }
func valueInt64(v *int64) int64 {
	if v == nil {
		return 0
	}
	return *v
}
func valueInt32(v *int32) int32 {
	if v == nil {
		return 0
	}
	return *v
}
func valueString(v *string) string {
	if v == nil {
		return ""
	}
	return *v
}
func formatDatePtr(v *time.Time) string {
	if v == nil {
		return ""
	}
	return v.Format("2006-01-02")
}
func strptr(v *string) *string { return v }
func maxInt64(v1, v2 int64) int64 {
	if v1 == 0 {
		return v2
	}
	return v1
}
func nonEmptyDecimal(v1, fallback string) string {
	if v1 == "" || v1 == "0" {
		return fallback
	}
	return v1
}
func sameDay(a, b time.Time) bool {
	return a.Year() == b.Year() && a.Month() == b.Month() && a.Day() == b.Day()
}

func pageCount(total int64, size int) int {
	if size <= 0 {
		return 0
	}
	pages := total / int64(size)
	if total%int64(size) != 0 {
		pages++
	}
	return int(pages)
}

func evaluatePhase(now time.Time, exchange *StockExchange) (bool, bool, MarketPhase) {
	location, err := time.LoadLocation(exchange.TimeZone)
	if err != nil {
		location = time.UTC
	}
	localNow := now.In(location)
	workingDay := localNow.Weekday() != time.Saturday && localNow.Weekday() != time.Sunday
	phase := resolveMarketPhase(localNow, exchange, workingDay)
	if !exchange.IsActive {
		return true, false, MarketPhaseRegularMarket
	}
	open := phase != MarketPhaseClosed
	afterHours := phase == MarketPhasePreMarket || phase == MarketPhasePostMarket
	return open, afterHours, phase
}

func dec(value string) decimal.Decimal {
	return decimal.RequireFromString(value)
}

func decDefault(value string) decimal.Decimal {
	if value == "" {
		return decimal.Zero
	}
	return decimal.RequireFromString(value)
}

func formatLocalDateTime(value time.Time) string {
	return value.UTC().Format("2006-01-02T15:04:05")
}

func (s *Service) ListPriceAlerts(ctx context.Context, userID int64) ([]PriceAlert, error) {
	return s.repo.ListPriceAlertsByUser(ctx, userID)
}

func (s *Service) CreatePriceAlert(ctx context.Context, userID int64, recipientType, userEmail, username string, listingID int64, condition PriceAlertCondition, threshold, notificationType string) (*PriceAlert, error) {
	if listingID <= 0 || !validAlertCondition(condition) {
		return nil, ErrBadRequest
	}
	thresholdDec, err := decimal.NewFromString(strings.TrimSpace(threshold))
	if err != nil || !thresholdDec.GreaterThan(decimal.Zero) {
		return nil, ErrBadRequest
	}
	notificationType, err = normalizeNotificationType(notificationType)
	if err != nil {
		return nil, err
	}
	exists, err := s.repo.ListingExists(ctx, listingID)
	if err != nil {
		return nil, err
	}
	if !exists {
		return nil, ErrNotFound
	}
	return s.repo.CreatePriceAlert(ctx, PriceAlert{
		UserID:           userID,
		RecipientType:    recipientType,
		ListingID:        listingID,
		Condition:        condition,
		Threshold:        thresholdDec.StringFixed(4),
		NotificationType: notificationType,
		UserEmail:        optionalString(userEmail),
		Username:         optionalString(username),
		Active:           true,
		CreatedAt:        time.Now().UTC(),
	})
}

func (s *Service) TogglePriceAlert(ctx context.Context, userID, alertID int64) (*PriceAlert, error) {
	alert, err := s.repo.ToggleOwnedPriceAlert(ctx, userID, alertID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	return alert, err
}

func (s *Service) DeletePriceAlert(ctx context.Context, userID, alertID int64) error {
	deleted, err := s.repo.DeleteOwnedPriceAlert(ctx, userID, alertID)
	if err != nil {
		return err
	}
	if !deleted {
		return ErrNotFound
	}
	return nil
}

func (s *Service) EvaluateActivePriceAlerts(ctx context.Context) (int, error) {
	alerts, err := s.repo.ListActivePriceAlerts(ctx)
	if err != nil {
		return 0, err
	}
	fired := 0
	for _, alert := range alerts {
		listing, err := s.repo.GetListing(ctx, alert.ListingID)
		if err != nil {
			continue
		}
		satisfied := alertSatisfied(alert, *listing)
		if !satisfied {
			if alert.LastTriggeredAt != nil {
				_ = s.repo.SetPriceAlertLastTriggered(ctx, alert.ID, nil)
			}
			continue
		}
		if alert.LastTriggeredAt != nil {
			continue
		}
		now := time.Now().UTC()
		if err := s.repo.SetPriceAlertLastTriggered(ctx, alert.ID, &now); err != nil {
			continue
		}
		if s.alertPublisher != nil {
			if err := s.alertPublisher.PublishPriceAlertTriggered(ctx, priceAlertPayload(alert, *listing)); err != nil && s.logger != nil {
				s.logger.Warn("price alert publish failed", "alertId", alert.ID, "error", err.Error())
			}
		}
		fired++
	}
	return fired, nil
}

func (s *Service) ListWatchlists(ctx context.Context, userID int64) ([]Watchlist, error) {
	return s.repo.ListWatchlistsByUser(ctx, userID)
}

func (s *Service) CreateWatchlist(ctx context.Context, userID int64, name string) (*Watchlist, error) {
	name = strings.TrimSpace(name)
	if name == "" || len(name) > 128 {
		return nil, ErrBadRequest
	}
	item, err := s.repo.CreateWatchlist(ctx, userID, name, time.Now().UTC())
	if item != nil {
		item.ItemCount = 0
	}
	return item, err
}

func (s *Service) DeleteWatchlist(ctx context.Context, userID, watchlistID int64) error {
	deleted, err := s.repo.DeleteOwnedWatchlist(ctx, userID, watchlistID)
	if err != nil {
		return err
	}
	if !deleted {
		return ErrNotFound
	}
	return nil
}

func (s *Service) ListWatchlistItems(ctx context.Context, userID, watchlistID int64, filter *ListingType) ([]WatchlistItem, error) {
	exists, err := s.repo.OwnedWatchlistExists(ctx, userID, watchlistID)
	if err != nil {
		return nil, err
	}
	if !exists {
		return nil, ErrNotFound
	}
	items, err := s.repo.ListWatchlistItems(ctx, watchlistID)
	if err != nil {
		return nil, err
	}
	if filter == nil {
		return items, nil
	}
	out := make([]WatchlistItem, 0, len(items))
	for _, item := range items {
		if item.Listing.ListingType == *filter {
			out = append(out, item)
		}
	}
	return out, nil
}

func (s *Service) AddWatchlistItem(ctx context.Context, userID, watchlistID, listingID int64) (*WatchlistItem, error) {
	exists, err := s.repo.OwnedWatchlistExists(ctx, userID, watchlistID)
	if err != nil {
		return nil, err
	}
	if !exists {
		return nil, ErrNotFound
	}
	listingExists, err := s.repo.ListingExists(ctx, listingID)
	if err != nil {
		return nil, err
	}
	if !listingExists {
		return nil, ErrNotFound
	}
	item, err := s.repo.CreateWatchlistItem(ctx, watchlistID, listingID, time.Now().UTC())
	if IsUniqueViolation(err) {
		return nil, ErrConflict
	}
	return item, err
}

func (s *Service) RemoveWatchlistItem(ctx context.Context, userID, watchlistID, itemID int64) error {
	exists, err := s.repo.OwnedWatchlistExists(ctx, userID, watchlistID)
	if err != nil {
		return err
	}
	if !exists {
		return ErrNotFound
	}
	deleted, err := s.repo.DeleteOwnedWatchlistItem(ctx, watchlistID, itemID)
	if err != nil {
		return err
	}
	if !deleted {
		return ErrNotFound
	}
	return nil
}

func (s *Service) ListDividendData(ctx context.Context) ([]DividendData, error) {
	return s.repo.ListDividendData(ctx)
}

func validAlertCondition(condition PriceAlertCondition) bool {
	return condition == PriceAlertAbove || condition == PriceAlertBelow || condition == PriceAlertPctDropIntraday
}

func normalizeNotificationType(value string) (string, error) {
	normalized := strings.ToUpper(strings.TrimSpace(value))
	switch normalized {
	case "EMAIL", "PUSH", "IN_APP", "ALL":
		return normalized, nil
	default:
		return "", ErrBadRequest
	}
}

func alertSatisfied(alert PriceAlert, listing Listing) bool {
	price := decimal.RequireFromString(listing.Price)
	threshold := decimal.RequireFromString(alert.Threshold)
	switch alert.Condition {
	case PriceAlertAbove:
		return price.GreaterThanOrEqual(threshold)
	case PriceAlertBelow:
		return price.LessThanOrEqual(threshold)
	case PriceAlertPctDropIntraday:
		changePercent, ok := calculateChangePercent(listing.Price, listing.Change)
		return ok && changePercent.LessThanOrEqual(threshold.Neg())
	default:
		return false
	}
}

func priceAlertPayload(alert PriceAlert, listing Listing) PriceAlertNotificationPayload {
	var clientID *int64
	if strings.EqualFold(alert.RecipientType, "CLIENT") {
		id := alert.UserID
		clientID = &id
	}
	username := "korisnice"
	if alert.Username != nil && strings.TrimSpace(*alert.Username) != "" {
		username = strings.TrimSpace(*alert.Username)
	}
	return PriceAlertNotificationPayload{
		Username:  username,
		UserEmail: alert.UserEmail,
		TemplateVariables: map[string]string{
			"name":           username,
			"ticker":         listing.Ticker,
			"price":          listing.Price,
			"triggeredPrice": listing.Price,
			"threshold":      alert.Threshold,
			"condition":      string(alert.Condition),
		},
		ClientID: clientID,
	}
}

func optionalString(value string) *string {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return nil
	}
	return &trimmed
}
