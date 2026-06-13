package service

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// ---------------------------------------------------------------------------
// DTOs
// ---------------------------------------------------------------------------

// ContractView is the frontend-friendly interbank option-contract representation.
// Mirrors the NegotiationView style. Premium is intentionally omitted — the
// interbank_contracts table tracks only the strike (premium lives on the
// negotiation row and was already settled by the accept 2PC).
type ContractView struct {
	LocalID                  string                 `json:"localId"`
	NegotiationID            string                 `json:"negotiationId"`
	BuyerID                  protocol.ForeignBankId `json:"buyerId"`
	SellerID                 protocol.ForeignBankId `json:"sellerId"`
	StockTicker              string                 `json:"ticker"`
	Amount                   int                    `json:"amount"`
	StrikeCurrency           string                 `json:"strikeCurrency"`
	StrikeAmount             decimal.Decimal        `json:"strikeAmount"`
	SettlementDate           time.Time              `json:"settlementDate"`
	Status                   string                 `json:"status"`
	OptionPseudoOwnerRouting int                    `json:"optionPseudoOwnerRouting"`
	OptionPseudoOwnerID      string                 `json:"optionPseudoOwnerId"`
	CreatedAt                time.Time              `json:"createdAt"`
	ExercisedAt              *time.Time             `json:"exercisedAt,omitempty"`
	ExpiredAt                *time.Time             `json:"expiredAt,omitempty"`
}

// ---------------------------------------------------------------------------
// Seams
// ---------------------------------------------------------------------------

// ContractStoreForService is the persistence seam for OtcContractsService.
// *store.ContractStore satisfies it; tests use a fake.
type ContractStoreForService interface {
	ListForUser(ctx context.Context, userForeignID string, includeAll bool) ([]*store.Contract, error)
	FindByID(ctx context.Context, id string) (*store.Contract, error)
	UpdateStatus(ctx context.Context, id, status string) error
	// ClaimExerciseByID atomically flips ACTIVE→EXERCISING under a row lock — the entry
	// claim that serializes concurrent buyer-side exercises of the same contract. Returns
	// (exists, claimed): claimed=true only for the single winner.
	ClaimExerciseByID(ctx context.Context, id string) (exists bool, claimed bool, err error)
	// RevertExercising returns an EXERCISING claim back to ACTIVE when the 2PC failed after
	// the claim, so the user can retry. No-op on non-EXERCISING rows.
	RevertExercising(ctx context.Context, id string) error
}

// ContractExerciser runs the buyer-side exercise 2PC. *Coordinator satisfies it.
type ContractExerciser interface {
	ExerciseContract(ctx context.Context, contract *store.Contract) (protocol.ForeignBankId, error)
}

// ---------------------------------------------------------------------------
// OtcContractsService
// ---------------------------------------------------------------------------

// OtcContractsService implements the FE-facing /api/interbank/otc/contracts/*
// wrapper: list the caller's interbank option contracts and exercise one.
type OtcContractsService struct {
	myRouting int
	store     ContractStoreForService
	exerciser ContractExerciser
	log       *slog.Logger
}

// NewOtcContractsService constructs the service.
func NewOtcContractsService(
	myRouting int,
	store ContractStoreForService,
	exerciser ContractExerciser,
	log *slog.Logger,
) *OtcContractsService {
	if log == nil {
		log = slog.Default()
	}
	return &OtcContractsService{
		myRouting: myRouting,
		store:     store,
		exerciser: exerciser,
		log:       log,
	}
}

// ListForUser returns the contracts where principalUserID is buyer or seller.
// Admin / supervisor callers set isAdmin=true to receive all contracts.
func (s *OtcContractsService) ListForUser(ctx context.Context, principalUserID int64, isAdmin bool) ([]ContractView, error) {
	var userForeignID string
	if !isAdmin {
		if principalUserID == 0 {
			return nil, fmt.Errorf("%w: principalUserID is zero and isAdmin is false", ErrContractForbidden)
		}
		userForeignID = fmt.Sprintf("C-%d", principalUserID)
	}

	rows, err := s.store.ListForUser(ctx, userForeignID, isAdmin)
	if err != nil {
		return nil, fmt.Errorf("contracts: list: %w", err)
	}

	views := make([]ContractView, 0, len(rows))
	for _, c := range rows {
		views = append(views, toContractView(c))
	}
	return views, nil
}

