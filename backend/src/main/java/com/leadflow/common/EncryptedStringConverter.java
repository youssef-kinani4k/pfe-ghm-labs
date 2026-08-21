package com.leadflow.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Chiffre une colonne texte de facon transparente pour l'entite.
 *
 * <p>Le bean est un {@code @Component} : Spring Boot installe {@code SpringBeanContainer}
 * dans Hibernate, ce qui permet a ce converter de recevoir {@link SecretCipher} par
 * injection plutot que d'etre instancie par un constructeur sans argument. Ce cablage est
 * implicite, d'ou le test d'integration dedie en tache 3.
 */
@Converter
@Component
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final SecretCipher cipher;

    public EncryptedStringConverter(SecretCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : cipher.decrypt(dbData);
    }
}
