/**
 * Etape 4 - Notification du commercial.
 *
 * <p>Consomme {@code leadflow.leads.notify}, liee a la cle {@code lead.synced} que la
 * synchronisation ERP publie deja. <b>Apres la synchronisation et non apres le routage</b> :
 * prevenir plus tot alerterait le commercial d'un lead qui n'est pas encore chez lui dans
 * l'ERP — il cliquerait, ne trouverait rien, et cesserait de faire confiance a l'alerte. Le
 * prix assume est qu'un lead dont la synchronisation echoue ne declenche aucune alerte ; cet
 * echec a deja son canal, le journal des morts.
 *
 * <p>La file est liee a la meme cle que la file d'observation du monitoring. Un
 * {@code DirectExchange} livre a <b>toutes</b> les files liees a une cle, donc le monitoring
 * continue de recevoir ce qu'il recevait, et aucun code du pipeline n'a eu a changer.
 *
 * <p><b>Trois invariants a ne pas casser.</b>
 *
 * <p>Cette couche est un <b>port</b> : le service ne connait que
 * {@link com.leadflow.notification.CanalDeNotification} et le modele de
 * {@code notification.model}. Aucun terme propre a un canal — objet, expediteur, corps HTML
 * — ne doit remonter dans {@link com.leadflow.notification.model.NotificationLead} : c'est
 * ce qui rend un second canal possible sans reecriture, et c'est l'invariant le plus facile
 * a casser par inadvertance.
 *
 * <p>Aucun {@code switch} sur le canal. Les implementations sont des {@code @Component}
 * collectes par {@link com.leadflow.notification.CanalDeNotificationRegistry} : un canal de
 * plus s'ajoute en ecrivant une classe.
 *
 * <p>Trois causes ne partent jamais en DLQ, parce qu'aucune repetition ne les reparera : un
 * score sous le seuil de la boutique, un commercial sans adresse, un lead sans commercial.
 * Elles ecrivent une trace explicite puis acquittent. C'est la distinction que la
 * qualification fait deja avec {@code DISCARDED} — une erreur deterministe rangee sous les
 * echecs techniques serait rejouee sans fin.
 *
 * <p>Ajouter un canal = une classe {@code @Component} implementant le port, dans son propre
 * sous-package. Aucun autre package n'a a etre modifie.
 */
package com.leadflow.notification;
