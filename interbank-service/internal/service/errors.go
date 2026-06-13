package service

import "errors"

// Domain error sentinels for the OTC negotiation and coordinator layer.
// Handlers map these to appropriate HTTP status codes:
//   - ErrNegotiationNotFound   → 404
//   - ErrNegotiationClosed     → 409
//   - ErrTurnViolation         → 409
//   - ErrNegotiationInvalid    → 400
//   - ErrSenderNotParty        → 403

var (
	// ErrNegotiationNotFound is returned when a negotiation row cannot be
	// located by the {routing, id} authoritative reference pair.
	ErrNegotiationNotFound = errors.New("service: negotiation not found")

	// ErrNegotiationClosed is returned when an operation requires an open
	// negotiation but the negotiation has is_ongoing=false or its settlement
	// date has passed.
	ErrNegotiationClosed = errors.New("service: negotiation closed")

	// ErrTurnViolation is returned per Tim 2 §6.3 when the sender's routing
	// matches the last_modified_by routing — it is not the sender's turn.
	ErrTurnViolation = errors.New("service: turn violation")

	// ErrNegotiationInvalid is returned for malformed payloads (past
	// settlement date, negative/zero amounts, routing number mismatches, etc.).
	ErrNegotiationInvalid = errors.New("service: negotiation payload invalid")

	// ErrSenderNotParty is returned when the authenticated sender is not
	// either the buyer or seller in the negotiation (multi-bank safety guard).
	ErrSenderNotParty = errors.New("service: sender is not a party to this negotiation")

	// ErrInterbankProtocol signals a protocol-level failure during 2PC
	// (e.g. local prepare failed, partner rejected, catastrophic commit).
	ErrInterbankProtocol = errors.New("service: inter-bank protocol failure")

	// ErrPaymentInvalid is returned for malformed outbound cross-bank payment
	// requests (e.g. the recipient routing equals our own — that is an intra-bank
	// transfer which banking-core handles, not this inter-bank coordinator).
	ErrPaymentInvalid = errors.New("service: outbound payment request invalid")

	// ErrContractNotFound is returned when an interbank option contract cannot be
	// located by id. Handlers map it to 404.
	ErrContractNotFound = errors.New("service: contract not found")

	// ErrContractNotExercisable is returned when an exercise is attempted on a
	// contract that is not ACTIVE (already exercised / expired / released) or whose
	// settlement date has passed. Handlers map it to 409.
	ErrContractNotExercisable = errors.New("service: contract not exercisable")

	// ErrContractForbidden is returned when the caller is neither the buyer nor an
	// admin/supervisor and therefore may not exercise the contract. Maps to 403.
	ErrContractForbidden = errors.New("service: contract operation forbidden")

	// ErrContractAlreadyExercising is returned when an exercise loses the entry-level
	// ACTIVE→EXERCISING claim because a concurrent exercise of the same contract already
	// holds it (or the contract is past ACTIVE). It guarantees the loser performs NO strike
	// reserve/commit, so the buyer is never double-charged. Handlers map it to 409.
	ErrContractAlreadyExercising = errors.New("service: contract exercise already in progress")
)
