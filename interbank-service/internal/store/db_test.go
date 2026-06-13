package store

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/shopspring/decimal"
)

// ---------------------------------------------------------------------------
// Fake querier + rows
// ---------------------------------------------------------------------------

// fakeRow implements pgx.Row. It copies the supplied values into the Scan
// destinations, or returns scanErr.
type fakeRow struct {
	vals    []any
	scanErr error
}

func (r *fakeRow) Scan(dest ...any) error {
	if r.scanErr != nil {
		return r.scanErr
	}
	return assignAll(dest, r.vals)
}

// fakeRows implements pgx.Rows over a slice of value-rows.
type fakeRows struct {
	rows    [][]any
	idx     int
	queryErr error
	scanErr error
	errAfter error
	closed  bool
}

func (r *fakeRows) Close()                                       { r.closed = true }
func (r *fakeRows) Err() error                                   { return r.errAfter }
func (r *fakeRows) CommandTag() pgconn.CommandTag                { return pgconn.CommandTag{} }
func (r *fakeRows) FieldDescriptions() []pgconn.FieldDescription { return nil }
func (r *fakeRows) Values() ([]any, error)                       { return nil, nil }
func (r *fakeRows) RawValues() [][]byte                          { return nil }
func (r *fakeRows) Conn() *pgx.Conn                              { return nil }

func (r *fakeRows) Next() bool {
	if r.idx >= len(r.rows) {
		return false
	}
	r.idx++
	return true
}

func (r *fakeRows) Scan(dest ...any) error {
	if r.scanErr != nil {
		return r.scanErr
	}
	return assignAll(dest, r.rows[r.idx-1])
}

// assignAll copies src values into the pointer destinations in dest.
func assignAll(dest []any, src []any) error {
	if len(dest) != len(src) {
		return errors.New("fake scan: column count mismatch")
	}
	for i := range dest {
		if err := assign(dest[i], src[i]); err != nil {
			return err
		}
	}
	return nil
}

func assign(dst, src any) error {
	switch d := dst.(type) {
	case *string:
		*d = src.(string)
	case *int:
		*d = src.(int)
	case *int64:
		*d = src.(int64)
	case *bool:
		*d = src.(bool)
	case *time.Time:
		*d = src.(time.Time)
	case *decimal.Decimal:
		*d = src.(decimal.Decimal)
	case **int:
		if src == nil {
			*d = nil
		} else {
			v := src.(int)
			*d = &v
		}
	case **string:
		if src == nil {
			*d = nil
		} else {
			v := src.(string)
			*d = &v
		}
	case **int64:
		if src == nil {
			*d = nil
		} else {
			v := src.(int64)
			*d = &v
		}
	case **time.Time:
		if src == nil {
			*d = nil
		} else {
			v := src.(time.Time)
			*d = &v
		}
	case *[]byte:
		if src == nil {
			*d = nil
		} else {
			*d = src.([]byte)
		}
	default:
		return errors.New("fake scan: unsupported dest type")
	}
	return nil
}

// fakeDB implements the querier interface.
type fakeDB struct {
	row       *fakeRow
	rows      *fakeRows
	queryErr  error
	execTag   pgconn.CommandTag
	execErr   error
	lastSQL   string
	lastArgs  []any
}

func (d *fakeDB) QueryRow(_ context.Context, sql string, args ...any) pgx.Row {
	d.lastSQL = sql
	d.lastArgs = args
	if d.row != nil {
		return d.row
	}
	return &fakeRow{scanErr: pgx.ErrNoRows}
}

func (d *fakeDB) Query(_ context.Context, sql string, args ...any) (pgx.Rows, error) {
	d.lastSQL = sql
	d.lastArgs = args
	if d.queryErr != nil {
		return nil, d.queryErr
	}
	if d.rows != nil {
		return d.rows, nil
	}
	return &fakeRows{}, nil
}

func (d *fakeDB) Exec(_ context.Context, sql string, args ...any) (pgconn.CommandTag, error) {
	d.lastSQL = sql
	d.lastArgs = args
	return d.execTag, d.execErr
}

