/**
 * Etape 3b - Synchronisation vers l'ERP/CRM cible.
 *
 * <p>Cette couche est un <b>port</b> : le pipeline ne connait que {@link
 * com.leadflow.crm.CrmConnector} et le modele de {@code crm.model}. Chaque ERP supporte
 * fournit un adaptateur dans son propre sous-package ({@code dolibarr}, {@code odoo}, ...)
 * et reste seul a connaitre son protocole, son vocabulaire et son format de donnees.
 *
 * <p>Ajouter un ERP = un sous-package + une implementation de {@code CrmConnector}
 * annotee {@code @Component}. Aucun autre package n'a a etre modifie : le registre les
 * decouvre par injection.
 */
package com.leadflow.crm;
