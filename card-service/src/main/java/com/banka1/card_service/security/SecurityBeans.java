package com.banka1.card_service.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Security configuration local to the card service.
 * This class exposes the beans that the module relies on directly.
 * It provides a JWT decoder for validating incoming access tokens and a password encoder
 * for hashing sensitive values such as CVV codes.
 * The password-encoder bean is declared locally with {@link ConditionalOnMissingBean}
 * so the service works even when an IDE does not resolve beans from shared library auto-configuration.
 */
@Configuration
@EnableMethodSecurity
public class SecurityBeans {

    /**
     * Creates a JWT decoder backed by the shared HMAC secret.
     * Example:
     * a token signed with the configured {@code jwt.secret} can be validated successfully,
     * while a token signed with a different secret will fail validation.
     *
     * @param secret JWT signing secret loaded from configuration
     * @return configured JWT decoder used by Spring Security resource-server support
     */
    @Bean
    public JwtDecoder jwtDecoder(@Value("${jwt.secret}") String secret) {
        SecretKey key = new SecretKeySpec(secret.getBytes(), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }

    /**
     * Creates the password encoder used for hashing security-sensitive values.
     * Argon2 is used because it is a modern adaptive hashing algorithm with salt support,
     * which means equal CVV values do not produce identical stored hashes.
     *
     * @return password encoder used by the card service
     */
    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
