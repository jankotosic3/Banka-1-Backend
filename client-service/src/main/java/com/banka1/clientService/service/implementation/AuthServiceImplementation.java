package com.banka1.clientService.service.implementation;

import com.banka1.clientService.domain.ClientConfirmationToken;
import com.banka1.clientService.domain.Klijent;
import com.banka1.clientService.domain.enums.Permission;
import com.banka1.clientService.dto.rabbitmq.EmailDto;
import com.banka1.clientService.dto.rabbitmq.EmailType;
import com.banka1.clientService.dto.requests.ActivateDto;
import com.banka1.clientService.dto.requests.ForgotPasswordDto;
import com.banka1.clientService.dto.requests.LoginRequestDto;
import com.banka1.clientService.dto.responses.LoginResponseDto;
import com.banka1.clientService.exception.BusinessException;
import com.banka1.clientService.exception.ErrorCode;
import com.banka1.clientService.rabbitMQ.RabbitClient;
import com.banka1.clientService.repository.ClientConfirmationTokenRepository;
import com.banka1.clientService.repository.KlijentRepository;
import com.banka1.clientService.service.AuthService;
import com.banka1.clientService.service.TokenService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeyLengthException;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Implementacija {@link AuthService} koja upravlja celokupnim zivotnim ciklusom autentifikacije
 * klijenata: prijava, aktivacija naloga i reset lozinke.
 * Email notifikacije se salju asinhorno putem RabbitMQ-a tek nakon uspesnog commit-a transakcije.
 */
@Service
@Transactional
public class AuthServiceImplementation implements AuthService {

    /** Repozitorijum za pristup podacima klijenata u bazi. */
    private final KlijentRepository klijentRepository;

    /** Enkoder lozinki koji koristi Argon2id algoritam. */
    private final PasswordEncoder passwordEncoder;

    /** HMAC-SHA256 potpisnik koji se koristi za generisanje JWT tokena. */
    private final MACSigner signer;

    /** Repozitorijum za pristup confirmation tokenima klijenata. */
    @Autowired
    private ClientConfirmationTokenRepository confirmationTokenRepository;

    /** Servis za generisanje i hesiranje tokena. */
    @Autowired
    private TokenService tokenService;

    /** Klijent za slanje poruka putem RabbitMQ-a. */
    @Autowired
    private RabbitClient rabbitClient;

    /** Naziv claim-a u JWT tokenu koji sadrzi role korisnika. */
    @Value("${banka.security.roles-claim}")
    private String rolesClaim;

    /** Naziv claim-a u JWT tokenu koji sadrzi identifikator korisnika. */
    @Value("${banka.security.id}")
    private String idClaim;

    /** Naziv issuera koji se upisuje u JWT token. */
    @Value("${banka.security.issuer}")
    private String issuer;

    /** Vreme vazenja JWT tokena u milisekundama. */
    @Value("${banka.security.expiration-time}")
    private Long expirationTime;

    /** Naziv claim-a u JWT-u koji nosi listu permisija korisnika. */
    @Value("${banka.security.permissions-claim}")
    private String permission;


    /** Bazni URL za aktivaciju naloga na koji se dodaje generisani token. */
    @Value("${url.activate-account}")
    private String urlActivateAccount;

    /** Bazni URL za reset lozinke na koji se dodaje generisani token. */
    @Value("${url.reset-password}")
    private String urlResetPassword;

    /** Vreme vazenja confirmation tokena u minutima. */
    @Value("${token.confirmation.expiration-time}")
    private Long confirmationTokenExpiration;

    /**
     * Kreira instancu servisa injektovanjem repozitorijuma, enkodera lozinki i JWT tajne.
     * {@link MACSigner} se inicijalizuje ovde jer zahteva tajnu vrednost pri konstruisanju.
     *
     * @param klijentRepository repozitorijum klijenata
     * @param passwordEncoder   enkoder lozinki
     * @param secret            JWT tajna vrednost ocitana iz konfiguracije
     * @throws KeyLengthException ako je tajna prekratka za HMAC-SHA256
     */
    public AuthServiceImplementation(
            KlijentRepository klijentRepository,
            PasswordEncoder passwordEncoder,
            @Value("${jwt.secret}") String secret
    ) throws KeyLengthException {
        this.klijentRepository = klijentRepository;
        this.passwordEncoder = passwordEncoder;
        this.signer = new MACSigner(secret);
    }

