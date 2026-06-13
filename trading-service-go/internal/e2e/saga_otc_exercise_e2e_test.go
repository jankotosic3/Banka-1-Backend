//go:build e2e

package e2e

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"testing"
	"time"

	"banka1/go-platform/auth"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/shopspring/decimal"
)

// ---------------------------------------------------------------------------
// Constants & shared helpers
// ---------------------------------------------------------------------------

// gatewayBaseURL / DB hosts can be overridden via env vars so the suite can
// run either from the host machine (default: localhost) or from a container
// attached to the compose network (e.g. GATEWAY_HOST=banka_api_gateway,
// DB_HOST=banka_postgres).
var (
	gatewayHost   = envOr("GATEWAY_HOST", "localhost")
	gatewayPort   = envOr("GATEWAY_PORT", "80")
	dbHost        = envOr("DB_HOST", "localhost")
	dbPort        = envOr("DB_PORT", "5432")
	toxiproxyHost = envOr("TOXIPROXY_HOST", "localhost")
	toxiproxyPort = envOr("TOXIPROXY_PORT", "8474")

	gatewayBaseURL   = fmt.Sprintf("http://%s:%s", gatewayHost, gatewayPort)
	tradingDSN       = fmt.Sprintf("postgres://postgres:postgres@%s:%s/trading?sslmode=disable", dbHost, dbPort)
	bankingCoreDSN   = fmt.Sprintf("postgres://postgres:postgres@%s:%s/banking_core?sslmode=disable", dbHost, dbPort)
	sagaDSN          = fmt.Sprintf("postgres://postgres:postgres@%s:%s/saga_db?sslmode=disable", dbHost, dbPort)
	toxiproxyBaseURL = fmt.Sprintf("http://%s:%s", toxiproxyHost, toxiproxyPort)
)

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

const (
	jwtSecret = "OvoJeNekaDugackaTajnaSifraZaJwtKojuNeStavljamoUProperties"
	jwtIssuer = "banka1"

	sagaTypeOtcExercise = "OTC_EXERCISE"

	bankingCoreContainer      = "banka_banking_core_service"
	sagaOrchestratorContainer = "saga-orchestrator-service"

	// bankingCoreToxiproxyProxy is the name of the toxiproxy proxy that
	// saga-orchestrator-service's banking-core traffic is routed through
	// (see setup/toxiproxy/toxiproxy.json and SAGA_BANKING_CORE_URL).
	bankingCoreToxiproxyProxy = "banking-core"
)

// SagaLog mirrors saga-orchestrator-service/internal/saga.SagaLog.
type SagaLog struct {
	Steps      []StepRecord     `json:"steps"`
	Refs       map[string]string `json:"refs"`
	CompCounts map[string]int   `json:"compCounts,omitempty"`
}

// StepRecord mirrors saga-orchestrator-service/internal/saga.StepRecord.
type StepRecord struct {
	Step    string `json:"step"`
	Outcome string `json:"outcome"`
	Error   string `json:"error,omitempty"`
}

type sagaSnapshot struct {
	State string
	Step  int
	Log   SagaLog
}

func mustOpenPool(t *testing.T, dsn string) *pgxpool.Pool {
	t.Helper()
	pool, err := pgxpool.New(context.Background(), dsn)
	if err != nil {
		t.Fatalf("open pool %s: %v", dsn, err)
	}
	t.Cleanup(pool.Close)
	return pool
}

// mintToken issues an HS256 access token for a CLIENT principal.
func mintToken(t *testing.T, clientID int64) string {
	t.Helper()
	svc := auth.NewService(auth.Config{
		Secret:              jwtSecret,
		Issuer:              jwtIssuer,
		IDClaim:             "id",
		RolesClaim:          "roles",
		PermissionsClaim:    "permissions",
		EmailClaim:          "email",
		AccessTokenDuration: time.Hour,
	})
	tok, err := svc.GenerateAccessToken(clientID, fmt.Sprintf("e2e-client-%d@banka1.test", clientID), "CLIENT_TRADING", []string{"CLIENT_OTC_TRADE", "CLIENT_SECURITIES_TRADE"})
	if err != nil {
		t.Fatalf("mint token: %v", err)
	}
	return tok
}

// contractFixture describes an option_contracts/otc_offers row pair to insert.
type contractFixture struct {
	Ticker     string
	BuyerID    int64
	SellerID   int64
	Amount     int
	Price      decimal.Decimal
	Settlement time.Time
	Status     string
}

// createContract inserts an otc_offers + option_contracts row, returning the
// contract id. Cleanup removes both rows (and any saga_instance row keyed by
// the contract id) at the end of the test.
func createContract(t *testing.T, tdb, sdb *pgxpool.Pool, f contractFixture) int64 {
	t.Helper()
	ctx := context.Background()

	var offerID int64
	err := tdb.QueryRow(ctx, `
		INSERT INTO otc_offers (stock_ticker, buyer_id, seller_id, amount, price_per_stock, premium, settlement_date, status, last_modified, created_at, version)
		VALUES ($1,$2,$3,$4,$5,0,$6,'ACCEPTED',now(),now(),0)
		RETURNING id`,
		f.Ticker, f.BuyerID, f.SellerID, f.Amount, f.Price, f.Settlement).Scan(&offerID)
	if err != nil {
		t.Fatalf("insert otc_offers: %v", err)
	}

	var contractID int64
	err = tdb.QueryRow(ctx, `
		INSERT INTO option_contracts (offer_id, stock_ticker, buyer_id, seller_id, amount, price_per_stock, settlement_date, status, created_at, version)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,now(),0)
		RETURNING id`,
		offerID, f.Ticker, f.BuyerID, f.SellerID, f.Amount, f.Price, f.Settlement, f.Status).Scan(&contractID)
	if err != nil {
		t.Fatalf("insert option_contracts: %v", err)
	}

	t.Cleanup(func() {
		cctx := context.Background()
		_, _ = tdb.Exec(cctx, `DELETE FROM option_contracts WHERE id=$1`, contractID)
		_, _ = tdb.Exec(cctx, `DELETE FROM otc_offers WHERE id=$1`, offerID)
		_, _ = sdb.Exec(cctx, `DELETE FROM saga_instance WHERE saga_type=$1 AND correlation_id=$2`, sagaTypeOtcExercise, strconv.FormatInt(contractID, 10))
	})

	return contractID
}

