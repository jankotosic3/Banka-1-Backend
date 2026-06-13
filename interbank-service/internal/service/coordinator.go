package service

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log/slog"
	"math/big"
	"strings"
	"time"

	"github.com/shopspring/decimal"

	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/protocol"
	"github.com/raf-si-2025/banka-1-go/interbank-service/internal/store"
)

// ---------------------------------------------------------------------------
// OutboundClient interface (Task 18 will provide the concrete implementation)
// ---------------------------------------------------------------------------

// OutboundClient is consumed by Coordinator to perform 2PC messaging toward
// the partner bank. The concrete implementation (InterbankClient, Task 18)
// signs requests with the partner's outbound token and POSTs to their
// /interbank endpoint. Tests use a fake.
type OutboundClient interface {
	// SendNewTx sends a NEW_TX message to the partner bank and returns their vote.
	SendNewTx(ctx context.Context, partnerRouting int, tx protocol.InterbankTransactionPayload) (protocol.TransactionVote, error)
	// SendCommitTx sends a COMMIT_TX message. Returns nil on 204.
	SendCommitTx(ctx context.Context, partnerRouting int, txID protocol.ForeignBankId) error
	// SendRollbackTx sends a ROLLBACK_TX message. Returns nil on 204.
	SendRollbackTx(ctx context.Context, partnerRouting int, txID protocol.ForeignBankId) error
}

// ---------------------------------------------------------------------------
// ContractStoreIface — persistence seam for Coordinator
// ---------------------------------------------------------------------------

// ContractStoreIface is the subset of store.ContractStore used by Coordinator.
type ContractStoreIface interface {
	Insert(ctx context.Context, c *store.Contract) error
}

// ---------------------------------------------------------------------------
// BankingCoreAccountResolver — resolve local user → 18-digit account number
// ---------------------------------------------------------------------------

// BankingCoreAccountResolver is a subset of BankingCoreReserver used by Coordinator
// to look up real account numbers for local parties and to record the transaction-history
// row for an outbound cross-bank money leg.
type BankingCoreAccountResolver interface {
	FindAccountByOwnerAndCurrency(ctx context.Context, ownerID int64, currency string) (string, error)
	// RecordInterbankPayment records a COMPLETED payment_table row so the outbound
	// money shows in transaction history. Best-effort: callers log and continue on failure.
	RecordInterbankPayment(ctx context.Context, orderNumber, fromAccount, toAccount string, amount decimal.Decimal, currency, recipientName, paymentPurpose string) error
}

// SellerNegResolver resolves the SELLER bank's AUTHORITATIVE negotiation id for a
// contract we (the buyer) hold. The cross-bank OTC option pseudo-account is keyed by the
// negotiation id AT THE BANK THAT ISSUED THE OPTION (the seller). When we are the buyer we
// hold only a NON-authoritative mirror of the seller's negotiation, so contract.NegotiationID
// is our LOCAL mirror id, not the seller's — exercising with the local id would address an
// option pseudo-account the seller bank cannot resolve. This seam maps our local negotiation
// id to the seller bank's authoritative id (the mirror row's remote_negotiation_id).
//
// Optional: when nil, or when no mirror/remote id is found, the Coordinator falls back to
// contract.NegotiationID (correct when we ARE the authoritative side, i.e. an intra-issued
// negotiation, or in tests with a single id space).
type SellerNegResolver interface {
	// SellerNegotiationID returns the seller-bank authoritative negotiation id for the
	// given LOCAL negotiation id. It returns "" (no error) when there is no mapping, so
	// the caller can fall back to the local id.
	SellerNegotiationID(ctx context.Context, localNegotiationID string) string
}

// LocalMonasReserver lets the Coordinator reserve and commit a LOCAL money debit
// OUTSIDE the inter-bank wire postings. The buyer-side exercise strike debit is NOT
// carried on the wire (it would unbalance the canonical MONAS group: OPTION +strike +
// seller −strike already nets to zero). Instead — mirroring how the SELLER side's bank
// consumes the buyer cash from its own reservation, and how the partner does
// reservationApplier.commitMonas AFTER a successful 2PC — we reserve the buyer's strike
// locally at prepare time and commit it only after the partner has voted YES and we have
// committed locally. On any abort we release it (no money moved).
//
// Optional: when nil (e.g. tests, or a deployment without a banking-core reserver) the
// buyer strike is not reserved here; conservation then relies on the buyer already having
// been debited upstream. Production always wires the banking-core reserver.
type LocalMonasReserver interface {
	FindAccountByOwnerAndCurrency(ctx context.Context, ownerID int64, currency string) (string, error)
	ReserveMonas(ctx context.Context, accountNum, currency string, amount decimal.Decimal, txIDRouting int, txIDLocal string) (string, error)
	CommitMonas(ctx context.Context, reservationID string) error
	ReleaseMonas(ctx context.Context, reservationID string) error
}