// commandTag builds a pgconn.CommandTag whose RowsAffected matches n.
func commandTag(n int) pgconn.CommandTag {
	switch n {
	case 0:
		return pgconn.NewCommandTag("UPDATE 0")
	default:
		return pgconn.NewCommandTag("UPDATE 1")
	}
}

// ---------------------------------------------------------------------------
// IsUniqueViolation
// ---------------------------------------------------------------------------

func TestIsUniqueViolation(t *testing.T) {
	if !IsUniqueViolation(&pgconn.PgError{Code: "23505"}) {
		t.Error("23505 should be unique violation")
	}
	if IsUniqueViolation(&pgconn.PgError{Code: "12345"}) {
		t.Error("non-23505 should not be unique violation")
	}
	if IsUniqueViolation(errors.New("plain")) {
		t.Error("plain error should not be unique violation")
	}
	if IsUniqueViolation(nil) {
		t.Error("nil should not be unique violation")
	}
}

// ---------------------------------------------------------------------------
// ContractStore
// ---------------------------------------------------------------------------

func contractRowVals() []any {
	now := time.Now()
	return []any{
		"c-1", "neg-1", 222, "C-2", 111, "C-5",
		"AAPL", 10, "USD", decimal.RequireFromString("200.00"), now, ContractStatusActive,
		222, "C-2",
		int64(0), now, nil, nil,
	}
}

func TestContractStore_FindByID_Hit(t *testing.T) {
	db := &fakeDB{row: &fakeRow{vals: contractRowVals()}}
	s := &ContractStore{pool: db}
	got, err := s.FindByID(context.Background(), "c-1")
	if err != nil {
		t.Fatal(err)
	}
	if got == nil || got.ID != "c-1" || got.StockTicker != "AAPL" {
		t.Errorf("got %+v", got)
	}
}

func TestContractStore_FindByNegotiationID_Miss(t *testing.T) {
	db := &fakeDB{row: &fakeRow{scanErr: pgx.ErrNoRows}}
	s := &ContractStore{pool: db}
	got, err := s.FindByNegotiationID(context.Background(), "nope")
	if err != nil || got != nil {
		t.Errorf("expected nil,nil; got %+v, %v", got, err)
	}
}

func TestContractStore_FindByID_ScanError(t *testing.T) {
	db := &fakeDB{row: &fakeRow{scanErr: errors.New("boom")}}
	s := &ContractStore{pool: db}
	if _, err := s.FindByID(context.Background(), "c-1"); err == nil {
		t.Error("expected scan error")
	}
}

func TestContractStore_Insert(t *testing.T) {
	now := time.Now()
	db := &fakeDB{row: &fakeRow{vals: []any{now}}}
	s := &ContractStore{pool: db}
	c := &Contract{ID: "c-1", StrikeAmount: decimal.NewFromInt(1)}
	if err := s.Insert(context.Background(), c); err != nil {
		t.Fatal(err)
	}
	if c.CreatedAt.IsZero() {
		t.Error("CreatedAt not populated")
	}
}

func TestContractStore_SumActiveBySellerAndTicker(t *testing.T) {
	db := &fakeDB{row: &fakeRow{vals: []any{42}}}
	s := &ContractStore{pool: db}
	sum, err := s.SumActiveBySellerAndTicker(context.Background(), 111, "C-5", "AAPL")
	if err != nil {
		t.Fatal(err)
	}
	if sum != 42 {
		t.Errorf("sum=%d want 42", sum)
	}
}

func TestContractStore_UpdateStatus(t *testing.T) {
	for _, st := range []string{ContractStatusExercised, ContractStatusExpired, ContractStatusReleased} {
		db := &fakeDB{execTag: commandTag(1)}
		s := &ContractStore{pool: db}
		if err := s.UpdateStatus(context.Background(), "c-1", st); err != nil {
			t.Errorf("%s: %v", st, err)
		}
	}
	// exec error
	db := &fakeDB{execErr: errors.New("boom")}
	s := &ContractStore{pool: db}
	if err := s.UpdateStatus(context.Background(), "c-1", ContractStatusActive); err == nil {
		t.Error("expected exec error")
	}
}

func TestNewContractStore_Nil(t *testing.T) {
	if NewContractStore(nil) == nil {
		t.Error("constructor returned nil")
	}
}