// portfolioSnapshot saves and restores quantity/reserved_quantity for a
// (userID, listingID) row.
func snapshotAndSetPortfolio(t *testing.T, tdb *pgxpool.Pool, userID, listingID int64, quantity, reserved int) {
	t.Helper()
	ctx := context.Background()

	var origQty, origReserved int
	err := tdb.QueryRow(ctx, `SELECT quantity, reserved_quantity FROM portfolio WHERE user_id=$1 AND listing_id=$2`, userID, listingID).Scan(&origQty, &origReserved)
	if err != nil {
		t.Fatalf("read portfolio: %v", err)
	}

	_, err = tdb.Exec(ctx, `UPDATE portfolio SET quantity=$1, reserved_quantity=$2, last_modified=now() WHERE user_id=$3 AND listing_id=$4`, quantity, reserved, userID, listingID)
	if err != nil {
		t.Fatalf("update portfolio: %v", err)
	}

	t.Cleanup(func() {
		cctx := context.Background()
		_, _ = tdb.Exec(cctx, `UPDATE portfolio SET quantity=$1, reserved_quantity=$2, last_modified=now() WHERE user_id=$3 AND listing_id=$4`, origQty, origReserved, userID, listingID)
	})
}

// snapshotAndSetAccountFunds saves and restores stanje/raspolozivo_stanje for
// the account belonging to ownerID, and also restores dnevna_potrosnja/
// mesecna_potrosnja/daily_limit_remaining (which the saga's real fund
// transfers mutate as a side effect, regardless of pass/fail).
func snapshotAndSetAccountFunds(t *testing.T, bdb *pgxpool.Pool, ownerID int64, stanje, raspolozivo decimal.Decimal) {
	t.Helper()
	ctx := context.Background()

	var accountID int64
	var origStanje, origRaspolozivo decimal.Decimal
	err := bdb.QueryRow(ctx, `SELECT id, stanje, raspolozivo_stanje FROM account_table WHERE vlasnik=$1 ORDER BY id LIMIT 1`, ownerID).Scan(&accountID, &origStanje, &origRaspolozivo)
	if err != nil {
		t.Fatalf("read account_table: %v", err)
	}

	_, err = bdb.Exec(ctx, `UPDATE account_table SET stanje=$1, raspolozivo_stanje=$2, updated_at=now() WHERE id=$3`, stanje, raspolozivo, accountID)
	if err != nil {
		t.Fatalf("update account_table: %v", err)
	}

	t.Cleanup(func() {
		cctx := context.Background()
		_, _ = bdb.Exec(cctx, `UPDATE account_table SET stanje=$1, raspolozivo_stanje=$2, updated_at=now() WHERE id=$3`, origStanje, origRaspolozivo, accountID)
	})

	restoreAccountUsage(t, bdb, accountID)
}

// restoreAccountUsage snapshots dnevna_potrosnja/mesecna_potrosnja/
// daily_limit_remaining for the given account row and restores them on
// cleanup, undoing any daily/monthly spend accounting performed by real
// fund transfers executed during the test.
func restoreAccountUsage(t *testing.T, bdb *pgxpool.Pool, accountID int64) {
	t.Helper()
	ctx := context.Background()

	var origDnevna, origMesecna decimal.Decimal
	var origDailyRemaining decimal.NullDecimal
	err := bdb.QueryRow(ctx, `SELECT dnevna_potrosnja, mesecna_potrosnja, daily_limit_remaining FROM account_table WHERE id=$1`, accountID).Scan(&origDnevna, &origMesecna, &origDailyRemaining)
	if err != nil {
		t.Fatalf("read account_table usage: %v", err)
	}

	t.Cleanup(func() {
		cctx := context.Background()
		_, _ = bdb.Exec(cctx, `UPDATE account_table SET dnevna_potrosnja=$1, mesecna_potrosnja=$2, daily_limit_remaining=$3, updated_at=now() WHERE id=$4`, origDnevna, origMesecna, origDailyRemaining, accountID)
	})
}

// restoreAccountUsageByOwner is a convenience wrapper for sellers/other
// accounts that aren't otherwise touched by snapshotAndSetAccountFunds.
func restoreAccountUsageByOwner(t *testing.T, bdb *pgxpool.Pool, ownerID int64) {
	t.Helper()
	ctx := context.Background()

	var accountID int64
	err := bdb.QueryRow(ctx, `SELECT id FROM account_table WHERE vlasnik=$1 ORDER BY id LIMIT 1`, ownerID).Scan(&accountID)
	if err != nil {
		t.Fatalf("read account_table: %v", err)
	}
	restoreAccountUsage(t, bdb, accountID)
}

func getAccountFunds(t *testing.T, bdb *pgxpool.Pool, ownerID int64) (stanje, raspolozivo decimal.Decimal) {
	t.Helper()
	ctx := context.Background()
	err := bdb.QueryRow(ctx, `SELECT stanje, raspolozivo_stanje FROM account_table WHERE vlasnik=$1 ORDER BY id LIMIT 1`, ownerID).Scan(&stanje, &raspolozivo)
	if err != nil {
		t.Fatalf("read account_table: %v", err)
	}
	return stanje, raspolozivo
}

func getPortfolio(t *testing.T, tdb *pgxpool.Pool, userID, listingID int64) (quantity, reserved int) {
	t.Helper()
	ctx := context.Background()
	err := tdb.QueryRow(ctx, `SELECT quantity, reserved_quantity FROM portfolio WHERE user_id=$1 AND listing_id=$2`, userID, listingID).Scan(&quantity, &reserved)
	if err != nil {
		t.Fatalf("read portfolio: %v", err)
	}
	return quantity, reserved
}

