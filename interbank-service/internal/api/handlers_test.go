package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/go-chi/chi/v5"
	"github.com/raf-si-2025/banka-1-go/shared/auth"
)

// ---------------------------------------------------------------------------
// fakePublicStockService
// ---------------------------------------------------------------------------

type fakePublicStockService struct {
	entries []PublicStockEntry
	err     error
}

func (f *fakePublicStockService) GetPublicStocks(_ context.Context) ([]PublicStockEntry, error) {
	return f.entries, f.err
}

// ---------------------------------------------------------------------------
// fakeUserResolver
// ---------------------------------------------------------------------------

type fakeUserResolver struct {
	info *UserDisplayInfo
	err  error
}

func (f *fakeUserResolver) ResolveUser(_ context.Context, _ string, _ int64) (*UserDisplayInfo, error) {
	return f.info, f.err
}

// ---------------------------------------------------------------------------
// Build helpers
// ---------------------------------------------------------------------------

func buildPublicStockRouter(svc PublicStockService) http.Handler {
	partners := &staticPartnerStore{partners: []auth.Partner{{
		Routing:      testTheirRouting,
		InboundToken: testApiKey,
	}}}
	h := NewPublicStockHandler(svc, nil)
	r := chi.NewRouter()
	r.Group(func(r chi.Router) {
		r.Use(auth.RequireXApiKey(partners))
		r.Get("/public-stock", h.Get)
	})
	return r
}

func buildUserDisplayRouter(myRouting int, resolver UserResolver) http.Handler {
	partners := &staticPartnerStore{partners: []auth.Partner{{
		Routing:      testTheirRouting,
		InboundToken: testApiKey,
	}}}
	h := NewUserDisplayHandler(myRouting, "Banka 1", resolver, nil)
	r := chi.NewRouter()
	r.Group(func(r chi.Router) {
		r.Use(auth.RequireXApiKey(partners))
		r.Get("/interbank/user/{rn}/{id}", h.Get)
		r.Get("/user/{rn}/{id}", h.Get)
	})
	return r
}

func doGet(r http.Handler, path string, apiKey string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, path, nil)
	if apiKey != "" {
		req.Header.Set("X-Api-Key", apiKey)
	}
	rr := httptest.NewRecorder()
	r.ServeHTTP(rr, req)
	return rr
}

// ---------------------------------------------------------------------------
// GET /public-stock tests
// ---------------------------------------------------------------------------

func TestPublicStock_Happy(t *testing.T) {
	svc := &fakePublicStockService{
		entries: []PublicStockEntry{
			{
				Stock: StockRef{Ticker: "AAPL"},
				Sellers: []SellerRow{
					{Seller: SellerID{RoutingNumber: 111, ID: "C-15"}, Amount: 75},
				},
			},
		},
	}
	r := buildPublicStockRouter(svc)
	rr := doGet(r, "/public-stock", testApiKey)
	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body: %s", rr.Code, rr.Body.String())
	}
	var entries []PublicStockEntry
	json.Unmarshal(rr.Body.Bytes(), &entries)
	if len(entries) != 1 || entries[0].Stock.Ticker != "AAPL" {
		t.Errorf("unexpected entries: %+v", entries)
	}
}

func TestPublicStock_Empty(t *testing.T) {
	svc := &fakePublicStockService{entries: nil}
	r := buildPublicStockRouter(svc)
	rr := doGet(r, "/public-stock", testApiKey)
	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rr.Code)
	}
	var entries []PublicStockEntry
	json.Unmarshal(rr.Body.Bytes(), &entries)
	if len(entries) != 0 {
		t.Errorf("expected empty array, got %d entries", len(entries))
	}
}

func TestPublicStock_NoAuth_401(t *testing.T) {
	svc := &fakePublicStockService{}
	r := buildPublicStockRouter(svc)
	rr := doGet(r, "/public-stock", "")
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", rr.Code)
	}
}

