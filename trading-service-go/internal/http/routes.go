package http

import (
	"net/http"

	"banka1/trading-service-go/internal/api"

	gpauth "banka1/go-platform/auth"
)

// registerRoutes registers the per-phase domain routes. Each protected route is
// wrapped in JWT auth + role checks mirroring the Java @PreAuthorize rules.
func registerRoutes(handle func(method, path string, handler http.Handler), app *App, jwtService *gpauth.Service) {
	handlers := NewHandlers(app)

	// Analytics — class-level @PreAuthorize hasAnyRole('ADMIN','SUPERVISOR','AGENT','SERVICE').
	// (analytics is not in SPECIFIKACIJA.md; this matches the Java controller.)
	analyticsRoles := gpauth.RequireRoles("ADMIN", "SUPERVISOR", "AGENT", "SERVICE")
	analytics := func(h http.HandlerFunc) http.Handler {
		return jwtService.Middleware(analyticsRoles(h))
	}
	handle(http.MethodGet, "/analytics/runs/latest", analytics(handlers.AnalyticsLatestRun))
	handle(http.MethodGet, "/analytics/clients/segments", analytics(handlers.AnalyticsClientSegments))
	handle(http.MethodGet, "/analytics/users/{userId}/portfolio-risk", analytics(handlers.AnalyticsPortfolioRisk))
	handle(http.MethodGet, "/analytics/tickers/top", analytics(handlers.AnalyticsTopTickers))

	// Portfolio (order-service module). getPortfolio + set-public:
	// CLIENT_BASIC/CLIENT_TRADING/AGENT/SUPERVISOR; exercise-option: AGENT/SUPERVISOR.
	// Errors use the order-service ApiErrorResponse / OTC shapes via orderSecured + writeDomainError.
	portfolioRoles := []string{"CLIENT_BASIC", "CLIENT_TRADING", "AGENT", "SUPERVISOR"}
	handle(http.MethodGet, "/portfolio", orderSecured(jwtService, portfolioRoles, handlers.PortfolioSummary))
	handle(http.MethodPut, "/portfolio/{id}/set-public", orderSecured(jwtService, portfolioRoles, handlers.PortfolioSetPublic))
	handle(http.MethodPost, "/portfolio/{id}/exercise-option", orderSecured(jwtService, []string{"AGENT", "SUPERVISOR"}, handlers.PortfolioExerciseOption))

	// Actuary (order-service module). All endpoints require SUPERVISOR (ADMIN via hierarchy).
	supervisor := []string{"SUPERVISOR"}
	handle(http.MethodGet, "/actuaries/agents", orderSecured(jwtService, supervisor, handlers.ActuaryAgents))
	handle(http.MethodPut, "/actuaries/agents/{id}/limit", orderSecured(jwtService, supervisor, handlers.ActuarySetLimit))
	handle(http.MethodPut, "/actuaries/agents/{id}/reset-limit", orderSecured(jwtService, supervisor, handlers.ActuaryResetLimit))
	handle(http.MethodPut, "/actuaries/agents/{id}/need-approval", orderSecured(jwtService, supervisor, handlers.ActuaryNeedApproval))
	handle(http.MethodGet, "/actuaries/profit", orderSecured(jwtService, supervisor, handlers.ActuaryProfit))
	handle(http.MethodGet, "/actuaries/profit/bank-summary", orderSecured(jwtService, supervisor, handlers.ActuaryBankSummary))

	// Order (order-service module). @PreAuthorize per OrderController:
	//  - buy/sell/confirm/cancel(POST): CLIENT_TRADING/AGENT/SUPERVISOR
	//  - GET /orders, PUT cancel/approve/decline: SUPERVISOR
	//  - GET /orders/my-orders: CLIENT_BASIC/CLIENT_TRADING/CLIENT/AGENT/SUPERVISOR
	trader := []string{"CLIENT_TRADING", "AGENT", "SUPERVISOR"}
	myOrderRoles := []string{"CLIENT_BASIC", "CLIENT_TRADING", "CLIENT", "AGENT", "SUPERVISOR"}
	authenticated := []string{"CLIENT_BASIC", "CLIENT_TRADING", "CLIENT", "AGENT", "SUPERVISOR", "ADMIN", "SERVICE"}
	handle(http.MethodPost, "/orders/buy", orderSecured(jwtService, trader, handlers.OrderBuy))
	handle(http.MethodPost, "/orders/sell", orderSecured(jwtService, trader, handlers.OrderSell))
	handle(http.MethodGet, "/orders", orderSecured(jwtService, supervisor, handlers.OrderList))
	handle(http.MethodGet, "/orders/my-orders", orderSecured(jwtService, myOrderRoles, handlers.OrderMyOrders))
	handle(http.MethodGet, "/orders/my-orders/paged", orderSecured(jwtService, myOrderRoles, handlers.OrderMyOrdersPaged))
	handle(http.MethodPost, "/orders/{id}/confirm", orderSecured(jwtService, trader, handlers.OrderConfirm))
	handle(http.MethodPost, "/orders/{id}/cancel", orderSecured(jwtService, trader, handlers.OrderCancel))
	handle(http.MethodPut, "/orders/{id}/cancel", orderSecured(jwtService, supervisor, handlers.OrderCancelSupervisor))
	handle(http.MethodPut, "/orders/{id}/approve", orderSecured(jwtService, supervisor, handlers.OrderApprove))
	handle(http.MethodPut, "/orders/{id}/decline", orderSecured(jwtService, supervisor, handlers.OrderDecline))

	// Recurring (standing) orders — Celina 3.6, mirrors RecurringOrderController
	// (@RequestMapping("/recurring-orders"), hasAnyRole CLIENT_TRADING/AGENT/
	// SUPERVISOR; PATCH pause/resume, DELETE cancel → 204).
	handle(http.MethodGet, "/recurring-orders", orderSecured(jwtService, trader, handlers.RecurringOrdersList))
	handle(http.MethodPost, "/recurring-orders", orderSecured(jwtService, trader, handlers.RecurringOrderCreate))
	handle(http.MethodPatch, "/recurring-orders/{id}/pause", orderSecured(jwtService, trader, handlers.RecurringOrderPause))
	handle(http.MethodPatch, "/recurring-orders/{id}/resume", orderSecured(jwtService, trader, handlers.RecurringOrderResume))
	handle(http.MethodDelete, "/recurring-orders/{id}", orderSecured(jwtService, trader, handlers.RecurringOrderDelete))
	handle(http.MethodPost, "/internal/recurring-orders/run-due", orderSecured(jwtService, []string{"SERVICE"}, handlers.RecurringOrdersRunDueInternal))

	// Tax (order-service module). Per TaxController @PreAuthorize:
	//  - POST /tax/collect, /tax/collect/current-month: SUPERVISOR
	//  - POST /internal/tax/capital-gains/run: SERVICE (inter-service trigger)
	//  - GET /tax/capital-gains/debts, /tax/capital-gains/{userId}, /tax/tracking: SUPERVISOR
	// The literal /tax/capital-gains/debts outranks the {userId} wildcard in ServeMux.
	// Tracking/debt reads can return 409 (OTC error shape) when strict FX fails.
	handle(http.MethodPost, "/tax/collect", orderSecured(jwtService, supervisor, handlers.TaxCollect))
	handle(http.MethodPost, "/tax/collect/current-month", orderSecured(jwtService, supervisor, handlers.TaxCollectCurrentMonth))
	handle(http.MethodPost, "/internal/tax/capital-gains/run", orderSecured(jwtService, []string{"SERVICE"}, handlers.TaxRunInternal))
	handle(http.MethodGet, "/tax/capital-gains/debts", orderSecured(jwtService, supervisor, handlers.TaxDebts))
	handle(http.MethodGet, "/tax/capital-gains/{userId}", orderSecured(jwtService, supervisor, handlers.TaxUserDebt))
	handle(http.MethodGet, "/tax/tracking", orderSecured(jwtService, supervisor, handlers.TaxTracking))

	// C3 TODO additions: watchlist, price alerts, audit log, and dividend history.
	handle(http.MethodGet, "/watchlist", orderSecured(jwtService, authenticated, handlers.Watchlists))
	handle(http.MethodPost, "/watchlist", orderSecured(jwtService, authenticated, handlers.Watchlists))
	handle(http.MethodDelete, "/watchlist/items/{id}", orderSecured(jwtService, authenticated, handlers.WatchlistItemDelete))
	handle(http.MethodGet, "/alerts", orderSecured(jwtService, authenticated, handlers.PriceAlerts))
	handle(http.MethodPost, "/alerts", orderSecured(jwtService, authenticated, handlers.PriceAlerts))
	// Audit log (WP-2 / Issue 9) — GET /audit mirrors AuditLogController
	// (ADMIN/SUPERVISOR, filters + Page envelope); GET /audit-log keeps the
	// legacy flat view the existing frontend audit page consumes (actorName /
	// targetName enrichment), now derived from the reshaped schema.
	handle(http.MethodGet, "/audit", orderSecured(jwtService, []string{"ADMIN", "SUPERVISOR"}, handlers.AuditLog))
	handle(http.MethodGet, "/audit-log", orderSecured(jwtService, []string{"ADMIN", "SUPERVISOR"}, handlers.AuditLogLegacy))

	// Dividend payouts (WP-14 Celina 3.7) — mirrors DividendController: GET has
	// no @PreAuthorize (any authenticated user, scoped to the JWT id claim);
	// the manual trigger run is ADMIN-only.
	handle(http.MethodGet, "/dividends", jwtService.Middleware(http.HandlerFunc(handlers.MyDividends)))
	handle(http.MethodPost, "/dividends/trigger", orderSecured(jwtService, []string{"ADMIN"}, handlers.DividendTrigger))

	// Funds (trading-service module). Per InvestmentFundController:
	//  - Discovery / details / analytics / securities / performance: any
	//    authenticated user (no @PreAuthorize on the GETs).
	//  - Supervisor mgmt (create / supervised / bank-invest / bank-redeem /
	//    bank-positions / positions / sell / transactions / dividends):
	//    permission FUND_AGENT_MANAGE (also SERVICE on dividends).
	//  - Client trading (invest / redeem / my-positions / my-transactions):
	//    role CLIENT_TRADING.
	//  - admin reassign-manager: role SERVICE.
	// Errors throw IllegalArgument/IllegalStateException → OTC 404/409 shape
	// via writeDomainError (OtcExceptionHandler is HIGHEST_PRECEDENCE in the
	// consolidated JVM).
	fundManage := func(h http.HandlerFunc) http.Handler {
		return jwtService.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			principal, ok := gpauth.PrincipalFromContext(r.Context())
			if !ok {
				writeDomainError(w, r, api.NewOrderError(http.StatusUnauthorized, "Unauthorized"))
				return
			}
			// FUND_AGENT_MANAGE is a permission, not a role. ADMIN/SUPERVISOR
			// implicitly hold it via role hierarchy (matches Java grants).
			if !principal.HasAnyPermission("FUND_AGENT_MANAGE") && !principal.HasAnyRole("ADMIN", "SUPERVISOR") {
				writeDomainError(w, r, api.NewOrderError(http.StatusForbidden, "Access denied"))
				return
			}
			h(w, r)
		}))
	}
	fundManageOrService := func(h http.HandlerFunc) http.Handler {
		return jwtService.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			principal, ok := gpauth.PrincipalFromContext(r.Context())
			if !ok {
				writeDomainError(w, r, api.NewOrderError(http.StatusUnauthorized, "Unauthorized"))
				return
			}
			if !principal.HasAnyPermission("FUND_AGENT_MANAGE") && !principal.HasAnyRole("SERVICE", "ADMIN", "SUPERVISOR") {
				writeDomainError(w, r, api.NewOrderError(http.StatusForbidden, "Access denied"))
				return
			}
			h(w, r)
		}))
	}
	clientTrader := []string{"CLIENT_TRADING"}
	serviceOnly := []string{"SERVICE"}

	// discovery / details / analytics / securities / performance (authenticated)
	handle(http.MethodGet, "/funds", orderSecured(jwtService, authenticated, handlers.FundsDiscovery))
	// literal /funds/supervised, /funds/my-positions, /funds/my-transactions,
	// /funds/bank-positions must come BEFORE the /funds/{id} routes — but Go
	// 1.22 ServeMux scores the literal segment higher than the wildcard, so
	// the registration order does not matter. (Same trick as /tax/capital-
	// gains/debts vs /tax/capital-gains/{userId}.)
	handle(http.MethodGet, "/funds/supervised", fundManage(handlers.FundsSupervised))
	handle(http.MethodGet, "/funds/my-positions", orderSecured(jwtService, clientTrader, handlers.FundsMyPositions))
	handle(http.MethodGet, "/funds/my-transactions", orderSecured(jwtService, clientTrader, handlers.FundsMyTransactions))
	handle(http.MethodGet, "/funds/bank-positions", fundManage(handlers.FundsBankPositions))
	handle(http.MethodGet, "/funds/{id}", orderSecured(jwtService, authenticated, handlers.FundsDetails))
	handle(http.MethodGet, "/funds/{id}/analytics", orderSecured(jwtService, authenticated, handlers.FundsAnalytics))
	handle(http.MethodGet, "/funds/{id}/securities", orderSecured(jwtService, authenticated, handlers.FundsSecurities))
	handle(http.MethodGet, "/funds/{id}/performance", orderSecured(jwtService, authenticated, handlers.FundsPerformance))
	handle(http.MethodGet, "/funds/{id}/positions", fundManage(handlers.FundsPositions))
	handle(http.MethodGet, "/funds/{id}/transactions", fundManage(handlers.FundsTransactions))

	// supervisor + admin
	handle(http.MethodPost, "/funds", fundManage(handlers.FundsCreate))
	handle(http.MethodPost, "/funds/{id}/bank-invest", fundManage(handlers.FundsBankInvest))
	handle(http.MethodPost, "/funds/{id}/bank-redeem", fundManage(handlers.FundsBankRedeem))
	handle(http.MethodPost, "/funds/{id}/securities/{ticker}/sell", fundManage(handlers.FundsSellSecurity))
	handle(http.MethodPost, "/funds/{id}/dividends", fundManageOrService(handlers.FundsRecordDividend))
	handle(http.MethodPatch, "/funds/admin/reassign-manager", orderSecured(jwtService, serviceOnly, handlers.FundsReassignManager))

	// client trading
	handle(http.MethodPost, "/funds/{id}/invest", orderSecured(jwtService, clientTrader, handlers.FundsInvest))
	handle(http.MethodPost, "/funds/{id}/redeem", orderSecured(jwtService, clientTrader, handlers.FundsRedeem))

	// /funds/internal — SERVICE only (inter-service: saga + order BUY callbacks)
	handle(http.MethodPost, "/funds/internal/{fundId}/liquidate", orderSecured(jwtService, serviceOnly, handlers.FundsInternalLiquidate))
	handle(http.MethodPost, "/funds/internal/{fundId}/holdings/add", orderSecured(jwtService, serviceOnly, handlers.FundsInternalAddHolding))
	handle(http.MethodPost, "/funds/internal/{fundId}/liquidity/debit", orderSecured(jwtService, serviceOnly, handlers.FundsInternalDebitLiquidity))

	// OTC (trading-service module). OtcController has NO @PreAuthorize — every
	// endpoint is "authenticated" (any valid JWT). So we wrap each in the JWT
	// middleware only, with no role gate (a missing/invalid token → 401 from the
	// shared Middleware; there is never a 403 for /otc). Business errors throw
	// IllegalArgument/IllegalState/InsufficientPublicStock → OTC 404/409/400 shape
	// via writeDomainError (OtcExceptionHandler is HIGHEST_PRECEDENCE).
	authd := func(h http.HandlerFunc) http.Handler {
		return jwtService.Middleware(h)
	}
	handle(http.MethodPost, "/otc/offers", authd(handlers.OtcCreateOffer))
	handle(http.MethodPost, "/otc/offers/{offerId}/counter", authd(handlers.OtcCounterOffer))
	handle(http.MethodPost, "/otc/offers/{offerId}/accept", authd(handlers.OtcAcceptOffer))
	handle(http.MethodPost, "/otc/offers/{offerId}/reject", authd(handlers.OtcRejectOffer))
	handle(http.MethodPost, "/otc/offers/{offerId}/withdraw", authd(handlers.OtcWithdrawOffer))
	handle(http.MethodGet, "/otc/offers/active", authd(handlers.OtcActiveOffers))
	handle(http.MethodGet, "/otc/offers/history", authd(handlers.OtcNegotiationHistory))
	handle(http.MethodGet, "/otc/public-stocks", authd(handlers.OtcPublicStocks))
	handle(http.MethodPost, "/otc/contracts/{contractId}/exercise", authd(handlers.OtcExerciseContract))
	handle(http.MethodPost, "/options/{contractId}/exercise", authd(handlers.OtcExerciseContract))
	handle(http.MethodGet, "/otc/contracts/my", authd(handlers.OtcMyContracts))
	handle(http.MethodGet, "/otc/my-positions", authd(handlers.OtcMyPositions))
	handle(http.MethodPost, "/otc/positions", authd(handlers.OtcAddPosition))
	handle(http.MethodPut, "/otc/positions/{positionId}", authd(handlers.OtcUpdatePosition))
	handle(http.MethodDelete, "/otc/positions/{positionId}", authd(handlers.OtcRemovePosition))

	// /stocks/internal — PUBLIC (banka.security.permit-all=/stocks/internal/**).
	// saga-orchestrator-service calls these directly without a JWT, so they are
	// registered with NO auth middleware (analogous to /actuator/*). {id} is the
	// reservation / ownership-transfer UUID (string).
	handle(http.MethodPost, "/stocks/internal/reserve", http.HandlerFunc(handlers.StocksInternalReserve))
	handle(http.MethodDelete, "/stocks/internal/reservations/{id}", http.HandlerFunc(handlers.StocksInternalRelease))
	handle(http.MethodPost, "/stocks/internal/reservations/{id}/transfer", http.HandlerFunc(handlers.StocksInternalTransfer))
	handle(http.MethodPost, "/stocks/internal/ownership-transfers/{id}/reverse", http.HandlerFunc(handlers.StocksInternalReverse))

	// Interbank (trading-service module). All /internal/interbank/* are
	// @PreAuthorize("hasRole('SERVICE')") — interbank-service calls them via
	// TradingInternalClient with a SERVICE JWT (NOT public, unlike /stocks/internal).
	// Stock 2PC reserve/commit/release over interbank_stock_reservations + shared
	// `portfolio`; option lifecycle (idempotent) over interbank_option_reservations;
	// public-stocks listing. Synchronous primitives — no saga/scheduler. Business
	// errors → OTC 404/409 shape via writeDomainError. {id} is the reservation UUID;
	// {negotiationId} is the option negotiation key (string).
	handle(http.MethodPost, "/internal/interbank/reserve-stock", orderSecured(jwtService, serviceOnly, handlers.InterbankReserveStock))
	handle(http.MethodPost, "/internal/interbank/credit-stock", orderSecured(jwtService, serviceOnly, handlers.InterbankCreditStock))
	handle(http.MethodPost, "/internal/interbank/reservations/{id}/commit-stock", orderSecured(jwtService, serviceOnly, handlers.InterbankCommitStock))
	handle(http.MethodDelete, "/internal/interbank/reservations/{id}", orderSecured(jwtService, serviceOnly, handlers.InterbankReleaseStock))
	handle(http.MethodPost, "/internal/interbank/options/{negotiationId}/reserve", orderSecured(jwtService, serviceOnly, handlers.InterbankReserveOption))
	handle(http.MethodPost, "/internal/interbank/options/{negotiationId}/exercise", orderSecured(jwtService, serviceOnly, handlers.InterbankExerciseOption))
	handle(http.MethodDelete, "/internal/interbank/options/{negotiationId}/release", orderSecured(jwtService, serviceOnly, handlers.InterbankReleaseOption))
	handle(http.MethodGet, "/internal/interbank/public-stocks", orderSecured(jwtService, serviceOnly, handlers.InterbankPublicStocks))
}