// exerciseRequest issues POST /options/{contractId}/exercise via the gateway
// with the given headers (used for fault injection / auth).
func exerciseRequest(t *testing.T, contractID int64, token string, headers map[string]string) (status int, body []byte) {
	t.Helper()
	url := fmt.Sprintf("%s/options/%d/exercise", gatewayBaseURL, contractID)
	req, err := http.NewRequest(http.MethodPost, url, nil)
	if err != nil {
		t.Fatalf("build request: %v", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	for k, v := range headers {
		req.Header.Set(k, v)
	}

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("exercise request: %v", err)
	}
	defer resp.Body.Close()

	buf := make([]byte, 0, 1024)
	tmp := make([]byte, 512)
	for {
		n, rerr := resp.Body.Read(tmp)
		if n > 0 {
			buf = append(buf, tmp[:n]...)
		}
		if rerr != nil {
			break
		}
	}
	return resp.StatusCode, buf
}

// awaitSagaTerminal polls saga_instance until the saga reaches a terminal
// state (COMPLETED / COMPENSATED / FAILED) or the timeout elapses. It returns
// the last observed snapshot (which may be non-terminal if the timeout hit).
func awaitSagaTerminal(t *testing.T, sdb *pgxpool.Pool, contractID int64, timeout time.Duration) (sagaSnapshot, bool) {
	t.Helper()
	correlationID := strconv.FormatInt(contractID, 10)
	deadline := time.Now().Add(timeout)

	for {
		snap, found := readSagaSnapshot(t, sdb, correlationID)
		if found && isTerminalState(snap.State) {
			return snap, true
		}
		if time.Now().After(deadline) {
			return snap, false
		}
		time.Sleep(200 * time.Millisecond)
	}
}

func readSagaSnapshot(t *testing.T, sdb *pgxpool.Pool, correlationID string) (sagaSnapshot, bool) {
	t.Helper()
	ctx := context.Background()

	var state string
	var step int
	var logBytes []byte
	err := sdb.QueryRow(ctx, `SELECT state, current_step, compensation_log FROM saga_instance WHERE saga_type=$1 AND correlation_id=$2`, sagaTypeOtcExercise, correlationID).Scan(&state, &step, &logBytes)
	if err != nil {
		return sagaSnapshot{}, false
	}

	var log SagaLog
	if len(logBytes) > 0 {
		_ = json.Unmarshal(logBytes, &log)
	}
	return sagaSnapshot{State: state, Step: step, Log: log}, true
}

func isTerminalState(state string) bool {
	switch state {
	case "COMPLETED", "COMPENSATED", "FAILED":
		return true
	default:
		return false
	}
}

// findStep returns the last StepRecord for the given step name, if any.
func findStep(log SagaLog, step string) (StepRecord, bool) {
	var found StepRecord
	ok := false
	for _, s := range log.Steps {
		if s.Step == step {
			found = s
			ok = true
		}
	}
	return found, ok
}

// countStep counts how many StepRecord entries exist for the given step name.
func countStep(log SagaLog, step string) int {
	n := 0
	for _, s := range log.Steps {
		if s.Step == step {
			n++
		}
	}
	return n
}

// dockerCmd runs a docker CLI command (used for SG-09/10/11 fault injection
// against running containers) and fails the test if it errors.
func dockerCmd(t *testing.T, args ...string) {
	t.Helper()
	cmd := exec.Command("docker", args...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("docker %v failed: %v\noutput: %s", args, err, string(out))
	}
}

// addLatencyToxic injects a "latency" toxic (with zero jitter) on the
// upstream side of the banking-core toxiproxy proxy, and registers cleanup
// to remove it afterwards.
func addLatencyToxic(t *testing.T, name string, latencyMs int) {
	t.Helper()

	payload, _ := json.Marshal(map[string]any{
		"name":     name,
		"type":     "latency",
		"stream":   "upstream",
		"toxicity": 1.0,
		"attributes": map[string]any{
			"latency": latencyMs,
			"jitter":  0,
		},
	})

	url := fmt.Sprintf("%s/proxies/%s/toxics", toxiproxyBaseURL, bankingCoreToxiproxyProxy)
	resp, err := http.Post(url, "application/json", bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("toxiproxy: add latency toxic: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		t.Fatalf("toxiproxy: add latency toxic: unexpected status %d", resp.StatusCode)
	}

	t.Cleanup(func() {
		req, err := http.NewRequest(http.MethodDelete, fmt.Sprintf("%s/proxies/%s/toxics/%s", toxiproxyBaseURL, bankingCoreToxiproxyProxy, name), nil)
		if err != nil {
			return
		}
		if resp, err := http.DefaultClient.Do(req); err == nil {
			resp.Body.Close()
		}
	})
}

// setBankingCoreProxyEnabled enables/disables the banking-core toxiproxy
// proxy. Disabling it causes connections from saga-orchestrator-service to
// banking-core to be refused immediately, simulating the upstream being
// completely down. If disabling, cleanup re-enables it afterwards.
func setBankingCoreProxyEnabled(t *testing.T, enabled bool) {
	t.Helper()

	setEnabled := func(v bool) error {
		payload, _ := json.Marshal(map[string]any{"enabled": v})
		req, err := http.NewRequest(http.MethodPost, fmt.Sprintf("%s/proxies/%s", toxiproxyBaseURL, bankingCoreToxiproxyProxy), bytes.NewReader(payload))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/json")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			return err
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return fmt.Errorf("unexpected status %d", resp.StatusCode)
		}
		return nil
	}

	if err := setEnabled(enabled); err != nil {
		t.Fatalf("toxiproxy: set banking-core proxy enabled=%v: %v", enabled, err)
	}

	if !enabled {
		t.Cleanup(func() {
			_ = setEnabled(true)
		})
	}
}

// ---------------------------------------------------------------------------
// Test fixtures: buyer = client 2 (Ana, RSD account, 150000.00),
// seller = client 1 (Marko, AAPL portfolio, listing_id=1, qty=200).
// ---------------------------------------------------------------------------

const (
	fixtureBuyerID  int64 = 2
	fixtureSellerID int64 = 1
	fixtureTicker         = "AAPL"
	fixtureListing  int64 = 1
)

func defaultFixture(amount int, price string) contractFixture {
	return contractFixture{
		Ticker:     fixtureTicker,
		BuyerID:    fixtureBuyerID,
		SellerID:   fixtureSellerID,
		Amount:     amount,
		Price:      decimal.RequireFromString(price),
		Settlement: time.Now().AddDate(0, 0, 7),
		Status:     "ACTIVE",
	}
}

// ---------------------------------------------------------------------------
// SG-01: Happy path — full saga F1..F5 completes successfully.
// ---------------------------------------------------------------------------

func TestSG01_HappyPath(t *testing.T) {
	tdb := mustOpenPool(t, tradingDSN)
	bdb := mustOpenPool(t, bankingCoreDSN)
	sdb := mustOpenPool(t, sagaDSN)

	// Ensure buyer has plenty of funds and seller has plenty of free shares.
	snapshotAndSetAccountFunds(t, bdb, fixtureBuyerID, decimal.RequireFromString("150000.00"), decimal.RequireFromString("150000.00"))
	restoreAccountUsageByOwner(t, bdb, fixtureSellerID)
	snapshotAndSetPortfolio(t, tdb, fixtureSellerID, fixtureListing, 200, 0)

	contractID := createContract(t, tdb, sdb, defaultFixture(2, "50.00"))

	token := mintToken(t, fixtureBuyerID)
	status, body := exerciseRequest(t, contractID, token, nil)
	if status != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted, got %d: %s", status, string(body))
	}

	snap, ok := awaitSagaTerminal(t, sdb, contractID, 30*time.Second)
	if !ok {
		t.Fatalf("saga did not reach a terminal state in time, last state=%s step=%d", snap.State, snap.Step)
	}
	if snap.State != "COMPLETED" {
		t.Fatalf("expected saga state COMPLETED, got %s (log=%+v)", snap.State, snap.Log)
	}

	for _, step := range []string{"F1", "F2", "F3", "F4", "F5"} {
		rec, found := findStep(snap.Log, step)
		if !found {
			t.Errorf("expected step %s to be recorded, log=%+v", step, snap.Log)
			continue
		}
		if rec.Outcome != "ok" {
			t.Errorf("expected step %s outcome=ok, got %q (err=%q)", step, rec.Outcome, rec.Error)
		}
	}
}

