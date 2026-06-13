package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"github.com/go-chi/chi/v5"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
	"github.com/raf-si-2025/banka-1-go/shared/auth"
)

// ---------------------------------------------------------------------------
// fakeInboundExecutor
// ---------------------------------------------------------------------------

type fakeInboundExecutor struct {
	prepareVote protocol.TransactionVote
	prepareErr  error
	commitErr   error
	rollbackErr error
}

func (f *fakeInboundExecutor) PrepareLocal(_ context.Context, _ protocol.InterbankTransactionPayload) (protocol.TransactionVote, error) {
	return f.prepareVote, f.prepareErr
}

func (f *fakeInboundExecutor) CommitLocal(_ context.Context, _ protocol.ForeignBankId) error {
	return f.commitErr
}

func (f *fakeInboundExecutor) RollbackLocal(_ context.Context, _ protocol.ForeignBankId) error {
	return f.rollbackErr
}

// ---------------------------------------------------------------------------
// fakeInboundMessageStore
// ---------------------------------------------------------------------------

type fakeInboundMessageStore struct {
	mu       sync.Mutex
	cached   map[string]*store.Message
	insertErr error
}

func newFakeInboundMessageStore() *fakeInboundMessageStore {
	return &fakeInboundMessageStore{cached: make(map[string]*store.Message)}
}

func (f *fakeInboundMessageStore) Lookup(_ context.Context, _ string, _ int, key string) (*store.Message, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if m, ok := f.cached[key]; ok {
		cp := *m
		return &cp, nil
	}
	return nil, nil
}

func (f *fakeInboundMessageStore) Insert(_ context.Context, m *store.Message) error {
	if f.insertErr != nil {
		return f.insertErr
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	cp := *m
	f.cached[m.LocallyGeneratedKey] = &cp
	return nil
}

// ---------------------------------------------------------------------------
// Test helpers
// ---------------------------------------------------------------------------

const (
	testMyRouting     = 111
	testTheirRouting  = 222
	testApiKey        = "test-key"
)

func buildTestRouter(exec InboundExecutor, msgs InboundMessageStore) http.Handler {
	partners := &staticPartnerStore{partners: []auth.Partner{{
		Routing:      testTheirRouting,
		InboundToken: testApiKey,
	}}}
	h := NewInboundHandler(exec, msgs, nil, nil)
	r := chi.NewRouter()
	r.Group(func(r chi.Router) {
		r.Use(auth.RequireXApiKey(partners))
		r.Post("/interbank", h.PostMessage)
	})
	return r
}

type staticPartnerStore struct{ partners []auth.Partner }

func (s *staticPartnerStore) Partners() []auth.Partner { return s.partners }

func newTxBody(key string, senderRouting int) []byte {
	msg := map[string]any{
		"idempotenceKey": map[string]any{
			"routingNumber":       senderRouting,
			"locallyGeneratedKey": key,
		},
		"messageType": "NEW_TX",
		"message": map[string]any{
			"transactionId": map[string]any{"routingNumber": senderRouting, "id": "tx-001"},
			"postings": []any{
				map[string]any{
					"account": map[string]any{"type": "ACCOUNT", "num": "222000000000000001"},
					"amount":  "-100.00",
					"asset":   map[string]any{"type": "MONAS", "asset": map[string]any{"currency": "USD"}},
				},
				map[string]any{
					"account": map[string]any{"type": "ACCOUNT", "num": "111000001234567890"},
					"amount":  "100.00",
					"asset":   map[string]any{"type": "MONAS", "asset": map[string]any{"currency": "USD"}},
				},
			},
		},
	}
	b, _ := json.Marshal(msg)
	return b
}

func commitBody(key string, senderRouting int, txLocal string) []byte {
	msg := map[string]any{
		"idempotenceKey": map[string]any{
			"routingNumber":       senderRouting,
			"locallyGeneratedKey": key,
		},
		"messageType": "COMMIT_TX",
		"message": map[string]any{
			"transactionId": map[string]any{"routingNumber": senderRouting, "id": txLocal},
		},
	}
	b, _ := json.Marshal(msg)
	return b
}

func rollbackBody(key string, senderRouting int, txLocal string) []byte {
	msg := map[string]any{
		"idempotenceKey": map[string]any{
			"routingNumber":       senderRouting,
			"locallyGeneratedKey": key,
		},
		"messageType": "ROLLBACK_TX",
		"message": map[string]any{
			"transactionId": map[string]any{"routingNumber": senderRouting, "id": txLocal},
		},
	}
	b, _ := json.Marshal(msg)
	return b
}

func doPost(r http.Handler, path string, body []byte, apiKey string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if apiKey != "" {
		req.Header.Set("X-Api-Key", apiKey)
	}
	rr := httptest.NewRecorder()
	r.ServeHTTP(rr, req)
	return rr
}

// ---------------------------------------------------------------------------
// Tests: POST /interbank NEW_TX
// ---------------------------------------------------------------------------

func TestInbound_NewTx_HappyYes(t *testing.T) {
	exec := &fakeInboundExecutor{
		prepareVote: protocol.TransactionVote{Vote: protocol.VoteYes},
	}
	msgs := newFakeInboundMessageStore()
	r := buildTestRouter(exec, msgs)

	rr := doPost(r, "/interbank", newTxBody("key-001", testTheirRouting), testApiKey)
	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body: %s", rr.Code, rr.Body.String())
	}
	var vote protocol.TransactionVote
	if err := json.Unmarshal(rr.Body.Bytes(), &vote); err != nil {
		t.Fatalf("unmarshal vote: %v", err)
	}
	if vote.Vote != protocol.VoteYes {
		t.Errorf("expected YES, got %s", vote.Vote)
	}
}

