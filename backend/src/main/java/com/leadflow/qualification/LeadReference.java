package com.leadflow.qualification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Reference lisible d'un lead, derivee de son identifiant.
 *
 * <p>Deterministe a dessein : c'est ce qui rend le rejeu d'une synchronisation ERP
 * inoffensif. Un tirage aleatoire produirait une reference differente a chaque tentative,
 * et l'ERP ne pourrait pas reconnaitre l'objet qu'il a deja cree.
 *
 * <p><b>Une empreinte, pas une tranche de l'identifiant.</b> Une premiere version prenait
 * les douze premiers caracteres de l'UUID. Or {@code BaseEntity} genere ses identifiants
 * avec {@code Style.TIME}, dont les 64 bits de poids fort portent l'adresse IP et un
 * identifiant de JVM : ils sont <b>constants pour toute la duree d'un demarrage</b>. Tous
 * les leads d'un meme run partageaient donc leur reference, Dolibarr retrouvait une
 * {@code ref} connue et rendait l'opportunite deja creee — cinq prospects rattaches a une
 * seule opportunite, defaut trouve en verifiant F4 contre un vrai ERP.
 *
 * <p>Prendre la queue corrigeait le symptome, mais les bits de poids faible ne sont pas
 * aleatoires non plus : ce sont l'horloge basse et un compteur remis a zero a chaque
 * demarrage. Seule une empreinte donne des bits uniformement repartis, sur lesquels le
 * raisonnement ci-dessous tient vraiment — et elle ne depend d'aucun detail de la strategie
 * de generation, qui a deja piege ce code une fois.
 *
 * <p>Douze caracteres hexadecimaux et non huit : huit font 32 bits, et par le paradoxe des
 * anniversaires une collision devient probable vers 65 000 leads, ce qui est atteignable
 * pour un middleware dont c'est le metier. Douze font 48 bits, soit une collision probable
 * vers 17 millions de leads.
 */
public final class LeadReference {

    private static final String PREFIXE = "LF-";
    private static final int LONGUEUR = 12;

    private LeadReference() {
    }

    /** @throws IllegalArgumentException si {@code leadId} est nul */
    public static String pour(UUID leadId) {
        if (leadId == null) {
            throw new IllegalArgumentException("Impossible de deriver une reference sans identifiant");
        }
        return PREFIXE + empreinte(leadId).substring(0, LONGUEUR).toUpperCase(Locale.ROOT);
    }

    private static String empreinte(UUID leadId) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(sha256.digest(leadId.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException absent) {
            // SHA-256 est exige de toute implementation de la plateforme.
            throw new IllegalStateException("SHA-256 indisponible", absent);
        }
    }
}
