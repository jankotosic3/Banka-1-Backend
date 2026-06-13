package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/go-chi/chi/v5"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/service"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
	"github.com/raf-si-2025/banka-1-go/shared/auth"
)

// ---------------------------------------------------------------------------
// Inbound error / edge paths
// ---------------------------------------------------------------------------

// erroringMessageStore allows controlling Lookup and Insert errors.
type erroringMessageStore struct {
	lookupErr error
	insertErr error
}

func (e *erroringMessageStore) Lookup(_ context.Context, _ string, _ int, _ string) (*store.Message, error) {
	return nil, e.lookupErr
}

func (e *erroringMessageStore) Insert(_ context.Context, _ *store.Message) error {
	return e.insertErr
}

func TestInbound_CacheLookupError_500(t *testing.T) {
	exec := &fakeInboundExecutor{prepareVote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	msgs := &erroringMessageStore{lookupErr: errors.New("db down")}
	r := buildTestRouter(exec, msgs)
	rr := doPost(r, "/interbank", newTxBody("k-lookuperr", testTheirRouting), testApiKey)
	if rr.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500 on cache lookup error, got %d", rr.Code)
	}
}

func TestInbound_NewTx_PrepareError_500(t *testing.T) {
	// This exercises persistAndReturnError + persistCacheSilent.
	exec := &fakeInboundExecutor{prepareErr: errors.New("prepare boom")}
	msgs := newFakeInboundMessageStore()
	r := buildTestRouter(exec, msgs)
	rr := doPost(r, "/interbank", newTxBody("k-prepfail", testTheirRouting), testApiKey)
	if rr.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500 on prepare error, got %d body: %s", rr.Code, rr.Body.String())
	}
	// Error body must be cached so a replay returns the cached error.
	rr2 := doPost(r, "/interbank", newTxBody("k-prepfail", testTheirRouting), testApiKey)
	if rr2.Code != http.StatusInternalServerError {
		t.Fatalf("expected cached 500 on replay, got %d", rr2.Code)
	}
}

func TestInbound_CommitTx_Error_500(t *testing.T) {
	exec := &fakeInboundExecutor{commitErr: errors.New("commit boom")}
	msgs := newFakeInboundMessageStore()
	r := buildTestRouter(exec, msgs)
	rr := doPost(r, "/interbank", commitBody("k-commiterr", testTheirRouting, "tx-1"), testApiKey)
	if rr.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500 on commit error, got %d", rr.Code)
	}
}

func TestInbound_RollbackTx_Error_500(t *testing.T) {
	exec := &fakeInboundExecutor{rollbackErr: errors.New("rollback boom")}
	msgs := newFakeInboundMessageStore()
	r := buildTestRouter(exec, msgs)
	rr := doPost(r, "/interbank", rollbackBody("k-rberr", testTheirRouting, "tx-1"), testApiKey)
	if rr.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500 on rollback error, got %d", rr.Code)
	}
}

func TestInbound_UnknownMessageType_400(t *testing.T) {
	exec := &fakeInboundExecutor{}
	msgs := newFakeInboundMessageStore()
	r := buildTestRouter(exec, msgs)
	body := map[string]any{
		"idempotenceKey": map[string]any{
			"routingNumber":       testTheirRouting,
			"locallyGeneratedKey": "k-unknown",
		},
		"messageType": "WAT_TX",
		"message":     map[string]any{},
	}
	b, _ := json.Marshal(body)
	rr := doPost(r, "/interbank", b, testApiKey)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for unknown message type, got %d", rr.Code)
	}
}

func TestInbound_InvalidJSON_400(t *testing.T) {
	exec := &fakeInboundExecutor{}
	msgs := newFakeInboundMessageStore()
	r := buildTestRouter(exec, msgs)
	rr := doPost(r, "/interbank", []byte("{not json"), testApiKey)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for invalid JSON, got %d", rr.Code)
	}
}

func TestInbound_NewTx_InsertError_StillSucceeds(t *testing.T) {
	// Insert failure on the success path is logged but the response is still 200.
	exec := &fakeInboundExecutor{prepareVote: protocol.TransactionVote{Vote: protocol.VoteYes}}
	msgs := &erroringMessageStore{insertErr: errors.New("insert failed")}
	r := buildTestRouter(exec, msgs)
	rr := doPost(r, "/interbank", newTxBody("k-insfail", testTheirRouting), testApiKey)
	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200 despite insert failure, got %d", rr.Code)
	}
}

// statusForHTTP / escapeJSON direct tests for the remaining branches.
func TestStatusForHTTP(t *testing.T) {
	if got := statusForHTTP(200); got != store.MessageStatusProcessed {
		t.Errorf("200 → %q", got)
	}
	if got := statusForHTTP(500); got != store.MessageStatusError {
		t.Errorf("500 → %q", got)
	}
}

