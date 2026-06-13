package api

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
	"github.com/raf-si-2025/banka-1-go/shared/auth"
)

// ---------------------------------------------------------------------------
// Interfaces (seams for testing)
// ---------------------------------------------------------------------------

// InboundExecutor is the subset of *service.Executor that InboundHandler needs.
type InboundExecutor interface {
	PrepareLocal(ctx context.Context, tx protocol.InterbankTransactionPayload) (protocol.TransactionVote, error)
	CommitLocal(ctx context.Context, txID protocol.ForeignBankId) error
	RollbackLocal(ctx context.Context, txID protocol.ForeignBankId) error
}

// InboundMessageStore is the subset of *store.MessageStore that InboundHandler needs.
type InboundMessageStore interface {
	Lookup(ctx context.Context, direction string, senderRouting int, key string) (*store.Message, error)
	Insert(ctx context.Context, m *store.Message) error
}

// BuyerContractRecorder records the buyer's local option contract after a
// successful inbound COMMIT_TX (cross-bank OTC option buy). Optional — when nil,
// the inbound path behaves exactly as before. Implemented by
// *service.BuyerContractRecorder.
type BuyerContractRecorder interface {
	RecordOnCommit(ctx context.Context, txID protocol.ForeignBankId)
}

// ---------------------------------------------------------------------------
// InboundHandler
// ---------------------------------------------------------------------------

// InboundHandler handles POST /interbank.
// Corresponds to Java InterbankInboundController + InboundDispatcher.
type InboundHandler struct {
	executor InboundExecutor
	messages InboundMessageStore
	recorder BuyerContractRecorder // optional — records buyer-side option contracts on commit
	log      *slog.Logger
}

// NewInboundHandler constructs the handler. recorder may be nil; when set, it is
// invoked after a successful inbound COMMIT_TX to record the buyer's local
// option contract for a cross-bank OTC option buy.
func NewInboundHandler(executor InboundExecutor, messages InboundMessageStore, recorder BuyerContractRecorder, log *slog.Logger) *InboundHandler {
	if log == nil {
		log = slog.Default()
	}
	return &InboundHandler{executor: executor, messages: messages, recorder: recorder, log: log}
}

// PostMessage handles POST /interbank.
func (h *InboundHandler) PostMessage(w http.ResponseWriter, r *http.Request) {
	// Partner is guaranteed to be present — RequireXApiKey middleware ran first.
	partner, ok := auth.GetPartner(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "missing partner context")
		return
	}
	senderRouting := partner.Routing

	// Decode envelope.
	var msg protocol.InterbankMessagePayload
	if err := json.NewDecoder(r.Body).Decode(&msg); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body: "+err.Error())
		return
	}

	// Routing-number mismatch check (partner impersonation guard).
	if senderRouting != msg.IdempotenceKey.RoutingNumber {
		h.log.WarnContext(r.Context(), "inbound: routing mismatch",
			"senderRouting", senderRouting,
			"idempotenceKey.routingNumber", msg.IdempotenceKey.RoutingNumber)
		writeError(w, http.StatusBadRequest,
			"idempotenceKey.routingNumber mismatches X-Api-Key sender")
		return // do NOT cache routing-mismatch failures
	}

	// Spec §2.2: locallyGeneratedKey is at most 64 bytes. Reject over-length keys
	// with 400 and WITHOUT caching — an over-length key must never become a cached
	// 200/204. Mirrors Banka 2 InterbankInboundController MAX_KEY_LENGTH=64.
	if len(msg.IdempotenceKey.LocallyGeneratedKey) > 64 {
		writeError(w, http.StatusBadRequest,
			"idempotenceKey.locallyGeneratedKey exceeds maximum length of 64 bytes")
		return // do NOT cache malformed-key failures
	}

	// Idempotency cache lookup.
	cached, err := h.messages.Lookup(r.Context(), store.DirectionInbound, senderRouting, msg.IdempotenceKey.LocallyGeneratedKey)
	if err != nil {
		h.log.ErrorContext(r.Context(), "inbound: cache lookup error", "err", err)
		writeError(w, http.StatusInternalServerError, "cache lookup failed")
		return
	}
	if cached != nil {
		// Type-aware idempotency (Banka 2 §2.2): the (senderRouting, key) pair is
		// unique PER MESSAGE. If the same key was already used for a DIFFERENT
		// messageType (e.g. a COMMIT_TX arriving under a key that cached a NEW_TX
		// vote), replaying the cached vote would skip the commit and strand money.
		// Reject the mismatch as a protocol violation (400) instead of replaying.
		if cached.MessageType != "" && cached.MessageType != string(msg.MessageType) {
			h.log.WarnContext(r.Context(), "inbound: idempotency key reused for a different messageType",
				"senderRouting", senderRouting,
				"key", msg.IdempotenceKey.LocallyGeneratedKey,
				"cachedType", cached.MessageType,
				"newType", string(msg.MessageType))
			writeError(w, http.StatusBadRequest,
				"idempotenceKey already used for a "+cached.MessageType+
					" message; cannot reuse it for "+string(msg.MessageType))
			return
		}
		h.log.InfoContext(r.Context(), "inbound: idempotency cache hit",
			"senderRouting", senderRouting,
			"key", msg.IdempotenceKey.LocallyGeneratedKey,
			"httpStatus", cached.HttpStatus)
		replayFromCache(w, cached)
		return
	}

	// Dispatch on message type.
	switch msg.MessageType {
	case protocol.MessageTypeNewTx:
		h.handleNewTx(w, r, msg, senderRouting)
	case protocol.MessageTypeCommitTx:
		h.handleCommitTx(w, r, msg, senderRouting)
	case protocol.MessageTypeRollbackTx:
		h.handleRollbackTx(w, r, msg, senderRouting)
	default:
		writeError(w, http.StatusBadRequest, "unknown messageType: "+string(msg.MessageType))
	}
}

