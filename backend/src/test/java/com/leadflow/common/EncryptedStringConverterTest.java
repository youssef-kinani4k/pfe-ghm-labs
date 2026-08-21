package com.leadflow.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.config.SecurityProperties;
import org.junit.jupiter.api.Test;

class EncryptedStringConverterTest {

    private static final String MASTER_KEY = "gh/SD1Jp/HfWc/sB/BybtvbUqehzXmv0YIZ8uMwutME=";

    private final SecretCipher cipher = new SecretCipher(new SecurityProperties(MASTER_KEY));
    private final EncryptedStringConverter converter = new EncryptedStringConverter(cipher);

    @Test
    void laColonneNeContientPasLaValeurEnClair() {
        String colonne = converter.convertToDatabaseColumn("secret-partage");

        assertThat(colonne).isNotNull().doesNotContain("secret-partage");
    }

    @Test
    void relitLaValeurDOrigine() {
        String colonne = converter.convertToDatabaseColumn("secret-partage");

        assertThat(converter.convertToEntityAttribute(colonne)).isEqualTo("secret-partage");
    }

    @Test
    void laisseNullInchange() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