    /**
     * Autentifikuje klijenta proverom email adrese i lozinke.
     * Blokira prijavu ako nalog nije aktiviran.
     *
     * @param dto podaci za prijavljivanje
     * @return odgovor sa JWT pristupnim tokenom
     * @throws BusinessException ako su kredencijali neispravni ili nalog nije aktivan
     */
    @Override
    @Transactional(readOnly = true)
    public LoginResponseDto login(LoginRequestDto dto) {
        Klijent klijent = klijentRepository.findByEmail(dto.getEmail())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS, ""));

        if (!klijent.isAktivan()) {
            throw new BusinessException(ErrorCode.USER_INACTIVE, "");
        }

        if (klijent.getPassword() == null
                || !passwordEncoder.matches(dto.getPassword(), klijent.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "");
        }

        List<String> permissions = new ArrayList<>();
        for (Permission x : klijent.getPermissionSet()) {
            permissions.add(x.name());
        }

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(klijent.getEmail())
                .issuer(issuer)
                .claim(idClaim, klijent.getId())
                .claim(rolesClaim, klijent.getRole().name())
                .claim(permission, permissions)
                .expirationTime(new Date(System.currentTimeMillis() + expirationTime))
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (Exception e) {
            throw new IllegalStateException("Greska pri generisanju JWT tokena");
        }

        return new LoginResponseDto(jwt.serialize(), klijent.getId(), klijent.getIme(), klijent.getPrezime(), klijent.getEmail());
    }

    /**
     * Proverava da li je confirmation token validan i vraca ID tokena.
     *
     * @param confirmationToken token iz korisnickog linka (nehesirani, duzine 43 znaka)
     * @return identifikator confirmation tokena ako je validan
     * @throws BusinessException ako je token nevazeci ili istekao
     */
    @Override
    public Long check(String confirmationToken) {
        if (confirmationToken == null || confirmationToken.isBlank() || confirmationToken.length() != 43) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "Pogresan token");
        }
        ClientConfirmationToken token = confirmationTokenRepository
                .findByValue(tokenService.sha256Hex(confirmationToken))
                .orElse(null);
        if (token == null
                || token.getExpirationDateTime() != null
                && token.getExpirationDateTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "Pogresan token");
        }
        if (token.getKlijent().isDeleted()) {
            throw new BusinessException(ErrorCode.CLIENT_NOT_FOUND, "Klijent je obrisan");
        }
        return token.getId();
    }

    /**
     * Menja lozinku klijenta i po potrebi aktivira nalog.
     * Nakon uspesne operacije confirmation token se brise.
     *
     * @param activateDto podaci sa ID-em potvrde, tokenom i novom lozinkom
     * @param aktiviraj   {@code true} za aktivaciju naloga, {@code false} za reset lozinke
     * @return poruka o uspesnom zavrsetku
     * @throws BusinessException ako je token nevazeci ili klijent neaktivan pri resetu
     */
    @Override
    public String editPassword(ActivateDto activateDto, boolean aktiviraj) {
        ClientConfirmationToken token = confirmationTokenRepository
                .findById(activateDto.getId())
                .orElse(null);
        if (token == null
                || !token.getValue().equals(tokenService.sha256Hex(activateDto.getConfirmationToken()))
                || token.getExpirationDateTime() != null
                && token.getExpirationDateTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "Pogresan token");
        }
        Klijent klijent = token.getKlijent();
        if (klijent.isDeleted()) {
            throw new BusinessException(ErrorCode.CLIENT_NOT_FOUND, "Ne moze se editovati obrisani klijent");
        }
        if (!aktiviraj && !klijent.isAktivan()) {
            throw new BusinessException(ErrorCode.USER_INACTIVE, "Klijent nije aktivan");
        }

        klijent.setPassword(passwordEncoder.encode(activateDto.getPassword()));
        if (aktiviraj) {
            klijent.setAktivan(true);
        }
        klijent.setConfirmationToken(null);
        return aktiviraj ? "Uspesno aktiviranje klijenta" : "Uspesna promena lozinke";
    }

    /**
     * Generise i salje token za reset lozinke na email klijenta.
     * Ako token vec postoji, osvezava njegovu vrednost i rok vazenja.
     *
     * @param dto zahtev sa email adresom klijenta
     * @return poruka o rezultatu operacije
     * @throws BusinessException ako klijent ne postoji ili nije aktivan
     */
    @Override
    public String forgotPassword(ForgotPasswordDto dto) {
        Klijent klijent = klijentRepository.findByEmail(dto.getEmail()).orElse(null);
        if (klijent == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "Ne postoji klijent sa ovim emailom");
        }
        if (!klijent.isAktivan()) {
            throw new BusinessException(ErrorCode.USER_INACTIVE, "Klijent nije aktivan");
        }

        String generated = tokenService.generateRandomToken();
        if (klijent.getConfirmationToken() != null) {
            klijent.getConfirmationToken().setValue(tokenService.sha256Hex(generated));
            klijent.getConfirmationToken().setExpirationDateTime(
                    LocalDateTime.now().plusMinutes(confirmationTokenExpiration));
        } else {
            ClientConfirmationToken confirmationToken = new ClientConfirmationToken(
                    tokenService.sha256Hex(generated),
                    LocalDateTime.now().plusMinutes(confirmationTokenExpiration),
                    klijent);
            klijent.setConfirmationToken(confirmationToken);
            confirmationTokenRepository.save(confirmationToken);
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                rabbitClient.sendEmailNotification(new EmailDto(
                        klijent.getIme(), klijent.getEmail(),
                        EmailType.CLIENT_PASSWORD_RESET, urlResetPassword + generated));
            }
        });
        return "Poslat mejl";
    }

    /**
     * Ponovo salje aktivacioni mejl za nalog koji jos nije aktiviran.
     *
     * @param email email adresa klijenta
     * @return poruka o rezultatu operacije
     * @throws BusinessException ako klijent ne postoji ili je obrisan
     */
    @Override
    public String resendActivation(String email) {
        Klijent klijent = klijentRepository.findByEmail(email).orElse(null);
        if (klijent == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "Ne postoji klijent sa ovim emailom");
        }
        if (klijent.isDeleted()) {
            throw new BusinessException(ErrorCode.CLIENT_NOT_FOUND, "Klijent je obrisan");
        }
        if (klijent.isAktivan()) {
            return "Nalog je vec aktivan";
        }

        String generated = tokenService.generateRandomToken();
        if (klijent.getConfirmationToken() != null) {
            klijent.getConfirmationToken().setValue(tokenService.sha256Hex(generated));
        } else {
            ClientConfirmationToken confirmationToken = new ClientConfirmationToken(
                    tokenService.sha256Hex(generated), klijent);
            klijent.setConfirmationToken(confirmationToken);
            confirmationTokenRepository.save(confirmationToken);
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                rabbitClient.sendEmailNotification(new EmailDto(
                        klijent.getIme(), klijent.getEmail(),
                        EmailType.CLIENT_CREATED, urlActivateAccount + generated));
            }
        });
        return "Poslat mejl";
    }
}