// ---------------------------------------------------------------------------
// Coordinator
// ---------------------------------------------------------------------------

// Coordinator orchestrates the synchronous 2PC for §3.6 GET /negotiations/{rn}/{id}/accept.
// Corresponds to Java InterbankCoordinatorService.
//
// Flow per spec §3.6 + §7:
//  1. Build 4-posting transaction (buyer premium debit, seller premium credit,
//     seller Option pseudo-account credit, buyer Person Option debit).
//  2. PrepareLocal (local side reservation).
//  3. OutboundClient.SendNewTx (partner side prepare).
//  4. On partner NO or error: rollback local + rollback partner; return error.
//  5. CommitLocal (finalize local reservations).
//  6. MarkClosed negotiation + insert contract ACTIVE.
//  7. OutboundClient.SendCommitTx (best-effort; retry scheduler covers failures).
type Coordinator struct {
	myRouting   int
	executor    *Executor
	outbound    OutboundClient
	negStore    NegotiationStoreIface
	contractSt  ContractStoreIface
	bcResolver  BankingCoreAccountResolver
	sellerNeg   SellerNegResolver  // optional — resolves the seller bank's authoritative negId for buyer-side exercise
	monasResv   LocalMonasReserver // optional — reserves/commits the buyer strike debit off-wire on exercise
	log         *slog.Logger
}

// NewCoordinator constructs a Coordinator. bcResolver may be nil — in that case
// MONAS account resolution falls back to TxAccount.Person (partner resolves).
// sellerNeg may be nil — in that case buyer-side ExerciseContract addresses the
// option pseudo-account with contract.NegotiationID directly (correct when we are
// the authoritative side; partner-mirror cases supply a resolver in production).
func NewCoordinator(
	myRouting int,
	executor *Executor,
	outbound OutboundClient,
	negStore NegotiationStoreIface,
	contractSt ContractStoreIface,
	bcResolver BankingCoreAccountResolver,
	sellerNeg SellerNegResolver,
	monasResv LocalMonasReserver,
	log *slog.Logger,
) *Coordinator {
	if log == nil {
		log = slog.Default()
	}
	return &Coordinator{
		myRouting:  myRouting,
		executor:   executor,
		outbound:   outbound,
		negStore:   negStore,
		contractSt: contractSt,
		bcResolver: bcResolver,
		sellerNeg:  sellerNeg,
		monasResv:  monasResv,
		log:        log,
	}
}

