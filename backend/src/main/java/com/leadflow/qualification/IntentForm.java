package com.leadflow.qualification;

/**
 * Ce que l'ecran envoie pour modifier le reglage.
 *
 * @param apiKey nouvelle cle. Absente ou vide, celle deja enregistree reste en place :
 *     l'operateur ne voit jamais la cle en clair, donc l'obliger a la ressaisir pour
 *     actionner l'interrupteur reviendrait a lui demander l'impossible
 * @param actif interrupteur de l'analyse. Absent, l'etat courant est conserve
 */
public record IntentForm(String apiKey, Boolean actif) {
}
