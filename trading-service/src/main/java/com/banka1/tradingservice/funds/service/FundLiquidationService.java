package com.banka1.tradingservice.funds.service;

import com.banka1.tradingservice.funds.client.MarketPriceClient;
import com.banka1.tradingservice.funds.client.AccountServiceClient;
import com.banka1.tradingservice.funds.domain.FundHolding;
import com.banka1.tradingservice.funds.domain.InvestmentFund;
import com.banka1.tradingservice.funds.repository.InvestmentFundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Likvidacija holdings-a fonda za zadati ciljni iznos (PR_15 C15.3).
 *
 * <p>Spec (Celina 4.txt — "ako iznos nije pokriven likvidnim sredstvima, vrsi se
 * automatska likvidacija dovoljnog broja hartija fonda da se pokrije zeljeni iznos").
 *
 * <p>Algoritam:
 * <ol>
 *   <li>Iterira aktivne FundHolding-e fonda po quantity DESC (likvidirati najvece pozicije prvo).
 *   <li>Za svaki holding, prodaje deo dok cumulative liquidatedAmount ne dostigne targetAmount.
 *   <li>"Prodaja" je u ovoj implementaciji simulirana — koristi {@code avgUnitPrice} kao
 *       trzisnu cenu (TBD: integracija sa market-service stock price feed-om).
 *   <li>Smanjuje quantity holdinga preko {@link FundHoldingService#reduce}.
 *   <li>Uvecava {@code likvidnaSredstva} fonda za stvarni dobijeni iznos.
 *   <li>Vraca {@link Result} sa liquidationId, totalLiquidatedAmount, holdingsSold count.
 * </ol>
 *
 * <p>Posto je likvidacija "atomicna" (1 transakcija), ako bilo sta padne, sve se
 * rollback-uje. Saga orchestrator vidi failure i ne ulazi u step 2 (transfer).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundLiquidationService {

    private static final String HOLDING_PRICE_CURRENCY = "USD";
    private static final String FUND_BASE_CURRENCY = "RSD";

    private final InvestmentFundRepository fundRepository;
    private final FundHoldingService fundHoldingService;
    /**
     * MarketPriceClient (PR_18 C18.1) — live market price feed za pravu likvidaciju.
     * Wrap-uje se u {@link ObjectProvider} jer u local profilu (test) bean ne postoji.
     */
    private final ObjectProvider<MarketPriceClient> marketPriceClientProvider;
    private final ObjectProvider<AccountServiceClient> accountServiceClientProvider;

    @Transactional
    public Result liquidateForFund(Long fundId, BigDecimal targetAmount, String correlationId) {
        InvestmentFund fund = fundRepository.findByIdForUpdate(fundId)
                .orElseThrow(() -> new IllegalArgumentException("Fond " + fundId + " ne postoji."));

        List<FundHolding> holdings = fundHoldingService.listByFund(fundId).stream()
                .sorted((a, b) -> b.getQuantity().compareTo(a.getQuantity()))
                .toList();

        // PR_18 C18.1: pre PR_18 koristio se {@code avgUnitPrice} (istorijska prosecna
        // cena kupovine) kao proxy za "market price". Sada povlacimo live cene preko
        // {@link MarketPriceClient}. Ako market-service ne odgovori, fall-back na
        // avgUnitPrice — bolje da likvidacija prodje sa konzervativnom cenom nego da
        // padne kompletno (network outage je tranzitan).
        Map<String, BigDecimal> livePrices = fetchLivePrices(holdings);

        BigDecimal liquidatedTotalUsd = BigDecimal.ZERO;
        int holdingsSoldCount = 0;
        List<String> soldTickers = new ArrayList<>();
        List<String> fallbackTickers = new ArrayList<>();

        for (FundHolding h : holdings) {
            BigDecimal liquidatedTotalRsdSoFar = convertUsdToRsd(liquidatedTotalUsd);
            if (liquidatedTotalRsdSoFar.compareTo(targetAmount) >= 0) {
                break;
            }
            // Live price ima prioritet; avg je fallback.
            BigDecimal unitPrice = livePrices.get(h.getStockTicker());
            if (unitPrice == null) {
                unitPrice = h.getAvgUnitPrice();
                fallbackTickers.add(h.getStockTicker());
            }
            if (unitPrice == null || unitPrice.signum() <= 0) {
                continue;
            }

            BigDecimal stillNeededRsd = targetAmount.subtract(liquidatedTotalRsdSoFar);
            BigDecimal stillNeeded = convertRsdToUsd(stillNeededRsd);
            BigDecimal positionValue = unitPrice.multiply(BigDecimal.valueOf(h.getQuantity()));

            int sellQty;
            BigDecimal sellAmount;
            if (positionValue.compareTo(stillNeeded) <= 0) {
                sellQty = h.getQuantity();
                sellAmount = positionValue.setScale(2, RoundingMode.HALF_UP);
            } else {
                sellQty = stillNeeded.divide(unitPrice, 0, RoundingMode.CEILING).intValue();
                sellQty = Math.min(sellQty, h.getQuantity());
                sellAmount = unitPrice.multiply(BigDecimal.valueOf(sellQty))
                        .setScale(2, RoundingMode.HALF_UP);
            }

            if (sellQty <= 0) {
                continue;
            }

            fundHoldingService.reduce(fundId, h.getStockTicker(), sellQty);
            liquidatedTotalUsd = liquidatedTotalUsd.add(sellAmount);
            holdingsSoldCount++;
            soldTickers.add(sellQty + "× " + h.getStockTicker() + "@" + unitPrice);
        }

        BigDecimal liquidatedTotal = convertUsdToRsd(liquidatedTotalUsd).setScale(2, RoundingMode.HALF_UP);

        // Uvecaj likvidnaSredstva fonda. NAPOMENA: pravo trgovanje preko exchange-a
        // (matching engine, order book) i dalje nije implementirano — "sell" je instant
        // fill na current market price. Zato ovde eksplicitno kreditiramo bankovni
        // racun fonda za prihod od simulirane prodaje pre SAGA payout transfera.
        fund.setLikvidnaSredstva(fund.getLikvidnaSredstva().add(liquidatedTotal));
        fundRepository.save(fund);
        creditFundAccount(fund, liquidatedTotal, correlationId);

        String liquidationId = UUID.randomUUID().toString();
        log.info("FundLiquidation: fundId={} target={} liquidatedRsd={} liquidatedUsd={} holdings={} live={} fallback={} liquidationId={} correlationId={}",
                fundId, targetAmount, liquidatedTotal, liquidatedTotalUsd, soldTickers,
                holdings.size() - fallbackTickers.size(), fallbackTickers, liquidationId, correlationId);

        if (liquidatedTotal.compareTo(targetAmount) < 0) {
            log.warn("FundLiquidation: nedovoljno hartija za pun iznos — likvidirano {} od trazenih {}",
                    liquidatedTotal, targetAmount);
        }

        return new Result(liquidationId, liquidatedTotal, holdingsSoldCount);
    }

    /**
     * Prodaja specificne kolicine hartije od strane supervizora.
     * Koristi live trzisnu cenu (fall-back na avgUnitPrice). Prihod uvecava likvidna sredstva fonda.
     */
    @Transactional
    public SellResult sellHolding(Long fundId, String ticker, int quantity) {
        InvestmentFund fund = fundRepository.findByIdForUpdate(fundId)
                .orElseThrow(() -> new IllegalArgumentException("Fond " + fundId + " ne postoji."));

        FundHolding holding = fundHoldingService.listByFund(fundId).stream()
                .filter(h -> h.getStockTicker().equalsIgnoreCase(ticker))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Fond " + fundId + " ne poseduje hartiju " + ticker + "."));

        if (quantity > holding.getQuantity()) {
            throw new IllegalArgumentException(
                    "Trazena kolicina " + quantity + " veca od raspolozive " + holding.getQuantity() + ".");
        }

        MarketPriceClient client = marketPriceClientProvider.getIfAvailable();
        BigDecimal unitPrice = null;
        if (client != null) {
            unitPrice = client.currentPrice(ticker).orElse(null);
        }
        if (unitPrice == null) {
            unitPrice = holding.getAvgUnitPrice();
        }

        BigDecimal proceedsUsd = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal proceeds = convertUsdToRsd(proceedsUsd).setScale(2, RoundingMode.HALF_UP);
        fundHoldingService.reduce(fundId, ticker, quantity);
        fund.setLikvidnaSredstva(fund.getLikvidnaSredstva().add(proceeds));
        fundRepository.save(fund);
        creditFundAccount(fund, proceeds, "supervisor-sell");

        log.info("Supervisor sold {}x {} from fund {} at {} = {} USD / {} RSD",
                quantity, ticker, fundId, unitPrice, proceedsUsd, proceeds);
        return new SellResult(ticker, quantity, unitPrice, proceeds);
    }

    /**
     * Povlaci current market prices za sve unique ticker-e u holdings. Tolerantno na
     * greske: ako market-service ne odgovori ili nije konfigurisan, vraca prazan map
     * — caller fall-back-uje na avgUnitPrice.
     */
    private Map<String, BigDecimal> fetchLivePrices(List<FundHolding> holdings) {
        if (holdings.isEmpty()) {
            return Map.of();
        }
        MarketPriceClient client = marketPriceClientProvider.getIfAvailable();
        if (client == null) {
            log.debug("MarketPriceClient nije dostupan — fall-back na avgUnitPrice za sve holdings.");
            return Map.of();
        }
        List<String> tickers = holdings.stream().map(FundHolding::getStockTicker).distinct().toList();
        return client.currentPrices(tickers);
    }

    private void creditFundAccount(InvestmentFund fund, BigDecimal amount, String correlationId) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        AccountServiceClient client = accountServiceClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("AccountServiceClient nije dostupan — credit racuna fonda nije moguc.");
        }
        Long ownerId = -1000L - fund.getId();
        client.creditAccount(fund.getAccountNumber(), amount, ownerId);
        log.info("FundLiquidation: credited fund bank account accountNumber={} ownerId={} amount={} correlationId={}",
                fund.getAccountNumber(), ownerId, amount, correlationId);
    }

    private BigDecimal convertUsdToRsd(BigDecimal amountUsd) {
        return convertCurrency(amountUsd, HOLDING_PRICE_CURRENCY, FUND_BASE_CURRENCY);
    }

    private BigDecimal convertRsdToUsd(BigDecimal amountRsd) {
        return convertCurrency(amountRsd, FUND_BASE_CURRENCY, HOLDING_PRICE_CURRENCY);
    }

    private BigDecimal convertCurrency(BigDecimal amount, String fromCurrency, String toCurrency) {
        if (amount == null || amount.signum() == 0) {
            return BigDecimal.ZERO;
        }
        MarketPriceClient client = marketPriceClientProvider.getIfAvailable();
        if (client == null) {
            return amount;
        }
        return client.convertNoCommission(amount, fromCurrency, toCurrency).orElse(amount);
    }

    public record Result(String liquidationId, BigDecimal liquidatedAmount, int holdingsSold) {}

    public record SellResult(String ticker, int quantitySold, BigDecimal unitPrice, BigDecimal proceeds) {}
}
