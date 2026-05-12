package com.banka1.security.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

/**
 * AES-GCM enkripcija za JMBG i druge GDPR-osetljive identifikatore (PR_07 C7.1).
 *
 * <p>Spec (Celina 1) trazi enkripciju JMBG-a u DB-u; pre PR_07 polje je bilo
 * plaintext u {@code clients.jmbg} koloni. AES-GCM-256 sa 12-byte IV-om i
 * 16-byte authentication tag-om garantuje da napadac koji se domogne database
 * dump-a ne moze procitati JMBG-ove bez glavnog kljuca.
 *
 * <p>Kljuc se provoze kroz env var {@code BANKA_JMBG_AES_KEY} (32 bajta = 256 bita,
 * Base64 kodiran). Production deploy mora postaviti ovaj env var sa kljucem
 * generisanim kroz: {@code openssl rand -base64 32}.
 *
 * <p>PR_29: Default vrednost je dozvoljena samo u {@code dev}/{@code local} profilu
 * (i u testovima gde nije aktivan ni jedan profil). U produkciji (bilo koji profil
 * koji nije {@code dev}/{@code local}/{@code test}) bean odbija da se inicijalizuje ako
 * env var nije eksplicitno postavljen, sto sprecava da se JMBG-ovi enkriptuju javno
 * poznatim dev kljucem.
 *
 * <p>Format izlaza: {@code Base64(IV || ciphertext || authTag)}, dovoljno za
 * cuvanje u TEXT koloni kao single string.
 */
@Component
public class JmbgEncryptor {

    private static final int IV_LENGTH = 12;     // GCM standard
    private static final int TAG_LENGTH = 128;   // bits

    /** Public dev kljuc — ne sme se koristiti u produkciji. Cuva se ovde samo da bi @PostConstruct
     * mogao da ga prepozna i odbije ako sluzajno procuri u prod env. */
    static final String DEV_DEFAULT_KEY_BASE64 = "VGhpc0lzQURldk9ubHkzMkJ5dGVBRVNLZXktMTIzNDU=";

    /** Profili u kojima je dozvoljeno pasti na dev default. */
    private static final Set<String> NON_PROD_PROFILES = Set.of("dev", "local", "test");

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public JmbgEncryptor(
            @Value("${banka.security.jmbg-aes-key:" + DEV_DEFAULT_KEY_BASE64 + "}") String base64Key,
            Environment springEnvironment) {
        boolean isDevProfile = isNonProdProfile(springEnvironment);
        boolean usingDevKey = DEV_DEFAULT_KEY_BASE64.equals(base64Key);

        if (usingDevKey && !isDevProfile) {
            throw new IllegalStateException(
                    "JMBG AES kljuc nije postavljen, a aktivni profili nisu dev/local/test. "
                            + "Postavi env var BANKA_SECURITY_JMBG_AES_KEY (32 bajta Base64-encoded). "
                            + "Generisi kljuc sa: openssl rand -base64 32");
        }

        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "BANKA_JMBG_AES_KEY mora biti tacno 256 bita (32 bajta posle Base64 decode-a).");
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    private static boolean isNonProdProfile(Environment env) {
        if (env == null) return true; // u testu bez @SpringBootTest dozvoli dev default
        String[] active = env.getActiveProfiles();
        if (active == null || active.length == 0) {
            // Bez aktivnog profila Spring koristi default profile — tretiramo kao non-prod
            // jer dev environment cesto ne postavlja SPRING_PROFILES_ACTIVE.
            return true;
        }
        return Arrays.stream(active).anyMatch(NON_PROD_PROFILES::contains);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_LENGTH + ct.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(ct, 0, combined, IV_LENGTH, ct.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String encryptedBase64) {
        if (encryptedBase64 == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedBase64);
            if (combined.length < IV_LENGTH + TAG_LENGTH / 8) {
                throw new IllegalArgumentException("Encrypted payload too short.");
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] ct = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
