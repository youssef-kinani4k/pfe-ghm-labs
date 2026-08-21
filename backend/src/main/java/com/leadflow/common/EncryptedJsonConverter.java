package com.leadflow.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Serialise une carte de reglages en JSON puis la chiffre.
 *
 * <p>Utilise pour {@code client.crm_config}, dont les cles dependent du fournisseur ERP :
 * les figer en colonnes casserait la promesse d'ajouter un ERP sans migration.
 */
@Converter
@Component
public class EncryptedJsonConverter implements AttributeConverter<Map<String, String>, String> {

    private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {};

    private final SecretCipher cipher;
    private final ObjectMapper objectMapper;

    public EncryptedJsonConverter(SecretCipher cipher, ObjectMapper objectMapper) {
        this.cipher = cipher;
        this.objectMapper = objectMapper;
    }

    @Override
    public String convertToDatabaseColumn(Map<String, String> attribute) {
        Map<String, String> valeur = attribute == null ? Map.of() : attribute;
        try {
            return cipher.encrypt(objectMapper.writeValueAsString(valeur));
        } catch (JacksonException e) {
            throw new IllegalStateException("Echec de serialisation de la configuration CRM", e);
        }
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(cipher.decrypt(dbData), TYPE);
        } catch (JacksonException e) {
            throw new IllegalStateException("Echec de lecture de la configuration CRM", e);
        }
    }
}