// AcceptNegotiation implements CoordinatorIface.AcceptNegotiation.
// The negotiation entity must have is_ongoing=true and settlement in the future;
// all turn checks have already been performed in OtcNegotiationService.
func (c *Coordinator) AcceptNegotiation(ctx context.Context, neg *store.Negotiation) error {
	partnerRouting := neg.BuyerRouting
	if partnerRouting == c.myRouting {
		partnerRouting = neg.SellerRouting
	}

	// Total premium = premium_amount (per-unit) × amount.
	totalPremium := neg.PremiumAmount.Mul(decimal.NewFromInt(int64(neg.Amount)))
	premCurrency := neg.PremiumCurrency
	strikeCurrency := neg.PriceCurrency

	optionDesc := protocol.OptionDescription{
		NegotiationId: protocol.ForeignBankId{RoutingNumber: c.myRouting, Id: neg.ID},
		Stock:         protocol.StockDescription{Ticker: neg.StockTicker},
		PricePerUnit:  protocol.MonetaryValue{Currency: strikeCurrency, Amount: neg.PriceAmount},
		SettlementDate: neg.SettlementDate.UTC().Format(time.RFC3339),
		Amount:        neg.Amount,
	}

	// Resolve MONAS accounts: our local parties get real 18-digit numbers;
	// partner-side parties become TxAccount.Person (partner resolves internally).
	buyerCashAcc := c.resolveMonasAccount(ctx, neg.BuyerRouting, neg.BuyerID, premCurrency)
	sellerCashAcc := c.resolveMonasAccount(ctx, neg.SellerRouting, neg.SellerID, premCurrency)

	postings := []protocol.Posting{
		// a) Buyer credit premium (-totalPremium): premium leaves buyer account.
		{
			Account: buyerCashAcc,
			Amount:  totalPremium.Neg(),
			Asset:   &protocol.MonasAsset{Currency: premCurrency},
		},
		// b) Seller debit premium (+totalPremium): seller receives premium.
		{
			Account: sellerCashAcc,
			Amount:  totalPremium,
			Asset:   &protocol.MonasAsset{Currency: premCurrency},
		},
		// c) Seller Option pseudo-account credit (-1 option unit).
		{
			Account: &protocol.OptionPseudoAccount{Id: protocol.ForeignBankId{RoutingNumber: c.myRouting, Id: neg.ID}},
			Amount:  decimal.NewFromFloat(-1),
			Asset:   &protocol.OptionAsset{OptionDescription: optionDesc},
		},
		// d) Buyer Person account debit (+1 option unit): buyer receives option.
		{
			Account: &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: neg.BuyerRouting, Id: neg.BuyerID}},
			Amount:  decimal.NewFromFloat(1),
			Asset:   &protocol.OptionAsset{OptionDescription: optionDesc},
		},
	}

	txID := protocol.ForeignBankId{RoutingNumber: c.myRouting, Id: generateTxID()}
	tx := protocol.InterbankTransactionPayload{
		TransactionId:  txID,
		Postings:       postings,
		Message:        "OTC accept for negotiation " + neg.ID,
		PaymentCode:    "289",
		PaymentPurpose: "OTC premium + option transfer",
	}

	// 1. PrepareLocal.
	localVote, err := c.executor.PrepareLocal(ctx, tx)
	if err != nil {
		return fmt.Errorf("%w: local prepare infra failure: %v", ErrInterbankProtocol, err)
	}
	if localVote.Vote != protocol.VoteYes {
		return fmt.Errorf("%w: local prepare rejected: %v", ErrInterbankProtocol, localVote.Reasons)
	}

	// 2. Partner prepare.
	partnerVote, err := c.outbound.SendNewTx(ctx, partnerRouting, tx)
	if err != nil {
		c.safeRollbackLocal(ctx, txID)
		return fmt.Errorf("%w: partner prepare exception: %v", ErrInterbankProtocol, err)
	}
	if partnerVote.Vote != protocol.VoteYes {
		c.safeRollbackLocal(ctx, txID)
		c.safeRollbackPartner(ctx, partnerRouting, txID)
		return fmt.Errorf("%w: partner rejected: %v", ErrInterbankProtocol, partnerVote.Reasons)
	}

	// 3. CommitLocal.
	if err := c.executor.CommitLocal(ctx, txID); err != nil {
		// Catastrophic: we committed locally but might fail to tell partner.
		c.safeRollbackPartner(ctx, partnerRouting, txID)
		return fmt.Errorf("%w: catastrophic commitLocal failure: %v", ErrInterbankProtocol, err)
	}

	// 4. Flip negotiation + create contract.
	if err := c.negStore.MarkClosed(ctx, neg.ID); err != nil {
		c.log.WarnContext(ctx, "coordinator: failed to mark negotiation closed",
			"neg", neg.ID, "err", err)
		// Non-fatal; 2PC is committed. Continue.
	}
	contract := &store.Contract{
		ID:                       generateContractID(),
		NegotiationID:            neg.ID,
		BuyerRouting:             neg.BuyerRouting,
		BuyerID:                  neg.BuyerID,
		SellerRouting:            neg.SellerRouting,
		SellerID:                 neg.SellerID,
		StockTicker:              neg.StockTicker,
		Amount:                   neg.Amount,
		StrikeCurrency:           neg.PriceCurrency,
		StrikeAmount:             neg.PriceAmount,
		SettlementDate:           neg.SettlementDate,
		Status:                   store.ContractStatusActive,
		OptionPseudoOwnerRouting: neg.BuyerRouting,
		OptionPseudoOwnerID:      neg.BuyerID,
	}
	if err := c.contractSt.Insert(ctx, contract); err != nil {
		c.log.ErrorContext(ctx, "coordinator: failed to insert contract",
			"neg", neg.ID, "err", err)
		// Non-fatal for the 2PC protocol; contract will be missing but 2PC committed.
	}

	// 5. Partner commit — CRITICAL for 204 response; best-effort (retry scheduler covers failures).
	if err := c.outbound.SendCommitTx(ctx, partnerRouting, txID); err != nil {
		c.log.WarnContext(ctx, "coordinator: sendCommitTx to partner failed — retry scheduler will pick it up",
			"partnerRouting", partnerRouting, "txID", txID, "err", err)
	}

	c.log.InfoContext(ctx, "accepted negotiation — contract ACTIVE",
		"neg", neg.ID, "contract", contract.ID, "tx", txID)
	return nil
}

