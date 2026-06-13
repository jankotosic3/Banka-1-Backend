// Package service contains the core business logic for interbank-service:
// validation, 2PC execution, OTC negotiation lifecycle, outbound coordination.
package service

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
)

// ErrAccountNotFound is the sentinel that BankingCoreReader implementations
// must return when a lookup misses. Validator translates this to NO_SUCH_ACCOUNT.
var ErrAccountNotFound = errors.New("service: account not found")

// AccountInfo is the minimal view of an account that the validator needs.
type AccountInfo struct {
	OwnerType        string
	OwnerID          int64
	Currency         string
	AvailableBalance decimal.Decimal
}

// NegotiationLite is the minimal view of a negotiation row that the validator needs.
//
// ContractStatus / ContractSettlement carry the lifecycle of the OPTION CONTRACT that
// the negotiation produced on accept (empty/zero when no contract exists yet). They are
// what makes an EXERCISE tx pass: a negotiation is closed (IsOngoing=false) the moment it
// is accepted, but the resulting option contract is still EXERCISABLE while it is ACTIVE
// and its settlement date has not passed. Binding exercisability to the contract — not to
// the negotiation's IsOngoing flag — is what lets an accepted cross-bank option actually
// be used (see validateNegotiationOpen / validateOption).
type NegotiationLite struct {
	IsOngoing      bool
	SettlementDate time.Time
	Amount         int
	PricePerUnit   decimal.Decimal

	// ContractStatus is the status of the option contract backing this negotiation
	// (e.g. "ACTIVE", "EXERCISED", "EXPIRED"), or "" when no contract exists yet
	// (accept has not happened). ContractSettlement is that contract's settlement date.
	ContractStatus     string
	ContractSettlement time.Time
}

// ContractStatusActive mirrors store.ContractStatusActive — the only contract status that
// makes an option exercisable. Duplicated here to keep the validator free of a store import.
const ContractStatusActive = "ACTIVE"

// negotiationExercisable reports whether the option backing this negotiation can still be
// exercised: there is an ACTIVE contract whose settlement date is not before now. This is
// the EXERCISE-time predicate (negotiation already closed on accept, contract still live).
func negotiationExercisable(neg *NegotiationLite, now time.Time) bool {
	if neg == nil || neg.ContractStatus != ContractStatusActive {
		return false
	}
	// Settlement strictly in the future (option is unusable on/after settlement).
	return neg.ContractSettlement.After(now)
}

// negotiationAcceptable reports whether the negotiation is still in its ACCEPT-time window:
// ongoing and not past settlement. This is the original (pre-accept) predicate.
func negotiationAcceptable(neg *NegotiationLite, now time.Time) bool {
	if neg == nil || !neg.IsOngoing {
		return false
	}
	return !neg.SettlementDate.Before(now)
}

// BankingCoreReader is what the validator needs from banking-core internal endpoints.
type BankingCoreReader interface {
	// ResolveAccount looks up an account by 18-digit number.
	// Returns ErrAccountNotFound if the account does not exist.
	ResolveAccount(ctx context.Context, num string) (*AccountInfo, error)
	// FindAccountByOwnerAndCurrency resolves (ownerID, currency) to an account number.
	// Returns ErrAccountNotFound if no matching account exists.
	FindAccountByOwnerAndCurrency(ctx context.Context, ownerID int64, currency string) (string, error)
}

// TradingReader is what the validator needs from trading-service internal endpoints.
// Currently unused by the validator (option lookups use our own store).
// Kept for future expansion (e.g. stock-existence checks for NO_SUCH_ASSET on STOCK postings).
type TradingReader interface{}

// NegotiationReader is what the validator needs to validate Option postings.
// In production this is implemented by a store adapter; in tests by a fake.
type NegotiationReader interface {
	// FindNegotiation returns the negotiation for id.
	// Returns (nil, non-nil-error) if not found; (nil, nil) is invalid and treated as not-found.
	FindNegotiation(ctx context.Context, id protocol.ForeignBankId) (*NegotiationLite, error)
}

// Validator runs each NEW_TX posting through the 8 NoVoteReason branches per spec §2.12.1.
type Validator struct {
	myRouting int
	negs      NegotiationReader
	bc        BankingCoreReader
	td        TradingReader
}

// NewValidator constructs a Validator for our routing-number identity.
func NewValidator(myRouting int, negs NegotiationReader, bc BankingCoreReader, td TradingReader) *Validator {
	return &Validator{myRouting: myRouting, negs: negs, bc: bc, td: td}
}

