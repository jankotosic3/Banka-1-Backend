package com.banka1.tradingservice.otc.service;

import com.banka1.order.client.StockClient;
import com.banka1.order.dto.StockListingDto;
import com.banka1.order.entity.Portfolio;
import com.banka1.order.repository.PortfolioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockReservationService {

    private final PortfolioRepository portfolioRepository;
    private final StockClient stockClient;
    private final JdbcTemplate jdbcTemplate;

    public record Reservation(String reservationId, String status) {}
    public record OwnershipTransfer(String ownershipTransferId, String status) {}

    @Transactional
    public Reservation reserve(Long sellerId, String stockTicker, int amount, String correlationId) {
        Portfolio portfolio = findPortfolioByTicker(sellerId, stockTicker);
        int available = portfolio.getQuantity() - (portfolio.getReservedQuantity() == null ? 0 : portfolio.getReservedQuantity());
        int reserved = portfolio.getReservedQuantity() == null ? 0 : portfolio.getReservedQuantity();
        boolean consumeExistingOtcReservation = isOtcExercise(correlationId) && reserved >= amount;
        if (available < amount && !consumeExistingOtcReservation) {
            throw new IllegalStateException("Prodavac " + sellerId + " nema dovoljno slobodnih " + stockTicker
                    + " akcija: available=" + available + " requested=" + amount);
        }
        if (!consumeExistingOtcReservation) {
            portfolio.setReservedQuantity(reserved + amount);
            portfolioRepository.save(portfolio);
        }

        UUID reservationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO stock_reservations (reservation_id, correlation_id, seller_id, listing_id, stock_ticker, amount, status) "
                        + "VALUES (?::uuid, ?, ?, ?, ?, ?, 'HELD')",
                reservationId.toString(), correlationId, sellerId, portfolio.getListingId(), stockTicker, amount);

        log.info("Stock reserved: seller={} ticker={} amount={} reservationId={} consumedExistingOtcReservation={}",
                sellerId, stockTicker, amount, reservationId, consumeExistingOtcReservation);
        return new Reservation(reservationId.toString(), "HELD");
    }

    @Transactional
    public Reservation release(String reservationId, String correlationId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT seller_id, listing_id, amount, status FROM stock_reservations WHERE reservation_id = ?::uuid",
                reservationId);
        if (rows.isEmpty()) {
            log.warn("Release: reservation {} not found — duplicate compensation? correlationId={}", reservationId, correlationId);
            return new Reservation(reservationId, "UNKNOWN");
        }
        Map<String, Object> row = rows.get(0);
        if (!"HELD".equals(row.get("status"))) {
            return new Reservation(reservationId, (String) row.get("status"));
        }
        Long sellerId = ((Number) row.get("seller_id")).longValue();
        Long listingId = ((Number) row.get("listing_id")).longValue();
        int amount = ((Number) row.get("amount")).intValue();

        portfolioRepository.findByUserIdAndListingId(sellerId, listingId).ifPresent(p -> {
            p.setReservedQuantity(Math.max(0, (p.getReservedQuantity() == null ? 0 : p.getReservedQuantity()) - amount));
            portfolioRepository.save(p);
        });
        jdbcTemplate.update("UPDATE stock_reservations SET status='RELEASED', released_at=NOW() WHERE reservation_id = ?::uuid AND status='HELD'",
                reservationId);
        log.info("Stock reservation released: reservationId={}", reservationId);
        return new Reservation(reservationId, "RELEASED");
    }

    @Transactional
    public OwnershipTransfer transferOwnership(String reservationId, Long buyerId, String correlationId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT seller_id, listing_id, stock_ticker, amount, status FROM stock_reservations WHERE reservation_id = ?::uuid",
                reservationId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Stock reservation " + reservationId + " not found");
        }
        Map<String, Object> row = rows.get(0);
        if (!"HELD".equals(row.get("status"))) {
            throw new IllegalStateException("Stock reservation " + reservationId + " is not HELD: " + row.get("status"));
        }
        Long sellerId = ((Number) row.get("seller_id")).longValue();
        Long listingId = ((Number) row.get("listing_id")).longValue();
        String ticker = (String) row.get("stock_ticker");
        int amount = ((Number) row.get("amount")).intValue();

        // Decrement seller portfolio
        Portfolio sellerPortfolio = portfolioRepository.findByUserIdAndListingId(sellerId, listingId)
                .orElseThrow(() -> new IllegalStateException("Seller " + sellerId + " portfolio for listing " + listingId + " not found"));
        sellerPortfolio.setQuantity(sellerPortfolio.getQuantity() - amount);
        sellerPortfolio.setReservedQuantity(Math.max(0, (sellerPortfolio.getReservedQuantity() == null ? 0 : sellerPortfolio.getReservedQuantity()) - amount));
        portfolioRepository.save(sellerPortfolio);

        // Upsert buyer portfolio
        Portfolio buyerPortfolio = portfolioRepository.findByUserIdAndListingId(buyerId, listingId)
                .orElseGet(() -> {
                    Portfolio p = new Portfolio();
                    p.setUserId(buyerId);
                    p.setListingId(listingId);
                    p.setListingType(sellerPortfolio.getListingType());
                    p.setQuantity(0);
                    p.setReservedQuantity(0);
                    p.setPublicQuantity(0);
                    p.setAveragePurchasePrice(sellerPortfolio.getAveragePurchasePrice());
                    return p;
                });
        buyerPortfolio.setQuantity(buyerPortfolio.getQuantity() + amount);
        portfolioRepository.save(buyerPortfolio);

        // Mark reservation committed
        jdbcTemplate.update("UPDATE stock_reservations SET status='COMMITTED' WHERE reservation_id = ?::uuid", reservationId);

        // Log transfer
        UUID transferId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO stock_ownership_transfers (transfer_id, reservation_id, correlation_id, seller_id, buyer_id, listing_id, stock_ticker, amount, status) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, 'COMPLETED')",
                transferId.toString(), reservationId, correlationId, sellerId, buyerId, listingId, ticker, amount);

        log.info("Ownership transferred: ticker={} amount={} seller={} buyer={} transferId={}", ticker, amount, sellerId, buyerId, transferId);
        return new OwnershipTransfer(transferId.toString(), "COMPLETED");
    }

    @Transactional
    public void reverseOwnership(String ownershipTransferId, String correlationId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT seller_id, buyer_id, listing_id, amount, status FROM stock_ownership_transfers WHERE transfer_id = ?::uuid",
                ownershipTransferId);
        if (rows.isEmpty()) {
            log.warn("Reverse ownership: transfer {} not found — correlationId={}", ownershipTransferId, correlationId);
            return;
        }
        Map<String, Object> row = rows.get(0);
        if (!"COMPLETED".equals(row.get("status"))) {
            log.info("Reverse ownership: transfer {} already in state {} — no-op", ownershipTransferId, row.get("status"));
            return;
        }
        Long sellerId = ((Number) row.get("seller_id")).longValue();
        Long buyerId = ((Number) row.get("buyer_id")).longValue();
        Long listingId = ((Number) row.get("listing_id")).longValue();
        int amount = ((Number) row.get("amount")).intValue();

        // Reverse buyer
        portfolioRepository.findByUserIdAndListingId(buyerId, listingId).ifPresent(p -> {
            p.setQuantity(Math.max(0, p.getQuantity() - amount));
            portfolioRepository.save(p);
        });
        // Restore seller
        portfolioRepository.findByUserIdAndListingId(sellerId, listingId).ifPresent(p -> {
            p.setQuantity(p.getQuantity() + amount);
            p.setReservedQuantity((p.getReservedQuantity() == null ? 0 : p.getReservedQuantity()) + amount);
            portfolioRepository.save(p);
        });
        jdbcTemplate.update("UPDATE stock_ownership_transfers SET status='REVERSED', reversed_at=NOW() WHERE transfer_id = ?::uuid", ownershipTransferId);
        log.info("Ownership reversed: transferId={}", ownershipTransferId);
    }

    private Portfolio findPortfolioByTicker(Long userId, String ticker) {
        for (Portfolio p : portfolioRepository.findByUserId(userId)) {
            try {
                StockListingDto listing = stockClient.getListing(p.getListingId());
                if (listing != null && ticker.equalsIgnoreCase(listing.getTicker())) {
                    return p;
                }
            } catch (Exception ignored) {}
        }
        throw new IllegalStateException("Korisnik " + userId + " nema portfolio poziciju za ticker " + ticker);
    }

    private boolean isOtcExercise(String correlationId) {
        return correlationId != null && correlationId.startsWith("otc-exercise-");
    }
}
