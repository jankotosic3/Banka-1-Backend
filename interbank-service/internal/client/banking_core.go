package client

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/shared/auth"
)

// AccountInfo is the response from GET /internal/interbank/account-resolve.
// This type is defined here (in the client package) and implements the
// service.BankingCoreReader interface — the service package defines the
// interface using its own AccountInfo type; injection wiring in main.go adapts
// between the two via a thin adapter or by using this type directly (they are
// structurally identical; see BankingCoreAdapter).
type AccountInfo struct {
	OwnerType        string          `json:"ownerType"`
	OwnerID          int64           `json:"ownerId"`
	Currency         string          `json:"currency"`
	AvailableBalance decimal.Decimal `json:"availableBalance"`
}

// BankingCoreClient calls the Java banking-core internal endpoints.
// All methods honour context cancellation and deadlines.
type BankingCoreClient struct {
	baseURL string
	issuer  *auth.S2SIssuer
	hc      *http.Client
}

// NewBankingCoreClient constructs a client. The timeout applies to each individual
// HTTP request as a backstop; callers should pass deadline-bearing contexts for
// per-call control.
func NewBankingCoreClient(baseURL string, issuer *auth.S2SIssuer, timeout time.Duration) *BankingCoreClient {
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	return &BankingCoreClient{
		baseURL: baseURL,
		issuer:  issuer,
		hc:      &http.Client{Timeout: timeout},
	}
}

// ResolveAccount looks up an account by its 18-digit number.
// Returns ErrNotFound (wrapped) if the upstream responds with 404.
func (c *BankingCoreClient) ResolveAccount(ctx context.Context, num string) (*AccountInfo, error) {
	u := c.baseURL + "/internal/interbank/account-resolve?num=" + url.QueryEscape(num)
	var info AccountInfo
	if err := c.do(ctx, http.MethodGet, u, nil, &info); err != nil {
		return nil, err
	}
	return &info, nil
}

// FindAccountByOwnerAndCurrency resolves (ownerID, currency) to an 18-digit
// account number. Returns ErrNotFound (wrapped) when no matching account exists.
func (c *BankingCoreClient) FindAccountByOwnerAndCurrency(ctx context.Context, ownerID int64, currency string) (string, error) {
	u := fmt.Sprintf("%s/internal/interbank/account-by-owner?ownerId=%d&currency=%s",
		c.baseURL, ownerID, url.QueryEscape(currency))
	var resp struct {
		AccountNumber string `json:"accountNumber"`
	}
	if err := c.do(ctx, http.MethodGet, u, nil, &resp); err != nil {
		return "", err
	}
	return resp.AccountNumber, nil
}

// ReserveMonas places a reservation for the given amount on the account.
// Returns the reservation UUID. Idempotent per (txIDRouting, txIDLocal) on the server.
func (c *BankingCoreClient) ReserveMonas(ctx context.Context, accountNum, currency string, amount decimal.Decimal, txIDRouting int, txIDLocal string) (string, error) {
	// NOTE: field names MUST match banking-core's ReserveMonasRequest json tags
	// (transactionIdRouting / transactionIdLocal). A previous mismatch
	// (txIdRouting / txIdLocal) left TransactionIdLocal empty server-side, so every
	// outbound MONAS reservation failed with "transactionIdLocal je obavezan" (400) —
	// breaking the sender-debit leg of cross-bank payments and OTC buyer premiums.
	body := map[string]any{
		"accountNum":           accountNum,
		"currency":             currency,
		"amount":               amount.String(),
		"transactionIdRouting": txIDRouting,
		"transactionIdLocal":   txIDLocal,
	}
	var resp struct {
		ReservationID string `json:"reservationId"`
	}
	if err := c.do(ctx, http.MethodPost, c.baseURL+"/internal/interbank/reserve-monas", body, &resp); err != nil {
		return "", err
	}
	return resp.ReservationID, nil
}

