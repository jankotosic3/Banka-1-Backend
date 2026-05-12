package com.banka1.security.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JPA AttributeConverter za transparentnu enkripciju JMBG polja (PR_07 C7.1).
 *
 * <p>Apply na entity polje:
 * <pre>
 *   &#64;Convert(converter = JmbgConverter.class)
 *   private String jmbg;
 * </pre>
 *
 * <p>Hibernate ce automatski enkriptovati pri INSERT/UPDATE i dekriptovati pri SELECT.
 * Klijentski kod radi sa plaintext-om, DB cuva ciphertext.
 */
@Component
@Converter(autoApply = false)
public class JmbgConverter implements AttributeConverter<String, String> {

    private static JmbgEncryptor encryptor;

    @Autowired
    public void setEncryptor(JmbgEncryptor enc) {
        JmbgConverter.encryptor = enc;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        return encryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        return encryptor.decrypt(dbData);
    }
}