func TestContractStore_FindByIDForUpdate_UsesRowLock(t *testing.T) {
	db := &fakeDB{row: &fakeRow{vals: contractRowVals()}}
	s := &ContractStore{pool: db}
	got, err := s.FindByIDForUpdate(context.Background(), "c-1")
	if err != nil {
		t.Fatal(err)
	}
	if got == nil || got.ID != "c-1" {
		t.Errorf("got %+v", got)
	}
	if !contains(db.lastSQL, "FOR UPDATE") {
		t.Errorf("FindByIDForUpdate must take a row lock; SQL = %s", db.lastSQL)
	}
}

func TestContractStore_ClaimExerciseByID(t *testing.T) {
	// Won the claim: existed=1, claimed=1.
	won := &fakeDB{row: &fakeRow{vals: []any{1, 1}}}
	s := &ContractStore{pool: won}
	exists, claimed, err := s.ClaimExerciseByID(context.Background(), "c-1")
	if err != nil {
		t.Fatal(err)
	}
	if !exists || !claimed {
		t.Errorf("expected exists+claimed, got exists=%v claimed=%v", exists, claimed)
	}
	if !contains(won.lastSQL, "FOR UPDATE") {
		t.Errorf("claim must lock the row; SQL = %s", won.lastSQL)
	}
	if !contains(won.lastSQL, "'EXERCISING'") || !contains(won.lastSQL, "'ACTIVE'") {
		t.Errorf("claim must CAS ACTIVE→EXERCISING; SQL = %s", won.lastSQL)
	}

	// Exists but lost the CAS (already EXERCISING/EXERCISED): existed=1, claimed=0.
	lost := &fakeDB{row: &fakeRow{vals: []any{1, 0}}}
	exists, claimed, _ = (&ContractStore{pool: lost}).ClaimExerciseByID(context.Background(), "c-1")
	if !exists || claimed {
		t.Errorf("loser: expected exists+!claimed, got exists=%v claimed=%v", exists, claimed)
	}

	// No such contract: existed=0, claimed=0.
	missing := &fakeDB{row: &fakeRow{vals: []any{0, 0}}}
	exists, claimed, _ = (&ContractStore{pool: missing}).ClaimExerciseByID(context.Background(), "nope")
	if exists || claimed {
		t.Errorf("missing: expected !exists+!claimed, got exists=%v claimed=%v", exists, claimed)
	}

	// Scan error propagates.
	boom := &fakeDB{row: &fakeRow{scanErr: errors.New("boom")}}
	if _, _, err := (&ContractStore{pool: boom}).ClaimExerciseByID(context.Background(), "c-1"); err == nil {
		t.Error("expected scan error to propagate")
	}
}

func TestContractStore_ClaimExerciseByNegotiation(t *testing.T) {
	// Won the claim: existed=1, claimed=1.
	won := &fakeDB{row: &fakeRow{vals: []any{1, 1}}}
	s := &ContractStore{pool: won}
	exists, claimed, err := s.ClaimExerciseByNegotiation(context.Background(), "neg-1")
	if err != nil {
		t.Fatal(err)
	}
	if !exists || !claimed {
		t.Errorf("expected exists+claimed, got exists=%v claimed=%v", exists, claimed)
	}
	if !contains(won.lastSQL, "FOR UPDATE") {
		t.Errorf("claim must lock the row; SQL = %s", won.lastSQL)
	}
	// FIX: on the buyer-coordinator side the entry claim already flipped ACTIVE→EXERCISING,
	// so the CAS must accept BOTH ACTIVE and EXERCISING — otherwise the buyer's stock-credit
	// leg is skipped while the strike is still debited (paid, no shares).
	if !contains(won.lastSQL, "'ACTIVE'") || !contains(won.lastSQL, "'EXERCISING'") {
		t.Errorf("CAS must claim ACTIVE or EXERCISING → EXERCISED; SQL = %s", won.lastSQL)
	}

	// Exists but already EXERCISED/terminal: existed=1, claimed=0 → settlement skipped (idempotent).
	lost := &fakeDB{row: &fakeRow{vals: []any{1, 0}}}
	exists, claimed, _ = (&ContractStore{pool: lost}).ClaimExerciseByNegotiation(context.Background(), "neg-1")
	if !exists || claimed {
		t.Errorf("already-settled: expected exists+!claimed, got exists=%v claimed=%v", exists, claimed)
	}

	// No matching contract: existed=0, claimed=0 → gate proceeds ungated.
	missing := &fakeDB{row: &fakeRow{vals: []any{0, 0}}}
	exists, claimed, _ = (&ContractStore{pool: missing}).ClaimExerciseByNegotiation(context.Background(), "nope")
	if exists || claimed {
		t.Errorf("missing: expected !exists+!claimed, got exists=%v claimed=%v", exists, claimed)
	}

	// Scan error propagates.
	boom := &fakeDB{row: &fakeRow{scanErr: errors.New("boom")}}
	if _, _, err := (&ContractStore{pool: boom}).ClaimExerciseByNegotiation(context.Background(), "neg-1"); err == nil {
		t.Error("expected scan error to propagate")
	}
}