func (h *InboundHandler) handleNewTx(w http.ResponseWriter, r *http.Request, msg protocol.InterbankMessagePayload, senderRouting int) {
	tx, ok := msg.Message.(*protocol.InterbankTransactionPayload)
	if !ok || tx == nil {
		h.persistAndReturnError(w, r.Context(), msg, senderRouting, http.StatusBadRequest, "NEW_TX: missing or invalid transaction payload")
		return
	}

	vote, err := h.executor.PrepareLocal(r.Context(), *tx)
	if err != nil {
		h.persistAndReturnError(w, r.Context(), msg, senderRouting, http.StatusInternalServerError, err.Error())
		return
	}

	body, marshalErr := json.Marshal(vote)
	if marshalErr != nil {
		h.persistAndReturnError(w, r.Context(), msg, senderRouting, http.StatusInternalServerError, marshalErr.Error())
		return
	}

	// Cache the response (IMPORTANT per Tim 2 §2.2).
	h.persistCache(r.Context(), msg, senderRouting, http.StatusOK, string(body))

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(body)
}

func (h *InboundHandler) handleCommitTx(w http.ResponseWriter, r *http.Request, msg protocol.InterbankMessagePayload, senderRouting int) {
	body, ok := msg.Message.(*protocol.CommitTransactionBody)
	if !ok || body == nil {
		h.persistAndReturnError(w, r.Context(), msg, senderRouting, http.StatusBadRequest, "COMMIT_TX: missing or invalid body")
		return
	}

	if err := h.executor.CommitLocal(r.Context(), body.TransactionId); err != nil {
		h.persistAndReturnError(w, r.Context(), msg, senderRouting, http.StatusInternalServerError, err.Error())
		return
	}

	// Buyer-side option contract recording: when this committed tx carried an
	// option receipt for one of our persons (we BOUGHT a cross-bank option),
	// record a local ACTIVE contract so the buyer can see/exercise it. Best-effort
	// and idempotent — the 2PC commit has already succeeded, so a recording
	// failure must not turn this COMMIT_TX into a 5xx.
	if h.recorder != nil {
		h.recorder.RecordOnCommit(r.Context(), body.TransactionId)
	}

	h.persistCache(r.Context(), msg, senderRouting, http.StatusNoContent, "")
	w.WriteHeader(http.StatusNoContent)
}