// SendOutboundPayment runs the inter-bank 2PC as coordinator for a plain
// cross-bank money payment: Banka 1 user → an account at a partner bank.
//
// This is the money-only sibling of AcceptNegotiation — same 2PC sequence, no
// option/contract. The transaction carries two MONAS postings (sender debit on
// our side, recipient credit on the partner's side); our PrepareLocal reserves
// only the sender debit (the credit lands at the partner), and CommitLocal
// finalizes that debit. The partner performs the recipient credit.
//
// Flow (mirrors AcceptNegotiation):
//  1. PrepareLocal (reserve sender debit).
//  2. OutboundClient.SendNewTx (partner prepare).
//  3. On partner NO or error: rollback local (+ rollback partner); return error.
//  4. CommitLocal (finalize sender debit).
//  5. OutboundClient.SendCommitTx (best-effort; retry scheduler covers failures).
//
// Returns the freshly generated transaction id on success.
func (c *Coordinator) SendOutboundPayment(ctx context.Context, fromAccount, toAccount string, amount decimal.Decimal, currency, message string) (protocol.ForeignBankId, error) {
	// FIX 5: cross-bank PLAIN payments are RSD-only by product decision — foreign
	// currencies are blocked (no FX on the inter-bank money rail) BEFORE any 2PC begins.
	// OTC settlement (AcceptNegotiation / ExerciseContract) is unaffected and stays USD.
	if !strings.EqualFold(strings.TrimSpace(currency), "RSD") {
		return protocol.ForeignBankId{}, fmt.Errorf("%w: medjubankarska placanja su podrzana samo u RSD (currency=%q)", ErrPaymentInvalid, currency)
	}

	// Derive the partner routing from the recipient account's 3-digit prefix.
	if len(toAccount) < 3 {
		return protocol.ForeignBankId{}, fmt.Errorf("%w: recipient account too short: %q", ErrPaymentInvalid, toAccount)
	}
	partnerRouting, err := parseBigInt(toAccount[:3])
	if err != nil {
		return protocol.ForeignBankId{}, fmt.Errorf("%w: recipient routing prefix not numeric: %q", ErrPaymentInvalid, toAccount[:3])
	}
	if int(partnerRouting) == c.myRouting {
		return protocol.ForeignBankId{}, fmt.Errorf("%w: recipient routing equals ours (%d) — use intra-bank transfer", ErrPaymentInvalid, c.myRouting)
	}

	postings := []protocol.Posting{
		// a) Sender debit (-amount): money leaves our local account.
		{
			Account: &protocol.RealAccount{Num: fromAccount},
			Amount:  amount.Neg(),
			Asset:   &protocol.MonasAsset{Currency: currency},
		},
		// b) Recipient credit (+amount): money arrives at the partner account.
		{
			Account: &protocol.RealAccount{Num: toAccount},
			Amount:  amount,
			Asset:   &protocol.MonasAsset{Currency: currency},
		},
	}

	txID := protocol.ForeignBankId{RoutingNumber: c.myRouting, Id: generateTxID()}
	tx := protocol.InterbankTransactionPayload{
		TransactionId:  txID,
		Postings:       postings,
		Message:        message,
		PaymentCode:    "289",
		PaymentPurpose: message,
	}

	// 1. PrepareLocal.
	localVote, err := c.executor.PrepareLocal(ctx, tx)
	if err != nil {
		return protocol.ForeignBankId{}, fmt.Errorf("%w: local prepare infra failure: %v", ErrInterbankProtocol, err)
	}
	if localVote.Vote != protocol.VoteYes {
		return protocol.ForeignBankId{}, fmt.Errorf("%w: local prepare rejected: %v", ErrInterbankProtocol, localVote.Reasons)
	}

	// 2. Partner prepare.
	partnerVote, err := c.outbound.SendNewTx(ctx, int(partnerRouting), tx)
	if err != nil {
		c.safeRollbackLocal(ctx, txID)
		return protocol.ForeignBankId{}, fmt.Errorf("%w: partner prepare exception: %v", ErrInterbankProtocol, err)
	}
	if partnerVote.Vote != protocol.VoteYes {
		c.safeRollbackLocal(ctx, txID)
		c.safeRollbackPartner(ctx, int(partnerRouting), txID)
		return protocol.ForeignBankId{}, fmt.Errorf("%w: partner rejected: %v", ErrInterbankProtocol, partnerVote.Reasons)
	}

	// 3. CommitLocal.
	if err := c.executor.CommitLocal(ctx, txID); err != nil {
		// Catastrophic: we committed locally but might fail to tell partner.
		c.safeRollbackPartner(ctx, int(partnerRouting), txID)
		return protocol.ForeignBankId{}, fmt.Errorf("%w: catastrophic commitLocal failure: %v", ErrInterbankProtocol, err)
	}

	// 3b. Record the outbound money in our transaction history. Best-effort: a
	// recording failure must NOT fail the (already committed) payment — log & continue.
	// Idempotent via deterministic order_number (ON CONFLICT DO NOTHING server-side).
	c.recordOutboundPayment(ctx, txID, fromAccount, toAccount, amount, currency, message)

	// 4. Partner commit — best-effort (retry scheduler covers failures).
	if err := c.outbound.SendCommitTx(ctx, int(partnerRouting), txID); err != nil {
		c.log.WarnContext(ctx, "coordinator: payment sendCommitTx to partner failed — retry scheduler will pick it up",
			"partnerRouting", partnerRouting, "txID", txID, "err", err)
	}

	c.log.InfoContext(ctx, "sent outbound cross-bank payment",
		"from", fromAccount, "to", toAccount, "amount", amount, "currency", currency,
		"partnerRouting", partnerRouting, "tx", txID)
	return txID, nil
}