func TestInbound_NewTx_IdempotencyReplay(t *testing.T) {
	exec := &fakeInboundExecutor{
		prepareVote: protocol.TransactionVote{Vote: protocol.VoteYes},
	}
	msgs := newFakeInboundMessageStore()
	r := buildTestRouter(exec, msgs)

	body := newTxBody("key-idem-001", testTheirRouting)
	// First call.
	rr1 := doPost(r, "/interbank", body, testApiKey)
	if rr1.Code != http.StatusOK {
		t.Fatalf("first call expected 200, got %d", rr1.Code)
	}
	// Second call with same key — should replay from cache without calling executor again.
	exec.prepareErr = nil // if executor was called again it would still succeed, but let's change vote
	exec.prepareVote = protocol.TransactionVote{Vote: protocol.VoteNo}
	rr2 := doPost(r, "/interbank", body, testApiKey)
	if rr2.Code != http.StatusOK {
		t.Fatalf("idempotency replay expected 200, got %d", rr2.Code)
	}
	// Response body should be the cached YES vote, not new NO vote.
	var vote protocol.TransactionVote
	json.Unmarshal(rr2.Body.Bytes(), &vote)
	if vote.Vote != protocol.VoteYes {
		t.Errorf("expected cached YES vote on replay, got %s", vote.Vote)
	}
}

func TestInbound_NewTx_RoutingMismatch(t *testing.T) {
	exec := &fakeInboundExecutor{
		prepareVote: protocol.TransactionVote{Vote: protocol.VoteYes},
	}
	msgs := newFakeInboundMessageStore()
	r := buildTestRouter(exec, msgs)

	// senderRouting in body = 999 != testTheirRouting (222).
	body := newTxBody("key-mismatch", 999)
	rr := doPost(r, "/interbank", body, testApiKey)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 on routing mismatch, got %d", rr.Code)
	}
}

func TestInbound_NoApiKey_401(t *testing.T) {
	exec := &fakeInboundExecutor{}
	msgs := newFakeInboundMessageStore()
	r := buildTestRouter(exec, msgs)
	rr := doPost(r, "/interbank", newTxBody("key-noauth", testTheirRouting), "")
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", rr.Code)
	}
}

