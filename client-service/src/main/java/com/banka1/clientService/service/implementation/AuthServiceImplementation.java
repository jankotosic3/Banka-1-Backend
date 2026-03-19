package com.banka1.clientService.service.implementation;

import com.banka1.clientService.domain.Klijent;
import com.banka1.clientService.dto.requests.LoginRequestDto;
import com.banka1.clientService.dto.responses.LoginResponseDto;
import com.banka1.clientService.exception.BusinessException;
import com.banka1.clientService.exception.ErrorCode;
import com.banka1.clientService.repository.KlijentRepository;
import com.banka1.clientService.service.AuthService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeyLengthException;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * Implementacija {@link AuthService} koja vrsi autentifikaciju klijenata.
 * Verifikacija lozinke se vrsi Argon2 algoritmom, a JWT token se potpisuje HMAC-SHA256 algoritmom.
 */
@Service
@Transactional(readOnly = true)
public class AuthServiceImplementation implements AuthService {

    /** Repozitorijum za pretragu klijenata pri autentifikaciji. */
    private final KlijentRepository klijentRepository;

    /** Enkoder za verifikaciju Argon2 hash lozinke. */
    private final PasswordEncoder passwordEncoder;

    /** Signer koji potpisuje JWT tokene HMAC-SHA256 algoritmom. */
    private final MACSigner signer;

    /** Naziv claim-a u JWT-u koji nosi ulogu klijenta. */
    @Value("${banka.security.roles-claim}")
    private String rolesClaim;

    /** Naziv claim-a u JWT-u koji nosi identifikator klijenta. */
    @Value("${banka.security.id}")
    private String idClaim;

    /** Issuer vrednost koja se upisuje u JWT token. */
    @Value("${banka.security.issuer}")
    private String issuer;

    /** Vreme trajanja JWT tokena u milisekundama. */
    @Value("${banka.security.expiration-time}")
    private Long expirationTime;

    /**
     * Inicijalizuje servis ucitavanjem HMAC tajne za potpisivanje tokena.
     *
     * @param klijentRepository repozitorijum klijenata
     * @param passwordEncoder   enkoder za verifikaciju lozinke
     * @param secret            HMAC tajna za potpisivanje JWT tokena
     * @throws KeyLengthException ako je tajna neodgovarajuce duzine za HS256
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
     * U slucaju neuspesne autentifikacije uvek se vraca ista greska
     * kako bi se sprecilo otkrivanje da li email postoji u sistemu.
     *
     * @param dto podaci za prijavljivanje
     * @return odgovor sa JWT pristupnim tokenom
     * @throws BusinessException ako klijent nije pronadjen ili je lozinka neispravna
     */
    @Override
    public LoginResponseDto login(LoginRequestDto dto) {
        Klijent klijent = klijentRepository.findByEmail(dto.getEmail())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS, ""));

        if (klijent.getPassword() == null
                || !passwordEncoder.matches(dto.getPassword(), klijent.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "");
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
}