// ExerciseContract runs the inter-bank 2PC as coordinator for the BUYER
// exercising a cross-bank OTC option before/at settlement.
//
// This is the buyer-side counterpart of the seller-centric exercise path. The
// option holder (our buyer) pays the strike (price × amount) to the seller and
// receives `amount` shares of the underlying.
//
// Wire shape — CANONICAL §2.7.2 exercise (mirror of the already-working
// B2-buyer / B1-seller tx, with the routings swapped). All four legs carry the
// SELLER bank's routing for the option pseudo-account; the seller's stock-delivery
// and strike-credit legs sit on the seller-bank OPTION pseudo-account / PERSON so the
// seller bank recognises the exercise from the SHAPE (Stock+Option signature) and
// settles its seller internally:
//
//	p1  OPTION{sellerRouting, sellerNegId}  +strike   MONAS{strikeCcy}  (option pseudo receives money)
//	p2  PERSON{sellerRouting, sellerId}     −strike   MONAS{strikeCcy}  (seller is CREDITED — negative)
//	p3  OPTION{sellerRouting, sellerNegId}  −k        STOCK{ticker}     (seller DELIVERS — exercise signature)
//	p4  PERSON{buyerRouting,  buyerId}      +k        STOCK{ticker}     (our buyer receives the shares)
//
// MONAS group nets to zero (p1 +strike, p2 −strike); STOCK group nets to zero
// (p3 −k, p4 +k) — the tx is balanced on both banks.
//
// The buyer's strike debit is NOT a wire posting (adding it would unbalance the MONAS
// group). It is reserved LOCALLY before the 2PC and committed only after a successful
// SendCommitTx — exactly as the partner consumes the buyer cash from its own
// reservation in the mirror direction (reservationApplier.commitMonas AFTER 2PC). On any
// abort the reservation is released and no money moves.
//
// Flow (mirrors AcceptNegotiation): reserve buyer strike → SendNewTx →
// (release+rollback on NO/err) → SendCommitTx → commit buyer strike. The seller bank's
// PrepareLocal validates the canonical legs; its CommitLocal delivers the seller stock and
// credits the seller strike (settleSellerOnInboundExercise). The caller
// (OtcContractsService) flips OUR buyer contract to EXERCISED after this returns nil.
// Returns the generated tx id.
func (c *Coordinator) ExerciseContract(ctx context.Context, contract *store.Contract) (protocol.ForeignBankId, error) {
	// The seller is the partner; the buyer is us.
	partnerRouting := contract.SellerRouting
	strikeCurrency := contract.StrikeCurrency
	totalStrike := contract.StrikeAmount.Mul(decimal.NewFromInt(int64(contract.Amount)))
	qty := decimal.NewFromInt(int64(contract.Amount))

	buyerParty := protocol.ForeignBankId{RoutingNumber: contract.BuyerRouting, Id: contract.BuyerID}
	sellerParty := protocol.ForeignBankId{RoutingNumber: contract.SellerRouting, Id: contract.SellerID}

	// The OPTION pseudo-account is keyed by the SELLER bank's AUTHORITATIVE negotiation id
	// (the bank that ISSUED the option resolves the pseudo-account → its seller contract).
	// We hold only a non-authoritative mirror, so contract.NegotiationID is our LOCAL id;
	// map it to the seller-bank id when a resolver is wired, else fall back to the local id.
	sellerNegID := contract.NegotiationID
	if c.sellerNeg != nil {
		if remote := c.sellerNeg.SellerNegotiationID(ctx, contract.NegotiationID); remote != "" {
			sellerNegID = remote
		}
	}
	optionPseudo := &protocol.OptionPseudoAccount{
		Id: protocol.ForeignBankId{RoutingNumber: contract.SellerRouting, Id: sellerNegID},
	}

	postings := []protocol.Posting{
		// p1) OPTION pseudo-account debit (+strike): the pseudo-account receives the money.
		{
			Account: optionPseudo,
			Amount:  totalStrike,
			Asset:   &protocol.MonasAsset{Currency: strikeCurrency},
		},
		// p2) Seller strike credit (−strike): the seller RECEIVES the strike. NEGATIVE is
		// the protocol "credit seller" sign — the seller bank credits its seller from the
		// swept pseudo-account; a positive amount would be read as a seller DEBIT (drain).
		{
			Account: &protocol.PersonAccount{Id: sellerParty},
			Amount:  totalStrike.Neg(),
			Asset:   &protocol.MonasAsset{Currency: strikeCurrency},
		},
		// p3) OPTION pseudo-account credit (−k STOCK): the seller delivers the shares. The
		// NEGATIVE STOCK leg on the OPTION pseudo-account is the EXERCISE signature the
		// seller bank keys on (isExercise = Stock+Option).
		{
			Account: optionPseudo,
			Amount:  qty.Neg(),
			Asset:   &protocol.StockAsset{Ticker: contract.StockTicker},
		},
		// p4) Buyer stock credit (+k STOCK): our buyer receives the shares (applied on our
		// CommitLocal via the trading-service option-exercise / stock-credit path).
		{
			Account: &protocol.PersonAccount{Id: buyerParty},
			Amount:  qty,
			Asset:   &protocol.StockAsset{Ticker: contract.StockTicker},
		},
	}

	txID := protocol.ForeignBankId{RoutingNumber: c.myRouting, Id: generateTxID()}
	tx := protocol.InterbankTransactionPayload{
		TransactionId:  txID,
		Postings:       postings,
		Message:        "OTC exercise for contract " + contract.ID,
		PaymentCode:    "289",
		PaymentPurpose: "OTC option exercise — strike + stock delivery",
	}

	// 0. Reserve the buyer's strike OFF-WIRE (not a wire posting — it would unbalance the
	// MONAS group). Released on any abort, committed only after a successful 2PC.
	buyerStrikeResID, err := c.reserveBuyerStrike(ctx, contract, strikeCurrency, totalStrike, txID)
	if err != nil {
		return protocol.ForeignBankId{}, fmt.Errorf("%w: buyer strike reservation failed: %v", ErrInterbankProtocol, err)
	}

	// 1. PrepareLocal (records the buyer's incoming STOCK credit; the strike legs ride the
	// seller-bank OPTION pseudo-account and are validated as balance-only on our side).
	localVote, err := c.executor.PrepareLocal(ctx, tx)
	if err != nil {
		c.releaseBuyerStrike(ctx, buyerStrikeResID)
		return protocol.ForeignBankId{}, fmt.Errorf("%w: local prepare infra failure: %v", ErrInterbankProtocol, err)
	}
	if localVote.Vote != protocol.VoteYes {
		c.releaseBuyerStrike(ctx, buyerStrikeResID)
		return protocol.ForeignBankId{}, fmt.Errorf("%w: local prepare rejected: %v", ErrInterbankProtocol, localVote.Reasons)
	}

	// 2. Partner prepare.
	partnerVote, err := c.outbound.SendNewTx(ctx, partnerRouting, tx)
	if err != nil {
		c.safeRollbackLocal(ctx, txID)
		c.releaseBuyerStrike(ctx, buyerStrikeResID)
		return protocol.ForeignBankId{}, fmt.Errorf("%w: partner prepare exception: %v", ErrInterbankProtocol, err)
	}
	if partnerVote.Vote != protocol.VoteYes {
		c.safeRollbackLocal(ctx, txID)
		c.safeRollbackPartner(ctx, partnerRouting, txID)
		c.releaseBuyerStrike(ctx, buyerStrikeResID)
		return protocol.ForeignBankId{}, fmt.Errorf("%w: partner rejected: %v", ErrInterbankProtocol, partnerVote.Reasons)
	}

	// 3. CommitLocal.
	if err := c.executor.CommitLocal(ctx, txID); err != nil {
		c.safeRollbackPartner(ctx, partnerRouting, txID)
		c.releaseBuyerStrike(ctx, buyerStrikeResID)
		return protocol.ForeignBankId{}, fmt.Errorf("%w: catastrophic commitLocal failure: %v", ErrInterbankProtocol, err)
	}

	// 3b. Commit the buyer strike — the 2PC is now durable (partner prepared YES, our
	// stock credit committed). The buyer's reserved strike is permanently debited. Mirrors
	// the partner doing reservationApplier.commitMonas AFTER its own successful 2PC.
	c.commitBuyerStrike(ctx, buyerStrikeResID)

	// 4. Partner commit — best-effort (retry scheduler covers failures).
	if err := c.outbound.SendCommitTx(ctx, partnerRouting, txID); err != nil {
		c.log.WarnContext(ctx, "coordinator: exercise sendCommitTx to partner failed — retry scheduler will pick it up",
			"partnerRouting", partnerRouting, "txID", txID, "err", err)
	}

	c.log.InfoContext(ctx, "exercised cross-bank option contract",
		"contract", contract.ID, "ticker", contract.StockTicker, "amount", contract.Amount,
		"partnerRouting", partnerRouting, "tx", txID)
	return txID, nil
}

