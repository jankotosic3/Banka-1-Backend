package http

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"banka1/banking-core-service-go/internal/config"
	"banka1/banking-core-service-go/internal/decimal"
	"banka1/banking-core-service-go/internal/service"
)

type Handler struct {
	cfg      config.Config
	services *service.Container
}

func NewHandler(cfg config.Config, services *service.Container) *Handler {
	return &Handler{cfg: cfg, services: services}
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	defer func() {
		if recovered := recover(); recovered != nil {
			writeError(w, http.StatusInternalServerError, "ERR_INTERNAL", "Interna greska", "unexpected panic")
		}
	}()

	h.applyCORS(w, r)

	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	if !h.enforceAuth(w, r) {
		return
	}

	path := r.URL.Path
	switch {
	case r.Method == http.MethodGet && path == "/actuator/health/liveness":
		h.liveness(w, r)
	case r.Method == http.MethodGet && (path == "/actuator/health/readiness" || path == "/actuator/health"):
		h.readiness(w, r)
	case r.Method == http.MethodGet && path == "/actuator/info":
		writeJSON(w, http.StatusOK, map[string]string{"app": "banking-core-service-go"})
	case r.Method == http.MethodGet && path == "/actuator/prometheus":
		h.prometheus(w, r)

	case r.Method == http.MethodPost && path == "/verification/generate":
		h.verificationGenerate(w, r)
	case r.Method == http.MethodPost && path == "/verification/validate":
		h.verificationValidate(w, r)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/verification/") && strings.HasSuffix(path, "/status"):
		id, _ := trimPrefixSuffix(path, "/verification/", "/status")
		h.verificationStatus(w, r, id)

	case r.Method == http.MethodGet && path == "/accounts/api/currencies/getAll":
		h.listCurrencies(w, r)
	case r.Method == http.MethodGet && path == "/accounts/api/currencies/getAllPage":
		h.listCurrenciesPage(w, r)
	case r.Method == http.MethodGet && path == "/accounts/api/currencies":
		h.getCurrencyByQuery(w, r)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/accounts/api/currencies/"):
		h.getCurrencyByPath(w, r, strings.TrimPrefix(path, "/accounts/api/currencies/"))

	case r.Method == http.MethodGet && path == "/accounts/api/sifra-delatnosti":
		h.listSifraDelatnosti(w, r)

	case r.Method == http.MethodPost && path == "/accounts/employee/accounts/checking":
		h.createCheckingAccount(w, r)
	case r.Method == http.MethodPost && path == "/accounts/employee/accounts/fx":
		h.createFXAccount(w, r)
	case r.Method == http.MethodGet && path == "/accounts/employee/accounts":
		h.searchEmployeeAccounts(w, r)
	case r.Method == http.MethodPut && strings.HasPrefix(path, "/accounts/employee/accounts/") && strings.HasSuffix(path, "/status"):
		accountNumber, _ := trimPrefixSuffix(path, "/accounts/employee/accounts/", "/status")
		h.editEmployeeAccountStatus(w, r, accountNumber)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/accounts/employee/accounts/client/"):
		h.employeeClientAccounts(w, r, strings.TrimPrefix(path, "/accounts/employee/accounts/client/"))
	case r.Method == http.MethodGet && path == "/accounts/employee/accounts/bank":
		h.employeeBankAccounts(w, r)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/accounts/employee/accounts/bank/"):
		h.employeeBankAccountByCurrency(w, r, strings.TrimPrefix(path, "/accounts/employee/accounts/bank/"))
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/accounts/employee/accounts/"):
		h.employeeAccountDetails(w, r, strings.TrimPrefix(path, "/accounts/employee/accounts/"))
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/accounts/employee/companies/"):
		h.employeeCompany(w, r, strings.TrimPrefix(path, "/accounts/employee/companies/"))
	case r.Method == http.MethodPut && strings.HasPrefix(path, "/accounts/employee/companies/"):
		h.updateEmployeeCompany(w, r, strings.TrimPrefix(path, "/accounts/employee/companies/"))

	case r.Method == http.MethodGet && path == "/accounts/client/accounts":
		h.clientAccountsPage(w, r)
	case r.Method == http.MethodPut && strings.HasPrefix(path, "/accounts/client/api/accounts/") && strings.HasSuffix(path, "/name"):
		accountNumber, _ := trimPrefixSuffix(path, "/accounts/client/api/accounts/", "/name")
		h.clientEditNameByNumber(w, r, accountNumber)
	case r.Method == http.MethodPatch && strings.HasPrefix(path, "/accounts/client/accounts/") && strings.HasSuffix(path, "/name"):
		id, _ := trimPrefixSuffix(path, "/accounts/client/accounts/", "/name")
		h.clientEditNameByID(w, r, id)
	case r.Method == http.MethodPatch && strings.HasPrefix(path, "/accounts/client/accounts/") && strings.HasSuffix(path, "/limits"):
		id, _ := trimPrefixSuffix(path, "/accounts/client/accounts/", "/limits")
		h.clientEditLimitsByID(w, r, id)
	case r.Method == http.MethodPut && strings.HasPrefix(path, "/accounts/client/api/accounts/") && strings.HasSuffix(path, "/limits"):
		accountNumber, _ := trimPrefixSuffix(path, "/accounts/client/api/accounts/", "/limits")
		h.clientEditLimitsByNumber(w, r, accountNumber)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/accounts/client/accounts/") && strings.HasSuffix(path, "/cards"):
		id, _ := trimPrefixSuffix(path, "/accounts/client/accounts/", "/cards")
		h.clientAccountCards(w, r, id)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/accounts/client/accounts/"):
		h.clientAccountDetailsByID(w, r, strings.TrimPrefix(path, "/accounts/client/accounts/"))
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/accounts/client/api/accounts/"):
		h.clientAccountDetailsByNumber(w, r, strings.TrimPrefix(path, "/accounts/client/api/accounts/"))

	case r.Method == http.MethodPost && path == "/api/cards/auto":
		h.autoCreateCard(w, r)
	case r.Method == http.MethodPost && path == "/api/cards/request":
		h.requestPersonalCard(w, r)
	case r.Method == http.MethodPost && path == "/api/cards/request/business":
		h.requestBusinessCard(w, r)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/api/cards/client/"):
		h.cardsForClient(w, r, strings.TrimPrefix(path, "/api/cards/client/"))
	case r.Method == http.MethodPut && strings.HasPrefix(path, "/api/cards/id/") && strings.HasSuffix(path, "/block"):
		id, _ := trimPrefixSuffix(path, "/api/cards/id/", "/block")
		h.blockCard(w, r, id)
	case r.Method == http.MethodPut && strings.HasPrefix(path, "/api/cards/id/") && strings.HasSuffix(path, "/limit"):
		id, _ := trimPrefixSuffix(path, "/api/cards/id/", "/limit")
		h.updateCardLimit(w, r, id)
	case r.Method == http.MethodPut && strings.HasPrefix(path, "/api/cards/id/") && strings.HasSuffix(path, "/unblock"):
		id, _ := trimPrefixSuffix(path, "/api/cards/id/", "/unblock")
		h.unblockCard(w, r, id)
	case r.Method == http.MethodPut && strings.HasPrefix(path, "/api/cards/id/") && strings.HasSuffix(path, "/deactivate"):
		id, _ := trimPrefixSuffix(path, "/api/cards/id/", "/deactivate")
		h.deactivateCard(w, r, id)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/api/cards/id/"):
		h.cardDetails(w, r, strings.TrimPrefix(path, "/api/cards/id/"))
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/api/cards/account/"):
		h.cardsByAccount(w, r, strings.TrimPrefix(path, "/api/cards/account/"))
	case r.Method == http.MethodGet && path == "/api/cards/all":
		h.allCards(w, r)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/api/cards/internal/account/"):
		h.internalCardsByAccount(w, r, strings.TrimPrefix(path, "/api/cards/internal/account/"))

	case r.Method == http.MethodPost && (path == "/transactions/payment" || path == "/transactions/payments"):
		h.newPayment(w, r)
	case r.Method == http.MethodGet && path == "/transactions/by-client":
		h.transactionsByClient(w, r, "all")
	case r.Method == http.MethodGet && path == "/transactions/by-sender-client":
		h.transactionsByClient(w, r, "sender")
	case r.Method == http.MethodGet && path == "/transactions/by-recipient-client":
		h.transactionsByClient(w, r, "recipient")
	case r.Method == http.MethodGet && path == "/transactions/by-this-client":
		h.transactionsByThisClient(w, r, "all")
	case r.Method == http.MethodGet && path == "/transactions/by-this-sender-client":
		h.transactionsByThisClient(w, r, "sender")
	case r.Method == http.MethodGet && path == "/transactions/by-this-recipient-client":
		h.transactionsByThisClient(w, r, "recipient")
	case r.Method == http.MethodGet && path == "/transactions/api/payments":
		h.findPayments(w, r)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/transactions/employee/accounts/"):
		h.transactionsForAccount(w, r, strings.TrimPrefix(path, "/transactions/employee/accounts/"), true)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/transactions/accounts/"):
		h.transactionsForAccount(w, r, strings.TrimPrefix(path, "/transactions/accounts/"), false)

	case r.Method == http.MethodGet && path == "/payment-recipients":
		h.paymentRecipientsList(w, r)
	case r.Method == http.MethodPost && path == "/payment-recipients":
		h.paymentRecipientCreate(w, r)
	case r.Method == http.MethodPut && strings.HasPrefix(path, "/payment-recipients/"):
		h.paymentRecipientUpdate(w, r, strings.TrimPrefix(path, "/payment-recipients/"))
	case r.Method == http.MethodDelete && strings.HasPrefix(path, "/payment-recipients/"):
		h.paymentRecipientDelete(w, r, strings.TrimPrefix(path, "/payment-recipients/"))

	case r.Method == http.MethodPost && (path == "/transfers" || path == "/transfers/"):
		h.executeTransfer(w, r)
	case r.Method == http.MethodGet && path == "/transfers":
		h.listTransfers(w, r)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/transfers/accounts/"):
		h.transfersForAccount(w, r, strings.TrimPrefix(path, "/transfers/accounts/"))
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/transfers/"):
		h.transferDetails(w, r, strings.TrimPrefix(path, "/transfers/"))

	case r.Method == http.MethodPost && path == "/accounts/createMarginAccount":
		h.createUserMarginAccount(w, r)
	case r.Method == http.MethodPost && path == "/accounts/company/createMarginAccount":
		h.createCompanyMarginAccount(w, r)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/accounts/getMarginUser/"):
		h.getMarginUser(w, r, strings.TrimPrefix(path, "/accounts/getMarginUser/"))
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/accounts/company/getMarginCompany/"):
		h.getMarginCompany(w, r, strings.TrimPrefix(path, "/accounts/company/getMarginCompany/"))
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/accounts/internal/by-owner/"):
		h.accountByOwnerAndCurrency(w, r, strings.TrimPrefix(path, "/accounts/internal/by-owner/"))
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/accounts/internal/default/"):
		h.defaultAccount(w, r, strings.TrimPrefix(path, "/accounts/internal/default/"))

	case r.Method == http.MethodPost && path == "/transactions/stockBuyMarginTransaction":
		h.buyOnMargin(w, r)
	case r.Method == http.MethodPost && path == "/transactions/stockSellMarginTransaction":
		h.sellOnMargin(w, r)
	case r.Method == http.MethodPost && strings.HasPrefix(path, "/transactions/addToMargin/"):
		h.addToMargin(w, r, strings.TrimPrefix(path, "/transactions/addToMargin/"))
	case r.Method == http.MethodPost && strings.HasPrefix(path, "/transactions/withdrawFromMargin/"):
		h.withdrawFromMargin(w, r, strings.TrimPrefix(path, "/transactions/withdrawFromMargin/"))
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/transactions/getAllMarginTransactions/"):
		h.marginHistory(w, r, strings.TrimPrefix(path, "/transactions/getAllMarginTransactions/"))

	case r.Method == http.MethodPost && path == "/transactions/internal/reserve-funds":
		h.reserveFunds(w, r)
	case r.Method == http.MethodDelete && strings.HasPrefix(path, "/transactions/internal/reservations/"):
		h.releaseFunds(w, r, strings.TrimPrefix(path, "/transactions/internal/reservations/"))
	case r.Method == http.MethodPost && strings.HasPrefix(path, "/transactions/internal/reservations/") && strings.HasSuffix(path, "/commit"):
		id, _ := trimPrefixSuffix(path, "/transactions/internal/reservations/", "/commit")
		h.commitFunds(w, r, id)
	case r.Method == http.MethodPost && path == "/transactions/internal/transfer":
		h.internalTransfer(w, r)
	case r.Method == http.MethodPost && strings.HasPrefix(path, "/transactions/internal/transfers/") && strings.HasSuffix(path, "/reverse"):
		id, _ := trimPrefixSuffix(path, "/transactions/internal/transfers/", "/reverse")
		h.reverseTransfer(w, r, id)

	case r.Method == http.MethodPost && path == "/internal/interbank/reserve-monas":
		h.reserveMonas(w, r)
	case r.Method == http.MethodPost && strings.HasPrefix(path, "/internal/interbank/reservations/") && strings.HasSuffix(path, "/commit-monas"):
		id, _ := trimPrefixSuffix(path, "/internal/interbank/reservations/", "/commit-monas")
		h.commitMonas(w, r, id)
	case r.Method == http.MethodDelete && strings.HasPrefix(path, "/internal/interbank/reservations/"):
		h.releaseMonas(w, r, strings.TrimPrefix(path, "/internal/interbank/reservations/"))
	case r.Method == http.MethodGet && path == "/internal/interbank/account-by-owner":
		h.accountByOwner(w, r)
	case r.Method == http.MethodGet && path == "/internal/interbank/account-resolve":
		h.resolveInterbankAccount(w, r)
	case r.Method == http.MethodPost && path == "/internal/interbank/record-payment":
		h.recordInterbankPayment(w, r)

	case r.Method == http.MethodPost && path == "/internal/accounts/transaction":
		h.internalTransaction(w, r, false)
	case r.Method == http.MethodPost && path == "/internal/accounts/exchange/buy":
		h.exchangeBuy(w, r)
	case r.Method == http.MethodPost && path == "/internal/accounts/exchange/sell":
		h.exchangeSell(w, r)
	case r.Method == http.MethodPost && path == "/internal/accounts/transactionFromBank":
		h.transactionFromBank(w, r)
	case r.Method == http.MethodPost && path == "/internal/accounts/debit":
		h.debitAccount(w, r)
	case r.Method == http.MethodPost && path == "/internal/accounts/credit":
		h.creditAccount(w, r)
	case r.Method == http.MethodPost && path == "/internal/accounts/creditBank":
		h.creditBank(w, r)
	case r.Method == http.MethodPost && path == "/internal/accounts/debitBank":
		h.debitBank(w, r)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/internal/accounts/state/"):
		h.getStateAccount(w, r, strings.TrimPrefix(path, "/internal/accounts/state/"))
	case r.Method == http.MethodPost && path == "/internal/accounts/transfer":
		h.internalTransaction(w, r, true)
	case r.Method == http.MethodGet && path == "/internal/accounts/info":
		h.internalInfo(w, r)
	case r.Method == http.MethodPost && path == "/internal/accounts/system":
		h.createSystemAccount(w, r)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/internal/accounts/id/") && strings.HasSuffix(path, "/details"):
		id, _ := trimPrefixSuffix(path, "/internal/accounts/id/", "/details")
		h.getAccountByID(w, r, id)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/internal/accounts/bank/"):
		h.getBankAccount(w, r, strings.TrimPrefix(path, "/internal/accounts/bank/"))
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/internal/accounts/") && strings.HasSuffix(path, "/details"):
		accountNumber, _ := trimPrefixSuffix(path, "/internal/accounts/", "/details")
		h.getAccountByNumber(w, r, accountNumber)
	case r.Method == http.MethodGet && strings.HasPrefix(path, "/employee/accounts/client/"):
		h.clientAccounts(w, r, strings.TrimPrefix(path, "/employee/accounts/client/"))

	default:
		if isKnownPath(path) {
			writeError(w, http.StatusMethodNotAllowed, "ERR_METHOD_NOT_ALLOWED", "Metod nije dozvoljen", r.Method+" nije podrzan za "+path)
		} else {
			writeError(w, http.StatusNotFound, "ERR_NOT_FOUND", "Resurs nije pronadjen", path)
		}
	}
}