// BalanceCheck verifies that postings sum to zero per asset-key.
// Returns up to one UNBALANCED_TX reason if any asset key is unbalanced; never errors.
func (v *Validator) BalanceCheck(postings []protocol.Posting) []protocol.NoVoteReason {
	sums := make(map[string]decimal.Decimal)
	for _, p := range postings {
		k := assetKey(p.Asset)
		sums[k] = sums[k].Add(p.Amount)
	}
	for _, s := range sums {
		if !s.IsZero() {
			return []protocol.NoVoteReason{{Reason: protocol.ReasonUnbalancedTx}}
		}
	}
	return nil
}

// assetKey derives the balance-check grouping key from an asset.
func assetKey(a protocol.Asset) string {
	switch v := a.(type) {
	case *protocol.MonasAsset:
		return "MONAS:" + v.Currency
	case *protocol.StockAsset:
		return "STOCK:" + v.Ticker
	case *protocol.OptionAsset:
		return fmt.Sprintf("OPTION:%d:%s", v.NegotiationId.RoutingNumber, v.NegotiationId.Id)
	default:
		return "UNKNOWN"
	}
}

// ValidatePosting runs the single-posting validation branches.
// Only validates "ours" postings (account references our routing number).
// Returns (nil, nil) when the posting is partner-side OR is valid.
// Returns (*NoVoteReason, nil) for a business-rule violation.
// Returns (nil, error) for unexpected infrastructure errors.
func (v *Validator) ValidatePosting(ctx context.Context, p protocol.Posting) (*protocol.NoVoteReason, error) {
	switch acc := p.Account.(type) {

	case *protocol.RealAccount:
		return v.validateRealAccount(ctx, acc, p)

	case *protocol.PersonAccount:
		return v.validatePersonAccount(ctx, acc, p)

	case *protocol.OptionPseudoAccount:
		return v.validateOptionPseudoAccount(ctx, acc, p)
	}

	// Unknown account type — not ours to validate.
	return nil, nil
}

// validateRealAccount handles the ACCOUNT type: ACCOUNT+MONAS is normal;
// ACCOUNT+anything-else is UNACCEPTABLE_ASSET.
func (v *Validator) validateRealAccount(ctx context.Context, acc *protocol.RealAccount, p protocol.Posting) (*protocol.NoVoteReason, error) {
	// Only validate accounts that belong to us.
	if !v.accountIsOurs(acc.Num) {
		return nil, nil
	}

	switch monas := p.Asset.(type) {
	case *protocol.MonasAsset:
		if v.bc == nil {
			return &protocol.NoVoteReason{Reason: protocol.ReasonNoSuchAccount, Posting: &p}, nil
		}
		info, err := v.bc.ResolveAccount(ctx, acc.Num)
		if err != nil {
			if errors.Is(err, ErrAccountNotFound) {
				return &protocol.NoVoteReason{Reason: protocol.ReasonNoSuchAccount, Posting: &p}, nil
			}
			return nil, err
		}
		if info.Currency != monas.Currency {
			return &protocol.NoVoteReason{Reason: protocol.ReasonNoSuchAsset, Posting: &p}, nil
		}
		if p.Amount.IsNegative() && info.AvailableBalance.LessThan(p.Amount.Abs()) {
			return &protocol.NoVoteReason{Reason: protocol.ReasonInsufficientAsset, Posting: &p}, nil
		}
		return nil, nil

	default:
		// ACCOUNT + STOCK, ACCOUNT + OPTION, etc.
		return &protocol.NoVoteReason{Reason: protocol.ReasonUnacceptableAsset, Posting: &p}, nil
	}
}

