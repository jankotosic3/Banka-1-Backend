package service

import (
	"context"
	"database/sql"
	"errors"
	"strings"

	"banka1/banking-core-service-go/internal/decimal"
	"banka1/banking-core-service-go/internal/uuid"
)

const (
	InterbankHeld      = "HELD"
	InterbankCommitted = "COMMITTED"
	InterbankReleased  = "RELEASED"
)

// interbankExternalSender is the sentinel stored in payment_table.from_account_number
// when a cross-bank credit arrives from a foreign Person-only counterparty that has no
// real 18-digit account number on the wire (e.g. an inbound OTC premium). The column is
// VARCHAR(255) NOT NULL, so an empty fromAccount cannot be persisted as NULL/blank — the
// sentinel keeps the audit row insertable while clearly marking the foreign origin.
const interbankExternalSender = "INTERBANK-EXTERNAL"

// normalizeInterbankFromAccount returns the account string to persist as the sender of a
// recorded inter-bank payment. A blank fromAccount (foreign Person-only sender) collapses
// to the interbankExternalSender sentinel; any non-blank value is passed through trimmed.
func normalizeInterbankFromAccount(fromAccount string) string {
	trimmed := strings.TrimSpace(fromAccount)
	if trimmed == "" {
		return interbankExternalSender
	}
	return trimmed
}

type InterbankService struct {
	db       *sql.DB
	accounts *AccountService
}

type ReserveMonasRequest struct {
	AccountNum           string          `json:"accountNum"`
	Currency             string          `json:"currency"`
	Amount               decimal.Decimal `json:"amount"`
	TransactionIDRouting int             `json:"transactionIdRouting"`
	TransactionIDLocal   string          `json:"transactionIdLocal"`
}

type ReserveMonasResponse struct {
	ReservationID string `json:"reservationId"`
}

type AccountResolveResponse struct {
	OwnerType        string          `json:"ownerType"`
	OwnerID          int64           `json:"ownerId"`
	Currency         string          `json:"currency"`
	AvailableBalance decimal.Decimal `json:"availableBalance"`
}

type AccountByOwnerResponse struct {
	AccountNumber string `json:"accountNumber"`
}

// RecordInterbankPaymentRequest is the body for POST /internal/interbank/record-payment.
// It is posted by interbank-service after a cross-bank 2PC money leg commits, so the
// movement shows up in this bank's transaction history (payment_table). The counterparty
// account may be foreign (belonging to another bank); in that case its client id is left
// as 0 because we have no local owner for it.
type RecordInterbankPaymentRequest struct {
	OrderNumber    string          `json:"orderNumber"`    // idempotency key (UNIQUE on payment_table.order_number)
	FromAccount    string          `json:"fromAccount"`    // sender 18-digit account (local on outbound, foreign on inbound)
	ToAccount      string          `json:"toAccount"`      // recipient 18-digit account (foreign on outbound, local on inbound)
	Amount         decimal.Decimal `json:"amount"`         // moved amount (initial = final, same-currency interbank)
	Currency       string          `json:"currency"`       // ISO currency code (from = to)
	RecipientName  string          `json:"recipientName"`  // optional display name
	PaymentPurpose string          `json:"paymentPurpose"` // optional purpose text
}

func NewInterbankService(db *sql.DB, accounts *AccountService) *InterbankService {
	return &InterbankService{db: db, accounts: accounts}
}

