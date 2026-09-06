package com.leadflow.routing;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrat de file publie sur {@code lead.reassigned} : une reattribution manuelle vient
 * d'avoir lieu, et l'ERP ne le sait pas encore.
 *
 * <p>Une reference, pas un contenu, comme {@link RoutedLeadMessage} : le consommateur relit
 * la base, donc un rejeu depuis la DLQ travaille sur la donnee a jour.
 *
 * <p>{@code previousSalesRepId} voyage sans etre utilise pour decider quoi que ce soit :
 * il rend le message lisible dans le journal des morts, ou l'operateur voit ce que la
 * propagation devait corriger.
 *
 * <p><b>Le rejeu est moins risque qu'une republication de {@code lead.qualified}</b> : poser
 * un responsable est une ecriture, pas une rotation, ce qui distingue cette cle de
 * {@code lead.qualified}. Ce n'etait cependant pas une idempotence garantie pour tous les
 * ERP : {@code DolibarrConnector} documente qu'aucune sonde ne permet de savoir si un
 * responsable est deja lie, si bien qu'un rejeu peut y tenter un doublon. Depuis F15, ce
 * doublon precis est reconnu et avale par {@code DolibarrClient.lieResponsable} — Dolibarr
 * le rend par un {@code 500} au corps distinctif, traite desormais comme le succes qu'il
 * decrit — donc un rejeu n'y echoue plus que sur un incident reel. Vrai de longue date pour
 * Odoo, ou l'ecriture de {@code user_id} est un simple remplacement.
 */
public record LeadReassignedMessage(
        UUID leadId,
        UUID clientId,
        UUID previousSalesRepId,
        UUID newSalesRepId,
        Instant reassignedAt) {
}
