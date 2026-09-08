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
     * <p>L'attribution du responsable est une <b>etape a part entiere</b>, et non un geste
     * imbrique dans la creation de l'opportunite. Dolibarr ignore {@code fk_user_resp} a la
     * creation comme en modification — la sonde de F5 l'a verifie dans les deux sens —, ce
     * qui impose un second appel. Imbrique dans la garde de creation, son echec laissait une
     * opportunite sans chef de projet que le rejeu sautait, puisque la reference de
     * l'opportunite etait deja connue. Depuis F11.2, {@code CrmSyncState.assigneeRef} porte
     * cette quatrieme reference et le rejeu repare.
     *
     * <p>Le jour ou un ERP apportera une cinquieme etape, la bonne reponse restera une carte
     * de references par etape plutot qu'un champ de plus.
     *
     * <p>La limitation sur la {@code ref} d'opportunite est levee depuis F3 : elle est
     * derivee du lead et donc stable, et la recherche prealable rend le rejeu inoffensif.
     *
     * <p><b>Fenetre residuelle, refermee depuis F15 : le rejeu repare une attribution absente
     * comme une attribution dont la confirmation s'est perdue.</b> {@code
     * chercheOpportuniteParRef} offre a la creation d'opportunite un moyen de retrouver ce
     * qui existe deja ; l'appel a {@code lieResponsable} n'a toujours pas d'equivalent —
     * aucune sonde ne permet de demander a Dolibarr « ce responsable est-il deja lie ? ». Si
     * le lien reussit cote ERP mais que la trace de {@code assigneeRef} n'est pas ecrite, le
     * rejeu retente {@code lieResponsable} avec le meme utilisateur, et ce doublon n'est plus
     * suppose mais observe : contre une vraie instance, Dolibarr le refuse par un
     * {@code 500} au corps {@code "Internal Server Error: Error : result :0"}, source
     * {@code api_projects.class.php}. {@code DolibarrClient.lieResponsable} reconnait cette
     * signature precise et la traite comme le succes qu'elle est — un lien deja pose est
     * l'etat recherche, pas un echec — si bien que le rejeu n'envoie plus un lead par
     * ailleurs entierement synchronise en DLQ. Le residu qui subsiste est etroit : la
     * reconnaissance porte sur ce message d'erreur precis, et une version future de Dolibarr
     * qui le reformulerait ferait a nouveau lever ce cas — au pire au meme niveau qu'avant ce
     * correctif, un echec bruyant plutot que silencieux.
     */
    @Override
    public CrmSyncResult sync(CrmLead lead, CrmTarget target, CrmSyncState previous) {
        String compte = previous.accountRef();
        String contact = previous.contactRef();
        String opportunite = previous.opportunityRef();
        String responsable = previous.assigneeRef();
        try {
            if (compte == null) {
                // Meme raison que pour l'opportunite plus bas : on demande d'abord a l'ERP
                // s'il connait deja ce courriel. Sans cette recherche, un prospect qui revient
                // par un second formulaire ouvre un doublon de tiers, et un rejeu apres une
                // reponse perdue en ouvre un troisieme.
                compte = client.chercheTiersParEmail(target, lead.email());
            }
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
            }
            // Etape a part entiere, et non imbriquee dans la creation : c'est ce qui rend
            // l'attribution rejouable. Imbriquee, un echec ici laissait une opportunite sans
            // chef de projet que le rejeu sautait, puisque sa reference etait deja connue.
            if (responsable == null && lead.assigneeRef() != null) {
                client.lieResponsable(target, opportunite, lead.assigneeRef());
                responsable = lead.assigneeRef();
            }
        } catch (CrmSyncException echec) {
            throw echec.avecEtat(new CrmSyncState(compte, contact, opportunite, responsable));
        }
        return new CrmSyncResult(
                providerId(), compte, contact, opportunite, responsable, null, Instant.now());
    }

    @Override
    public String resolveAssignee(CrmAssignee assignee, CrmTarget target) {
        return client.chercheUtilisateurParEmail(target, assignee.email());
    }

    /**
     * Dolibarr rattache le responsable au projet par un appel dedie — {@code fk_user_resp}
     * est ignore a la creation comme en modification, la sonde de F5 l'a verifie dans les
     * deux sens. C'est le meme appel que l'etape d'attribution de {@link #sync}.
     *
     * <p><strong>Mais deux appels, pas un.</strong> {@code lieResponsable} ajoute un contact et
     * n'en retire aucun : sans le retrait qui suit, la fiche porterait deux
     * {@code PROJECTLEADER} apres une reaffectation, dont un qui n'a plus rien a voir avec le
     * lead — soit exactement l'ambiguite que cette feature doit lever. Ce n'est pas une
     * supposition : la recette de F15 l'a observe sur une vraie instance. Odoo n'a pas ce
     * besoin, son {@code write} sur {@code user_id} remplacant la valeur.
     *
     * <p><strong>L'ordre est porteur de sens</strong> : on pose le nouveau lien avant de
     * retirer l'ancien. Une panne entre les deux laisse le bon responsable present en plus de
     * l'ancien, soit l'etat qui precedait ce correctif — genant, jamais faux. L'ordre inverse
     * pourrait laisser la fiche sans aucun chef de projet.
     *
     * <p>Deux cas ne retirent rien. L'ancienne reference absente signifie que la
     * synchronisation d'origine n'avait lie personne, il n'y a alors aucun lien a defaire.
     * Et l'ancienne confondue avec la nouvelle est le <strong>rejeu</strong> d'une
     * reaffectation deja passee : la trace porte alors deja le nouveau responsable, et
     * retirer ce qu'on vient de poser laisserait le projet sans responsable. La livraison
     * etant at-least-once, ce cas est attendu, pas theorique.
     */
    @Override
    public void reaffecte(CrmSyncState references, String assigneeRef, CrmTarget cible) {
        if (references.opportunityRef() == null) {
            throw new CrmSyncException(
                    providerId(), "Aucune opportunite connue : rien a reaffecter", null);
        }
        client.lieResponsable(cible, references.opportunityRef(), assigneeRef);
        String ancien = references.assigneeRef();
        if (ancien != null && !ancien.equals(assigneeRef)) {
            client.retireResponsable(cible, references.opportunityRef(), ancien);
        }
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
