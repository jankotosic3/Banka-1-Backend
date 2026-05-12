package com.banka1.bankingcore.transaction.service.internal;

import com.banka1.bankingcore.account.client.AccountServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Banking-core servis za rezervaciju klijentskih sredstava (PR_14 C14.6).
 *
 * <p>Implementira pattern koji ocekuje SAGA orchestrator:
 * <ul>
 *   <li>{@code reserve}: skida iznos sa klijentskog tekuceg racuna i upisuje
 *       red u {@code fund_reservations} sa status='HELD'.</li>
 *   <li>{@code release} (kompenzacija): vraca iznos kreditom na isti racun i
 *       prelazi na status='RELEASED'.</li>
 *   <li>{@code commit}: rezervacija je iskoriscena (sredstva su dalje preneta);
 *       ostavlja red sa status='COMMITTED' radi audit-a.</li>
 * </ul>
 *
 * <p>Idempotentnost: release i commit traze status='HELD'; ako je rezervacija
 * vec u terminal state-u (RELEASED/COMMITTED), operacija je no-op i servis
 * vraca isti rezultat (200 OK ka SAGA orchestrator-u).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundReservationService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<AccountServiceClient> accountServiceClientProvider;

    /**
     * Rezervise iznos. Pretrazuje RSD tekuci racun klijenta i debituje ga preko
     * account-service-a. Ako klijent nema RSD racun ili nema dovoljno sredstava,
     * baca {@code IllegalStateException} (account-service vraca HTTP 400/409).
     *
     * @return reservation id (UUID, string) koji SAGA orchestrator pamti za
     *         kasniji release/commit poziv.
     */
    @Transactional
    public Reservation reserve(Long ownerId, BigDecimal amount, String correlationId) {
        AccountServiceClient client = accountServiceClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("AccountServiceClient nije dostupan — rezervacija ne moze proci.");
        }
        String accountNumber = client.findClientAccounts(ownerId).stream()
                .filter(a -> "RSD".equals(a.currency()))
                .map(AccountServiceClient.AccountDetails::accountNumber)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Klijent " + ownerId + " nema RSD tekuci racun — rezervacija odbijena."));

        client.debit(accountNumber, amount, ownerId);
        UUID reservationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO fund_reservations (reservation_id, correlation_id, owner_id, account_number, amount, currency, status) "
                        + "VALUES (?::uuid, ?, ?, ?, ?, 'RSD', 'HELD')",
                reservationId.toString(), correlationId, ownerId, accountNumber, amount);

        log.info("Reserved funds: ownerId={} account={} amount={} reservationId={} correlationId={}",
                ownerId, accountNumber, amount, reservationId, correlationId);
        return new Reservation(reservationId.toString(), "HELD");
    }

    /**
     * Vraca rezervisan iznos klijentu (kompenzacija). Ako rezervacija nije u
     * stanju HELD (vec puštena ili commit-ovana), vraca {@code true} bez
     * sporednog efekta — SAGA kompenzacija sme da bude pozvana vise puta.
     */
    @Transactional
    public Reservation release(String reservationId, String correlationId) {
        AccountServiceClient client = accountServiceClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("AccountServiceClient nije dostupan — release ne moze proci.");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT owner_id, account_number, amount, status FROM fund_reservations "
                        + "WHERE reservation_id = ?::uuid",
                reservationId);
        if (rows.isEmpty()) {
            log.warn("Release: rezervacija {} ne postoji — verovatno duplicate kompenzacija. correlationId={}",
                    reservationId, correlationId);
            return new Reservation(reservationId, "UNKNOWN");
        }
        Map<String, Object> row = rows.get(0);
        String currentStatus = (String) row.get("status");
        if (!"HELD".equals(currentStatus)) {
            log.info("Release: rezervacija {} vec u state-u {} — no-op. correlationId={}",
                    reservationId, currentStatus, correlationId);
            return new Reservation(reservationId, currentStatus);
        }

        Long ownerId = ((Number) row.get("owner_id")).longValue();
        String accountNumber = (String) row.get("account_number");
        BigDecimal amount = (BigDecimal) row.get("amount");

        client.credit(accountNumber, amount, ownerId);
        jdbcTemplate.update(
                "UPDATE fund_reservations SET status='RELEASED', released_at=NOW() "
                        + "WHERE reservation_id = ?::uuid AND status='HELD'",
                reservationId);

        log.info("Released funds: reservationId={} ownerId={} account={} amount={} correlationId={}",
                reservationId, ownerId, accountNumber, amount, correlationId);
        return new Reservation(reservationId, "RELEASED");
    }

    /**
     * Markira rezervaciju kao iskoriscenu — sredstva su dalje preneta kroz
     * normalan transfer, vise nema sta da se vraca klijentu.
     */
    @Transactional
    public Reservation commit(String reservationId, String correlationId) {
        int affected = jdbcTemplate.update(
                "UPDATE fund_reservations SET status='COMMITTED', committed_at=NOW() "
                        + "WHERE reservation_id = ?::uuid AND status='HELD'",
                reservationId);
        log.info("Commit reservation {}: rows={} correlationId={}", reservationId, affected, correlationId);
        return new Reservation(reservationId, "COMMITTED");
    }

    public record Reservation(String reservationId, String status) {}
}