// ---------------------------------------------------------------------------
// Tests: COMMIT_TX
// ---------------------------------------------------------------------------

func TestInbound_CommitTx_Happy(t *testing.T) {
	exec := &fakeInboundExecutor{}
	msgs := newFakeInboundMessageStore()
	r := buildTestRouter(exec, msgs)

	body := commitBody("key-commit-01", testTheirRouting, "tx-abc")
	rr := doPost(r, "/interbank", body, testApiKey)
	if rr.Code != http.StatusNoContent {
		t.Fatalf("expected 204, got %d body: %s", rr.Code, rr.Body.String())
	}
}

func TestInbound_CommitTx_IdempotencyReplay(t *testing.T) {
	exec := &fakeInboundExecutor{}
	msgs := newFakeInboundMessageStore()
	r := buildTestRouter(exec, msgs)

	body := commitBody("key-commit-idem", testTheirRouting, "tx-idem")
	doPost(r, "/interbank", body, testApiKey)                // first call
	rr := doPost(r, "/interbank", body, testApiKey)          // second call
	if rr.Code != http.StatusNoContent {
		t.Fatalf("idempotency replay expected 204, got %d", rr.Code)
	}
}

// ---------------------------------------------------------------------------
// Tests: ROLLBACK_TX
// ---------------------------------------------------------------------------

func TestInbound_RollbackTx_Happy(t *testing.T) {
	exec := &fakeInboundExecutor{}
	msgs := newFakeInboundMessageStore()
	r := buildTestRouter(exec, msgs)

	body := rollbackBody("key-rollback-01", testTheirRouting, "tx-xyz")
	rr := doPost(r, "/interbank", body, testApiKey)
	if rr.Code != http.StatusNoContent {
		t.Fatalf("expected 204, got %d body: %s", rr.Code, rr.Body.String())
	}
}

// ---------------------------------------------------------------------------
// Tests: protocol-compatibility guards (Banka 2 interop)
// ---------------------------------------------------------------------------

// A locallyGeneratedKey longer than 64 bytes must be rejected with 400 and NOT
// cached (Banka 2 §2.2). Regression guard for the missing length check.
func TestInbound_KeyTooLong_400(t *testing.T) {
	exec := &fakeInboundExecutor{prepareVote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	msgs := newFakeInboundMessageStore()
	r := buildTestRouter(exec, msgs)

	longKey := ""
	for i := 0; i < 65; i++ {
		longKey += "x"
	}
	rr := doPost(r, "/interbank", newTxBody(longKey, testTheirRouting), testApiKey)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for >64-byte key, got %d body: %s", rr.Code, rr.Body.String())
	}
	// Must NOT have been cached.
	if cached, _ := msgs.Lookup(context.Background(), store.DirectionInbound, testTheirRouting, longKey); cached != nil {
		t.Errorf("over-length key must not be cached")
	}
}

// Reusing one idempotency key across two DIFFERENT messageTypes (e.g. NEW_TX
// then COMMIT_TX under the same key) must return 400, not replay the cached
// vote — otherwise a COMMIT_TX would replay a NEW_TX vote and strand money
// (Banka 2 §2.2 type-aware idempotency).
func TestInbound_KeyReusedDifferentType_400(t *testing.T) {
	exec := &fakeInboundExecutor{prepareVote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	msgs := newFakeInboundMessageStore()
	r := buildTestRouter(exec, msgs)

	const key = "shared-key-1"
	// 1) NEW_TX caches a vote under `key`.
	rr1 := doPost(r, "/interbank", newTxBody(key, testTheirRouting), testApiKey)
	if rr1.Code != http.StatusOK {
		t.Fatalf("NEW_TX expected 200, got %d", rr1.Code)
	}
	// 2) COMMIT_TX reusing the SAME key — must be 400 (type mismatch), not 204.
	rr2 := doPost(r, "/interbank", commitBody(key, testTheirRouting, "tx-abc"), testApiKey)
	if rr2.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 on key reuse across messageType, got %d body: %s", rr2.Code, rr2.Body.String())
	}
}
