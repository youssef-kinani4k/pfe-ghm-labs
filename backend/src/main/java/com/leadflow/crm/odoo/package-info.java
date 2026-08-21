/**
 * Adaptateur Odoo (JSON-RPC sur {@code /jsonrpc}, ou API REST selon le module installe).
 *
 * <p>Odoo impose une authentification en deux temps : {@code common.authenticate} renvoie
 * un {@code uid} qui accompagne ensuite chaque appel {@code object.execute_kw}. La
 * correspondance differe de Dolibarr : {@code CrmAccount} et {@code CrmContact} tombent
 * tous deux dans {@code res.partner} (distingues par {@code is_company}), et
 * {@code CrmOpportunity} dans {@code crm.lead}. Ces divergences restent confinees ici.
 */
package com.leadflow.crm.odoo;
