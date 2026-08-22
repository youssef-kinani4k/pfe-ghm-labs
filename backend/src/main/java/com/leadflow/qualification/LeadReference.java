package com.leadflow.qualification;

import java.util.Locale;
import java.util.UUID;

/**
 * Reference lisible d'un lead, derivee de son identifiant.
 *
 * <p>Deterministe a dessein : c'est ce qui rend le rejeu d'une synchronisation ERP
 * inoffensif. Un tirage aleatoire produirait une reference differente a chaque tentative,
 * et l'ERP ne pourrait pas reconnaitre l'objet qu'il a deja cree.
 *
 * <p>Douze caracteres hexadecimaux et non huit : huit font 32 bits, et par le paradoxe des
 * anniversaires une collision devient probable vers 65 000 leads, ce qui est atteignable
 * pour un middleware dont c'est le metier. Douze font 48 bits.
 *
 * <p><b>Les douze derniers caracteres, jamais les premiers.</b> Les identifiants sont
 * ordonnes dans le temps : leur tete est un horodatage, identique pour tous les leads d'une
 * meme periode. Une reference tiree de la tete valait donc la meme chose pour dix leads
 * consecutifs, et Dolibarr, retrouvant une {@code ref} connue, rendait l'opportunite deja
 * creee au lieu d'en creer une — quatre prospects sur cinq se retrouvaient rattaches a
 * l'opportunite du premier. Le raisonnement sur les 48 bits ci-dessus ne tient que si ces
 * bits sont aleatoires, ce qui n'est vrai que de la queue de l'identifiant.
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
        String hexadecimal = leadId.toString().replace("-", "");
        return PREFIXE + hexadecimal.substring(hexadecimal.length() - LONGUEUR)
                .toUpperCase(Locale.ROOT);
    }
}
