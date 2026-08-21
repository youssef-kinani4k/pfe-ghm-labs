package com.leadflow.crm.dolibarr;

import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmTarget;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
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

    private final RestClient.Builder builder;

    public DolibarrClient(@Qualifier("dolibarrRestClientBuilder") RestClient.Builder builder) {
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

    private CrmSyncException echec(String chemin, Exception cause) {
        return new CrmSyncException(PROVIDER_ID, "Appel Dolibarr en echec sur " + chemin, cause);
    }
}