// ---------------------------------------------------------------------------
// SG-02a: Non-existent contract -> 404.
// ---------------------------------------------------------------------------

func TestSG02a_ContractNotFound(t *testing.T) {
	token := mintToken(t, fixtureBuyerID)
	status, _ := exerciseRequest(t, 99999999, token, nil)
	if status != http.StatusNotFound {
		t.Fatalf("expected 404 Not Found, got %d", status)
	}
}

// ---------------------------------------------------------------------------
// SG-02b: Caller is not the buyer -> 403.
// ---------------------------------------------------------------------------

func TestSG02b_NotBuyer(t *testing.T) {
	tdb := mustOpenPool(t, tradingDSN)
	sdb := mustOpenPool(t, sagaDSN)

	contractID := createContract(t, tdb, sdb, defaultFixture(1, "50.00"))

	// Authenticate as the seller, not the buyer.
	token := mintToken(t, fixtureSellerID)
	status, _ := exerciseRequest(t, contractID, token, nil)
	if status != http.StatusForbidden {
		t.Fatalf("expected 403 Forbidden, got %d", status)
	}
}

// ---------------------------------------------------------------------------
// SG-02c: Contract already EXERCISED.
//
// The spec document describes this as a 400 Bad Request, but
// trading-service-go/internal/otc/service.go actually returns 409 Conflict
// ("Ugovor nije u statusu 'važeći': EXERCISED") for any non-ACTIVE contract.
// This test asserts the actual implemented behavior.
// ---------------------------------------------------------------------------

func TestSG02c_AlreadyExercised(t *testing.T) {
	tdb := mustOpenPool(t, tradingDSN)
	sdb := mustOpenPool(t, sagaDSN)

	f := defaultFixture(1, "50.00")
	f.Status = "EXERCISED"
	contractID := createContract(t, tdb, sdb, f)

	token := mintToken(t, fixtureBuyerID)
	status, body := exerciseRequest(t, contractID, token, nil)
	if status != http.StatusConflict {
		t.Fatalf("expected 409 Conflict for an already-EXERCISED contract, got %d: %s", status, string(body))
	}
}

// ---------------------------------------------------------------------------
// SG-02d: Settlement date already passed.
//
// The spec document describes this as a 400 Bad Request, but
// trading-service-go/internal/otc/service.go actually returns 409 Conflict
// ("Rok za iskorišćavanje opcije je prošao."). This test asserts the actual
// implemented behavior.
// ---------------------------------------------------------------------------

func TestSG02d_SettlementDatePassed(t *testing.T) {
	tdb := mustOpenPool(t, tradingDSN)
	sdb := mustOpenPool(t, sagaDSN)

	f := defaultFixture(1, "50.00")
	f.Settlement = time.Now().AddDate(0, 0, -1)
	contractID := createContract(t, tdb, sdb, f)

	token := mintToken(t, fixtureBuyerID)
	status, body := exerciseRequest(t, contractID, token, nil)
	if status != http.StatusConflict {
		t.Fatalf("expected 409 Conflict for a contract past its settlement date, got %d: %s", status, string(body))
	}
}

// ---------------------------------------------------------------------------
// SG-03: F1 fails (insufficient buyer funds).
//
// Since F1 is the first step, nothing has been reserved yet, so
// otcExerciseFail finds no refs to compensate and the saga lands directly in
// COMPENSATED (with allOK left at its default true) rather than FAILED. FAILED
// is only reached when a compensating step (C1..C4) exhausts its retries.
// ---------------------------------------------------------------------------

func TestSG03_InsufficientFunds(t *testing.T) {
	tdb := mustOpenPool(t, tradingDSN)
	bdb := mustOpenPool(t, bankingCoreDSN)
	sdb := mustOpenPool(t, sagaDSN)

	// Buyer has almost no funds; totalCostUSD = 2 * 50 = 100 USD (~10000+ RSD).
	snapshotAndSetAccountFunds(t, bdb, fixtureBuyerID, decimal.RequireFromString("10.00"), decimal.RequireFromString("10.00"))
	restoreAccountUsageByOwner(t, bdb, fixtureSellerID)
	snapshotAndSetPortfolio(t, tdb, fixtureSellerID, fixtureListing, 200, 0)

	contractID := createContract(t, tdb, sdb, defaultFixture(2, "50.00"))

	token := mintToken(t, fixtureBuyerID)
	status, body := exerciseRequest(t, contractID, token, nil)
	if status != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted (saga starts async), got %d: %s", status, string(body))
	}

	snap, ok := awaitSagaTerminal(t, sdb, contractID, 30*time.Second)
	if !ok {
		t.Fatalf("saga did not reach a terminal state in time, last state=%s step=%d", snap.State, snap.Step)
	}
	if snap.State != "COMPENSATED" {
		t.Fatalf("expected saga state COMPENSATED, got %s (log=%+v)", snap.State, snap.Log)
	}

	rec, found := findStep(snap.Log, "F1")
	if !found {
		t.Fatalf("expected step F1 to be recorded, log=%+v", snap.Log)
	}
	if rec.Outcome == "ok" {
		t.Fatalf("expected step F1 to fail (insufficient funds), but it succeeded: log=%+v", snap.Log)
	}
	if len(snap.Log.Refs) != 0 {
		t.Errorf("expected no refs to have been recorded (F1 never reserved anything), got %+v", snap.Log.Refs)
	}
	for _, step := range []string{"C1", "C2", "C3", "C4"} {
		if _, found := findStep(snap.Log, step); found {
			t.Errorf("did not expect compensating step %s to run when F1 fails, log=%+v", step, snap.Log)
		}
	}
}