// RecordInterbankPayment inserts a COMPLETED payment_table row for a cross-bank money
// movement so it appears in transaction history. Idempotent on order_number
// (ON CONFLICT DO NOTHING), matching the InternalService transfer audit style.
//
// sender_client_id / recipient_client_id are resolved from the local account when the
// account belongs to this bank; the foreign counterparty has no local owner so its id
// is left as 0 (the column is NOT NULL, so a sentinel 0 is used rather than NULL).
func (s *InterbankService) RecordInterbankPayment(ctx context.Context, req RecordInterbankPaymentRequest) error {
	// fromAccount is NO LONGER required: an inbound cross-bank credit from a foreign
	// Person-only counterparty (e.g. an OTC premium) carries no real sender account on
	// the wire, so it arrives blank. Collapsing it to a sentinel keeps the audit row
	// insertable (from_account_number is VARCHAR(255) NOT NULL) instead of 400-ing and
	// dropping the received money out of transaction history. orderNumber/toAccount stay
	// mandatory.
	if req.OrderNumber == "" || req.ToAccount == "" {
		return BadRequest("orderNumber i toAccount su obavezni")
	}
	if req.Amount.Sign() <= 0 {
		return BadRequest("Amount must be positive")
	}
	currency := strings.ToUpper(strings.TrimSpace(req.Currency))
	if currency == "" {
		return BadRequest("currency je obavezan")
	}

	fromAccount := normalizeInterbankFromAccount(req.FromAccount)
	senderClientID := s.resolveLocalOwner(ctx, fromAccount)
	recipientClientID := s.resolveLocalOwner(ctx, req.ToAccount)

	recipientName := strings.TrimSpace(req.RecipientName)
	if recipientName == "" {
		recipientName = "Account " + req.ToAccount
	}
	purpose := strings.TrimSpace(req.PaymentPurpose)
	if purpose == "" {
		purpose = "Interbank payment"
	}

	_, err := s.db.ExecContext(ctx, `
INSERT INTO payment_table (
    from_account_number, to_account_number, initial_amount, final_amount, commission,
    sender_client_id, recipient_client_id, recipient_name,
    payment_code, reference_number, payment_purpose, status,
    from_currency, to_currency, exchange_rate, order_number, created_at, updated_at, version
) VALUES ($1, $2, $3, $3, 0, $4, $5, $6, '289', NULL, $7, 'COMPLETED',
          $8, $8, 1, $9, NOW(), NOW(), 0)
ON CONFLICT (order_number) DO NOTHING
`, fromAccount, req.ToAccount, req.Amount,
		senderClientID, recipientClientID, recipientName,
		purpose, currency, req.OrderNumber)
	return err
}

// resolveLocalOwner returns the owner (vlasnik) id of a local account, or 0 when the
// account is not found locally (e.g. it belongs to a foreign bank). Best-effort:
// resolution failures degrade to 0 rather than failing the audit record.
func (s *InterbankService) resolveLocalOwner(ctx context.Context, accountNumber string) int64 {
	acc, err := s.accounts.getByNumber(ctx, s.db, accountNumber, false)
	if err != nil {
		return 0
	}
	return acc.OwnerID
}

