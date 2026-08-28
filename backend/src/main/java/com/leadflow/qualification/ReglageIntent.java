package com.leadflow.qualification;

/**
 * Ce que l'analyseur a besoin de savoir du reglage, et rien de plus.
 *
 * <p>Port et non service concret : {@link GeminiIntentAnalyzer} n'a aucune raison de
 * connaitre la base, et ses tests contractuels n'ont aucune raison de la monter. C'est le
 * meme partage qu'entre un adaptateur ERP et {@code CrmSyncService} — l'adaptateur recoit
 * ce dont il a besoin, il ne va pas le chercher.
 *
 * <p>Les deux methodes sont interrogees <b>a chaque analyse</b> : c'est ce qui fait qu'un
 * changement dans la console prend effet au lead suivant.
 */
public interface ReglageIntent {

    /** @return la cle d'API a utiliser, ou {@code null} s'il n'y en a aucune */
    String cleEffective();

    /** @return {@code false} pour rester en mode lexical sans effacer la cle */
    boolean actif();
}