// Exercise exercises the option contract `id` on behalf of principalUserID.
// Only the buyer (or an admin/supervisor) may exercise. The contract must be
// ACTIVE and its settlement date must not have passed. On success the contract
// is flipped to EXERCISED and the resulting view is returned.
func (s *OtcContractsService) Exercise(ctx context.Context, principalUserID int64, isAdmin bool, id string) (*ContractView, error) {
	contract, err := s.store.FindByID(ctx, id)
	if err != nil {
		return nil, fmt.Errorf("contracts: find: %w", err)
	}
	if contract == nil {
		return nil, fmt.Errorf("%w: %s", ErrContractNotFound, id)
	}

	// Authorization: the caller must be the contract's buyer unless admin/supervisor.
	if !isAdmin {
		buyerForeignID := fmt.Sprintf("C-%d", principalUserID)
		if principalUserID == 0 || contract.BuyerID != buyerForeignID {
			return nil, fmt.Errorf("%w: caller is not the buyer of contract %s", ErrContractForbidden, id)
		}
	}

	// The buyer side must be ours — we can only exercise an option we hold.
	if contract.BuyerRouting != s.myRouting {
		return nil, fmt.Errorf("%w: contract %s is not buyer-side for our bank", ErrContractForbidden, id)
	}

	// State checks (cheap pre-filter on the snapshot; the authoritative ACTIVE guard is the
	// atomic claim below — the snapshot can race a concurrent exercise between read and claim).
	if contract.Status != store.ContractStatusActive {
		return nil, fmt.Errorf("%w: contract %s is %s (must be ACTIVE)", ErrContractNotExercisable, id, contract.Status)
	}
	if !contract.SettlementDate.IsZero() && contract.SettlementDate.Before(time.Now()) {
		return nil, fmt.Errorf("%w: contract %s settlement date has passed", ErrContractNotExercisable, id)
	}

	// ENTRY CLAIM (mirrors B2 claimForExercise): atomically flip ACTIVE→EXERCISING under a
	// row lock BEFORE any reserve/commit. This serializes concurrent Exercise(sameContract)
	// calls — exactly one wins the claim; every other loses and is rejected here, so the
	// buyer-strike reserve+commit in Coordinator.ExerciseContract runs at most once per
	// contract (no double charge). The contract is NOT covered by the executor ContractGate
	// on the buyer (off-wire) side, so this is the only serialization point for that path.
	exists, claimed, err := s.store.ClaimExerciseByID(ctx, id)
	if err != nil {
		return nil, fmt.Errorf("contracts: claim exercise: %w", err)
	}
	if !exists {
		// The row vanished between FindByID and the claim — treat as not found.
		return nil, fmt.Errorf("%w: %s", ErrContractNotFound, id)
	}
	if !claimed {
		// Lost the race: another exercise already holds the claim (EXERCISING) or the
		// contract moved past ACTIVE (EXERCISED/terminal). Reject WITHOUT reserve/commit.
		return nil, fmt.Errorf("%w: contract %s", ErrContractAlreadyExercising, id)
	}
	// We hold the claim; reflect it on the snapshot for any downstream read.
	contract.Status = store.ContractStatusExercising

	if _, err := s.exerciser.ExerciseContract(ctx, contract); err != nil {
		// 2PC/exercise failed after we claimed → revert EXERCISING→ACTIVE so the user can
		// retry. Best-effort: a revert failure leaves the contract stuck EXERCISING (a manual
		// /retry path can recover), but it must NOT mask the original exercise error.
		if revertErr := s.store.RevertExercising(ctx, id); revertErr != nil {
			s.log.ErrorContext(ctx, "contracts: exercise failed AND revert EXERCISING→ACTIVE failed — contract stuck EXERCISING",
				"contract", id, "exerciseErr", err, "revertErr", revertErr)
		}
		return nil, err
	}

	if err := s.store.UpdateStatus(ctx, id, store.ContractStatusExercised); err != nil {
		// The 2PC committed; failing to flip status is non-fatal but logged — the view will
		// still reflect EXERCISING until a retry. Surface as a soft warning.
		s.log.WarnContext(ctx, "contracts: exercise committed but status flip failed",
			"contract", id, "err", err)
	} else {
		contract.Status = store.ContractStatusExercised
	}

	view := toContractView(contract)
	return &view, nil
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

func toContractView(c *store.Contract) ContractView {
	return ContractView{
		LocalID:                  c.ID,
		NegotiationID:            c.NegotiationID,
		BuyerID:                  protocol.ForeignBankId{RoutingNumber: c.BuyerRouting, Id: c.BuyerID},
		SellerID:                 protocol.ForeignBankId{RoutingNumber: c.SellerRouting, Id: c.SellerID},
		StockTicker:              c.StockTicker,
		Amount:                   c.Amount,
		StrikeCurrency:           c.StrikeCurrency,
		StrikeAmount:             c.StrikeAmount,
		SettlementDate:           c.SettlementDate,
		Status:                   c.Status,
		OptionPseudoOwnerRouting: c.OptionPseudoOwnerRouting,
		OptionPseudoOwnerID:      c.OptionPseudoOwnerID,
		CreatedAt:                c.CreatedAt,
		ExercisedAt:              c.ExercisedAt,
		ExpiredAt:                c.ExpiredAt,
	}
}