// reserveBuyerStrike places an off-wire reservation on the buyer's strike account for the
// buyer-side exercise. Returns "" (no error) when no reserver is wired or the account
// cannot be resolved as ours — conservation then relies on an upstream debit, and the
// commit/release helpers no-op on an empty id. A reservation failure (insufficient funds)
// IS surfaced so the exercise aborts before any cross-bank movement.
func (c *Coordinator) reserveBuyerStrike(ctx context.Context, contract *store.Contract, currency string, amount decimal.Decimal, txID protocol.ForeignBankId) (string, error) {
	if c.monasResv == nil || contract.BuyerRouting != c.myRouting {
		return "", nil
	}
	ownerID, err := parseBuyerOwnerID(contract.BuyerID)
	if err != nil {
		// A non-numeric buyer id is unexpected for our own party; surface it so the
		// exercise aborts rather than silently skipping the buyer debit.
		return "", fmt.Errorf("parse buyer owner id %q: %w", contract.BuyerID, err)
	}
	accountNum, err := c.monasResv.FindAccountByOwnerAndCurrency(ctx, ownerID, currency)
	if err != nil || accountNum == "" {
		return "", fmt.Errorf("resolve buyer %d %s account: %w", ownerID, currency, err)
	}
	resID, err := c.monasResv.ReserveMonas(ctx, accountNum, currency, amount.Abs(), txID.RoutingNumber, txID.Id)
	if err != nil {
		return "", fmt.Errorf("reserve buyer strike on %s: %w", accountNum, err)
	}
	return resID, nil
}