func TestContractStore_RevertExercising(t *testing.T) {
	db := &fakeDB{execTag: commandTag(1)}
	s := &ContractStore{pool: db}
	if err := s.RevertExercising(context.Background(), "c-1"); err != nil {
		t.Fatal(err)
	}
	if !contains(db.lastSQL, "'ACTIVE'") || !contains(db.lastSQL, "status = 'EXERCISING'") {
		t.Errorf("revert must only touch EXERCISING rows and set ACTIVE; SQL = %s", db.lastSQL)
	}

	// exec error propagates.
	boom := &fakeDB{execErr: errors.New("boom")}
	if err := (&ContractStore{pool: boom}).RevertExercising(context.Background(), "c-1"); err == nil {
		t.Error("expected exec error to propagate")
	}
}

func TestContractStore_ListForUser_ScopedAndAll(t *testing.T) {
	// Scoped (non-admin): filters by buyer_id OR seller_id and binds the user id.
	scoped := &fakeDB{rows: &fakeRows{rows: [][]any{contractRowVals(), contractRowVals()}}}
	s := &ContractStore{pool: scoped}
	out, err := s.ListForUser(context.Background(), "C-2", false)
	if err != nil {
		t.Fatalf("ListForUser scoped: %v", err)
	}
	if len(out) != 2 {
		t.Errorf("expected 2 rows, got %d", len(out))
	}
	if len(scoped.lastArgs) != 1 || scoped.lastArgs[0] != "C-2" {
		t.Errorf("expected user-id bound arg, got %v", scoped.lastArgs)
	}
	if !contains(scoped.lastSQL, "buyer_id = $1 OR seller_id = $1") {
		t.Errorf("scoped query should filter by user, got: %s", scoped.lastSQL)
	}

	// Admin (includeAll): no user-id arg, no WHERE filter.
	all := &fakeDB{rows: &fakeRows{rows: [][]any{contractRowVals()}}}
	s2 := &ContractStore{pool: all}
	out2, err := s2.ListForUser(context.Background(), "", true)
	if err != nil {
		t.Fatalf("ListForUser all: %v", err)
	}
	if len(out2) != 1 {
		t.Errorf("expected 1 row, got %d", len(out2))
	}
	if len(all.lastArgs) != 0 {
		t.Errorf("admin list should bind no args, got %v", all.lastArgs)
	}
}

// contains is a tiny substring helper (avoids importing strings just for tests).
func contains(haystack, needle string) bool {
	return len(haystack) >= len(needle) && (func() bool {
		for i := 0; i+len(needle) <= len(haystack); i++ {
			if haystack[i:i+len(needle)] == needle {
				return true
			}
		}
		return false
	})()
}

// ---------------------------------------------------------------------------
// MessageStore
// ---------------------------------------------------------------------------

func messageRowVals() []any {
	now := time.Now()
	return []any{
		int64(1), DirectionInbound, 222, "key-1", "NEW_TX", MessageStatusProcessed,
		"{}", nil, nil, 0,
		nil, nil, now, nil, int64(0),
	}
}

func TestMessageStore_Insert(t *testing.T) {
	now := time.Now()
	db := &fakeDB{row: &fakeRow{vals: []any{int64(7), now}}}
	s := &MessageStore{pool: db}
	m := &Message{Direction: DirectionInbound}
	if err := s.Insert(context.Background(), m); err != nil {
		t.Fatal(err)
	}
	if m.ID != 7 || m.Version != 0 {
		t.Errorf("m=%+v", m)
	}
}

