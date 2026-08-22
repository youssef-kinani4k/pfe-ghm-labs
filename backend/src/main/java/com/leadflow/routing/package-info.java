/**
 * Etape 3a - Routage et attribution.
 *
 * <p>Consomme {@code leadflow.leads.qualified}, choisit le commercial destinataire selon la
 * strategie declaree par le client, ecrit {@code assigned_sales_rep_id} et le statut
 * {@code ROUTED}, puis publie une reference sur {@code leadflow.leads.routed} a destination
 * de la synchronisation ERP.
 *
 * <p><b>Trois invariants a ne pas casser.</b>
 *
 * <p>Aucun {@code switch} sur la strategie. Les implementations de
 * {@link com.leadflow.routing.AssignmentStrategy} sont des {@code @Component} collectes par
 * {@link com.leadflow.routing.AssignmentStrategyRegistry} : une quatrieme strategie s'ajoute
 * en ecrivant une classe.
 *
 * <p>Les strategies sont des fonctions pures. La liste des eligibles leur arrive filtree sur
 * les commerciaux actifs et ordonnee du moins recemment servi au plus recemment servi ;
 * {@link com.leadflow.routing.RotationOrder} est la seule classe du package a lire la base.
 * C'est ce qui rend le departage equitable dans les trois strategies sans le reecrire.
 *
 * <p>Un lead deja attribue n'est jamais reattribue, mais son message est republie. Le tour
 * de role n'est pas idempotent — le rejouer decalerait la rotation — alors que republier est
 * sans danger, {@code CrmSyncService} rejouant via {@code CrmSyncState}.
 */
package com.leadflow.routing;
