/**
 * Flux temps reel du dashboard : une file d'observation, un diffuseur SSE, un controleur.
 *
 * <p>Le monitoring declare <b>sa propre file</b>, liee a l'exchange du pipeline sur les
 * memes cles de routage : un {@code DirectExchange} livre a toutes les files liees a une
 * cle, donc l'observateur ne prend rien aux consommateurs metier. Cette file n'a pas de DLX,
 * un echec d'affichage n'etant pas un echec de lead.
 */
package com.leadflow.monitoring.stream;