// commitBuyerStrike permanently debits a previously reserved buyer strike. No-op on an
// empty id. Best-effort: a commit failure is logged (the 2PC is already durable).
func (c *Coordinator) commitBuyerStrike(ctx context.Context, reservationID string) {
	if c.monasResv == nil || reservationID == "" {
		return
	}
	if err := c.monasResv.CommitMonas(ctx, reservationID); err != nil {
		c.log.ErrorContext(ctx, "coordinator: commit buyer strike failed — 2PC committed, manual reconciliation needed",
			"reservationID", reservationID, "err", err)
	}
}

// releaseBuyerStrike frees a buyer strike reservation on abort. No-op on an empty id.
func (c *Coordinator) releaseBuyerStrike(ctx context.Context, reservationID string) {
	if c.monasResv == nil || reservationID == "" {
		return
	}
	if err := c.monasResv.ReleaseMonas(ctx, reservationID); err != nil {
		c.log.WarnContext(ctx, "coordinator: release buyer strike failed", "reservationID", reservationID, "err", err)
	}
}

// parseBuyerOwnerID strips a C-/E- prefix and parses the numeric owner id.
func parseBuyerOwnerID(buyerID string) (int64, error) {
	numericPart := buyerID
	if len(buyerID) > 2 && (buyerID[:2] == "C-" || buyerID[:2] == "E-") {
		numericPart = buyerID[2:]
	}
	return parseBigInt(numericPart)
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

func (c *Coordinator) resolveMonasAccount(ctx context.Context, userRouting int, userID string, currency string) protocol.TxAccount {
	if userRouting != c.myRouting || c.bcResolver == nil {
		return &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: userRouting, Id: userID}}
	}
	// Strip C-/E- prefix to get numeric owner id.
	numericPart := userID
	if len(userID) > 2 && (userID[:2] == "C-" || userID[:2] == "E-") {
		numericPart = userID[2:]
	}
	ownerID, err := parseBigInt(numericPart)
	if err != nil {
		c.log.WarnContext(ctx, "coordinator: parse owner id failed; fallback to Person",
			"userID", userID, "err", err)
		return &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: userRouting, Id: userID}}
	}
	accountNum, err := c.bcResolver.FindAccountByOwnerAndCurrency(ctx, ownerID, currency)
	if err != nil || accountNum == "" {
		c.log.WarnContext(ctx, "coordinator: resolve MONAS account failed; fallback to Person",
			"userID", userID, "currency", currency, "err", err)
		return &protocol.PersonAccount{Id: protocol.ForeignBankId{RoutingNumber: userRouting, Id: userID}}
	}
	return &protocol.RealAccount{Num: accountNum}
}

