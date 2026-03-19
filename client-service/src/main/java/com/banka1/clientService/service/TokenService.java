package com.banka1.clientService.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Servis koji pruza kriptografske pomocne metode:
 * generisanje nasumicnih URL-safe tokena i SHA-256 hesiranje.
 */
@Service
public class TokenService {

    private final SecureRandom random = new SecureRandom();
    private final byte[] bytes = new byte[32];

    /**
     * Generise nasumican URL-safe Base64 token bez padding-a.
     * Koristi 32 nasumicna bajta (256 bita entropije) sto rezultuje tokenom duzine 43 znaka.
     *
     * @return URL-safe nasumicni token pogodan za slanje u linkovima
     */
    public String generateRandomToken() {
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Hesira prosledjenu vrednost koristeci SHA-256 algoritam.
     *
     * @param value vrednost koja se hesira
     * @return SHA-256 hash u hex formatu
     */
    public String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 nije dostupan", e);
        }
    }

    private String toHex(byte[] hashBytes) {
        StringBuilder sb = new StringBuilder(hashBytes.length * 2);
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