// validatePersonAccount handles the PERSON type. Only validate persons whose
// routing matches ours. PERSON+MONAS → resolve to real account; PERSON+OPTION →
// valid by default (spec §3.6 accept option-leg); PERSON+STOCK → UNACCEPTABLE.
func (v *Validator) validatePersonAccount(ctx context.Context, person *protocol.PersonAccount, p protocol.Posting) (*protocol.NoVoteReason, error) {
	if person.Id.RoutingNumber != v.myRouting {
		// Partner-side person — not our responsibility.
		return nil, nil
	}

	switch monas := p.Asset.(type) {
	case *protocol.MonasAsset:
		ownerID, err := parseOwnerID(person.Id.Id)
		if err != nil {
			return &protocol.NoVoteReason{Reason: protocol.ReasonNoSuchAccount, Posting: &p}, nil
		}
		if v.bc == nil {
			return &protocol.NoVoteReason{Reason: protocol.ReasonNoSuchAccount, Posting: &p}, nil
		}
		num, err := v.bc.FindAccountByOwnerAndCurrency(ctx, ownerID, monas.Currency)
		if err != nil || num == "" {
			return &protocol.NoVoteReason{Reason: protocol.ReasonNoSuchAccount, Posting: &p}, nil
		}
		info, err := v.bc.ResolveAccount(ctx, num)
		if err != nil {
			if errors.Is(err, ErrAccountNotFound) {
				return &protocol.NoVoteReason{Reason: protocol.ReasonNoSuchAccount, Posting: &p}, nil
			}
			return nil, err
		}
		if p.Amount.IsNegative() && info.AvailableBalance.LessThan(p.Amount.Abs()) {
			return &protocol.NoVoteReason{Reason: protocol.ReasonInsufficientAsset, Posting: &p}, nil
		}
		return nil, nil

	case *protocol.OptionAsset:
		// PERSON + OPTION is the accept option-leg per spec §3.6 — valid.
		_ = monas
		return nil, nil

	case *protocol.StockAsset:
		// PERSON + STOCK:
		//   - Positive (incoming) → our user RECEIVES stock (e.g. the buyer-side
		//     leg of a cross-bank OTC option exercise). Receiving an asset is always
		//     acceptable — existence/capacity is not our concern here, exactly like
		//     an incoming MONAS credit. The actual stock crediting happens via
		//     trading-service on commit, outside the 2PC posting validation.
		//   - Negative (outgoing) → our user would DELIVER stock via a raw posting.
		//     B1 routes local stock delivery through trading-service reservations,
		//     not raw protocol postings, so this stays UNACCEPTABLE_ASSET.
		if p.Amount.IsPositive() {
			return nil, nil
		}
		return &protocol.NoVoteReason{Reason: protocol.ReasonUnacceptableAsset, Posting: &p}, nil

	default:
		// PERSON + any other unsupported asset — UNACCEPTABLE_ASSET.
		return &protocol.NoVoteReason{Reason: protocol.ReasonUnacceptableAsset, Posting: &p}, nil
	}
}

// validateOptionPseudoAccount handles the OPTION account type.
//
// OPTION + OPTION asset, routing=ours → run the option-specific checks.
// OPTION + OPTION asset, routing=partner → not ours.
//
// OPTION + MONAS/STOCK asset (the premium / share legs a partner-coordinated accept
// hangs off the option pseudo-account, e.g. B2 as coordinator with B1 as seller): when
// the pseudo-account is on OUR routing and references a valid, ongoing negotiation, the
// accompanying leg is accepted (FIX 3). Previously ANY non-OPTION asset on the pseudo-
// account was rejected with UNACCEPTABLE_ASSET, which obered B2's accept-tx → 2PC abort.
// The global balance-check still applies; only genuinely unsupported asset types remain
// UNACCEPTABLE.
func (v *Validator) validateOptionPseudoAccount(ctx context.Context, opt *protocol.OptionPseudoAccount, p protocol.Posting) (*protocol.NoVoteReason, error) {
	switch p.Asset.(type) {
	case *protocol.OptionAsset:
		if opt.Id.RoutingNumber != v.myRouting {
			return nil, nil
		}
		return v.validateOption(ctx, opt.Id, p)

	case *protocol.MonasAsset, *protocol.StockAsset:
		// Premium/share leg riding the option pseudo-account. Only our negotiations are
		// our responsibility; a partner-routed pseudo-account is validated by the partner.
		if opt.Id.RoutingNumber != v.myRouting {
			return nil, nil
		}
		// The negotiation backing this pseudo-account must exist and still be ongoing for
		// the leg to be acceptable (mirrors the OPTION_NEGOTIATION_NOT_FOUND /
		// OPTION_USED_OR_EXPIRED checks, without the option-amount rule which only applies
		// to the OPTION unit leg).
		return v.validateNegotiationOpen(ctx, opt.Id, p)

	default:
		return &protocol.NoVoteReason{Reason: protocol.ReasonUnacceptableAsset, Posting: &p}, nil
	}
}

