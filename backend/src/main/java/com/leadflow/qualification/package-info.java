/**
 * Etape 2 - Qualification, nettoyage et scoring.
 *
 * <p>Consomme {@code leadflow.leads.captured}, relit le payload brut en base et en tire un
 * lead qualifie : identite normalisee, intention detectee, score borne. Publie ensuite une
 * reference sur {@code leadflow.leads.qualified} a destination du routage (F4).
 *
 * <p><b>Trois invariants a ne pas casser.</b>
 *
 * <p>Seul l'email peut faire echouer la qualification. Un telephone, un pays ou un nom
 * illisible met le champ a {@code null} ; un evenement sans email exploitable n'ecrit aucun
 * lead et marque {@code raw_lead_event} en {@code DISCARDED} — statut terminal, distinct du
 * {@code FAILED} que le filet de republication rebalaye. Une erreur deterministe ne part
 * jamais en DLQ : elle y echouerait a l'identique aux trois tentatives et au rejeu.
 *
 * <p>L'analyseur d'intention ne leve jamais d'exception. Gemini est le chemin nominal, mais
 * toute defaillance retombe sur {@link com.leadflow.qualification.RuleBasedIntentAnalyzer},
 * et {@code intent_source} garde la trace de qui a repondu.
 *
 * <p>Ce paquet porte aussi le seul reglage global de l'instance :
 * {@link com.leadflow.qualification.IntentSettings}, la cle d'API et l'interrupteur de
 * l'analyse, servis par {@link com.leadflow.qualification.IntentAdminController}. Il vit ici
 * et non dans {@code monitoring/} — qui n'ecrit que {@code dead_letter} — ni dans
 * {@code tenant/}, qui ne porte que ce qui distingue une boutique d'une autre. La cle est
 * relue a chaque analyse, si bien qu'un changement prend effet au lead suivant.
 *
 * <p>L'idempotence est tranchee par la contrainte unique {@code lead.raw_event_id}, jamais
 * par une lecture prealable. La livraison etant at-least-once, deux messages peuvent porter
 * le meme {@code eventId} en parallele.
 */
package com.leadflow.qualification;
