package com.leadflow.crm.dolibarr;

import com.leadflow.crm.CrmConnector;
import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Traduction du modele pivot vers le vocabulaire de Dolibarr.
 *
 * <p>Dolibarr separe le Tiers et le Contact en deux endpoints, et n'a pas d'objet
 * « opportunite » de plein droit : ce qui s'en rapproche est le projet dote des champs
 * d'opportunite. Ces divergences se resolvent ici, jamais en amont.
 */
@Component
public class DolibarrConnector implements CrmConnector {

    /** Statut « prospect » d'un tiers Dolibarr. */
    private static final String TIERS_PROSPECT = "2";

    private final DolibarrClient client;

    public DolibarrConnector(DolibarrClient client) {
        this.client = client;
    }

    @Override
    public String providerId() {
        return DolibarrClient.PROVIDER_ID;
    }

    /**
     * <b>Limitation connue.</b> Si {@code lieResponsable} echoue apres la creation de
     * l'opportunite, l'etat partiel porte deja la reference de celle-ci : au rejeu, tout le
     * bloc est saute et l'opportunite reste sans chef de projet, sans que rien ne le signale.
     * {@link CrmSyncState} n'a pas de logement pour cette quatrieme etape — il modelise
     * l'idempotence comme trois references connues, ce qui suffit tant qu'une
     * synchronisation se decompose en trois creations. Le jour ou un ERP en apportera une
     * quatrieme, la bonne reponse sera une carte de references par etape plutot qu'un champ
     * de plus. Decision reportee a F3, quand le consommateur de file sera cable.
     */
    @Override
    public CrmSyncResult sync(CrmLead lead, CrmTarget target, CrmSyncState previous) {
        String compte = previous.accountRef();
        String contact = previous.contactRef();
        String opportunite = previous.opportunityRef();
        try {
            if (compte == null) {
                compte = client.creeTiers(target, corpsTiers(lead));
            }
            if (contact == null) {
                contact = client.creeContact(target, corpsContact(lead, compte));
            }
            if (opportunite == null) {
                opportunite = client.creeOpportunite(target, corpsOpportunite(lead, compte));
                if (lead.assigneeRef() != null) {
                    client.lieResponsable(target, opportunite, lead.assigneeRef());
                }
            }
        } catch (CrmSyncException echec) {
            throw echec.avecEtat(new CrmSyncState(compte, contact, opportunite));
        }
        return new CrmSyncResult(providerId(), compte, contact, opportunite, null, Instant.now());
    }

    @Override
    public String resolveAssignee(CrmAssignee assignee, CrmTarget target) {
        return client.chercheUtilisateurParEmail(target, assignee.email());
    }

    private Map<String, Object> corpsTiers(CrmLead lead) {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("name", nomDuTiers(lead));
        corps.put("client", TIERS_PROSPECT);
        corps.put("email", lead.email());
        if (lead.phone() != null) {
            corps.put("phone", lead.phone());
        }
        if (lead.countryCode() != null) {
            corps.put("country_code", lead.countryCode());
        }
        corps.put("note_private", note(lead));
        return corps;
    }

    /**
     * Dolibarr exige un tiers pour rattacher un contact. Un formulaire B2C n'en fournit pas :
     * on nomme alors le tiers d'apres la personne. Le pivot n'a pas a connaitre cette
     * contrainte.
     */
    private String nomDuTiers(CrmLead lead) {
        if (lead.companyName() != null && !lead.companyName().isBlank()) {
            return lead.companyName();
        }
        String nom = (valeur(lead.firstName()) + " " + valeur(lead.lastName())).trim();
        return nom.isBlank() ? lead.email() : nom;
    }

    private Map<String, Object> corpsContact(CrmLead lead, String compte) {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("socid", compte);
        corps.put("lastname", valeur(lead.lastName()));
        corps.put("firstname", valeur(lead.firstName()));
        corps.put("email", lead.email());
        if (lead.phone() != null) {
            corps.put("phone_pro", lead.phone());
        }
        return corps;
    }

    private Map<String, Object> corpsOpportunite(CrmLead lead, String compte) {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("ref", reference());
        corps.put("socid", compte);
        corps.put("title", lead.detectedIntent() == null ? "Lead LeadFlow" : lead.detectedIntent());
        corps.put("usage_opportunity", "1");
        corps.put("opp_status", "1");
        corps.put("note_private", note(lead));
        return corps;
    }

    /**
     * Dolibarr exige une {@code ref} sur un projet, et la refuse en doublon (contrainte
     * {@code uk_projet_ref}). Elle est tiree au hasard plutot que derivee du lead : le pivot
     * ne porte pas d'identifiant, et la non-recreation au rejeu est deja garantie en amont
     * par {@code CrmSyncState}. Une opportunite ne peut donc etre creee deux fois que si la
     * reponse de Dolibarr s'est perdue apres coup — cas ou une ref stable aurait, elle, fait
     * echouer le rejeu au lieu de le laisser passer.
     */
    private String reference() {
        return "LF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /** Le score n'a pas d'equivalent chez Dolibarr : il finit en texte, pas en champ. */
    private String note(CrmLead lead) {
        return "LeadFlow — intention : " + valeur(lead.detectedIntent())
                + " | score : " + lead.score()
                + (lead.sector() == null ? "" : " | secteur : " + lead.sector())
                + "\n" + valeur(lead.message());
    }

    private String valeur(String texte) {
        return texte == null ? "" : texte;
    }
}
