package com.leadflow.common;

import com.leadflow.config.SecurityProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Chiffrement des secrets stockes en base, en AES-256-GCM.
 *
 * <p>Un vecteur d'initialisation aleatoire est tire a chaque ecriture et prefixe au
 * chiffre. Deux chiffrements d'une meme valeur different donc, ce qui interdit toute
 * recherche par valeur chiffree — comportement voulu, aucun cas d'usage ne le demande.
 */
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(SecurityProperties properties) {
        if (properties.masterKey() == null || properties.masterKey().isBlank()) {
            throw new IllegalStateException(
                    "leadflow.security.master-key est absente. Definir LEADFLOW_MASTER_KEY.");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(properties.masterKey());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("leadflow.security.master-key n'est pas du base64 valide", e);
        }
        if (raw.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "leadflow.security.master-key doit faire 32 octets, recu " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] packed = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array();
            return Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Echec du chiffrement d'un secret", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] packed = Base64.getDecoder().decode(encoded);
            if (packed.length <= IV_LENGTH_BYTES) {
                throw new IllegalStateException("Valeur chiffree tronquee");
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[packed.length - IV_LENGTH_BYTES];
            ByteBuffer.wrap(packed).get(iv).get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Echec du dechiffrement d'un secret", e);
        }
    }
}