func (h *InboundHandler) handleRollbackTx(w http.ResponseWriter, r *http.Request, msg protocol.InterbankMessagePayload, senderRouting int) {
	body, ok := msg.Message.(*protocol.RollbackTransactionBody)
	if !ok || body == nil {
		h.persistAndReturnError(w, r.Context(), msg, senderRouting, http.StatusBadRequest, "ROLLBACK_TX: missing or invalid body")
		return
	}

	if err := h.executor.RollbackLocal(r.Context(), body.TransactionId); err != nil {
		h.persistAndReturnError(w, r.Context(), msg, senderRouting, http.StatusInternalServerError, err.Error())
		return
	}

	h.persistCache(r.Context(), msg, senderRouting, http.StatusNoContent, "")
	w.WriteHeader(http.StatusNoContent)
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

// replayFromCache replays the cached HTTP status + body verbatim.
func replayFromCache(w http.ResponseWriter, cached *store.Message) {
	status := http.StatusOK
	if cached.HttpStatus != nil {
		status = *cached.HttpStatus
	}
	if status == http.StatusNoContent {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if cached.ResponseBody != nil && *cached.ResponseBody != "" {
		_, _ = w.Write([]byte(*cached.ResponseBody))
	}
}

// persistAndReturnError caches an error response and writes it to the response writer.
// Per Tim 2 §2.2 IMPORTANT-1: error responses must be cached so retries get the same error.
func (h *InboundHandler) persistAndReturnError(w http.ResponseWriter, ctx context.Context, msg protocol.InterbankMessagePayload, senderRouting, status int, errMsg string) {
	body := `{"error":"` + escapeJSON(errMsg) + `"}`
	h.persistCacheSilent(ctx, msg, senderRouting, status, body)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(body))
}

// persistCache inserts the idempotency cache row. Logs on failure.
func (h *InboundHandler) persistCache(ctx context.Context, msg protocol.InterbankMessagePayload, senderRouting, httpStatus int, responseBody string) {
	var rb *string
	if responseBody != "" {
		rb = &responseBody
	}
	m := buildMessage(msg, senderRouting, httpStatus, rb)
	if err := h.messages.Insert(ctx, m); err != nil {
		if !store.IsUniqueViolation(err) {
			h.log.WarnContext(ctx, "inbound: failed to persist idempotency cache",
				"key", msg.IdempotenceKey.LocallyGeneratedKey, "err", err)
		}
		// Unique violation: concurrent identical request already cached — safe to ignore.
	}
}

// persistCacheSilent is like persistCache but for error paths.
func (h *InboundHandler) persistCacheSilent(ctx context.Context, msg protocol.InterbankMessagePayload, senderRouting, httpStatus int, responseBody string) {
	rb := responseBody
	m := buildMessage(msg, senderRouting, httpStatus, &rb)
	if err := h.messages.Insert(ctx, m); err != nil && !store.IsUniqueViolation(err) {
		h.log.WarnContext(ctx, "inbound: failed to persist error cache",
			"key", msg.IdempotenceKey.LocallyGeneratedKey, "err", err)
	}
}

func buildMessage(msg protocol.InterbankMessagePayload, senderRouting, httpStatus int, responseBody *string) *store.Message {
	reqBody, _ := json.Marshal(msg)
	hs := httpStatus
	return &store.Message{
		Direction:           store.DirectionInbound,
		SenderRoutingNumber: senderRouting,
		LocallyGeneratedKey: msg.IdempotenceKey.LocallyGeneratedKey,
		MessageType:         string(msg.MessageType),
		Status:              statusForHTTP(httpStatus),
		RequestBody:         string(reqBody),
		ResponseBody:        responseBody,
		HttpStatus:          &hs,
	}
}

func statusForHTTP(httpStatus int) string {
	if httpStatus >= 400 {
		return store.MessageStatusError
	}
	return store.MessageStatusProcessed
}

// escapeJSON minimally escapes a string for JSON embedding.
func escapeJSON(s string) string {
	b, _ := json.Marshal(s)
	// json.Marshal adds surrounding quotes; strip them.
	if len(b) >= 2 {
		return string(b[1 : len(b)-1])
	}
	return s
}

// writeError writes a JSON error response.
func writeError(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(`{"error":"` + escapeJSON(msg) + `"}`))
}