func TestEscapeJSON(t *testing.T) {
	if got := escapeJSON(`a"b`); got != `a\"b` {
		t.Errorf("got %q", got)
	}
	if got := escapeJSON(""); got != "" {
		t.Errorf("empty got %q", got)
	}
}

// ---------------------------------------------------------------------------
// replayFromCache: 204 branch
// ---------------------------------------------------------------------------

func TestInbound_Replay204(t *testing.T) {
	exec := &fakeInboundExecutor{}
	msgs := newFakeInboundMessageStore()
	// Pre-seed a 204 cached COMMIT result.
	status := http.StatusNoContent
	msgs.cached["k-pre204"] = &store.Message{
		LocallyGeneratedKey: "k-pre204",
		HttpStatus:          &status,
	}
	r := buildTestRouter(exec, msgs)
	rr := doPost(r, "/interbank", commitBody("k-pre204", testTheirRouting, "tx"), testApiKey)
	if rr.Code != http.StatusNoContent {
		t.Fatalf("expected replay 204, got %d", rr.Code)
	}
}

// ---------------------------------------------------------------------------
// OTC handler: invalid JSON body and error mappings
// ---------------------------------------------------------------------------

func TestOtc_Create_InvalidJSON_400(t *testing.T) {
	svc := &fakeOtcService{}
	r := buildOtcRouter(svc)
	rr := doMethod(r, http.MethodPost, "/negotiations", []byte("{bad"), testApiKey)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for invalid JSON, got %d", rr.Code)
	}
}

func TestOtc_Counter_InvalidJSON_400(t *testing.T) {
	svc := &fakeOtcService{}
	r := buildOtcRouter(svc)
	rr := doMethod(r, http.MethodPut, "/negotiations/111/neg-1", []byte("{bad"), testApiKey)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for invalid JSON, got %d", rr.Code)
	}
}

func TestOtc_Get_GenericError_500(t *testing.T) {
	svc := &fakeOtcService{getErr: errors.New("boom")}
	r := buildOtcRouter(svc)
	rr := doMethod(r, http.MethodGet, "/negotiations/111/neg-1", nil, testApiKey)
	if rr.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500 for generic error, got %d", rr.Code)
	}
}

func TestOtc_Create_SenderNotParty_403(t *testing.T) {
	svc := &fakeOtcService{createErr: service.ErrSenderNotParty}
	r := buildOtcRouter(svc)
	rr := doMethod(r, http.MethodPost, "/negotiations", otcOfferBody(), testApiKey)
	if rr.Code != http.StatusForbidden {
		t.Fatalf("expected 403, got %d", rr.Code)
	}
}

// pathRnID: non-numeric routing returns rn=0.
func TestPathRnID_NonNumeric(t *testing.T) {
	r := chi.NewRouter()
	var gotRn int
	var gotID string
	r.Get("/x/{rn}/{id}", func(w http.ResponseWriter, req *http.Request) {
		gotRn, gotID = pathRnID(req)
		w.WriteHeader(http.StatusOK)
	})
	req := httptest.NewRequest(http.MethodGet, "/x/abc/neg-1", nil)
	rr := httptest.NewRecorder()
	r.ServeHTTP(rr, req)
	if gotRn != 0 || gotID != "neg-1" {
		t.Errorf("got rn=%d id=%q", gotRn, gotID)
	}
}

// ---------------------------------------------------------------------------
// public-stock error
// ---------------------------------------------------------------------------

func TestPublicStock_Error_500(t *testing.T) {
	svc := &fakePublicStockService{err: errors.New("boom")}
	r := buildPublicStockRouter(svc)
	rr := doGet(r, "/public-stock", testApiKey)
	if rr.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500, got %d", rr.Code)
	}
}

// ---------------------------------------------------------------------------
// user-display extra branches
// ---------------------------------------------------------------------------

func TestUserDisplay_BadRouting_400(t *testing.T) {
	resolver := &fakeUserResolver{}
	r := buildUserDisplayRouter(testMyRouting, resolver)
	rr := doGet(r, "/interbank/user/notanumber/C-1", testApiKey)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for non-numeric routing, got %d", rr.Code)
	}
}

func TestUserDisplay_ShortID_400(t *testing.T) {
	resolver := &fakeUserResolver{}
	r := buildUserDisplayRouter(testMyRouting, resolver)
	rr := doGet(r, "/interbank/user/111/C", testApiKey)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for short id, got %d", rr.Code)
	}
}

func TestUserDisplay_NonNumericSuffix_400(t *testing.T) {
	resolver := &fakeUserResolver{}
	r := buildUserDisplayRouter(testMyRouting, resolver)
	rr := doGet(r, "/interbank/user/111/C-xx", testApiKey)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for non-numeric suffix, got %d", rr.Code)
	}
}