// ---------------------------------------------------------------------------
// SG-04: F2 fails (seller has insufficient free stock) -> saga COMPENSATED,
// C1 (release buyer funds) runs.
// ---------------------------------------------------------------------------

func TestSG04_InsufficientStock(t *testing.T) {
	tdb := mustOpenPool(t, tradingDSN)
	bdb := mustOpenPool(t, bankingCoreDSN)
	sdb := mustOpenPool(t, sagaDSN)

	snapshotAndSetAccountFunds(t, bdb, fixtureBuyerID, decimal.RequireFromString("150000.00"), decimal.RequireFromString("150000.00"))
	restoreAccountUsageByOwner(t, bdb, fixtureSellerID)
	// Seller only has 1 free share but the contract requires 2.
	snapshotAndSetPortfolio(t, tdb, fixtureSellerID, fixtureListing, 1, 0)

	contractID := createContract(t, tdb, sdb, defaultFixture(2, "50.00"))

	token := mintToken(t, fixtureBuyerID)
	status, body := exerciseRequest(t, contractID, token, nil)
	if status != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted, got %d: %s", status, string(body))
	}

	snap, ok := awaitSagaTerminal(t, sdb, contractID, 30*time.Second)
	if !ok {
		t.Fatalf("saga did not reach a terminal state in time, last state=%s step=%d", snap.State, snap.Step)
	}
	if snap.State != "COMPENSATED" {
		t.Fatalf("expected saga state COMPENSATED, got %s (log=%+v)", snap.State, snap.Log)
	}

	if rec, found := findStep(snap.Log, "F2"); !found || rec.Outcome == "ok" {
		t.Fatalf("expected step F2 to fail (insufficient stock), log=%+v", snap.Log)
	}
	if rec, found := findStep(snap.Log, "C1"); !found || rec.Outcome != "ok" {
		t.Fatalf("expected compensating step C1 to run successfully, log=%+v", snap.Log)
	}
}

// ---------------------------------------------------------------------------
// SG-05: Forced failure at F3 -> saga COMPENSATED via C2 then C1.
// ---------------------------------------------------------------------------

func TestSG05_ForceFailF3(t *testing.T) {
	tdb := mustOpenPool(t, tradingDSN)
	bdb := mustOpenPool(t, bankingCoreDSN)
	sdb := mustOpenPool(t, sagaDSN)

	snapshotAndSetAccountFunds(t, bdb, fixtureBuyerID, decimal.RequireFromString("150000.00"), decimal.RequireFromString("150000.00"))
	restoreAccountUsageByOwner(t, bdb, fixtureSellerID)
	snapshotAndSetPortfolio(t, tdb, fixtureSellerID, fixtureListing, 200, 0)

	contractID := createContract(t, tdb, sdb, defaultFixture(2, "50.00"))

	token := mintToken(t, fixtureBuyerID)
	status, body := exerciseRequest(t, contractID, token, map[string]string{
		"X-Saga-Force-Fail": "F3",
	})
	if status != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted, got %d: %s", status, string(body))
	}

	snap, ok := awaitSagaTerminal(t, sdb, contractID, 30*time.Second)
	if !ok {
		t.Fatalf("saga did not reach a terminal state in time, last state=%s step=%d", snap.State, snap.Step)
	}
	if snap.State != "COMPENSATED" {
		t.Fatalf("expected saga state COMPENSATED, got %s (log=%+v)", snap.State, snap.Log)
	}

	for _, step := range []string{"F1", "F2"} {
		if rec, found := findStep(snap.Log, step); !found || rec.Outcome != "ok" {
			t.Errorf("expected step %s to succeed, log=%+v", step, snap.Log)
		}
	}
	if rec, found := findStep(snap.Log, "F3"); !found || rec.Outcome == "ok" {
		t.Fatalf("expected step F3 to be force-failed, log=%+v", snap.Log)
	}
	for _, step := range []string{"C2", "C1"} {
		if rec, found := findStep(snap.Log, step); !found || rec.Outcome != "ok" {
			t.Errorf("expected compensating step %s to succeed, log=%+v", step, snap.Log)
		}
	}
}

// ---------------------------------------------------------------------------
// SG-06: Forced failure at F4 -> saga COMPENSATED via C3, C2, C1.
// ---------------------------------------------------------------------------

func TestSG06_ForceFailF4(t *testing.T) {
	tdb := mustOpenPool(t, tradingDSN)
	bdb := mustOpenPool(t, bankingCoreDSN)
	sdb := mustOpenPool(t, sagaDSN)

	snapshotAndSetAccountFunds(t, bdb, fixtureBuyerID, decimal.RequireFromString("150000.00"), decimal.RequireFromString("150000.00"))
	restoreAccountUsageByOwner(t, bdb, fixtureSellerID)
	snapshotAndSetPortfolio(t, tdb, fixtureSellerID, fixtureListing, 200, 0)

	contractID := createContract(t, tdb, sdb, defaultFixture(2, "50.00"))

	token := mintToken(t, fixtureBuyerID)
	status, body := exerciseRequest(t, contractID, token, map[string]string{
		"X-Saga-Force-Fail": "F4",
	})
	if status != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted, got %d: %s", status, string(body))
	}

	snap, ok := awaitSagaTerminal(t, sdb, contractID, 30*time.Second)
	if !ok {
		t.Fatalf("saga did not reach a terminal state in time, last state=%s step=%d", snap.State, snap.Step)
	}
	if snap.State != "COMPENSATED" {
		t.Fatalf("expected saga state COMPENSATED, got %s (log=%+v)", snap.State, snap.Log)
	}

	for _, step := range []string{"F1", "F2", "F3"} {
		if rec, found := findStep(snap.Log, step); !found || rec.Outcome != "ok" {
			t.Errorf("expected step %s to succeed, log=%+v", step, snap.Log)
		}
	}
	if rec, found := findStep(snap.Log, "F4"); !found || rec.Outcome == "ok" {
		t.Fatalf("expected step F4 to be force-failed, log=%+v", snap.Log)
	}
	for _, step := range []string{"C3", "C2", "C1"} {
		if rec, found := findStep(snap.Log, step); !found || rec.Outcome != "ok" {
			t.Errorf("expected compensating step %s to succeed, log=%+v", step, snap.Log)
		}
	}
}

