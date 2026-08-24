package com.leadflow.tenant;

import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Fabrique les deux valeurs d'integration d'une boutique.
 *
 * <p>La cle publique est <b>opaque</b> : elle voyage dans l'URL du webhook, et y inscrire le
 * nom de la boutique revelerait la liste des clients a quiconque verrait passer un lien.
 *
 * <p>Le secret fait 32 octets, rendus en hexadecimal — le format de 64 caracteres deja en
 * usage dans le jeu de demonstration.
 */
@Component
public class CleGenerator {

    private static final SecureRandom ALEA = new SecureRandom();

    public String clePublique() {
        byte[] octets = new byte[12];
        ALEA.nextBytes(octets);
        return HexFormat.of().formatHex(octets);
    }

    public String secretHmac() {
        byte[] octets = new byte[32];
        ALEA.nextBytes(octets);
        return HexFormat.of().formatHex(octets);
    }
}
