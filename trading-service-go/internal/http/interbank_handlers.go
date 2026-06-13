package http

import (
	"net/http"

	"banka1/trading-service-go/internal/api"
	"banka1/trading-service-go/internal/interbank"

	"banka1/go-platform/httpx"
)

// ===================== /internal/interbank (SERVICE) ======================
//
// interbank-service calls these via TradingInternalClient with a SERVICE JWT
// (Java @PreAuthorize("hasRole('SERVICE')")). Business errors are
// IllegalArgument → 404 / IllegalState → 409 in the OTC body shape
// ({status,error,message,timestamp}) via writeDomainError (the consolidated JVM's
// OtcExceptionHandler is @Order(HIGHEST_PRECEDENCE)).

// InterbankReserveStock ↔ POST /internal/interbank/reserve-stock (200,
// {reservationId}).
func (h *Handlers) InterbankReserveStock(w http.ResponseWriter, r *http.Request) {
	var req interbank.ReserveStockReq
	if err := decodeJSONLenient(r, &req); err != nil {
		writeDomainError(w, r, api.NewOrderError(http.StatusBadRequest, "Malformed JSON request body"))
		return
	}
	reservationID, err := h.app.Interbank.ReserveStock(r.Context(), req.SellerUserID, req.Ticker,
		req.Quantity, req.TransactionIDRouting, req.TransactionIDLocal)
	if err != nil {
		writeDomainError(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, interbank.ReserveStockRes{ReservationID: reservationID})
}

// InterbankCreditStock ↔ POST /internal/interbank/credit-stock (204). Credits the
// local buyer the shares delivered on a cross-bank OTC option exercise (FIX 1).
func (h *Handlers) InterbankCreditStock(w http.ResponseWriter, r *http.Request) {
	var req interbank.CreditStockReq
	if err := decodeJSONLenient(r, &req); err != nil {
		writeDomainError(w, r, api.NewOrderError(http.StatusBadRequest, "Malformed JSON request body"))
		return
	}
	if err := h.app.Interbank.CreditStock(r.Context(), req.BuyerUserID, req.Ticker,
		req.Quantity, req.TransactionIDRouting, req.TransactionIDLocal, req.StrikePrice); err != nil {
		writeDomainError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// InterbankCommitStock ↔ POST /internal/interbank/reservations/{id}/commit-stock
// (204). {id} is the reservation UUID (string).
func (h *Handlers) InterbankCommitStock(w http.ResponseWriter, r *http.Request) {
	if err := h.app.Interbank.CommitStock(r.Context(), r.PathValue("id")); err != nil {
		writeDomainError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// InterbankReleaseStock ↔ DELETE /internal/interbank/reservations/{id} (204).
// release/abort; {id} is the reservation UUID (string).
func (h *Handlers) InterbankReleaseStock(w http.ResponseWriter, r *http.Request) {
	if err := h.app.Interbank.ReleaseStock(r.Context(), r.PathValue("id")); err != nil {
		writeDomainError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// InterbankReserveOption ↔ POST /internal/interbank/options/{negotiationId}/reserve
// (204). Idempotent (existing negotiation → no-op).
func (h *Handlers) InterbankReserveOption(w http.ResponseWriter, r *http.Request) {
	var req interbank.ReserveOptionReq
	if err := decodeJSONLenient(r, &req); err != nil {
		writeDomainError(w, r, api.NewOrderError(http.StatusBadRequest, "Malformed JSON request body"))
		return
	}
	if err := h.app.Interbank.ReserveOption(r.Context(), r.PathValue("negotiationId"),
		req.SellerForeignID, req.Ticker, req.Quantity); err != nil {
		writeDomainError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// InterbankExerciseOption ↔ POST /internal/interbank/options/{negotiationId}/exercise
// (204). Idempotent (missing / terminal → no-op).
func (h *Handlers) InterbankExerciseOption(w http.ResponseWriter, r *http.Request) {
	if err := h.app.Interbank.ExerciseOption(r.Context(), r.PathValue("negotiationId")); err != nil {
		writeDomainError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// InterbankReleaseOption ↔ DELETE /internal/interbank/options/{negotiationId}/release
// (204). Idempotent (missing / terminal → no-op).
func (h *Handlers) InterbankReleaseOption(w http.ResponseWriter, r *http.Request) {
	if err := h.app.Interbank.ReleaseOption(r.Context(), r.PathValue("negotiationId")); err != nil {
		writeDomainError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// InterbankPublicStocks ↔ GET /internal/interbank/public-stocks (200, list).
func (h *Handlers) InterbankPublicStocks(w http.ResponseWriter, r *http.Request) {
	out, err := h.app.Interbank.PublicStocks(r.Context())
	if err != nil {
		writeDomainError(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, out)
}