func TestMessageStore_Insert_Error(t *testing.T) {
	db := &fakeDB{row: &fakeRow{scanErr: &pgconn.PgError{Code: "23505"}}}
	s := &MessageStore{pool: db}
	err := s.Insert(context.Background(), &Message{})
	if !IsUniqueViolation(err) {
		t.Errorf("expected unique violation, got %v", err)
	}
}

func TestMessageStore_Lookup_Hit(t *testing.T) {
	db := &fakeDB{row: &fakeRow{vals: messageRowVals()}}
	s := &MessageStore{pool: db}
	got, err := s.Lookup(context.Background(), DirectionInbound, 222, "key-1")
	if err != nil {
		t.Fatal(err)
	}
	if got == nil || got.LocallyGeneratedKey != "key-1" {
		t.Errorf("got %+v", got)
	}
}

func TestMessageStore_Lookup_Miss(t *testing.T) {
	db := &fakeDB{row: &fakeRow{scanErr: pgx.ErrNoRows}}
	s := &MessageStore{pool: db}
	got, err := s.Lookup(context.Background(), DirectionInbound, 222, "key-1")
	if err != nil || got != nil {
		t.Errorf("expected nil,nil; got %+v, %v", got, err)
	}
}

func TestMessageStore_Lookup_Error(t *testing.T) {
	db := &fakeDB{row: &fakeRow{scanErr: errors.New("boom")}}
	s := &MessageStore{pool: db}
	if _, err := s.Lookup(context.Background(), DirectionInbound, 222, "k"); err == nil {
		t.Error("expected error")
	}
}

