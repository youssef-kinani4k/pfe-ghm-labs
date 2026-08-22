package com.leadflow.crm.dolibarr;

import com.leadflow.crm.CrmHttpConfig;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmTarget;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Transport REST vers une instance Dolibarr. Ne connait pas le modele pivot : il recoit des
 * cartes deja traduites par {@link DolibarrConnector} et rend des identifiants.
 *
 * <p>Dolibarr renvoie l'identifiant d'un objet cree sous forme de nombre nu dans le corps de
 * la reponse — verifie par la sonde de la tache 1 — normalise en chaine ici, parce que le
 * pivot manipule des references opaques.
 */
@Component
public class DolibarrClient {

    static final String PROVIDER_ID = "dolibarr";

    /** Au-dela, c'est une page d'erreur du serveur et non un diagnostic. */
    private static final int MESSAGE_MAX = 500;

    private final RestClient.Builder builder;

    @Autowired
    public DolibarrClient(CrmHttpConfig http) {
        this(http.builderPour(PROVIDER_ID));
    }

    /** Constructeur des tests : un builder nu, branche sur {@code MockRestServiceServer}. */
    public DolibarrClient(RestClient.Builder builder) {
        this.builder = builder;
    }

    public String creeTiers(CrmTarget target, Map<String, Object> corps) {
        return cree(target, "/thirdparties", corps);
    }

    public String creeContact(CrmTarget target, Map<String, Object> corps) {
        return cree(target, "/contacts", corps);
    }

    public String creeOpportunite(CrmTarget target, Map<String, Object> corps) {
        return cree(target, "/projects", corps);
    }

    /**
     * Cherche une opportunite par sa {@code ref}.
     *
     * <p>Existe pour rendre le rejeu inoffensif : la {@code ref} etant desormais derivee du
     * lead, elle est stable d'une tentative a l'autre. Sans cette recherche, un rejeu apres
     * une reponse perdue se heurterait a {@code uk_projet_ref} — un echec deterministe, donc
     * trois tentatives puis DLQ, alors que l'objet existe deja et que tout va bien.
     *
     * <p>Le filtre {@code sqlfilters} est construit par concatenation : la {@code ref} doit
     * donc rester une reference {@code LF-} derivee du lead. Les apostrophes sont retirees
     * plutot qu'echappees, ce qui suffit pour cette forme et pour elle seule.
     *
     * @return l'identifiant de l'opportunite, ou {@code null} si l'ERP n'en connait aucune
     */
    @SuppressWarnings("unchecked")
    public String chercheOpportuniteParRef(CrmTarget target, String ref) {
        String filtre = "(t.ref:=:'" + ref.replace("'", "") + "')";
        try {
            List<Map<String, Object>> reponse = restClient(target)
                    .get()
                    .uri(uri -> uri.path("/projects").queryParam("sqlfilters", filtre).build())
                    .retrieve()
                    .body(List.class);
            if (reponse == null || reponse.isEmpty()) {
                return null;
            }
            Object id = reponse.getFirst().get("id");
            return id == null ? null : String.valueOf(id);
        } catch (HttpClientErrorException.NotFound absente) {
            // Selon les versions, Dolibarr repond 404 sur une recherche sans resultat :
            // c'est une absence, pas une panne.
            return null;
        } catch (RestClientException e) {
            throw echec("/projects", e);
        }
    }

    /**
     * Assigne un utilisateur interne a une opportunite.
     *
     * <p>Appel distinct parce que Dolibarr ignore {@code fk_user_resp}, a la creation comme
     * en modification : la sonde de la tache 1 l'a verifie dans les deux sens. C'est le seul
     * endroit de F5 ou une etape de {@code sync} compte deux appels.
     */
    public void lieResponsable(CrmTarget target, String opportuniteRef, String utilisateurRef) {
        String chemin = "/projects/" + opportuniteRef + "/contacts";
        try {
            restClient(target)
                    .post()
                    .uri(uri -> uri.path(chemin)
                            .queryParam("fk_socpeople", utilisateurRef)
                            .queryParam("type_contact", "PROJECTLEADER")
                            .queryParam("source", "internal")
                            .build())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw echec(chemin, e);
        }
    }

    /** @return l'identifiant de l'utilisateur, ou {@code null} si l'ERP n'en connait aucun. */
    @SuppressWarnings("unchecked")
    public String chercheUtilisateurParEmail(CrmTarget target, String email) {
        String filtre = "(t.email:=:'" + email.replace("'", "") + "')";
        try {
            List<Map<String, Object>> reponse = restClient(target)
                    .get()
                    .uri(uri -> uri.path("/users").queryParam("sqlfilters", filtre).build())
                    .retrieve()
                    .body(List.class);
            if (reponse == null || reponse.isEmpty()) {
                return null;
            }
            Object id = reponse.getFirst().get("id");
            return id == null ? null : String.valueOf(id);
        } catch (RestClientException e) {
            throw echec("/users", e);
        }
    }

    private String cree(CrmTarget target, String chemin, Map<String, Object> corps) {
        try {
            Object identifiant = restClient(target)
                    .post()
                    .uri(chemin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(corps)
                    .retrieve()
                    .body(Object.class);
            if (identifiant == null) {
                throw new CrmSyncException(
                        PROVIDER_ID, "Dolibarr n'a renvoye aucun identifiant sur " + chemin, null);
            }
            return String.valueOf(identifiant);
        } catch (RestClientException e) {
            throw echec(chemin, e);
        }
    }

    private RestClient restClient(CrmTarget target) {
        return builder.clone()
                .baseUrl(reglage(target, "baseUrl"))
                .defaultHeader("DOLAPIKEY", reglage(target, "apiKey"))
                .build();
    }

    /** La valeur n'apparait jamais dans le message : {@code crm_config} contient des secrets. */
    private String reglage(CrmTarget target, String cle) {
        String valeur = target.settings() == null ? null : target.settings().get(cle);
        if (valeur == null || valeur.isBlank()) {
            throw new CrmSyncException(
                    PROVIDER_ID, "Reglage '" + cle + "' absent de la configuration du client", null);
        }
        return valeur;
    }

    /**
     * Le message de la cause est repris dans celui de l'exception : {@code error_message} de
     * la trace ne recoit que {@code getMessage()}, et sans lui l'exploitant sait qu'un appel
     * a echoue sans jamais savoir lequel des champs Dolibarr a ete refuse. Tronque parce
     * qu'un serveur en erreur peut repondre une page HTML entiere.
     *
     * <p>Sans risque pour les secrets : la cle voyage dans l'en-tete {@code DOLAPIKEY}, et
     * {@code RestClientResponseException} ne porte que le corps de la reponse.
     */
    private CrmSyncException echec(String chemin, Exception cause) {
        String detail = cause == null ? null : cause.getMessage();
        String message = "Appel Dolibarr en echec sur " + chemin;
        if (detail != null && !detail.isBlank()) {
            message += " : " + (detail.length() > MESSAGE_MAX ? detail.substring(0, MESSAGE_MAX) : detail);
        }
        return new CrmSyncException(PROVIDER_ID, message, cause);
    }
}