func (h *Handler) createUserMarginAccount(w http.ResponseWriter, r *http.Request) {
	if !h.requireAuthenticated(w, r) {
		return
	}
	var req service.CreateUserMarginAccountRequest
	if !decode(w, r, &req) {
		return
	}
	resp, err := h.services.MarginAccounts.CreateForUser(r.Context(), req)
	respond(w, resp, http.StatusCreated, err)
}

func (h *Handler) createCompanyMarginAccount(w http.ResponseWriter, r *http.Request) {
	if !h.requireAuthenticated(w, r) {
		return
	}
	var req service.CreateCompanyMarginAccountRequest
	if !decode(w, r, &req) {
		return
	}
	resp, err := h.services.MarginAccounts.CreateForCompany(r.Context(), req)
	respond(w, resp, http.StatusCreated, err)
}

func (h *Handler) getMarginUser(w http.ResponseWriter, r *http.Request, rawID string) {
	if !h.requireAuthenticated(w, r) {
		return
	}
	id, ok := parseIntPath(w, rawID)
	if !ok {
		return
	}
	resp, err := h.services.MarginAccounts.FindByUserID(r.Context(), id)
	respond(w, resp, http.StatusOK, err)
}

func (h *Handler) getMarginCompany(w http.ResponseWriter, r *http.Request, rawID string) {
	if !h.requireAuthenticated(w, r) {
		return
	}
	id, ok := parseIntPath(w, rawID)
	if !ok {
		return
	}
	resp, err := h.services.MarginAccounts.FindByCompanyID(r.Context(), id)
	respond(w, resp, http.StatusOK, err)
}