func TestUserDisplay_NilInfo_404(t *testing.T) {
	resolver := &fakeUserResolver{info: nil, err: nil}
	r := buildUserDisplayRouter(testMyRouting, resolver)
	rr := doGet(r, "/interbank/user/111/C-5", testApiKey)
	if rr.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for nil info, got %d", rr.Code)
	}
}

// ---------------------------------------------------------------------------
// claim helpers
// ---------------------------------------------------------------------------

func TestClaimToInt64(t *testing.T) {
	cases := []struct {
		in   any
		want int64
	}{
		{nil, 0},
		{float64(42), 42},
		{int64(7), 7},
		{int(9), 9},
		{json.Number("123"), 123},
		{"456", 456},
		{"notnum", 0},
	}
	for _, c := range cases {
		if got := claimToInt64(c.in); got != c.want {
			t.Errorf("claimToInt64(%v) = %d, want %d", c.in, got, c.want)
		}
	}
}

func TestExtractPrincipalID_NoClaims(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	if got := extractPrincipalID(req); got != 0 {
		t.Errorf("expected 0 without claims, got %d", got)
	}
}

func TestExtractPrincipalID_WithClaims(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req = req.WithContext(auth.PutClaims(req.Context(), &auth.Claims{ID: int64(99)}))
	if got := extractPrincipalID(req); got != 99 {
		t.Errorf("expected 99, got %d", got)
	}
}

func TestHasAdminOrSupervisor(t *testing.T) {
	reqNo := httptest.NewRequest(http.MethodGet, "/", nil)
	if hasAdminOrSupervisor(reqNo) {
		t.Error("no claims should be false")
	}
	reqClient := httptest.NewRequest(http.MethodGet, "/", nil)
	reqClient = reqClient.WithContext(auth.PutClaims(reqClient.Context(), &auth.Claims{Roles: auth.StringOrSlice{"CLIENT"}}))
	if hasAdminOrSupervisor(reqClient) {
		t.Error("CLIENT should be false")
	}
	reqAdmin := httptest.NewRequest(http.MethodGet, "/", nil)
	reqAdmin = reqAdmin.WithContext(auth.PutClaims(reqAdmin.Context(), &auth.Claims{Roles: auth.StringOrSlice{"admin"}}))
	if !hasAdminOrSupervisor(reqAdmin) {
		t.Error("admin should be true (case-insensitive)")
	}
	reqSup := httptest.NewRequest(http.MethodGet, "/", nil)
	reqSup = reqSup.WithContext(auth.PutClaims(reqSup.Context(), &auth.Claims{Roles: auth.StringOrSlice{"SUPERVISOR"}}))
	if !hasAdminOrSupervisor(reqSup) {
		t.Error("SUPERVISOR should be true")
	}
}

// ---------------------------------------------------------------------------
// outbound Get + error branches + PartnerPublicStock bad bankCode
// ---------------------------------------------------------------------------

func TestOutboundGet_NotFound_404(t *testing.T) {
	ns := newFakeOutboundStoreAPI()
	client := &fakeOutboundClientAPI{}
	r := buildOutboundRouter(claimsOtcTrade(15), ns, client)
	rr := doOutbound(r, http.MethodGet, "/api/interbank/otc/negotiations/ghost", nil)
	if rr.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for unknown negotiation, got %d body: %s", rr.Code, rr.Body.String())
	}
}

func TestOutboundGet_Happy(t *testing.T) {
	ns := newFakeOutboundStoreAPI()
	remoteID := "neg-get-r"
	ns.rows["neg-get"] = &store.Negotiation{
		ID:                    "neg-get",
		BuyerRouting:          111,
		BuyerID:               "C-15",
		SellerRouting:         222,
		SellerID:              "C-2",
		IsOngoing:             true,
		RemoteNegotiationID:   &remoteID,
		LastModifiedByRouting: 222,
		LastModifiedByID:      "C-2",
	}
	client := &fakeOutboundClientAPI{}
	r := buildOutboundRouter(claimsOtcTrade(15), ns, client)
	rr := doOutbound(r, http.MethodGet, "/api/interbank/otc/negotiations/neg-get", nil)
	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body: %s", rr.Code, rr.Body.String())
	}
}

func TestOutboundPublicStock_BadBankCode_400(t *testing.T) {
	ns := newFakeOutboundStoreAPI()
	client := &fakeOutboundClientAPI{}
	r := buildOutboundRouter(claimsOtcTrade(15), ns, client)
	rr := doOutbound(r, http.MethodGet, "/api/interbank/otc/public-stock?bankCode=abc", nil)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for bad bankCode, got %d", rr.Code)
	}
}

