/**
 * Journal des messages morts : ecriture depuis la DLQ, lecture par l'ecran, rejeu unitaire.
 *
 * <p>Ce package contient la <b>seule table que le monitoring ecrit</b> et le <b>seul verbe
 * d'ecriture metier de F6</b> — un rejeu, qui remet dans la file un message qui en venait,
 * a l'identique et jamais fabrique.
 */
package com.leadflow.monitoring.deadletter;
