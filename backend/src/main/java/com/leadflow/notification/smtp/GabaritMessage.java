package com.leadflow.notification.smtp;

import com.leadflow.notification.model.NotificationLead;

/**
 * Compose l'objet et le corps du message.
 *
 * <p><b>Le seul endroit de la feature ou des phrases sont ecrites.</b> Le service rend des
 * faits, le pivot porte des faits ; la mise en francais appartient au canal, exactement
 * comme la chronologie laisse ses libelles au template Angular. Un second canal ecrira son
 * propre gabarit sans toucher a celui-ci.
 *
 * <p>Texte brut et non HTML : un client de messagerie le rend correctement partout, et rien
 * n'a a etre echappe.
 *
 * <p><b>Il ne cite aucun lien vers le dashboard</b>, et porte a la place de quoi agir : le
 * telephone du prospect et son message. Le commercial n'a pas de compte sur la console — un
 * lien l'enverrait sur un ecran de connexion qu'il ne peut pas franchir.
 *
 * <p>Tous les champs du prospect sauf son adresse peuvent etre nuls — la qualification ne
 * fait echouer un lead que sur son email. Chaque ligne est donc conditionnelle, et jamais
 * une concatenation qui enverrait « null » a un commercial.
 */
final class GabaritMessage {

    private GabaritMessage() {
    }

    /** Ce que le commercial lit sans ouvrir : qui, et a quel point c'est chaud. */
    static String objet(NotificationLead lead) {
        String qui = lead.nomProspect() != null ? lead.nomProspect() : lead.emailProspect();
        return "Nouveau lead a traiter : " + qui + " (score " + lead.score() + ")";
    }

    static String corps(NotificationLead lead) {
        StringBuilder corps = new StringBuilder();
        corps.append("Bonjour");
        if (lead.nomCommercial() != null && !lead.nomCommercial().isBlank()) {
            corps.append(' ').append(lead.nomCommercial());
        }
        corps.append(",\n\n");
        corps.append("Un lead vient de vous etre attribue.\n\n");

        ligne(corps, "Prospect", lead.nomProspect());
        ligne(corps, "E-mail", lead.emailProspect());
        ligne(corps, "Telephone", lead.telephoneProspect());
        ligne(corps, "Societe", lead.societeProspect());
        ligne(corps, "Intention", lead.intention());
        ligne(corps, "Score", String.valueOf(lead.score()));

        // Ce que le prospect a ecrit, en entier et cite tel quel. C'est la seule chose du
        // message qui ne soit pas un fait derive, et souvent la seule qui dise quoi lui
        // repondre.
        if (lead.messageProspect() != null && !lead.messageProspect().isBlank()) {
            corps.append("\nCe que le prospect a ecrit :\n");
            corps.append(lead.messageProspect().trim()).append('\n');
        }

        corps.append("\n-- \nLeadFlow\n");
        return corps.toString();
    }

    /** N'ecrit rien plutot que d'ecrire une ligne vide ou un « null ». */
    private static void ligne(StringBuilder corps, String etiquette, String valeur) {
        if (valeur != null && !valeur.isBlank()) {
            corps.append(etiquette).append(" : ").append(valeur).append('\n');
        }
    }
}