func (h *Handler) defaultAccount(w http.ResponseWriter, r *http.Request, rawID string) {
	id, ok := parseIntPath(w, rawID)
	if !ok {
		return
	}
	resp, err := h.services.Accounts.FindDefaultRSDByOwner(r.Context(), id)
	if err != nil {
		respond(w, nil, http.StatusOK, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{
		"ownerId":       strconv.FormatInt(id, 10),
		"accountNumber": resp.AccountNumber,
	})
}

func (h *Handler) accountByOwnerAndCurrency(w http.ResponseWriter, r *http.Request, raw string) {
	parts := strings.Split(strings.Trim(raw, "/"), "/")
	if len(parts) != 3 || parts[1] != "currency" || strings.TrimSpace(parts[0]) == "" || strings.TrimSpace(parts[2]) == "" {
		writeError(w, http.StatusBadRequest, "ERR_VALIDATION", "Neispravni podaci", "putanja mora biti /accounts/internal/by-owner/{ownerId}/currency/{currencyCode}")
		return
	}
	id, ok := parseIntPath(w, parts[0])
	if !ok {
		return
	}
	resp, err := h.services.Accounts.FindByOwnerAndCurrency(r.Context(), id, parts[2])
	if err != nil {
		var serviceErr *service.Error
		if errors.As(err, &serviceErr) && serviceErr.Status == http.StatusNotFound {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		handleError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) buyOnMargin(w http.ResponseWriter, r *http.Request) {
	if !h.requireAuthenticated(w, r) {
		return
	}
	var req service.StockMarginTransactionRequest
	if !decode(w, r, &req) {
		return
	}
	respondNoContent(w, h.services.MarginTx.BuyOnMargin(r.Context(), req))
}

func (h *Handler) sellOnMargin(w http.ResponseWriter, r *http.Request) {
	if !h.requireAuthenticated(w, r) {
		return
	}
	var req service.StockMarginTransactionRequest
	if !decode(w, r, &req) {
		return
	}
	respondNoContent(w, h.services.MarginTx.SellOnMargin(r.Context(), req))
}

func (h *Handler) addToMargin(w http.ResponseWriter, r *http.Request, rawID string) {
	if !h.requireAuthenticated(w, r) {
		return
	}
	id, ok := parseIntPath(w, rawID)
	if !ok {
		return
	}
	var req service.MarginTransferRequest
	if !decode(w, r, &req) {
		return
	}
	respondNoContent(w, h.services.MarginTx.AddToMarginForUser(r.Context(), id, req))
}

func (h *Handler) withdrawFromMargin(w http.ResponseWriter, r *http.Request, rawID string) {
	if !h.requireAuthenticated(w, r) {
		return
	}
	id, ok := parseIntPath(w, rawID)
	if !ok {
		return
	}
	var req service.MarginTransferRequest
	if !decode(w, r, &req) {
		return
	}
	respondNoContent(w, h.services.MarginTx.WithdrawFromMarginForUser(r.Context(), id, req))
}

func (h *Handler) marginHistory(w http.ResponseWriter, r *http.Request, accountNumber string) {
	if !h.requireAuthenticated(w, r) {
		return
	}
	resp, err := h.services.MarginTx.History(r.Context(), accountNumber)
	respond(w, resp, http.StatusOK, err)
}

func (h *Handler) reserveFunds(w http.ResponseWriter, r *http.Request) {
	if !h.requireServiceRole(w, r) {
		return
	}
	var req service.ReserveFundsRequest
	if !decode(w, r, &req) {
		return
	}
	resp, err := h.services.Internal.ReserveFunds(r.Context(), req.OwnerID, req.Amount, correlationID(r))
	respond(w, resp, http.StatusOK, err)
}

func (h *Handler) releaseFunds(w http.ResponseWriter, r *http.Request, id string) {
	if !h.requireServiceRole(w, r) {
		return
	}
	resp, err := h.services.Internal.ReleaseFunds(r.Context(), id, correlationID(r))
	respond(w, resp, http.StatusOK, err)
}

func (h *Handler) commitFunds(w http.ResponseWriter, r *http.Request, id string) {
	if !h.requireServiceRole(w, r) {
		return
	}
	resp, err := h.services.Internal.CommitFunds(r.Context(), id, correlationID(r))
	respond(w, resp, http.StatusOK, err)
}

func (h *Handler) internalTransfer(w http.ResponseWriter, r *http.Request) {
	if !h.requireServiceRole(w, r) {
		return
	}
	var req service.InternalTransferRequest
	if !decode(w, r, &req) {
		return
	}
	resp, err := h.services.Internal.Transfer(r.Context(), req, correlationID(r))
	respond(w, resp, http.StatusOK, err)
}

func (h *Handler) reverseTransfer(w http.ResponseWriter, r *http.Request, id string) {
	if !h.requireServiceRole(w, r) {
		return
	}
	resp, err := h.services.Internal.ReverseTransfer(r.Context(), id, correlationID(r))
	respond(w, resp, http.StatusOK, err)
}

func (h *Handler) reserveMonas(w http.ResponseWriter, r *http.Request) {
	if !h.requireServiceRole(w, r) {
		return
	}
	var req service.ReserveMonasRequest
	if !decode(w, r, &req) {
		return
	}
	id, err := h.services.Interbank.ReserveMonas(r.Context(), req)
	respond(w, service.ReserveMonasResponse{ReservationID: id}, http.StatusOK, err)
}

func (h *Handler) commitMonas(w http.ResponseWriter, r *http.Request, id string) {
	if !h.requireServiceRole(w, r) {
		return
	}
	respondNoContent(w, h.services.Interbank.CommitReservation(r.Context(), id))
}

func (h *Handler) releaseMonas(w http.ResponseWriter, r *http.Request, id string) {
	if !h.requireServiceRole(w, r) {
		return
	}
	respondNoContent(w, h.services.Interbank.ReleaseReservation(r.Context(), id))
}

func (h *Handler) accountByOwner(w http.ResponseWriter, r *http.Request) {
	if !h.requireServiceRole(w, r) {
		return
	}
	ownerID, err := strconv.ParseInt(r.URL.Query().Get("ownerId"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "ERR_VALIDATION", "Neispravni podaci", "ownerId je obavezan")
		return
	}
	resp, err := h.services.Interbank.AccountByOwner(r.Context(), ownerID, r.URL.Query().Get("currency"))
	respond(w, resp, http.StatusOK, err)
}

func (h *Handler) resolveInterbankAccount(w http.ResponseWriter, r *http.Request) {
	if !h.requireServiceRole(w, r) {
		return
	}
	resp, err := h.services.Interbank.ResolveAccount(r.Context(), r.URL.Query().Get("num"))
	respond(w, resp, http.StatusOK, err)
}

func (h *Handler) recordInterbankPayment(w http.ResponseWriter, r *http.Request) {
	if !h.requireServiceRole(w, r) {
		return
	}
	var req service.RecordInterbankPaymentRequest
	if !decode(w, r, &req) {
		return
	}
	respondEmptyOK(w, h.services.Interbank.RecordInterbankPayment(r.Context(), req))
}

type creditDebitRequest struct {
	AccountNumber string `json:"accountNumber"`
	Amount        any    `json:"amount"`
	ClientID      int64  `json:"clientId"`
}

func (h *Handler) debitAccount(w http.ResponseWriter, r *http.Request) {
	var decoded struct {
		AccountNumber string          `json:"accountNumber"`
		Amount        json.RawMessage `json:"amount"`
		ClientID      int64           `json:"clientId"`
	}
	if !decode(w, r, &decoded) {
		return
	}
	amount, err := parseRawDecimal(decoded.Amount)
	if err != nil {
		writeError(w, http.StatusBadRequest, "ERR_VALIDATION", "Neispravni podaci", err.Error())
		return
	}
	respondEmptyOK(w, h.services.Accounts.Debit(r.Context(), decoded.AccountNumber, amount, decoded.ClientID))
}

func (h *Handler) creditAccount(w http.ResponseWriter, r *http.Request) {
	var decoded struct {
		AccountNumber string          `json:"accountNumber"`
		Amount        json.RawMessage `json:"amount"`
		ClientID      int64           `json:"clientId"`
	}
	if !decode(w, r, &decoded) {
		return
	}
	amount, err := parseRawDecimal(decoded.Amount)
	if err != nil {
		writeError(w, http.StatusBadRequest, "ERR_VALIDATION", "Neispravni podaci", err.Error())
		return
	}
	respondEmptyOK(w, h.services.Accounts.Credit(r.Context(), decoded.AccountNumber, amount, decoded.ClientID))
}

func (h *Handler) getAccountByID(w http.ResponseWriter, r *http.Request, rawID string) {
	id, ok := parseIntPath(w, rawID)
	if !ok {
		return
	}
	resp, err := h.services.Accounts.GetByID(r.Context(), id)
	respond(w, resp, http.StatusOK, err)
}

func (h *Handler) getBankAccount(w http.ResponseWriter, r *http.Request, currency string) {
	resp, err := h.services.Accounts.GetBankAccount(r.Context(), currency)
	respond(w, resp, http.StatusOK, err)
}

func (h *Handler) getAccountByNumber(w http.ResponseWriter, r *http.Request, accountNumber string) {
	resp, err := h.services.Accounts.GetByNumber(r.Context(), accountNumber)
	respond(w, resp, http.StatusOK, err)
}

func (h *Handler) clientAccounts(w http.ResponseWriter, r *http.Request, rawID string) {
	parts := strings.SplitN(rawID, "/", 2)
	id, ok := parseIntPath(w, parts[0])
	if !ok {
		return
	}
	resp, err := h.services.Accounts.FindClientAccounts(r.Context(), id)
	respond(w, map[string]any{"content": resp}, http.StatusOK, err)
}

func decode(w http.ResponseWriter, r *http.Request, target any) bool {
	defer r.Body.Close()
	if err := json.NewDecoder(r.Body).Decode(target); err != nil {
		writeError(w, http.StatusBadRequest, "ERR_VALIDATION", "Neispravni podaci", err.Error())
		return false
	}
	return true
}

func respond(w http.ResponseWriter, payload any, status int, err error) {
	if err != nil {
		handleError(w, err)
		return
	}
	writeJSON(w, status, payload)
}

func respondNoContent(w http.ResponseWriter, err error) {
	if err != nil {
		handleError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func handleError(w http.ResponseWriter, err error) {
	var serviceErr *service.Error
	if errors.As(err, &serviceErr) {
		writeError(w, serviceErr.Status, serviceErr.Code, serviceErr.Title, serviceErr.Message)
		return
	}
	writeError(w, http.StatusInternalServerError, "ERR_INTERNAL", "Interna greska", err.Error())
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func writeError(w http.ResponseWriter, status int, code, title, desc string) {
	writeJSON(w, status, map[string]any{
		"errorCode":  code,
		"errorTitle": title,
		"errorDesc":  desc,
		"timestamp":  time.Now().Format("2006-01-02T15:04:05.999999999"),
	})
}

func parseIntPath(w http.ResponseWriter, raw string) (int64, bool) {
	raw = strings.Trim(raw, "/")
	id, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "ERR_VALIDATION", "Neispravni podaci", "neispravan id")
		return 0, false
	}
	return id, true
}

func trimPrefixSuffix(path, prefix, suffix string) (string, bool) {
	if !strings.HasPrefix(path, prefix) || !strings.HasSuffix(path, suffix) {
		return "", false
	}
	return strings.TrimSuffix(strings.TrimPrefix(path, prefix), suffix), true
}

func correlationID(r *http.Request) string {
	if v := r.Header.Get("X-Correlation-Id"); v != "" {
		return v
	}
	return "no-correlation"
}

func parseRawDecimal(raw json.RawMessage) (decimal.Decimal, error) {
	var d decimal.Decimal
	if err := d.UnmarshalJSON(raw); err != nil {
		return d, err
	}
	return d, nil
}

// isKnownPath returns true when the path matches a registered route (ignoring HTTP method).
// Used to return 405 instead of 404 when the path is valid but the method is wrong.
func isKnownPath(path string) bool {
	switch path {
	case
		"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info", "/actuator/prometheus",
		"/verification/generate", "/verification/validate",
		"/accounts/api/currencies/getAll", "/accounts/api/currencies/getAllPage", "/accounts/api/currencies",
		"/accounts/api/sifra-delatnosti",
		"/accounts/employee/accounts/checking", "/accounts/employee/accounts/fx",
		"/accounts/employee/accounts", "/accounts/employee/accounts/bank",
		"/accounts/client/accounts",
		"/api/cards/auto", "/api/cards/request", "/api/cards/request/business", "/api/cards/all",
		"/transactions/payment", "/transactions/payments",
		"/transactions/by-client", "/transactions/by-sender-client", "/transactions/by-recipient-client",
		"/transactions/by-this-client", "/transactions/by-this-sender-client", "/transactions/by-this-recipient-client",
		"/transactions/api/payments",
		"/payment-recipients",
		"/transfers", "/transfers/",
		"/accounts/createMarginAccount", "/accounts/company/createMarginAccount",
		"/transactions/stockBuyMarginTransaction", "/transactions/stockSellMarginTransaction",
		"/transactions/internal/reserve-funds", "/transactions/internal/transfer",
		"/internal/interbank/reserve-monas", "/internal/interbank/account-by-owner", "/internal/interbank/account-resolve",
		"/internal/interbank/record-payment",
		"/internal/accounts/transaction", "/internal/accounts/exchange/buy", "/internal/accounts/exchange/sell",
		"/internal/accounts/transactionFromBank", "/internal/accounts/debit", "/internal/accounts/credit",
		"/internal/accounts/creditBank", "/internal/accounts/debitBank",
		"/internal/accounts/transfer", "/internal/accounts/info", "/internal/accounts/system":
		return true
	}
	for _, prefix := range []string{
		"/verification/",
		"/accounts/api/currencies/",
		"/accounts/employee/accounts/",
		"/accounts/employee/companies/",
		"/accounts/client/api/accounts/",
		"/accounts/client/accounts/",
		"/accounts/getMarginUser/",
		"/accounts/company/getMarginCompany/",
		"/accounts/internal/by-owner/",
		"/accounts/internal/default/",
		"/api/cards/client/",
		"/api/cards/id/",
		"/api/cards/account/",
		"/api/cards/internal/account/",
		"/transactions/employee/accounts/",
		"/transactions/accounts/",
		"/transactions/addToMargin/",
		"/transactions/withdrawFromMargin/",
		"/transactions/getAllMarginTransactions/",
		"/transactions/internal/reservations/",
		"/transactions/internal/transfers/",
		"/payment-recipients/",
		"/transfers/accounts/",
		"/transfers/",
		"/internal/interbank/reservations/",
		"/internal/accounts/state/",
		"/internal/accounts/id/",
		"/internal/accounts/bank/",
		"/internal/accounts/",
		"/employee/accounts/client/",
	} {
		if strings.HasPrefix(path, prefix) {
			return true
		}
	}
	return false
}
