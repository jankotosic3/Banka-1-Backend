package com.banka1.credit_service.service.implementation;

import com.banka1.credit_service.domain.Loan;
import com.banka1.credit_service.domain.LoanRequest;
import com.banka1.credit_service.domain.enums.CurrencyCode;
import com.banka1.credit_service.domain.enums.InterestType;
import com.banka1.credit_service.domain.enums.LoanType;
import com.banka1.credit_service.domain.enums.Status;
import com.banka1.credit_service.dto.request.BankPaymentDto;
import com.banka1.credit_service.dto.request.LoanRequestDto;
import com.banka1.credit_service.dto.response.*;
import com.banka1.credit_service.repository.LoanRequestRepository;
import com.banka1.credit_service.rest_client.AccountService;
import com.banka1.credit_service.rest_client.ExchangeService;
import com.banka1.credit_service.service.LoanService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor

public class LoanServiceImplementation implements LoanService {


    private final AccountService accountService;
    private final ExchangeService exchangeService;
    private final LoanRequestRepository loanRequestRepository;

    @Value("${banka.security.id}")
    private String appPropertiesId;

    private final double startRange=-1.5;
    private final double endRange=1.5;

    private BigDecimal referenceRate=BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(startRange, endRange)).setScale(4, RoundingMode.HALF_UP);



    BigDecimal[] iznosi={BigDecimal.valueOf(500_000), BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(2_000_000), BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(10_000_000), BigDecimal.valueOf(20_000_000)};

    private BigDecimal interestRate(BigDecimal amount, CurrencyCode currencyCode,LoanType loanType, InterestType interestType)
    {

        if(currencyCode!=CurrencyCode.RSD)
        {
            ConversionResponseDto conversionResponseDto=exchangeService.calculate(currencyCode,CurrencyCode.RSD,amount);
            amount=conversionResponseDto.toAmount();
            if(amount==null)
                throw new RuntimeException("Greska sa exchange servisom");
        }

        int start=0;
        int end=iznosi.length-1;
        while(start<=end)
        {
            int mid=start + (end-start)/2;
            int result=amount.compareTo(iznosi[mid]);
            switch (result)
            {
                case 0 -> start=mid;
                case -1-> end=mid-1;
                case 1-> start=mid+1;
            }
            if(result==0)
                break;
        }
        BigDecimal val=BigDecimal.valueOf(6.25).subtract(BigDecimal.valueOf(0.25).multiply(BigDecimal.valueOf(start))).add(loanType.getMarza());
        if(interestType==InterestType.VARIABLE) {
            val = val.add(getReferenceRate());
        }
        return val.divide(BigDecimal.valueOf(1200),10, RoundingMode.HALF_UP);
    }

    @Transactional
    @Override
    public LoanRequestResponseDto request(Jwt jwt, LoanRequestDto loanRequestDto) {
        if(loanRequestDto.getLoanType()== LoanType.STAMBENI)
        {
                if(loanRequestDto.getRepaymentPeriod()>360 || loanRequestDto.getRepaymentPeriod() % 60 != 0)
                {
                    throw new IllegalArgumentException("Nevalidan repaymentPeriod, mora biti 60, 120, 180, 240, 300 ili 360");
                }
        }
        else
        {
            if(loanRequestDto.getRepaymentPeriod()>84 || loanRequestDto.getRepaymentPeriod() % 12 != 0)
            {
                throw new IllegalArgumentException("Nevalidan repaymentPeriod, mora biti 12, 24, 36, 48, 60, 72 ili 84");
            }
        }
        AccountDetailsResponseDto accountDetailsResponseDto=accountService.getDetails(loanRequestDto.getAccountNumber());
        if(accountDetailsResponseDto == null)
            throw new IllegalArgumentException("Ne postoji racun:"+loanRequestDto.getAccountNumber());
        if(accountDetailsResponseDto.getOwnerId()==null || !accountDetailsResponseDto.getOwnerId().equals(((Number) jwt.getClaim(appPropertiesId)).longValue()))
            throw new IllegalArgumentException("Nisi vlasnik racuna");
        if(accountDetailsResponseDto.getCurrency()!=loanRequestDto.getCurrency())
        {
            throw new IllegalArgumentException("Valuta racuna ne odgovara valuti kredita");
        }
        LoanRequest loanRequest=loanRequestRepository.save(new LoanRequest(loanRequestDto.getLoanType(),loanRequestDto.getInterestType(),loanRequestDto.getAmount(),loanRequestDto.getCurrency(),loanRequestDto.getPurpose(),loanRequestDto.getMonthlySalary(),loanRequestDto.getEmploymentStatus(),loanRequestDto.getCurrentEmploymentPeriod(),loanRequestDto.getRepaymentPeriod(),loanRequestDto.getContactPhone(),loanRequestDto.getAccountNumber(),loanRequestDto.getClientId(), Status.PENDING));
        return new LoanRequestResponseDto(loanRequest.getId(),loanRequest.getCreatedAt());
    }

    @Transactional
    @Override
    public String confirmation(Jwt jwt, Long id,Status status) {

        if(loanRequestRepository.updateStatus(id,status)!=1)
        {
            LoanRequest loanRequest=loanRequestRepository.findById(id).orElse(null);
            if(loanRequest==null)
                throw new RuntimeException("Ne postoji loanRequest sa ovim id-em");
            throw new RuntimeException("Umesto PENDING status je: "+loanRequest.getStatus());
        }
        if(status==Status.APPROVED)
        {
            LoanRequest loanRequest=loanRequestRepository.findById(id).orElse(null);
            if(loanRequest==null)
                throw new IllegalStateException("Ako se ovo desi obavezno neka me neko kontaktira (Ognjen) posto ovo ne bi trebalo da je moguce");
            BigDecimal interest=interestRate(loanRequest.getAmount(),loanRequest.getCurrency(),loanRequest.getLoanType(),loanRequest.getInterestType());
            BigDecimal stepen=interest.add(BigDecimal.ONE).pow(loanRequest.getRepaymentPeriod());
            BigDecimal val=interest.multiply(stepen).divide(stepen.subtract(BigDecimal.ONE),10, RoundingMode.HALF_UP);
            BigDecimal monthlyRate=loanRequest.getAmount().multiply(val);
            UpdatedBalanceResponseDto updatedBalanceResponseDto=accountService.transactionFromBank(new BankPaymentDto(loanRequest.getAccountNumber(),loanRequest.getAmount()));
            Loan loan=new Loan();
            loan.setLoanType(loanRequest.getLoanType());
            loan.setAccountNumber(loanRequest.getAccountNumber());
            loan.setAmount(updatedBalanceResponseDto.getReceiverBalance());
            //loan.setRepaymentMethod(loanRequest.getRepaymentMethod());
            loan.setInterestType(loanRequest.getInterestType());
            //loan.setAgreementDate(loanRequest.getAgreementDate());
            //loan.setMaturityDate(loanRequest.getMaturityDate());
            //loan.setInstallmentAmount(loanRequest.getInstallmentAmount());
            //loan.setNextInstallmentDate(loanRequest);
            //loan.setRemainingDebt(loanRequest.getre);
            loan.setCurrency(loanRequest.getCurrency());
            //todo ovo bi trebalo da radi ali proveri
            loan.setStatus(loanRequest.getStatus());

        }
        return "";
    }


    @Scheduled(cron = "0 0 0 1 * *", zone = "Europe/Belgrade")
    public void generateReferenceRate()
    {
        double random = ThreadLocalRandom.current().nextDouble(startRange, endRange);
        setReferenceRate(BigDecimal.valueOf(random).setScale(4, RoundingMode.HALF_UP));
    }

    public synchronized BigDecimal getReferenceRate() {
        return referenceRate;
    }

    public synchronized void setReferenceRate(BigDecimal referenceRate) {
        this.referenceRate = referenceRate;
    }

    @Override
    public Page<LoanResponseDto> find(Jwt jwt, int page, int size) {
        return null;
    }

    @Override
    public LoanInfoResponseDto info(Jwt jwt, Long id) {
        return null;
    }

    @Override
    public Page<LoanRequest> findAllLoanRequest(Jwt jwt, String vrstaKredita, String brojRacuna, int page, int size) {
        return null;
    }

    @Override
    public Page<LoanResponseDto> findAllLoans(Jwt jwt, String vrstaKredita, String brojRacuna, Status status, int page, int size) {
        return null;
    }
}
