package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"

	"github.com/raf-si-2025/banka-1-go/shared/auth"
)

// ServerDeps carries all handler dependencies for dependency injection.
type ServerDeps struct {
	Partners        auth.PartnerStore
	InboundHandler  *InboundHandler
	OtcHandler      *OtcHandler
	PublicStock     *PublicStockHandler
	UserDisplay     *UserDisplayHandler
	OtcOutbound     *OtcOutboundHandler
	OtcContracts    *OtcContractsHandler
	Payments        *PaymentHandler
	JWTSecret       string
}

// corsPreflight short-circuits OPTIONS requests with 204 No Content so the
// browser preflight succeeds. The actual Access-Control-* response headers are
// emitted by nginx api-gateway via `add_header ... always` directives, so this
// middleware only needs to return a 2xx status to satisfy the browser.
// When the request is not OPTIONS, the chain proceeds normally.
func corsPreflight(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// NewRouter assembles the chi router with all routes.
// All inter-bank protocol routes are protected by RequireXApiKey middleware.
func NewRouter(d ServerDeps) http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.RequestID)
	r.Use(middleware.Recoverer)
	r.Use(corsPreflight)

	// Health check — no auth required.
	r.Get("/health", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	})

	// All inter-bank protocol routes require X-Api-Key.
	r.Group(func(r chi.Router) {
		r.Use(auth.RequireXApiKey(d.Partners))

		// §6.1 — POST /interbank (NEW_TX / COMMIT_TX / ROLLBACK_TX)
		r.Post("/interbank", d.InboundHandler.PostMessage)

		// §3.1 — GET /public-stock
		r.Get("/public-stock", d.PublicStock.Get)

		// §3.2 — POST /negotiations (create)
		r.Post("/negotiations", d.OtcHandler.Create)

		// §3.3 — PUT /negotiations/{rn}/{id} (counter-offer)
		r.Put("/negotiations/{rn}/{id}", d.OtcHandler.Counter)

		// §3.4 — GET /negotiations/{rn}/{id} (get state)
		r.Get("/negotiations/{rn}/{id}", d.OtcHandler.Get)

		// §3.5 — DELETE /negotiations/{rn}/{id} (close)
		r.Delete("/negotiations/{rn}/{id}", d.OtcHandler.Delete)

		// §3.6 — GET /negotiations/{rn}/{id}/accept (synchronous 2PC)
		r.Get("/negotiations/{rn}/{id}/accept", d.OtcHandler.Accept)

		// §3.7 — GET /interbank/user/{rn}/{id} (authoritative path, rerouted per PR_32)
		r.Get("/interbank/user/{rn}/{id}", d.UserDisplay.Get)

		// §3.7 alias — GET /user/{rn}/{id} (Tim 2 MINOR-1 spec path)
		r.Get("/user/{rn}/{id}", d.UserDisplay.Get)
	})

	// FE-facing JWT-authenticated OTC outbound wrapper routes.
	// Requires OTC_TRADE permission (or ROLE_ADMIN / ROLE_SUPERVISOR).
	if d.OtcOutbound != nil {
		r.Group(func(r chi.Router) {
			r.Use(auth.RequireJWT(d.JWTSecret))
			r.Use(auth.RequirePermission("OTC_TRADE", "CLIENT_OTC_TRADE", "ROLE_ADMIN", "ROLE_SUPERVISOR"))
			r.Route("/api/interbank/otc", func(r chi.Router) {
				r.Post("/negotiations", d.OtcOutbound.Create)
				r.Get("/negotiations", d.OtcOutbound.List)
				r.Get("/negotiations/{id}", d.OtcOutbound.Get)
				r.Put("/negotiations/{id}/counter", d.OtcOutbound.Counter)
				r.Post("/negotiations/{id}/accept", d.OtcOutbound.Accept)
				r.Delete("/negotiations/{id}", d.OtcOutbound.Delete)
				r.Get("/public-stock", d.OtcOutbound.PartnerPublicStock)

				// Buyer-side option contracts (cross-bank OTC option buy lifecycle).
				if d.OtcContracts != nil {
					r.Get("/contracts/my", d.OtcContracts.List)
					r.Post("/contracts/{id}/exercise", d.OtcContracts.Exercise)
				}
			})
		})
	}

	// FE-facing JWT-authenticated outbound cross-bank payment route.
	// Same permissions as the OTC outbound routes.
	if d.Payments != nil {
		r.Group(func(r chi.Router) {
			r.Use(auth.RequireJWT(d.JWTSecret))
			r.Use(auth.RequirePermission("OTC_TRADE", "CLIENT_OTC_TRADE", "ROLE_ADMIN", "ROLE_SUPERVISOR"))
			r.Post("/api/interbank/payments", d.Payments.Send)
		})
	}

	return r
}

// NewRouterWithMock is like NewRouter but also calls mountMock with the chi.Router
// so the caller can register additional routes (e.g. mock Banka 2 controller)
// before the handler is frozen.
func NewRouterWithMock(d ServerDeps, mountMock func(chi.Router)) http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.RequestID)
	r.Use(middleware.Recoverer)
	r.Use(corsPreflight)

	r.Get("/health", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	})

	r.Group(func(r chi.Router) {
		r.Use(auth.RequireXApiKey(d.Partners))
		r.Post("/interbank", d.InboundHandler.PostMessage)
		r.Get("/public-stock", d.PublicStock.Get)
		r.Post("/negotiations", d.OtcHandler.Create)
		r.Put("/negotiations/{rn}/{id}", d.OtcHandler.Counter)
		r.Get("/negotiations/{rn}/{id}", d.OtcHandler.Get)
		r.Delete("/negotiations/{rn}/{id}", d.OtcHandler.Delete)
		r.Get("/negotiations/{rn}/{id}/accept", d.OtcHandler.Accept)
		r.Get("/interbank/user/{rn}/{id}", d.UserDisplay.Get)
		r.Get("/user/{rn}/{id}", d.UserDisplay.Get)
	})

	if d.OtcOutbound != nil {
		r.Group(func(r chi.Router) {
			r.Use(auth.RequireJWT(d.JWTSecret))
			r.Use(auth.RequirePermission("OTC_TRADE", "CLIENT_OTC_TRADE", "ROLE_ADMIN", "ROLE_SUPERVISOR"))
			r.Route("/api/interbank/otc", func(r chi.Router) {
				r.Post("/negotiations", d.OtcOutbound.Create)
				r.Get("/negotiations", d.OtcOutbound.List)
				r.Get("/negotiations/{id}", d.OtcOutbound.Get)
				r.Put("/negotiations/{id}/counter", d.OtcOutbound.Counter)
				r.Post("/negotiations/{id}/accept", d.OtcOutbound.Accept)
				r.Delete("/negotiations/{id}", d.OtcOutbound.Delete)
				r.Get("/public-stock", d.OtcOutbound.PartnerPublicStock)

				// Buyer-side option contracts (cross-bank OTC option buy lifecycle).
				if d.OtcContracts != nil {
					r.Get("/contracts/my", d.OtcContracts.List)
					r.Post("/contracts/{id}/exercise", d.OtcContracts.Exercise)
				}
			})
		})
	}

	if d.Payments != nil {
		r.Group(func(r chi.Router) {
			r.Use(auth.RequireJWT(d.JWTSecret))
			r.Use(auth.RequirePermission("OTC_TRADE", "CLIENT_OTC_TRADE", "ROLE_ADMIN", "ROLE_SUPERVISOR"))
			r.Post("/api/interbank/payments", d.Payments.Send)
		})
	}

	if mountMock != nil {
		mountMock(r)
	}

	return r
}