// CreditMonas credits an account for an INCOMING inter-bank transfer (money arriving
// at one of our accounts). Unlike CommitMonas — which finalizes a prior reservation
// (a debit) — this directly increases the recipient's balance via banking-core's
// POST /internal/accounts/credit. clientID MUST be the account owner: banking-core
// validates ownership (validateMutable) and rejects a mismatch unless the owner is the
// bank (-1). Returns nil on 2xx.
func (c *BankingCoreClient) CreditMonas(ctx context.Context, accountNum string, amount decimal.Decimal, clientID int64) error {
	body := map[string]any{
		"accountNumber": accountNum,
		"amount":        amount.String(),
		"clientId":      clientID,
	}
	return c.do(ctx, http.MethodPost, c.baseURL+"/internal/accounts/credit", body, nil)
}

// RecordInterbankPayment asks banking-core to insert a COMPLETED payment_table row for a
// cross-bank money movement so it shows up in this bank's transaction history. orderNumber
// is the idempotency key (banking-core does ON CONFLICT DO NOTHING). Best-effort by
// contract: callers log and continue on failure — it must never roll back a settled
// transfer. Returns nil on 2xx.
func (c *BankingCoreClient) RecordInterbankPayment(ctx context.Context, orderNumber, fromAccount, toAccount string, amount decimal.Decimal, currency, recipientName, paymentPurpose string) error {
	body := map[string]any{
		"orderNumber":    orderNumber,
		"fromAccount":    fromAccount,
		"toAccount":      toAccount,
		"amount":         amount.String(),
		"currency":       currency,
		"recipientName":  recipientName,
		"paymentPurpose": paymentPurpose,
	}
	return c.do(ctx, http.MethodPost, c.baseURL+"/internal/interbank/record-payment", body, nil)
}

// CommitMonas permanently debits the reserved amount. Returns nil on 204.
func (c *BankingCoreClient) CommitMonas(ctx context.Context, reservationID string) error {
	u := c.baseURL + "/internal/interbank/reservations/" + url.PathEscape(reservationID) + "/commit-monas"
	return c.do(ctx, http.MethodPost, u, nil, nil)
}

// ReleaseMonas frees the reservation back to available balance. Returns nil on 204.
func (c *BankingCoreClient) ReleaseMonas(ctx context.Context, reservationID string) error {
	u := c.baseURL + "/internal/interbank/reservations/" + url.PathEscape(reservationID)
	return c.do(ctx, http.MethodDelete, u, nil, nil)
}

// do is the shared HTTP helper. body is JSON-marshalled if non-nil.
// out is JSON-unmarshalled from the response if non-nil. 204 with nil out is OK.
func (c *BankingCoreClient) do(ctx context.Context, method, rawURL string, body, out any) error {
	req, err := buildRequest(ctx, method, rawURL, body)
	if err != nil {
		return err
	}
	tok, err := c.issuer.IssueToken()
	if err != nil {
		return fmt.Errorf("client: issue S2S token: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	return execRequest(c.hc, req, out)
}

// ---------------------------------------------------------------------------
// Shared low-level helpers (unexported, used by all three client types)
// ---------------------------------------------------------------------------

// buildRequest creates an *http.Request with optional JSON body.
func buildRequest(ctx context.Context, method, rawURL string, body any) (*http.Request, error) {
	var bodyReader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, fmt.Errorf("client: marshal body: %w", err)
		}
		bodyReader = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, rawURL, bodyReader)
	if err != nil {
		return nil, fmt.Errorf("client: build request: %w", err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	return req, nil
}

// execRequest sends req, maps 404 → ErrNotFound, 4xx/5xx → ErrUpstream,
// and JSON-decodes into out on 2xx (out may be nil).
func execRequest(hc *http.Client, req *http.Request, out any) error {
	resp, err := hc.Do(req)
	if err != nil {
		return fmt.Errorf("client: request: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return fmt.Errorf("%w: %s %s", ErrNotFound, req.Method, req.URL.Path)
	}
	if resp.StatusCode >= 400 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("%w: status=%d body=%s", ErrUpstream, resp.StatusCode, b)
	}
	if out == nil {
		return nil
	}
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("client: read body: %w", err)
	}
	if len(b) == 0 {
		return nil
	}
	return json.Unmarshal(b, out)
}