// ---------------------------------------------------------------------------
// SG-07: Forced failure at F5 -> saga COMPENSATED via C4, C3, C2, C1.
// ---------------------------------------------------------------------------

func TestSG07_ForceFailF5(t *testing.T) {
	tdb := mustOpenPool(t, tradingDSN)
	bdb := mustOpenPool(t, bankingCoreDSN)
	sdb := mustOpenPool(t, sagaDSN)

	snapshotAndSetAccountFunds(t, bdb, fixtureBuyerID, decimal.RequireFromString("150000.00"), decimal.RequireFromString("150000.00"))
	restoreAccountUsageByOwner(t, bdb, fixtureSellerID)
	snapshotAndSetPortfolio(t, tdb, fixtureSellerID, fixtureListing, 200, 0)

	contractID := createContract(t, tdb, sdb, defaultFixture(2, "50.00"))

	token := mintToken(t, fixtureBuyerID)
	status, body := exerciseRequest(t, contractID, token, map[string]string{
		"X-Saga-Force-Fail": "F5",
	})
	if status != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted, got %d: %s", status, string(body))
	}

	snap, ok := awaitSagaTerminal(t, sdb, contractID, 30*time.Second)
	if !ok {
		t.Fatalf("saga did not reach a terminal state in time, last state=%s step=%d", snap.State, snap.Step)
	}
	if snap.State != "COMPENSATED" {
		t.Fatalf("expected saga state COMPENSATED, got %s (log=%+v)", snap.State, snap.Log)
	}

	for _, step := range []string{"F1", "F2", "F3", "F4"} {
		if rec, found := findStep(snap.Log, step); !found || rec.Outcome != "ok" {
			t.Errorf("expected step %s to succeed, log=%+v", step, snap.Log)
		}
	}
	if rec, found := findStep(snap.Log, "F5"); !found || rec.Outcome == "ok" {
		t.Fatalf("expected step F5 to be force-failed, log=%+v", snap.Log)
	}
	for _, step := range []string{"C4", "C3", "C2", "C1"} {
		if rec, found := findStep(snap.Log, step); !found || rec.Outcome != "ok" {
			t.Errorf("expected compensating step %s to succeed, log=%+v", step, snap.Log)
		}
	}
}

// ---------------------------------------------------------------------------
// SG-08: F3 force-fail combined with a transient C2 compensation failure
// (X-Saga-Compensate-Fail: C2, X-Saga-Compensate-Fail-Times: 1) -> saga still
// reaches COMPENSATED, with C2 retried (recorded as a failed attempt then a
// successful one).
// ---------------------------------------------------------------------------

func TestSG08_CompensationRetry(t *testing.T) {
	tdb := mustOpenPool(t, tradingDSN)
	bdb := mustOpenPool(t, bankingCoreDSN)
	sdb := mustOpenPool(t, sagaDSN)

	snapshotAndSetAccountFunds(t, bdb, fixtureBuyerID, decimal.RequireFromString("150000.00"), decimal.RequireFromString("150000.00"))
	restoreAccountUsageByOwner(t, bdb, fixtureSellerID)
	snapshotAndSetPortfolio(t, tdb, fixtureSellerID, fixtureListing, 200, 0)

	contractID := createContract(t, tdb, sdb, defaultFixture(2, "50.00"))

	token := mintToken(t, fixtureBuyerID)
	status, body := exerciseRequest(t, contractID, token, map[string]string{
		"X-Saga-Force-Fail":          "F3",
		"X-Saga-Compensate-Fail":     "C2",
		"X-Saga-Compensate-Fail-Times": "1",
	})
	if status != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted, got %d: %s", status, string(body))
	}

	// Compensation retry loop is up to 120 * 500ms = 60s; allow generous timeout.
	snap, ok := awaitSagaTerminal(t, sdb, contractID, 90*time.Second)
	if !ok {
		t.Fatalf("saga did not reach a terminal state in time, last state=%s step=%d", snap.State, snap.Step)
	}
	if snap.State != "COMPENSATED" {
		t.Fatalf("expected saga state COMPENSATED, got %s (log=%+v)", snap.State, snap.Log)
	}

	c2Count := countStep(snap.Log, "C2")
	if c2Count < 2 {
		t.Errorf("expected step C2 to be recorded at least twice (failed attempt + successful retry), got %d occurrences, log=%+v", c2Count, snap.Log)
	}

	if rec, found := findStep(snap.Log, "C2"); !found || rec.Outcome != "ok" {
		t.Errorf("expected the final C2 attempt to succeed, log=%+v", snap.Log)
	}
	if rec, found := findStep(snap.Log, "C1"); !found || rec.Outcome != "ok" {
		t.Errorf("expected compensating step C1 to succeed, log=%+v", snap.Log)
	}

	if cc, ok := snap.Log.CompCounts["C2"]; ok {
		t.Logf("CompCounts[C2]=%d", cc)
	}
}

// ---------------------------------------------------------------------------
// SG-09a: banking-core-service is paused (unreachable) before the exercise
// call -> F1 (reserve funds) cannot reach banking-core and times out.
//
// As with SG-03, F1 is the first step: nothing has been reserved yet, so
// otcExerciseFail has no refs to compensate and the saga lands in
// COMPENSATED (not FAILED) with only the failed F1 recorded.
// ---------------------------------------------------------------------------

