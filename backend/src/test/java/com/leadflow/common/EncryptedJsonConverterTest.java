package com.leadflow.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.config.SecurityProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EncryptedJsonConverterTest {

    private static final String MASTER_KEY = "gh/SD1Jp/HfWc/sB/BybtvbUqehzXmv0YIZ8uMwutME=";

    private final SecretCipher cipher = new SecretCipher(new SecurityProperties(MASTER_KEY));
    private final EncryptedJsonConverter converter =
            new EncryptedJsonConverter(cipher, new ObjectMapper());

    @Test
    void relitLaMemeCarte() {
        Map<String, String> config = Map.of(
                "baseUrl", "http://localhost:8081/api/index.php",
                "apiKey", "cle-dolibarr");

        String colonne = converter.convertToDatabaseColumn(config);

        assertThat(converter.convertToEntityAttribute(colonne)).isEqualTo(config);
    }

    @Test
    void laColonneNeContientNiCleNiValeurEnClair() {
        Map<String, String> config = Map.of("apiKey", "cle-dolibarr");

        String colonne = converter.convertToDatabaseColumn(config);

        assertThat(colonne).doesNotContain("apiKey").doesNotContain("cle-dolibarr");
    }

    @Test
    void traiteNullCommeUneCarteVide() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
    }
}