func (s *InterbankService) ReserveMonas(ctx context.Context, req ReserveMonasRequest) (string, error) {
	if req.AccountNum == "" || req.Currency == "" || req.TransactionIDLocal == "" {
		return "", BadRequest("accountNum, currency i transactionIdLocal su obavezni")
	}
	if req.Amount.Sign() <= 0 {
		return "", BadRequest("Amount must be positive")
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer tx.Rollback()

	account, err := s.accounts.getByNumber(ctx, tx, req.AccountNum, true)
	if err != nil {
		return "", err
	}
	if account.Currency != req.Currency {
		return "", BadRequest("Currency mismatch: account=%s requested=%s", account.Currency, req.Currency)
	}
	if account.AvailableBalance.Cmp(req.Amount) < 0 {
		return "", Conflict("ERR_INSUFFICIENT_ASSET", "Nedovoljno raspolozivo stanje", "Insufficient available balance: have=%s need=%s", account.AvailableBalance.String(), req.Amount.String())
	}
	if _, err := tx.ExecContext(ctx, `
UPDATE account_table
   SET raspolozivo_stanje = raspolozivo_stanje - $1,
       version = COALESCE(version, 0) + 1,
       updated_at = now()
 WHERE id = $2
`, req.Amount, account.ID); err != nil {
		return "", err
	}

	reservationID, err := uuid.New()
	if err != nil {
		return "", err
	}
	if _, err := tx.ExecContext(ctx, `
INSERT INTO interbank_reservations (
    reservation_id, transaction_id_routing, transaction_id_local,
    account_number, currency, amount, status
) VALUES ($1::uuid, $2, $3, $4, $5, $6, 'HELD')
`, reservationID, req.TransactionIDRouting, req.TransactionIDLocal, req.AccountNum, req.Currency, req.Amount); err != nil {
		return "", err
	}
	if err := tx.Commit(); err != nil {
		return "", err
	}
	return reservationID, nil
}

func (s *InterbankService) CommitReservation(ctx context.Context, reservationID string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	res, err := s.loadReservationForUpdate(ctx, tx, reservationID)
	if err != nil {
		return err
	}
	if res.Status == InterbankCommitted {
		return nil
	}
	if res.Status != InterbankHeld {
		return Conflict("ERR_INVALID_RESERVATION_STATE", "Neispravno stanje rezervacije", "Cannot commit reservation %s in state %s", reservationID, res.Status)
	}
	account, err := s.accounts.getByNumber(ctx, tx, res.AccountNumber, true)
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
UPDATE account_table
   SET stanje = stanje - $1,
       version = COALESCE(version, 0) + 1,
       updated_at = now()
 WHERE id = $2
`, res.Amount, account.ID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
UPDATE interbank_reservations
   SET status = 'COMMITTED', finalized_at = NOW()
 WHERE reservation_id = $1::uuid
`, reservationID); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *InterbankService) ReleaseReservation(ctx context.Context, reservationID string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	res, err := s.loadReservationForUpdate(ctx, tx, reservationID)
	if err != nil {
		return err
	}
	if res.Status == InterbankReleased {
		return nil
	}
	if res.Status == InterbankCommitted {
		return Conflict("ERR_INVALID_RESERVATION_STATE", "Neispravno stanje rezervacije", "Cannot release reservation %s - already COMMITTED", reservationID)
	}
	account, err := s.accounts.getByNumber(ctx, tx, res.AccountNumber, true)
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
UPDATE account_table
   SET raspolozivo_stanje = raspolozivo_stanje + $1,
       version = COALESCE(version, 0) + 1,
       updated_at = now()
 WHERE id = $2
`, res.Amount, account.ID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
UPDATE interbank_reservations
   SET status = 'RELEASED', finalized_at = NOW()
 WHERE reservation_id = $1::uuid
`, reservationID); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *InterbankService) AccountByOwner(ctx context.Context, ownerID int64, currency string) (AccountByOwnerResponse, error) {
	acc, err := s.accounts.getByOwnerAndCurrency(ctx, s.db, ownerID, currency, false)
	if err != nil {
		return AccountByOwnerResponse{}, err
	}
	return AccountByOwnerResponse{AccountNumber: acc.AccountNumber}, nil
}

func (s *InterbankService) ResolveAccount(ctx context.Context, accountNumber string) (AccountResolveResponse, error) {
	acc, err := s.accounts.getByNumber(ctx, s.db, accountNumber, false)
	if err != nil {
		return AccountResolveResponse{}, err
	}
	return AccountResolveResponse{
		OwnerType:        resolveOwnerType(acc.OwnerID),
		OwnerID:          acc.OwnerID,
		Currency:         acc.Currency,
		AvailableBalance: acc.AvailableBalance,
	}, nil
}

type interbankReservationRow struct {
	AccountNumber string
	Amount        decimal.Decimal
	Status        string
}

func (s *InterbankService) loadReservationForUpdate(ctx context.Context, tx *sql.Tx, reservationID string) (interbankReservationRow, error) {
	var out interbankReservationRow
	err := tx.QueryRowContext(ctx, `
SELECT account_number, amount, status
  FROM interbank_reservations
 WHERE reservation_id = $1::uuid
 FOR UPDATE
`, reservationID).Scan(&out.AccountNumber, &out.Amount, &out.Status)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return interbankReservationRow{}, NotFound("Reservation not found: %s", reservationID)
		}
		return interbankReservationRow{}, err
	}
	return out, nil
}

func resolveOwnerType(ownerID int64) string {
	switch ownerID {
	case -1:
		return "BANK"
	case -2:
		return "STATE"
	case -3:
		return "EXCHANGE"
	default:
		return "CLIENT"
	}
}
