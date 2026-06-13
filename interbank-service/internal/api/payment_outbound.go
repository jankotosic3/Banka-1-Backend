package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/client"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/service"
)

// ---------------------------------------------------------------------------
// PaymentHandler
// ---------------------------------------------------------------------------

// PaymentHandler handles the FE-facing POST /api/interbank/payments route.
// It lets a Banka 1 user initiate a plain cross-bank money payment to an
// account at a partner bank (e.g. Banka 2, routing 222). The handler runs the
// inter-bank 2PC as coordinator via Coordinator.SendOutboundPayment.
//
// Like OtcOutboundHandler this route uses JWT auth (not X-Api-Key) so Angular
// clients can call it directly without exposing the inter-bank token.
type PaymentHandler struct {
	coord *service.Coordinator
	bc    service.BankingCoreReader
	log   *slog.Logger
}

// NewPaymentHandler constructs the handler. bc resolves the sender account so we
// can verify ownership and currency before initiating the 2PC.
func NewPaymentHandler(coord *service.Coordinator, bc service.BankingCoreReader, log *slog.Logger) *PaymentHandler {
	if log == nil {
		log = slog.Default()
	}
	return &PaymentHandler{coord: coord, bc: bc, log: log}
}

// outboundPaymentRequest is the FE request body for POST /api/interbank/payments.
// Amount accepts both a JSON string ("100.50") and a bare number per the
// shopspring/decimal unmarshaller used across the protocol DTOs.
type outboundPaymentRequest struct {
	FromAccount string          `json:"fromAccount"`
	ToAccount   string          `json:"toAccount"`
	Amount      decimal.Decimal `json:"amount"`
	Currency    string          `json:"currency"`
	Message     string          `json:"message,omitempty"`
}

// outboundPaymentResponse is returned on a successful 2PC commit.
type outboundPaymentResponse struct {
	TransactionID string `json:"transactionId"`
	Status        string `json:"status"`
}

// Send handles POST /api/interbank/payments.
func (h *PaymentHandler) Send(w http.ResponseWriter, r *http.Request) {
	principalID := extractPrincipalID(r)
	if principalID == 0 {
		writeError(w, http.StatusUnauthorized, "missing or invalid principal id claim")
		return
	}

	var req outboundPaymentRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid body: "+err.Error())
		return
	}

	// Basic validation.
	if req.FromAccount == "" || req.ToAccount == "" {
		writeError(w, http.StatusBadRequest, "fromAccount and toAccount are required")
		return
	}
	if req.Currency == "" {
		writeError(w, http.StatusBadRequest, "currency is required")
		return
	}
	if !req.Amount.IsPositive() {
		writeError(w, http.StatusBadRequest, "amount must be greater than zero")
		return
	}

	// Validate the sender account against banking-core: it must exist, belong to
	// the JWT principal (unless admin/supervisor), and be in the requested currency.
	info, err := h.bc.ResolveAccount(r.Context(), req.FromAccount)
	if err != nil {
		if errors.Is(err, service.ErrAccountNotFound) || errors.Is(err, client.ErrNotFound) {
			writeError(w, http.StatusNotFound, "sender account not found")
			return
		}
		h.log.ErrorContext(r.Context(), "payment: resolve sender account failed", "account", req.FromAccount, "err", err)
		writeError(w, http.StatusInternalServerError, "failed to resolve sender account")
		return
	}
	if info == nil {
		writeError(w, http.StatusNotFound, "sender account not found")
		return
	}
	if !hasAdminOrSupervisor(r) && info.OwnerID != principalID {
		writeError(w, http.StatusForbidden, "sender account does not belong to the authenticated user")
		return
	}
	if info.Currency != req.Currency {
		writeError(w, http.StatusBadRequest, "currency does not match the sender account currency")
		return
	}

	txID, err := h.coord.SendOutboundPayment(r.Context(), req.FromAccount, req.ToAccount, req.Amount, req.Currency, req.Message)
	if err != nil {
		h.handlePaymentError(w, r, err)
		return
	}

	writeJSON(w, http.StatusOK, outboundPaymentResponse{
		TransactionID: fmt.Sprintf("%d:%s", txID.RoutingNumber, txID.Id),
		Status:        "COMPLETED",
	})
}

// ---------------------------------------------------------------------------
// Error mapping
// ---------------------------------------------------------------------------

func (h *PaymentHandler) handlePaymentError(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, service.ErrPaymentInvalid):
		writeError(w, http.StatusBadRequest, err.Error())
	case errors.Is(err, service.ErrAccountNotFound), errors.Is(err, client.ErrNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	case errors.Is(err, service.ErrInterbankProtocol):
		// Partner rejected / 2PC could not complete — treat as a conflict.
		writeError(w, http.StatusConflict, err.Error())
	default:
		h.log.ErrorContext(r.Context(), "payment: unexpected error", "err", err)
		writeError(w, http.StatusInternalServerError, err.Error())
	}
}
