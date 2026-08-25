package com.leadflow.crm.odoo;

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
 * Traduction du modele pivot vers le vocabulaire d'Odoo.
 *
 * <p>Odoo place la societe et le contact dans le meme modele {@code res.partner}, distingues
 * par {@code is_company} et relies par {@code parent_id} — la ou Dolibarr a deux endpoints.
 * L'opportunite est un {@code crm.lead}, qui exige le module {@code crm} installe sur
 * l'instance.
 */
@Component
public class OdooConnector implements CrmConnector {

    private static final String PARTENAIRE = "res.partner";
    private static final String OPPORTUNITE = "crm.lead";

    private final OdooClient client;

    public OdooConnector(OdooClient client) {
        this.client = client;
    }

    @Override
    public String providerId() {
        return OdooClient.PROVIDER_ID;
    }

    @Override
    public CrmSyncResult sync(CrmLead lead, CrmTarget target, CrmSyncState previous) {
        String societe = previous.accountRef();
        String contact = previous.contactRef();
        String opportunite = previous.opportunityRef();

        boolean societeAttendue = lead.companyName() != null && !lead.companyName().isBlank();
        boolean toutExiste = contact != null && opportunite != null
                && (!societeAttendue || societe != null);
        if (toutExiste) {
            return new CrmSyncResult(
                    providerId(), societe, contact, opportunite, null, Instant.now());
        }

        int uid = client.authentifie(target);
        try {
            if (societeAttendue && societe == null) {
                societe = client.cree(target, uid, PARTENAIRE, champsSociete(lead));
            }
            if (contact == null) {
                contact = client.cree(target, uid, PARTENAIRE, champsContact(lead, societe));
            }
            if (opportunite == null) {
                opportunite = client.cree(
                        target, uid, OPPORTUNITE, champsOpportunite(lead, societe, contact));
            }
        } catch (CrmSyncException echec) {
            throw echec.avecEtat(new CrmSyncState(societe, contact, opportunite));
        }
        return new CrmSyncResult(providerId(), societe, contact, opportunite, null, Instant.now());
    }

    @Override
    public String resolveAssignee(CrmAssignee assignee, CrmTarget target) {
        return client.chercheUtilisateurParEmail(
                target, client.authentifie(target), assignee.email());
    }

    @Override
    public List<CrmSettingSpec> reglagesAttendus() {
        return List.of(
                new CrmSettingSpec("baseUrl", "Adresse du serveur, sans /jsonrpc", false),
                new CrmSettingSpec("database", "Base Odoo visee", false),
                new CrmSettingSpec("username", "Login du compte de service", false),
                new CrmSettingSpec("apiKey", "Mot de passe ou cle d'API de ce compte", true));
    }

    @Override
    public CrmCheck verifieAcces(CrmTarget cible) {
        return client.verifieAcces(cible);
    }

    private Map<String, Object> champsSociete(CrmLead lead) {
        Map<String, Object> champs = new LinkedHashMap<>();
        champs.put("name", lead.companyName());
        champs.put("is_company", true);
        champs.put("email", lead.email());
        if (lead.phone() != null) {
            champs.put("phone", lead.phone());
        }
        return champs;
    }

    private Map<String, Object> champsContact(CrmLead lead, String societe) {
        Map<String, Object> champs = new LinkedHashMap<>();
        champs.put("name", nomDeLaPersonne(lead));
        champs.put("is_company", false);
        champs.put("email", lead.email());
        if (lead.phone() != null) {
            champs.put("phone", lead.phone());
        }
        if (societe != null) {
            champs.put("parent_id", societe);
        }
        return champs;
    }

    private Map<String, Object> champsOpportunite(CrmLead lead, String societe, String contact) {
        Map<String, Object> champs = new LinkedHashMap<>();
        champs.put("name", lead.detectedIntent() == null ? "Lead LeadFlow" : lead.detectedIntent());
        champs.put("type", "opportunity");
        champs.put("partner_id", societe != null ? societe : contact);
        champs.put("email_from", lead.email());
        champs.put("description", lead.message() == null ? "" : lead.message());
        champs.put("priority", priorite(lead.score()));
        if (lead.phone() != null) {
            champs.put("phone", lead.phone());
        }
        if (lead.assigneeRef() != null) {
            champs.put("user_id", lead.assigneeRef());
        }
        return champs;
    }

    /**
     * Le score de LeadFlow va de 0 a 100, la priorite d'Odoo de 0 a 3 — la sonde a verifie
     * qu'une valeur hors echelle est refusee. La conversion vit ici : aucune des deux
     * echelles n'a a remonter dans le pivot.
     */
    private String priorite(int score) {
        if (score >= 90) {
            return "3";
        }
        if (score >= 70) {
            return "2";
        }
        if (score >= 40) {
            return "1";
        }
        return "0";
    }

    private String nomDeLaPersonne(CrmLead lead) {
        String prenom = lead.firstName() == null ? "" : lead.firstName();
        String nom = lead.lastName() == null ? "" : lead.lastName();
        String complet = (prenom + " " + nom).trim();
        return complet.isBlank() ? lead.email() : complet;
    }
}