func TestMessageStore_FindPending(t *testing.T) {
	db := &fakeDB{rows: &fakeRows{rows: [][]any{messageRowVals(), messageRowVals()}}}
	s := &MessageStore{pool: db}
	out, err := s.FindPending(context.Background(), 5, time.Now(), 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 2 {
		t.Errorf("len=%d want 2", len(out))
	}
}

func TestMessageStore_FindPending_QueryError(t *testing.T) {
	db := &fakeDB{queryErr: errors.New("boom")}
	s := &MessageStore{pool: db}
	if _, err := s.FindPending(context.Background(), 5, time.Now(), 10); err == nil {
		t.Error("expected query error")
	}
}

func TestMessageStore_FindPending_ScanError(t *testing.T) {
	db := &fakeDB{rows: &fakeRows{rows: [][]any{messageRowVals()}, scanErr: errors.New("scan")}}
	s := &MessageStore{pool: db}
	if _, err := s.FindPending(context.Background(), 5, time.Now(), 10); err == nil {
		t.Error("expected scan error")
	}
}

func TestMessageStore_UpdateOptimistic_Success(t *testing.T) {
	db := &fakeDB{execTag: commandTag(1)}
	s := &MessageStore{pool: db}
	m := &Message{ID: 1, Version: 0}
	if err := s.UpdateOptimistic(context.Background(), m); err != nil {
		t.Fatal(err)
	}
	if m.Version != 1 {
		t.Errorf("version=%d want 1", m.Version)
	}
}

func TestMessageStore_UpdateOptimistic_Conflict(t *testing.T) {
	db := &fakeDB{execTag: commandTag(0)}
	s := &MessageStore{pool: db}
	err := s.UpdateOptimistic(context.Background(), &Message{ID: 1})
	if !errors.Is(err, ErrOptimisticLockConflict) {
		t.Errorf("expected conflict, got %v", err)
	}
}

func TestMessageStore_UpdateOptimistic_ExecError(t *testing.T) {
	db := &fakeDB{execErr: errors.New("boom")}
	s := &MessageStore{pool: db}
	if err := s.UpdateOptimistic(context.Background(), &Message{}); err == nil {
		t.Error("expected exec error")
	}
}

// ---------------------------------------------------------------------------
// NegotiationStore
// ---------------------------------------------------------------------------

func negotiationRowVals() []any {
	now := time.Now()
	return []any{
		"neg-1", 222, "C-2", 111, "C-5",
		"AAPL", 10, "USD", decimal.RequireFromString("200.00"),
		"USD", decimal.RequireFromString("500.00"), now,
		222, "C-2",
		true, true, nil, nil,
		int64(0), now, now,
	}
}

func TestNegotiationStore_Insert(t *testing.T) {
	now := time.Now()
	db := &fakeDB{row: &fakeRow{vals: []any{now, now}}}
	s := &NegotiationStore{pool: db}
	n := &Negotiation{ID: "neg-1", PriceAmount: decimal.NewFromInt(1), PremiumAmount: decimal.NewFromInt(1)}
	if err := s.Insert(context.Background(), n); err != nil {
		t.Fatal(err)
	}
	if n.CreatedAt.IsZero() || n.LastModifiedAt.IsZero() {
		t.Error("timestamps not populated")
	}
}

func TestNegotiationStore_FindByID_Hit(t *testing.T) {
	db := &fakeDB{row: &fakeRow{vals: negotiationRowVals()}}
	s := &NegotiationStore{pool: db}
	got, err := s.FindByID(context.Background(), "neg-1")
	if err != nil {
		t.Fatal(err)
	}
	if got == nil || got.StockTicker != "AAPL" {
		t.Errorf("got %+v", got)
	}
}

func TestNegotiationStore_FindByID_Miss(t *testing.T) {
	db := &fakeDB{row: &fakeRow{scanErr: pgx.ErrNoRows}}
	s := &NegotiationStore{pool: db}
	got, err := s.FindByID(context.Background(), "x")
	if err != nil || got != nil {
		t.Errorf("expected nil,nil; got %+v, %v", got, err)
	}
}

func TestNegotiationStore_FindByAuthoritativeRef(t *testing.T) {
	db := &fakeDB{row: &fakeRow{vals: negotiationRowVals()}}
	s := &NegotiationStore{pool: db}
	got, err := s.FindByAuthoritativeRef(context.Background(), 111, "neg-1")
	if err != nil || got == nil {
		t.Errorf("got %+v, %v", got, err)
	}
}

func TestNegotiationStore_FindByRoutingAndID(t *testing.T) {
	db := &fakeDB{row: &fakeRow{vals: negotiationRowVals()}}
	s := &NegotiationStore{pool: db}
	got, err := s.FindByRoutingAndID(context.Background(), 111, "neg-1")
	if err != nil || got == nil {
		t.Errorf("got %+v, %v", got, err)
	}
}

func TestNegotiationStore_UpdateCounter_Success(t *testing.T) {
	db := &fakeDB{execTag: commandTag(1)}
	s := &NegotiationStore{pool: db}
	n := &Negotiation{ID: "neg-1", Version: 0, PriceAmount: decimal.NewFromInt(1), PremiumAmount: decimal.NewFromInt(1)}
	if err := s.UpdateCounter(context.Background(), n); err != nil {
		t.Fatal(err)
	}
	if n.Version != 1 {
		t.Errorf("version=%d want 1", n.Version)
	}
}

func TestNegotiationStore_UpdateCounter_Conflict(t *testing.T) {
	db := &fakeDB{execTag: commandTag(0)}
	s := &NegotiationStore{pool: db}
	n := &Negotiation{ID: "neg-1", PriceAmount: decimal.NewFromInt(1), PremiumAmount: decimal.NewFromInt(1)}
	if err := s.UpdateCounter(context.Background(), n); !errors.Is(err, ErrOptimisticLockConflict) {
		t.Errorf("expected conflict, got %v", err)
	}
}

func TestNegotiationStore_UpdateCounter_ExecError(t *testing.T) {
	db := &fakeDB{execErr: errors.New("boom")}
	s := &NegotiationStore{pool: db}
	n := &Negotiation{PriceAmount: decimal.NewFromInt(1), PremiumAmount: decimal.NewFromInt(1)}
	if err := s.UpdateCounter(context.Background(), n); err == nil {
		t.Error("expected exec error")
	}
}

func TestNegotiationStore_MarkClosed(t *testing.T) {
	db := &fakeDB{execTag: commandTag(1)}
	s := &NegotiationStore{pool: db}
	if err := s.MarkClosed(context.Background(), "neg-1"); err != nil {
		t.Fatal(err)
	}
}

func TestNegotiationStore_ListForUser(t *testing.T) {
	db := &fakeDB{rows: &fakeRows{rows: [][]any{negotiationRowVals(), negotiationRowVals()}}}
	s := &NegotiationStore{pool: db}
	out, err := s.ListForUser(context.Background(), "C-2", false)
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 2 {
		t.Errorf("len=%d want 2", len(out))
	}
}

func TestNegotiationStore_ListForUser_All(t *testing.T) {
	db := &fakeDB{rows: &fakeRows{rows: [][]any{negotiationRowVals()}}}
	s := &NegotiationStore{pool: db}
	out, err := s.ListForUser(context.Background(), "", true)
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 1 {
		t.Errorf("len=%d want 1", len(out))
	}
}

func TestNegotiationStore_ListForUser_QueryError(t *testing.T) {
	db := &fakeDB{queryErr: errors.New("boom")}
	s := &NegotiationStore{pool: db}
	if _, err := s.ListForUser(context.Background(), "C-2", false); err == nil {
		t.Error("expected query error")
	}
}

// ---------------------------------------------------------------------------
// TransactionStore
// ---------------------------------------------------------------------------

func transactionRowVals(refs []byte) []any {
	now := time.Now()
	return []any{
		int64(1), 222, "tx-1", TxStatusPrepared,
		"[]", refs, "{}", now, nil,
	}
}

func TestTransactionStore_PersistPrepared(t *testing.T) {
	now := time.Now()
	db := &fakeDB{row: &fakeRow{vals: []any{int64(5), now}}}
	s := &TransactionStore{pool: db}
	tx := &Transaction{TransactionIdRouting: 222, TransactionIdLocal: "tx-1"}
	if err := s.PersistPrepared(context.Background(), tx); err != nil {
		t.Fatal(err)
	}
	if tx.ID != 5 {
		t.Errorf("ID=%d want 5", tx.ID)
	}
}

func TestTransactionStore_PersistPrepared_WithRefs(t *testing.T) {
	now := time.Now()
	db := &fakeDB{row: &fakeRow{vals: []any{int64(6), now}}}
	s := &TransactionStore{pool: db}
	tx := &Transaction{
		TransactionIdRouting: 222,
		TransactionIdLocal:   "tx-2",
		ReservationRefs:      []ReservationRef{{Kind: RefKindMonas, ReservationID: "res-1"}},
	}
	if err := s.PersistPrepared(context.Background(), tx); err != nil {
		t.Fatal(err)
	}
}

func TestTransactionStore_FindByID_Hit(t *testing.T) {
	db := &fakeDB{row: &fakeRow{vals: transactionRowVals([]byte(`[{"kind":"MONAS","reservationId":"res-1"}]`))}}
	s := &TransactionStore{pool: db}
	got, err := s.FindByID(context.Background(), 222, "tx-1")
	if err != nil {
		t.Fatal(err)
	}
	if got == nil || got.Status != TxStatusPrepared || len(got.ReservationRefs) != 1 {
		t.Errorf("got %+v", got)
	}
}

func TestTransactionStore_FindByID_Miss(t *testing.T) {
	db := &fakeDB{row: &fakeRow{scanErr: pgx.ErrNoRows}}
	s := &TransactionStore{pool: db}
	got, err := s.FindByID(context.Background(), 222, "x")
	if err != nil || got != nil {
		t.Errorf("expected nil,nil; got %+v, %v", got, err)
	}
}

func TestTransactionStore_FindByID_BadRefsJSON(t *testing.T) {
	db := &fakeDB{row: &fakeRow{vals: transactionRowVals([]byte(`{not json`))}}
	s := &TransactionStore{pool: db}
	if _, err := s.FindByID(context.Background(), 222, "tx-1"); err == nil {
		t.Error("expected unmarshal error for bad refs JSON")
	}
}

func TestTransactionStore_UpdateStatus(t *testing.T) {
	db := &fakeDB{execTag: commandTag(1)}
	s := &TransactionStore{pool: db}
	if err := s.UpdateStatus(context.Background(), 222, "tx-1", TxStatusCommitted); err != nil {
		t.Fatal(err)
	}
}