func TestOutboundCreate_InvalidJSON_400(t *testing.T) {
	ns := newFakeOutboundStoreAPI()
	client := &fakeOutboundClientAPI{}
	r := buildOutboundRouter(claimsOtcTrade(15), ns, client)
	rr := doOutbound(r, http.MethodPost, "/api/interbank/otc/negotiations", []byte("{bad"))
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for invalid JSON, got %d", rr.Code)
	}
}

func TestOutboundCounter_InvalidJSON_400(t *testing.T) {
	ns := newFakeOutboundStoreAPI()
	client := &fakeOutboundClientAPI{}
	r := buildOutboundRouter(claimsOtcTrade(15), ns, client)
	rr := doOutbound(r, http.MethodPut, "/api/interbank/otc/negotiations/x/counter", []byte("{bad"))
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for invalid JSON, got %d", rr.Code)
	}
}

// ---------------------------------------------------------------------------
// NewRouter (full) + NewRouterWithMock + corsPreflight
// ---------------------------------------------------------------------------

func fullTestDeps() ServerDeps {
	return ServerDeps{
		Partners: &staticPartnerStore{partners: []auth.Partner{{
			Routing:      testTheirRouting,
			InboundToken: testApiKey,
		}}},
		InboundHandler: NewInboundHandler(&fakeInboundExecutor{prepareVote: protocol.TransactionVote{Vote: protocol.VoteYes}}, newFakeInboundMessageStore(), nil, nil),
		OtcHandler:     NewOtcHandler(&fakeOtcService{}, nil),
		PublicStock:    NewPublicStockHandler(&fakePublicStockService{}, nil),
		UserDisplay:    NewUserDisplayHandler(testMyRouting, "Banka 1", &fakeUserResolver{info: &UserDisplayInfo{DisplayName: "X"}}, nil),
	}
}

func TestNewRouter_FullRoutes(t *testing.T) {
	r := NewRouter(fullTestDeps())
	// public-stock routed and auth-protected.
	rr := doGet(r, "/public-stock", testApiKey)
	if rr.Code != http.StatusOK {
		t.Fatalf("public-stock via NewRouter expected 200, got %d", rr.Code)
	}
	// user alias route.
	rr2 := doGet(r, "/user/111/C-15", testApiKey)
	if rr2.Code != http.StatusOK {
		t.Fatalf("user alias via NewRouter expected 200, got %d", rr2.Code)
	}
}

func TestNewRouter_WithOtcOutbound(t *testing.T) {
	deps := fullTestDeps()
	svc := service.NewOtcOutboundService(111, newFakeOutboundStoreAPI(), &fakeOutboundClientAPI{}, nil, &staticPartnerNamesAPI{}, nil)
	deps.OtcOutbound = NewOtcOutboundHandler(svc, nil)
	deps.JWTSecret = "secret"
	r := NewRouter(deps)
	// No JWT → 401 on the JWT-protected branch (exercises route registration).
	req := httptest.NewRequest(http.MethodGet, "/api/interbank/otc/negotiations", nil)
	rr := httptest.NewRecorder()
	r.ServeHTTP(rr, req)
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 without JWT, got %d", rr.Code)
	}
}

func TestCorsPreflight(t *testing.T) {
	r := NewRouter(fullTestDeps())
	req := httptest.NewRequest(http.MethodOptions, "/public-stock", nil)
	rr := httptest.NewRecorder()
	r.ServeHTTP(rr, req)
	if rr.Code != http.StatusNoContent {
		t.Fatalf("expected 204 for OPTIONS preflight, got %d", rr.Code)
	}
}

func TestNewRouterWithMock(t *testing.T) {
	mounted := false
	r := NewRouterWithMock(fullTestDeps(), func(rt chi.Router) {
		mounted = true
		rt.Get("/mock/ping", func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("pong"))
		})
	})
	if !mounted {
		t.Fatal("mountMock was not called")
	}
	req := httptest.NewRequest(http.MethodGet, "/mock/ping", nil)
	rr := httptest.NewRecorder()
	r.ServeHTTP(rr, req)
	if rr.Code != http.StatusOK || rr.Body.String() != "pong" {
		t.Fatalf("mock route: code=%d body=%q", rr.Code, rr.Body.String())
	}
	// health works through this router too.
	req2 := httptest.NewRequest(http.MethodGet, "/health", nil)
	rr2 := httptest.NewRecorder()
	r.ServeHTTP(rr2, req2)
	if rr2.Code != http.StatusOK {
		t.Fatalf("health via NewRouterWithMock expected 200, got %d", rr2.Code)
	}
}

func TestNewRouterWithMock_NilMount(t *testing.T) {
	r := NewRouterWithMock(fullTestDeps(), nil)
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rr := httptest.NewRecorder()
	r.ServeHTTP(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rr.Code)
	}
}
