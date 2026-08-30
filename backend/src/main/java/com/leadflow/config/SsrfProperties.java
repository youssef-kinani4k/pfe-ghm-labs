package com.leadflow.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Destinations que le serveur s'autorise a appeler pour joindre un ERP.
 *
 * <p>Sans cette politique, {@code POST /api/admin/crm/test} appelle l'URL qu'on lui donne :
 * un operateur — ou quiconque obtiendrait son jeton — peut faire sonder par le serveur les
 * adresses de son propre reseau, metadonnees d'instance cloud comprises.
 *
 * <p>La liste d'exceptions n'est pas un compromis de facade : le Dolibarr et l'Odoo de la
 * pile vivent dans le reseau Docker, donc sur des adresses privees. Un refus strict rendrait
 * la recette impossible. Le profil prod y met les noms de service, le profil dev
 * {@code localhost} ; une instance reellement exposee vide la liste.
 *
 * @param actif presence de la politique
 * @param hotesAutorises noms d'hotes qui passent meme en adresse privee, correspondance
 *     exacte — ni prefixe, ni suffixe, ni joker
 */
@ConfigurationProperties(prefix = "leadflow.security.crm")
public record SsrfProperties(boolean actif, List<String> hotesAutorises) {
}