// ---------------------------------------------------------------------------
// GET /interbank/user/{rn}/{id} tests
// ---------------------------------------------------------------------------

func TestUserDisplay_HappyClient(t *testing.T) {
	resolver := &fakeUserResolver{info: &UserDisplayInfo{DisplayName: "Ana Anić"}}
	r := buildUserDisplayRouter(testMyRouting, resolver)
	rr := doGet(r, "/interbank/user/111/C-15", testApiKey)
	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body: %s", rr.Code, rr.Body.String())
	}
	var dto UserInformationDto
	json.Unmarshal(rr.Body.Bytes(), &dto)
	if dto.DisplayName != "Ana Anić" {
		t.Errorf("expected Ana Anić, got %s", dto.DisplayName)
	}
	if dto.BankDisplayName != "Banka 1" {
		t.Errorf("expected Banka 1, got %s", dto.BankDisplayName)
	}
}

func TestUserDisplay_HappyEmployee(t *testing.T) {
	resolver := &fakeUserResolver{info: &UserDisplayInfo{DisplayName: "Marko Marković"}}
	r := buildUserDisplayRouter(testMyRouting, resolver)
	rr := doGet(r, "/interbank/user/111/E-5", testApiKey)
	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rr.Code)
	}
}

func TestUserDisplay_WrongRouting_404(t *testing.T) {
	resolver := &fakeUserResolver{info: &UserDisplayInfo{DisplayName: "Someone"}}
	r := buildUserDisplayRouter(testMyRouting, resolver)
	rr := doGet(r, "/interbank/user/999/C-1", testApiKey)
	if rr.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for wrong routing, got %d", rr.Code)
	}
}

func TestUserDisplay_UserNotFound_404(t *testing.T) {
	resolver := &fakeUserResolver{err: errors.New("not found")}
	r := buildUserDisplayRouter(testMyRouting, resolver)
	rr := doGet(r, "/interbank/user/111/C-99", testApiKey)
	if rr.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", rr.Code)
	}
}

func TestUserDisplay_BadPrefix_400(t *testing.T) {
	resolver := &fakeUserResolver{}
	r := buildUserDisplayRouter(testMyRouting, resolver)
	rr := doGet(r, "/interbank/user/111/X-1", testApiKey)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for bad prefix, got %d", rr.Code)
	}
}

func TestUserDisplay_AliasPath_Works(t *testing.T) {
	// Tim 2 MINOR-1: /user/{rn}/{id} alias must also work.
	resolver := &fakeUserResolver{info: &UserDisplayInfo{DisplayName: "Mile Interbank"}}
	r := buildUserDisplayRouter(testMyRouting, resolver)
	rr := doGet(r, "/user/111/C-15", testApiKey)
	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200 on alias path, got %d", rr.Code)
	}
}

func TestUserDisplay_NoAuth_401(t *testing.T) {
	resolver := &fakeUserResolver{}
	r := buildUserDisplayRouter(testMyRouting, resolver)
	rr := doGet(r, "/interbank/user/111/C-1", "")
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", rr.Code)
	}
}

// ---------------------------------------------------------------------------
// /health (no auth)
// ---------------------------------------------------------------------------

func TestHealthEndpoint(t *testing.T) {
	deps := ServerDeps{
		Partners:       &staticPartnerStore{},
		InboundHandler: NewInboundHandler(&fakeInboundExecutor{}, newFakeInboundMessageStore(), nil, nil),
		OtcHandler:     NewOtcHandler(&fakeOtcService{}, nil),
		PublicStock:    NewPublicStockHandler(&fakePublicStockService{}, nil),
		UserDisplay:    NewUserDisplayHandler(testMyRouting, "Banka 1", &fakeUserResolver{}, nil),
	}
	r := NewRouter(deps)
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rr := httptest.NewRecorder()
	r.ServeHTTP(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200 for /health, got %d", rr.Code)
	}
}
