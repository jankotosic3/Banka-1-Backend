package com.banka1.bankingcore.account.service.margin;

import com.banka1.bankingcore.account.domain.margin.CompanyMarginAccount;
import com.banka1.bankingcore.account.domain.margin.MarginAccount;
import com.banka1.bankingcore.account.domain.margin.UserMarginAccount;
import com.banka1.bankingcore.account.dto.margin.CreateCompanyMarginAccountDto;
import com.banka1.bankingcore.account.dto.margin.CreateUserMarginAccountDto;
import com.banka1.bankingcore.account.dto.margin.MarginAccountResponseDto;
import com.banka1.bankingcore.account.repository.margin.CompanyMarginAccountRepository;
import com.banka1.bankingcore.account.repository.margin.MarginAccountRepository;
import com.banka1.bankingcore.account.repository.margin.UserMarginAccountRepository;
import com.banka1.bankingcore.account.service.AccountNumberGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Servis za marzne racune (PR_03 C3.4).
 *
 * <p>Spec: Marzni_Racuni.txt — kreiranje, citanje, aktivacija/deaktivacija po
 * threshold-u maintenanceMargin-a.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarginAccountService {

    private final MarginAccountRepository marginAccountRepository;
    private final UserMarginAccountRepository userMarginAccountRepository;
    private final CompanyMarginAccountRepository companyMarginAccountRepository;
    private final AccountNumberGenerator accountNumberGenerator;

    /**
     * Kreira marzni racun za fizicko lice (user). Spec: "korisnik moze imati samo jedan
     * marzni racun" — proverava se pre insert-a; uniqueness na DB nivou je dodatna
     * zastita.
     *
     * @throws IllegalStateException ako user vec ima marzni racun.
     */
    @Transactional
    public MarginAccountResponseDto createForUser(CreateUserMarginAccountDto dto) {
        if (userMarginAccountRepository.existsByUserId(dto.getUserId())) {
            throw new IllegalStateException(
                    "Korisnik " + dto.getUserId() + " vec ima marzni racun (spec: max jedan po klijentu).");
        }

        UserMarginAccount account = new UserMarginAccount();
        applyCommonFields(account, dto.getInitialMargin(), dto.getMaintenanceMargin(), dto.getBankParticipation());
        account.setUserId(dto.getUserId());

        UserMarginAccount saved = userMarginAccountRepository.save(account);
        log.info("Created UserMarginAccount id={} for userId={} (employeeId={} initiated)",
                saved.getId(), saved.getUserId(), dto.getEmployeeId());

        return toResponseDto(saved);
    }

    /**
     * Kreira marzni racun za pravno lice (company).
     */
    @Transactional
    public MarginAccountResponseDto createForCompany(CreateCompanyMarginAccountDto dto) {
        if (companyMarginAccountRepository.existsByCompanyId(dto.getCompanyId())) {
            throw new IllegalStateException(
                    "Kompanija " + dto.getCompanyId() + " vec ima marzni racun (spec: max jedan po kompaniji).");
        }

        CompanyMarginAccount account = new CompanyMarginAccount();
        applyCommonFields(account, dto.getInitialMargin(), dto.getMaintenanceMargin(), dto.getBankParticipation());
        account.setCompanyId(dto.getCompanyId());

        CompanyMarginAccount saved = companyMarginAccountRepository.save(account);
        log.info("Created CompanyMarginAccount id={} for companyId={} (employeeId={} initiated)",
                saved.getId(), saved.getCompanyId(), dto.getEmployeeId());

        return toResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public MarginAccountResponseDto findByUserId(Long userId) {
        return userMarginAccountRepository.findByUserId(userId)
                .map(this::toResponseDto)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Marzni racun za userId=" + userId + " ne postoji."));
    }

    @Transactional(readOnly = true)
    public MarginAccountResponseDto findByCompanyId(Long companyId) {
        return companyMarginAccountRepository.findByCompanyId(companyId)
                .map(this::toResponseDto)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Marzni racun za companyId=" + companyId + " ne postoji."));
    }

    /**
     * Spec: "ako InitialMargin vrednost prilikom skidanja para padne ispod
     * MaitenanceMargin, racun se blokira; kada InitialMargin predje MaitenanceMargin,
     * racun se odblokira".
     *
     * <p>Poziva se iz buy/sell flow-a posle svakog menjanja initialMargin-a.
     * Ne vrsi save() — ostavlja flushing pozivaocu, koji je u transakciji.
     */
    public void recalcActive(MarginAccount account) {
        boolean shouldBeActive = account.getInitialMargin().compareTo(account.getMaintenanceMargin()) >= 0;
        if (shouldBeActive != account.isActive()) {
            log.info("Margin account {} state change: {} -> {} (initial={}, maintenance={})",
                    account.getAccountNumber(), account.isActive(), shouldBeActive,
                    account.getInitialMargin(), account.getMaintenanceMargin());
            account.setActive(shouldBeActive);
        }
    }

    // ------------------------------------------------------------------
    // Pomocni
    // ------------------------------------------------------------------

    private void applyCommonFields(MarginAccount acc, BigDecimal initial, BigDecimal maintenance, BigDecimal participation) {
        acc.setInitialMargin(initial);
        acc.setMaintenanceMargin(maintenance);
        acc.setBankParticipation(participation);
        acc.setLoanValue(BigDecimal.ZERO);
        // Spec: "korisnik moze imati samo jedan marzni racun"; broj se generise prema
        // mod-11 algoritmu kao i obican racun (vec implementirano u AccountNumberGenerator
        // iz banking-core.account.service paketa).
        acc.setAccountNumber(accountNumberGenerator.generate());
        // initial >= maintenance pri kreiranju je tipican slucaj; ako nije, racun ce
        // krenuti kao deactive sto je ispravno.
        acc.setActive(initial.compareTo(maintenance) >= 0);
    }

    private MarginAccountResponseDto toResponseDto(MarginAccount account) {
        MarginAccountResponseDto.MarginAccountResponseDtoBuilder b = MarginAccountResponseDto.builder()
                .accountNumber(account.getAccountNumber())
                .initialMargin(account.getInitialMargin())
                .loanValue(account.getLoanValue())
                .maintenanceMargin(account.getMaintenanceMargin())
                .bankParticipation(account.getBankParticipation())
                .active(account.isActive());

        if (account instanceof UserMarginAccount user) {
            b.userId(user.getUserId());
        } else if (account instanceof CompanyMarginAccount company) {
            b.companyId(company.getCompanyId());
        }

        return b.build();
    }
}
