package com.banka1.security.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JmbgEncryptorTest {

    private final String testKey = Base64.getEncoder().encodeToString(new byte[32]);  // 32 zero bytes

    @Test
    void encrypt_then_decrypt_vraca_original() {
        JmbgEncryptor enc = new JmbgEncryptor(testKey);
        String plain = "1234567890123";
        String ciphertext = enc.encrypt(plain);
        String back = enc.decrypt(ciphertext);
        assertThat(back).isEqualTo(plain);
    }

    @Test
    void encrypt_dva_puta_isti_plaintext_proizvodi_razlicit_ciphertext_zbog_random_IV() {
        JmbgEncryptor enc = new JmbgEncryptor(testKey);
        String c1 = enc.encrypt("1234567890123");
        String c2 = enc.encrypt("1234567890123");
        assertThat(c1).isNotEqualTo(c2);
    }

    @Test
    void encrypt_null_vraca_null() {
        JmbgEncryptor enc = new JmbgEncryptor(testKey);
        assertThat(enc.encrypt(null)).isNull();
        assertThat(enc.decrypt(null)).isNull();
    }

    @Test
    void konstruktor_throws_kada_kljuc_nije_32_bajta() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new JmbgEncryptor(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bajta");
    }

    @Test
    void decrypt_corrupted_ciphertext_throws() {
        JmbgEncryptor enc = new JmbgEncryptor(testKey);
        String ciphertext = enc.encrypt("12345");
        // Tamper: izmeni jedan bajt u Base64 ciphertext-u
        char[] chars = ciphertext.toCharArray();
        chars[chars.length - 5] = chars[chars.length - 5] == 'A' ? 'B' : 'A';
        String corrupted = new String(chars);

        assertThatThrownBy(() -> enc.decrypt(corrupted))
                .isInstanceOf(IllegalStateException.class);
    }
}
