package com.leadflow.capture;

/**
 * Ce qu'une signature valide apprend a l'appelant.
 *
 * @param canonique la forme canonique {@code t=<epoch>,v1=<hex>}, cle d'idempotence de la
 *     soumission. Reconstruite a partir des valeurs validees, jamais reprise du texte recu.
 * @param secretPrecedent vrai quand c'est le secret precedent de la boutique qui a repondu,
 *     et non le courant. Le verificateur ne sait pas ce que cela veut dire : il rend le rang
 *     du secret qui a correspondu, l'appelant en tire la notion de transition.
 */
public record SignatureVerifiee(String canonique, boolean secretPrecedent) {
}
