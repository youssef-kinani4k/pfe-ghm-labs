package com.leadflow.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.config.SecurityProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String MASTER_KEY = "gh/SD1Jp/HfWc/sB/BybtvbUqehzXmv0YIZ8uMwutME=";

    private final SecretCipher cipher = new SecretCipher(new SecurityProperties(MASTER_KEY));

    @Test
    void chiffreEtDechiffreLaMemeValeur() {
        String clair = "c6702b700b1673ae027ce903ff753c4239522e71b21297259c396540b9e5ec19";

        assertThat(cipher.decrypt(cipher.encrypt(clair))).isEqualTo(clair);
    }

    @Test
    void deuxChiffrementsDeLaMemeValeurDifferent() {
        String clair = "secret-partage";

        assertThat(cipher.encrypt(clair)).isNotEqualTo(cipher.encrypt(clair));
    }

    @Test
    void rejetteUnChiffreAltere() {
        byte[] chiffre = Base64.getDecoder().decode(cipher.encrypt("secret-partage"));
        chiffre[chiffre.length - 1] ^= 0x01;
        String altere = Base64.getEncoder().encodeToString(chiffre);

        assertThatThrownBy(() -> cipher.decrypt(altere)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refuseUneCleDeLongueurIncorrecte() {
        SecurityProperties tropCourte = new SecurityProperties(
                Base64.getEncoder().encodeToString(new byte[16]));

        assertThatThrownBy(() -> new SecretCipher(tropCourte))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