// validateNegotiationOpen verifies that the option backing an option pseudo-account is in
// a usable lifecycle state for the MONAS/STOCK legs riding the pseudo-account. Two distinct
// transaction shapes hang such legs off the pseudo-account:
//
//   - ACCEPT-shape (partner-coordinated accept, FIX 3): the negotiation is still ONGOING
//     and the contract does not exist yet. negotiationAcceptable covers this.
//   - EXERCISE-shape (cross-bank §2.7.2 exercise of an accepted option): the negotiation is
//     CLOSED (accept set is_ongoing=false), but the resulting contract is still ACTIVE with
//     a future settlement. negotiationExercisable covers this.
//
// Previously this rejected ANY non-ongoing negotiation with OPTION_USED_OR_EXPIRED, which
// made every accepted cross-bank option impossible to exercise (accept always closes the
// negotiation). We now accept the leg when EITHER predicate holds, and only reject
// OPTION_USED_OR_EXPIRED when neither does (declined / already-exercised / expired contract).
func (v *Validator) validateNegotiationOpen(ctx context.Context, id protocol.ForeignBankId, p protocol.Posting) (*protocol.NoVoteReason, error) {
	if v.negs == nil {
		return &protocol.NoVoteReason{Reason: protocol.ReasonOptionNegotiationNotFound, Posting: &p}, nil
	}
	neg, err := v.negs.FindNegotiation(ctx, id)
	if err != nil || neg == nil {
		return &protocol.NoVoteReason{Reason: protocol.ReasonOptionNegotiationNotFound, Posting: &p}, nil
	}
	now := time.Now()
	if negotiationAcceptable(neg, now) || negotiationExercisable(neg, now) {
		return nil, nil
	}
	return &protocol.NoVoteReason{Reason: protocol.ReasonOptionUsedOrExpired, Posting: &p}, nil
}

// validateOption performs the three option-specific checks:
// OPTION_NEGOTIATION_NOT_FOUND, OPTION_USED_OR_EXPIRED, OPTION_AMOUNT_INCORRECT.
func (v *Validator) validateOption(ctx context.Context, id protocol.ForeignBankId, p protocol.Posting) (*protocol.NoVoteReason, error) {
	if v.negs == nil {
		return &protocol.NoVoteReason{Reason: protocol.ReasonOptionNegotiationNotFound, Posting: &p}, nil
	}
	neg, err := v.negs.FindNegotiation(ctx, id)
	if err != nil || neg == nil {
		return &protocol.NoVoteReason{Reason: protocol.ReasonOptionNegotiationNotFound, Posting: &p}, nil
	}
	// Lifecycle: accept-time (ongoing) OR exercise-time (closed negotiation, ACTIVE
	// contract, future settlement). Reject only when the option is genuinely unusable.
	// See validateNegotiationOpen for the rationale; kept consistent so the OPTION-unit
	// leg and the MONAS/STOCK side legs apply the same exercisability rule.
	now := time.Now()
	if !negotiationAcceptable(neg, now) && !negotiationExercisable(neg, now) {
		return &protocol.NoVoteReason{Reason: protocol.ReasonOptionUsedOrExpired, Posting: &p}, nil
	}
	// Amount check: |amount| ∈ {1, k, k·π}
	abs := p.Amount.Abs()
	k := decimal.NewFromInt(int64(neg.Amount))
	kPi := k.Mul(neg.PricePerUnit)
	one := decimal.NewFromInt(1)
	if !abs.Equal(one) && !abs.Equal(k) && !abs.Equal(kPi) {
		return &protocol.NoVoteReason{Reason: protocol.ReasonOptionAmountIncorrect, Posting: &p}, nil
	}
	return nil, nil
}

// accountIsOurs returns true if the account number's routing prefix (first 3 digits)
// matches our routing number.
//
// Length is NOT fixed at 18: Banka 1 issues both 18-digit (bank/exchange) and
// 19-digit (client) account numbers, so we match on the routing prefix only.
// A previous `len(num) != 18` guard silently rejected every 19-digit client
// account, so inbound cross-bank postings to a client were not recognised as ours
// and the recipient was never credited (cross-bank money vanished). Existence is
// still verified downstream by ResolveAccount, so prefix-matching is safe.
func (v *Validator) accountIsOurs(num string) bool {
	if len(num) < 18 {
		return false
	}
	prefix := num[:3]
	return prefix == fmt.Sprintf("%03d", v.myRouting)
}

// parseOwnerID extracts the numeric portion of a prefixed id like "C-7" or "E-42".
func parseOwnerID(prefixedID string) (int64, error) {
	if len(prefixedID) < 3 {
		return 0, fmt.Errorf("short id %q", prefixedID)
	}
	if prefixedID[0] != 'C' && prefixedID[0] != 'E' {
		return 0, fmt.Errorf("unexpected prefix in %q", prefixedID)
	}
	parts := strings.SplitN(prefixedID, "-", 2)
	if len(parts) != 2 {
		return 0, fmt.Errorf("missing dash in %q", prefixedID)
	}
	var x int64
	_, err := fmt.Sscanf(parts[1], "%d", &x)
	return x, err
}