func TestSG09a_BankingCoreUnavailable(t *testing.T) {
	tdb := mustOpenPool(t, tradingDSN)
	bdb := mustOpenPool(t, bankingCoreDSN)
	sdb := mustOpenPool(t, sagaDSN)

	snapshotAndSetAccountFunds(t, bdb, fixtureBuyerID, decimal.RequireFromString("150000.00"), decimal.RequireFromString("150000.00"))
	restoreAccountUsageByOwner(t, bdb, fixtureSellerID)
	snapshotAndSetPortfolio(t, tdb, fixtureSellerID, fixtureListing, 200, 0)

	contractID := createContract(t, tdb, sdb, defaultFixture(2, "50.00"))

	dockerCmd(t, "pause", bankingCoreContainer)
	t.Cleanup(func() {
		_ = exec.Command("docker", "unpause", bankingCoreContainer).Run()
	})

	token := mintToken(t, fixtureBuyerID)
	status, body := exerciseRequest(t, contractID, token, nil)
	if status != http.StatusAccepted {
		// Unpause immediately so subsequent tests aren't affected if the
		// gateway call itself failed before reaching the saga.
		_ = exec.Command("docker", "unpause", bankingCoreContainer).Run()
		t.Fatalf("expected 202 Accepted, got %d: %s", status, string(body))
	}

	// F1's banking-core call times out after ~30s (saga.Services.Timeout).
	snap, ok := awaitSagaTerminal(t, sdb, contractID, 60*time.Second)

	// Restore banking-core before asserting / failing further.
	if uerr := exec.Command("docker", "unpause", bankingCoreContainer).Run(); uerr != nil {
		t.Fatalf("failed to unpause %s: %v", bankingCoreContainer, uerr)
	}

	if !ok {
		t.Fatalf("saga did not reach a terminal state in time, last state=%s step=%d", snap.State, snap.Step)
	}
	if snap.State != "COMPENSATED" {
		t.Fatalf("expected saga state COMPENSATED while banking-core is unreachable, got %s (log=%+v)", snap.State, snap.Log)
	}
	if rec, found := findStep(snap.Log, "F1"); !found || rec.Outcome == "ok" {
		t.Fatalf("expected step F1 to fail while banking-core is paused, log=%+v", snap.Log)
	}
	for _, step := range []string{"C1", "C2", "C3", "C4"} {
		if _, found := findStep(snap.Log, step); found {
			t.Errorf("did not expect compensating step %s to run when F1 fails, log=%+v", step, snap.Log)
		}
	}
}

// ---------------------------------------------------------------------------
// SG-09b/c: toxiproxy-based latency / outage fault injection on the
// banking-core link.
//
// saga-orchestrator-service's banking-core URL (SAGA_SAGA_SERVICES_BANKING_CORE_URL,
// see setup/docker-compose.yml) is routed through banka_toxiproxy's
// "banking-core" proxy (transparent passthrough by default, defined in
// setup/toxiproxy/toxiproxy.json). These tests add/remove toxics on that
// proxy via toxiproxy's admin API at runtime.
// ---------------------------------------------------------------------------

// TestSG09b_ToxiproxyLatencyExceedsTimeout injects latency on the
// banking-core link that exceeds saga-orchestrator's banking-core client
// timeout (SAGA_SAGA_SERVICES_TIMEOUT, default 30s), so F1's ReserveFunds
// call times out and the saga compensates without ever recording any refs.
func TestSG09b_ToxiproxyLatencyExceedsTimeout(t *testing.T) {
	tdb := mustOpenPool(t, tradingDSN)
	bdb := mustOpenPool(t, bankingCoreDSN)
	sdb := mustOpenPool(t, sagaDSN)

	snapshotAndSetAccountFunds(t, bdb, fixtureBuyerID, decimal.RequireFromString("150000.00"), decimal.RequireFromString("150000.00"))
	restoreAccountUsageByOwner(t, bdb, fixtureSellerID)
	snapshotAndSetPortfolio(t, tdb, fixtureSellerID, fixtureListing, 200, 0)

	contractID := createContract(t, tdb, sdb, defaultFixture(2, "50.00"))

	addLatencyToxic(t, "sg09b-latency", 35000)

	token := mintToken(t, fixtureBuyerID)
	status, body := exerciseRequest(t, contractID, token, nil)
	if status != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted, got %d: %s", status, string(body))
	}

	snap, ok := awaitSagaTerminal(t, sdb, contractID, 60*time.Second)
	if !ok {
		t.Fatalf("saga did not reach a terminal state in time, last state=%s step=%d", snap.State, snap.Step)
	}
	if snap.State != "COMPENSATED" {
		t.Fatalf("expected saga state COMPENSATED with banking-core latency exceeding the client timeout, got %s (log=%+v)", snap.State, snap.Log)
	}
	if rec, found := findStep(snap.Log, "F1"); !found || rec.Outcome == "ok" {
		t.Fatalf("expected step F1 to fail (timeout) due to injected latency, log=%+v", snap.Log)
	}
	for _, step := range []string{"C1", "C2", "C3", "C4"} {
		if _, found := findStep(snap.Log, step); found {
			t.Errorf("did not expect compensating step %s to run when F1 fails, log=%+v", step, snap.Log)
		}
	}
}

// TestSG09c_ToxiproxyBankingCoreDown disables the banking-core toxiproxy
// proxy entirely, so saga-orchestrator's connection to banking-core is
// refused immediately (simulating the upstream being down). F1 fails fast
// and the saga compensates without ever recording any refs.
func TestSG09c_ToxiproxyBankingCoreDown(t *testing.T) {
	tdb := mustOpenPool(t, tradingDSN)
	bdb := mustOpenPool(t, bankingCoreDSN)
	sdb := mustOpenPool(t, sagaDSN)

	snapshotAndSetAccountFunds(t, bdb, fixtureBuyerID, decimal.RequireFromString("150000.00"), decimal.RequireFromString("150000.00"))
	restoreAccountUsageByOwner(t, bdb, fixtureSellerID)
	snapshotAndSetPortfolio(t, tdb, fixtureSellerID, fixtureListing, 200, 0)

	contractID := createContract(t, tdb, sdb, defaultFixture(2, "50.00"))

	setBankingCoreProxyEnabled(t, false)

	token := mintToken(t, fixtureBuyerID)
	status, body := exerciseRequest(t, contractID, token, nil)
	if status != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted, got %d: %s", status, string(body))
	}

	snap, ok := awaitSagaTerminal(t, sdb, contractID, 30*time.Second)
	if !ok {
		t.Fatalf("saga did not reach a terminal state in time, last state=%s step=%d", snap.State, snap.Step)
	}
	if snap.State != "COMPENSATED" {
		t.Fatalf("expected saga state COMPENSATED with banking-core proxy down, got %s (log=%+v)", snap.State, snap.Log)
	}
	if rec, found := findStep(snap.Log, "F1"); !found || rec.Outcome == "ok" {
		t.Fatalf("expected step F1 to fail (connection refused) with banking-core proxy down, log=%+v", snap.Log)
	}
	for _, step := range []string{"C1", "C2", "C3", "C4"} {
		if _, found := findStep(snap.Log, step); found {
			t.Errorf("did not expect compensating step %s to run when F1 fails, log=%+v", step, snap.Log)
		}
	}
}

