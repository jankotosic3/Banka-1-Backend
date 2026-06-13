package api

import (
	"errors"
	"log/slog"
	"net/http"

	"github.com/go-chi/chi/v5"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/service"
)

// ---------------------------------------------------------------------------
// OtcContractsHandler
// ---------------------------------------------------------------------------

// OtcContractsHandler handles the FE-facing JWT-authed interbank option-contract
// routes:
//
//	GET  /api/interbank/otc/contracts/my          — list the caller's contracts
//	POST /api/interbank/otc/contracts/{id}/exercise — buyer exercises a contract
//
// These complete the BUYER side of the cross-bank OTC option lifecycle: a Banka 1
// user who bought an option from a partner bank can now see and exercise it.
type OtcContractsHandler struct {
	svc *service.OtcContractsService
	log *slog.Logger
}

// NewOtcContractsHandler constructs the handler.
func NewOtcContractsHandler(svc *service.OtcContractsService, log *slog.Logger) *OtcContractsHandler {
	if log == nil {
		log = slog.Default()
	}
	return &OtcContractsHandler{svc: svc, log: log}
}

// List handles GET /api/interbank/otc/contracts/my.
// Admin/supervisor callers may pass ?all=true to see every contract.
func (h *OtcContractsHandler) List(w http.ResponseWriter, r *http.Request) {
	principalID := extractPrincipalID(r)
	isAdmin := hasAdminOrSupervisor(r) && r.URL.Query().Get("all") == "true"

	views, err := h.svc.ListForUser(r.Context(), principalID, isAdmin)
	if err != nil {
		h.handleContractError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, views)
}

// Exercise handles POST /api/interbank/otc/contracts/{id}/exercise.
func (h *OtcContractsHandler) Exercise(w http.ResponseWriter, r *http.Request) {
	principalID := extractPrincipalID(r)
	isAdmin := hasAdminOrSupervisor(r)
	id := chi.URLParam(r, "id")

	view, err := h.svc.Exercise(r.Context(), principalID, isAdmin, id)
	if err != nil {
		h.handleContractError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, view)
}

// ---------------------------------------------------------------------------
// Error mapping
// ---------------------------------------------------------------------------

func (h *OtcContractsHandler) handleContractError(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, service.ErrContractNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	case errors.Is(err, service.ErrContractForbidden):
		writeError(w, http.StatusForbidden, err.Error())
	case errors.Is(err, service.ErrContractNotExercisable):
		writeError(w, http.StatusConflict, err.Error())
	case errors.Is(err, service.ErrContractAlreadyExercising):
		// Lost the concurrent-exercise entry claim — another exercise is in flight.
		writeError(w, http.StatusConflict, err.Error())
	case errors.Is(err, service.ErrInterbankProtocol):
		// Partner rejected / 2PC could not complete — treat as a conflict.
		writeError(w, http.StatusConflict, err.Error())
	default:
		h.log.ErrorContext(r.Context(), "contracts: unexpected error", "err", err)
		writeError(w, http.StatusInternalServerError, err.Error())
	}
}
