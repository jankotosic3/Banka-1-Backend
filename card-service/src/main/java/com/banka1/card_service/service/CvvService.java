package com.banka1.card_service.service;

import com.banka1.card_service.dto.GeneratedCvv;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

/**
 * Service responsible for CVV generation and verification.
 * Security rules enforced by this class are simple.
 * Generated CVV always contains exactly three digits.
 * Plain CVV is meant to be shown once and not persisted.
 * Stored CVV uses a one-way hash produced by {@link PasswordEncoder}.
 *
 * Example:
 * plain value {@code "123"} may be stored as an Argon2 hash such as {@code "$argon2id$v=19$..."}.
 */
@Service
public class CvvService {

    /**
     * Hashing strategy used for protecting CVV values at rest.
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Cryptographically strong random generator used to produce unpredictable CVV digits.
     */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Creates the service with the configured password encoder.
     *
     * @param passwordEncoder hashing component used to protect CVV values before persistence
     */
    public CvvService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Generates a new three-digit CVV and its hashed form.
     * The returned DTO contains both values because the application must store only the hash,
     * but may need to display the plain CVV one time right after card creation.
     *
     * @return plain and hashed CVV pair
     */
    public GeneratedCvv generateCvv() {
        String plainCvv = String.format("%03d", secureRandom.nextInt(1000));
        return new GeneratedCvv(plainCvv, hashCvv(plainCvv));
    }

    /**
     * Hashes a plain three-digit CVV value.
     * This method rejects malformed values such as {@code "12"}, {@code "1234"}, or {@code "12A"}.
     *
     * @param plainCvv plain CVV
     * @return hashed CVV
     */
    public String hashCvv(String plainCvv) {
        if (plainCvv == null || !plainCvv.matches("\\d{3}")) {
            throw new IllegalArgumentException("CVV must contain exactly 3 digits.");
        }
        return passwordEncoder.encode(plainCvv);
    }

    /**
     * Verifies a plain CVV against a stored hash.
     * Example:
     * if the stored hash was generated from {@code "123"}, then {@code matches("123", hash)}
     * returns {@code true}, while {@code matches("321", hash)} returns {@code false}.
     *
     * @param plainCvv plain CVV
     * @param hashedCvv stored CVV hash
     * @return {@code true} when the values match
     */
    public boolean matches(String plainCvv, String hashedCvv) {
        return passwordEncoder.matches(plainCvv, hashedCvv);
    }
}