// ---------------------------------------------------------------------------
// SG-10: Inject a delay at F3 (X-Saga-Inject-Delay: F3:5000) and pause
// banking-core mid-flight (after F1/F2 reserved/locked, before F3 attempts to
// transfer funds), then unpause once F3's banking-core call has timed out,
// expecting the saga to compensate (C2 then C1) and reach COMPENSATED.
//
// Note: `docker pause` freezes the container's processes via the cgroup
// freezer but does not drop already-established/queued TCP connections. If
// banking-core is unpaused *before* F3's HTTP call has timed out, the queued
// request is processed immediately on resume and F3 succeeds anyway (the
// saga then completes normally). So banking-core must stay paused for longer
// than F3's delay (5s) plus the banking-core client timeout (~30s) for F3 to
// actually fail.
// ---------------------------------------------------------------------------

func TestSG10_DelayAndTransientOutage(t *testing.T) {
	tdb := mustOpenPool(t, tradingDSN)
	bdb := mustOpenPool(t, bankingCoreDSN)
	sdb := mustOpenPool(t, sagaDSN)

	snapshotAndSetAccountFunds(t, bdb, fixtureBuyerID, decimal.RequireFromString("150000.00"), decimal.RequireFromString("150000.00"))
	restoreAccountUsageByOwner(t, bdb, fixtureSellerID)
	snapshotAndSetPortfolio(t, tdb, fixtureSellerID, fixtureListing, 200, 0)

	contractID := createContract(t, tdb, sdb, defaultFixture(2, "50.00"))

	token := mintToken(t, fixtureBuyerID)
	status, body := exerciseRequest(t, contractID, token, map[string]string{
		"X-Saga-Inject-Delay": "F3:5000",
	})
	if status != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted, got %d: %s", status, string(body))
	}

	// F1/F2 have no injected delay and complete almost instantly; pause
	// banking-core shortly after so F1/F2 are unaffected but F3 (which
	// sleeps 5s before calling banking-core) is not.
	time.Sleep(500 * time.Millisecond)
	dockerCmd(t, "pause", bankingCoreContainer)

	unpaused := false
	t.Cleanup(func() {
		if !unpaused {
			_ = exec.Command("docker", "unpause", bankingCoreContainer).Run()
		}
	})

	// Keep banking-core paused for the F3 delay (5s) plus the banking-core
	// client timeout (~30s) plus margin, so F3's call genuinely times out
	// instead of succeeding once resumed.
	time.Sleep(38 * time.Second)
	if uerr := exec.Command("docker", "unpause", bankingCoreContainer).Run(); uerr != nil {
		t.Fatalf("failed to unpause %s: %v", bankingCoreContainer, uerr)
	}
	unpaused = true

	snap, ok := awaitSagaTerminal(t, sdb, contractID, 90*time.Second)
	if !ok {
		t.Fatalf("saga did not reach a terminal state in time, last state=%s step=%d", snap.State, snap.Step)
	}
	if snap.State != "COMPENSATED" {
		t.Fatalf("expected saga state COMPENSATED after delayed F3 + transient banking-core outage, got %s (log=%+v)", snap.State, snap.Log)
	}
	if rec, found := findStep(snap.Log, "F3"); !found || rec.Outcome == "ok" {
		t.Fatalf("expected step F3 to fail (banking-core paused at the time of the call), log=%+v", snap.Log)
	}
	for _, step := range []string{"F1", "F2"} {
		if rec, found := findStep(snap.Log, step); !found || rec.Outcome != "ok" {
			t.Errorf("expected step %s to succeed before banking-core was paused, log=%+v", step, snap.Log)
		}
	}
	for _, step := range []string{"C2", "C1"} {
		if rec, found := findStep(snap.Log, step); !found || rec.Outcome != "ok" {
			t.Errorf("expected compensating step %s to succeed once banking-core was unpaused, log=%+v", step, snap.Log)
		}
	}
}

// ---------------------------------------------------------------------------
// SG-11: kill the saga coordinator mid-flight and restart it, expecting the
// saga to eventually be picked back up and reach a terminal state.
//
// NOTE: the spec refers to killing the "trading" container as the SAGA
// coordinator. In this codebase the SAGA orchestration (F1..F5/C1..C5) is
// executed by the separate `saga-orchestrator-service` container, not by
// `banka_trading_service` (trading-service-go only publishes the initial
// ExerciseRequestedEvent). Killing banka_trading_service would not interrupt
// an in-flight saga at all, so this test targets `saga-orchestrator-service`
// instead, which is the actual coordinator process.
// ---------------------------------------------------------------------------

func TestSG11_CoordinatorRestart(t *testing.T) {
	tdb := mustOpenPool(t, tradingDSN)
	bdb := mustOpenPool(t, bankingCoreDSN)
	sdb := mustOpenPool(t, sagaDSN)

	snapshotAndSetAccountFunds(t, bdb, fixtureBuyerID, decimal.RequireFromString("150000.00"), decimal.RequireFromString("150000.00"))
	restoreAccountUsageByOwner(t, bdb, fixtureSellerID)
	snapshotAndSetPortfolio(t, tdb, fixtureSellerID, fixtureListing, 200, 0)

	contractID := createContract(t, tdb, sdb, defaultFixture(2, "50.00"))

	token := mintToken(t, fixtureBuyerID)
	status, body := exerciseRequest(t, contractID, token, map[string]string{
		"X-Saga-Inject-Delay": "F3:5000",
	})
	if status != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted, got %d: %s", status, string(body))
	}

	// Let the saga get into F3's injected delay, then SIGKILL the coordinator.
	time.Sleep(1 * time.Second)
	dockerCmd(t, "kill", "-s", "KILL", sagaOrchestratorContainer)

	restarted := false
	t.Cleanup(func() {
		if !restarted {
			_ = exec.Command("docker", "start", sagaOrchestratorContainer).Run()
		}
	})

	// Give it a moment, then restart the coordinator container.
	time.Sleep(2 * time.Second)
	dockerCmd(t, "start", sagaOrchestratorContainer)
	restarted = true

	snap, ok := awaitSagaTerminal(t, sdb, contractID, 120*time.Second)
	if !ok {
		t.Fatalf("saga did not reach a terminal state after coordinator restart, last state=%s step=%d (log=%+v)", snap.State, snap.Step, snap.Log)
	}
	t.Logf("saga reached terminal state %s after coordinator restart (log=%+v)", snap.State, snap.Log)
}