// recordOutboundPayment writes a transaction-history row for an outbound cross-bank
// payment (money leaving one of our accounts). Best-effort: failures are logged and
// swallowed so they never affect the already-committed transfer. No-op when no resolver
// is wired (e.g. tests that pass a nil bcResolver).
func (c *Coordinator) recordOutboundPayment(ctx context.Context, txID protocol.ForeignBankId, fromAccount, toAccount string, amount decimal.Decimal, currency, message string) {
	if c.bcResolver == nil {
		return
	}
	purpose := message
	if purpose == "" {
		purpose = "Interbank outbound payment"
	}
	orderNumber := interbankOrderNumber(txID, "OUT", "")
	if err := c.bcResolver.RecordInterbankPayment(ctx, orderNumber, fromAccount, toAccount, amount, currency, "", purpose); err != nil {
		c.log.WarnContext(ctx, "coordinator: record outbound interbank payment failed — transfer already committed, continuing",
			"txID", txID, "from", fromAccount, "to", toAccount, "err", err)
	}
}

func (c *Coordinator) safeRollbackLocal(ctx context.Context, txID protocol.ForeignBankId) {
	if err := c.executor.RollbackLocal(ctx, txID); err != nil {
		c.log.WarnContext(ctx, "coordinator: safeRollbackLocal failed", "txID", txID, "err", err)
	}
}

func (c *Coordinator) safeRollbackPartner(ctx context.Context, partnerRouting int, txID protocol.ForeignBankId) {
	if err := c.outbound.SendRollbackTx(ctx, partnerRouting, txID); err != nil {
		c.log.WarnContext(ctx, "coordinator: safeRollbackPartner failed",
			"partnerRouting", partnerRouting, "txID", txID, "err", err)
	}
}

func generateTxID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return "tx-" + hex.EncodeToString(b)
}

func generateContractID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return "otc-" + hex.EncodeToString(b)
}

// parseBigInt parses a numeric string into int64. Used for owner ID extraction.
func parseBigInt(s string) (int64, error) {
	n := new(big.Int)
	if _, ok := n.SetString(s, 10); !ok {
		return 0, fmt.Errorf("not a valid integer: %q", s)
	}
	if !n.IsInt64() {
		return 0, fmt.Errorf("integer too large for int64: %q", s)
	}
	return n.Int64(), nil
}

