package com.banka1.clientService.service.implementation;

import com.banka1.clientService.domain.ClientConfirmationToken;
import com.banka1.clientService.domain.Klijent;
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
import java.util.Date;

/**
 * Implementacija {@link AuthService} koja upravlja celokupnim zivotnim ciklusom autentifikacije
 * klijenata: prijava, aktivacija naloga i reset lozinke.
 * Email notifikacije se salju asinhorno putem RabbitMQ-a tek nakon uspesnog commit-a transakcije.
 */
@Service
@Transactional
public class AuthServiceImplementation implements AuthService {

    private final KlijentRepository klijentRepository;
    private final PasswordEncoder passwordEncoder;
    private final MACSigner signer;

    @Autowired
    private ClientConfirmationTokenRepository confirmationTokenRepository;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private RabbitClient rabbitClient;

    @Value("${banka.security.roles-claim}")
    private String rolesClaim;

    @Value("${banka.security.id}")
    private String idClaim;

    @Value("${banka.security.issuer}")
    private String issuer;

    @Value("${banka.security.expiration-time}")
    private Long expirationTime;

    @Value("${url.activate-account}")
    private String urlActivateAccount;

    @Value("${url.reset-password}")
    private String urlResetPassword;

    @Value("${token.confirmation.expiration-time}")
    private Long confirmationTokenExpiration;

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

        if (klijent.getPassword() == null
                || !passwordEncoder.matches(dto.getPassword(), klijent.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "");
        }

        if (!klijent.isAktivan()) {
            throw new BusinessException(ErrorCode.USER_INACTIVE, "");
        }

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(klijent.getEmail())
                .issuer(issuer)
                .claim(idClaim, klijent.getId())
                .claim(rolesClaim, klijent.getRole().name())
                .expirationTime(new Date(System.currentTimeMillis() + expirationTime))
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (Exception e) {
            throw new IllegalStateException("Greska pri generisanju JWT tokena");
        }

        return new LoginResponseDto(jwt.serialize());
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
