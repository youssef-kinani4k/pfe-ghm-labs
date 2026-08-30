package com.leadflow.crm.dolibarr;

import com.leadflow.crm.CrmConnector;
import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmCheck;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSettingSpec;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * {@link CrmSyncState} n'a pas de logement pour cette quatrieme etape. Le jour ou un ERP
     * en apportera une cinquieme, la bonne reponse sera une carte de references par etape
     * plutot qu'un champ de plus.
     *
     * <p>La limitation sur la {@code ref} d'opportunite, elle, est levee depuis F3 : elle est
     * derivee du lead et donc stable, et la recherche prealable rend le rejeu inoffensif.
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
                // On demande d'abord a l'ERP s'il connait deja cette ref : la reference etant
                // stable, un rejeu apres une reponse perdue retrouve son opportunite au lieu
                // d'en creer une seconde.
                opportunite = client.chercheOpportuniteParRef(target, lead.reference());
            }
            if (opportunite == null) {
                opportunite = client.creeOpportunite(target, corpsOpportunite(lead, compte));
                if (lead.assigneeRef() != null) {
                    client.lieResponsable(target, opportunite, lead.assigneeRef());
                }
            }
        } catch (CrmSyncException echec) {
            throw echec.avecEtat(new CrmSyncState(compte, contact, opportunite, null));
        }
        return new CrmSyncResult(providerId(), compte, contact, opportunite, null, null, Instant.now());
    }

    @Override
    public String resolveAssignee(CrmAssignee assignee, CrmTarget target) {
        return client.chercheUtilisateurParEmail(target, assignee.email());
    }

    @Override
    public List<CrmSettingSpec> reglagesAttendus() {
        return List.of(
                new CrmSettingSpec("baseUrl", "Adresse de l'API, /api/index.php compris", false),
                new CrmSettingSpec("apiKey", "Cle d'API de l'utilisateur de service", true));
    }

    @Override
    public CrmCheck verifieAcces(CrmTarget cible) {
        return client.verifieAcces(cible);
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
        corps.put("ref", lead.reference());
        corps.put("socid", compte);
        corps.put("title", lead.detectedIntent() == null ? "Lead LeadFlow" : lead.detectedIntent());
        corps.put("usage_opportunity", "1");
        corps.put("opp_status", "1");
        corps.put("note_private", note(lead));
        return corps;
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
