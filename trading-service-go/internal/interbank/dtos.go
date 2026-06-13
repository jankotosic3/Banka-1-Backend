package interbank

import "github.com/shopspring/decimal"

// These are the request/response DTOs for the /internal/interbank* endpoints.
// JSON tags match the Java controller record field names exactly — interbank-
// service's TradingInternalClient depends on them being byte-compatible.

// ReserveStockReq mirrors InterbankStockReservationController.ReserveStockReq.
// The @NotNull/@NotBlank/@Positive annotations on the Java record are NOT enforced
// (the controller has no @Valid), so validation happens in the service layer
// (quantity > 0, ticker non-blank) exactly as Java does.
type ReserveStockReq struct {
	SellerUserID         int64  `json:"sellerUserId"`
	Ticker               string `json:"ticker"`
	Quantity             int    `json:"quantity"`
	TransactionIDRouting int    `json:"transactionIdRouting"`
	TransactionIDLocal   string `json:"transactionIdLocal"`
}

// ReserveStockRes mirrors InterbankStockReservationController.ReserveStockRes — the
// 200 body of POST /reserve-stock. Java's UUID field serializes as its canonical
// lowercase string, which is exactly what newUUIDv4 produces.
type ReserveStockRes struct {
	ReservationID string `json:"reservationId"`
}

// CreditStockReq is the body of POST /internal/interbank/credit-stock (FIX 1):
// interbank-service posts it on the COMMIT of a cross-bank OTC option exercise so the
// local buyer is credited the shares delivered by the partner-side seller. buyerUserId
// is the local owner id (the "C-N" prefix already stripped by interbank-service);
// strikePrice is the fallback average purchase price when market-service has no live
// price for the ticker.
type CreditStockReq struct {
	BuyerUserID          int64           `json:"buyerUserId"`
	Ticker               string          `json:"ticker"`
	Quantity             int             `json:"quantity"`
	TransactionIDRouting int             `json:"transactionIdRouting"`
	TransactionIDLocal   string          `json:"transactionIdLocal"`
	StrikePrice          decimal.Decimal `json:"strikePrice"`
}

// ReserveOptionReq mirrors InterbankOptionController.ReserveOptionReq.
// sellerForeignId is a pointer so an absent field (Java null →
// "sellerForeignId must not be null") is distinguishable from a present value
// (parsed by parseUserID, stripping the "C-"/"E-" prefix).
type ReserveOptionReq struct {
	SellerForeignID *string `json:"sellerForeignId"`
	Ticker          string  `json:"ticker"`
	Quantity        int     `json:"quantity"`
}

// --- GET /public-stocks response (mirrors PublicStocksInternalController records) -

// StockDescription mirrors the record StockDescription(String ticker).
type StockDescription struct {
	Ticker string `json:"ticker"`
}

// ForeignBankId mirrors the record ForeignBankId(int routingNumber, String id):
// the {routingNumber, "C-"+userId} foreign-bank tag the interbank protocol uses.
type ForeignBankId struct {
	RoutingNumber int    `json:"routingNumber"`
	ID            string `json:"id"`
}

// PublicStockSeller mirrors the record PublicStockSeller(ForeignBankId seller, int amount).
type PublicStockSeller struct {
	Seller ForeignBankId `json:"seller"`
	Amount int           `json:"amount"`
}

// PublicStockEntry mirrors the record
// PublicStockEntry(StockDescription stock, List<PublicStockSeller> sellers): one
// ticker and its sellers, grouped in insertion (Postgres row) order.
type PublicStockEntry struct {
	Stock   StockDescription    `json:"stock"`
	Sellers []PublicStockSeller `json:"sellers"`
}
