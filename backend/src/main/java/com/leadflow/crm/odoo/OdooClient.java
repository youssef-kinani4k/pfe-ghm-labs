package com.leadflow.crm.odoo;

import com.leadflow.crm.CrmHttpConfig;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmTarget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Transport JSON-RPC vers une instance Odoo.
 *
 * <p>Odoo repond {@code HTTP 200} meme quand l'appel echoue : l'erreur est dans le corps,
 * sous la cle {@code error}. Toute reponse passe donc par {@link #resultat(Map, String)}, qui
 * inspecte le corps avant de rendre quoi que ce soit — s'en remettre au code de statut ferait
 * passer un echec pour un succes. La sonde de la tache 1 l'a confirme, message exploitable
 * compris : {@code error.data.message}.
 */
@Component
public class OdooClient {

    static final String PROVIDER_ID = "odoo";

    private final RestClient.Builder builder;

    @Autowired
    public OdooClient(CrmHttpConfig http) {
        this(http.builderPour(PROVIDER_ID));
    }

    /** Constructeur des tests : un builder nu, branche sur {@code MockRestServiceServer}. */
    public OdooClient(RestClient.Builder builder) {
        this.builder = builder;
    }

    public int authentifie(CrmTarget target) {
        Map<String, Object> reponse = appelle(target, "common", "authenticate", List.of(
                reglage(target, "database"),
                reglage(target, "username"),
                reglage(target, "apiKey"),
                Map.of()));
        Object uid = resultat(reponse, "authenticate");
        if (!(uid instanceof Number nombre) || nombre.intValue() <= 0) {
            throw new CrmSyncException(
                    PROVIDER_ID, "Odoo a refuse l'authentification de l'utilisateur configure", null);
        }
        return nombre.intValue();
    }

    public String cree(CrmTarget target, int uid, String modele, Map<String, Object> champs) {
        Map<String, Object> reponse =
                executeKw(target, uid, modele, "create", List.of(List.of(champs)));
        Object identifiant = resultat(reponse, modele + ".create");
        // Un corps sans « result » ni « error » — reponse tronquee, proxy intercale — rendrait
        // null, et le lead finirait SYNCED avec une reference vide. Odoo cree toujours un
        // entier : tout le reste est un echec, y compris le « false » que rend un appel refuse.
        if (!(identifiant instanceof Number nombre)) {
            throw new CrmSyncException(
                    PROVIDER_ID, "Odoo n'a renvoye aucun identifiant sur " + modele + ".create", null);
        }
        return String.valueOf(nombre.longValue());
    }

    /**
     * @return l'identifiant de l'utilisateur, ou {@code null} si Odoo n'en connait aucun.
     *     La recherche porte sur {@code login} OU {@code email} : {@code res.users} expose
     *     les deux, et un commercial identifie par son courriel echapperait a une recherche
     *     sur le seul {@code login}.
     */
    public String chercheUtilisateurParEmail(CrmTarget target, int uid, String email) {
        Map<String, Object> reponse = executeKw(target, uid, "res.users", "search",
                List.of(List.of(List.of("|", List.of("login", "=", email),
                        List.of("email", "=", email)))));
        Object trouves = resultat(reponse, "res.users.search");
        if (!(trouves instanceof List<?> liste) || liste.isEmpty()) {
            return null;
        }
        return String.valueOf(liste.getFirst());
    }

    private Map<String, Object> executeKw(
            CrmTarget target, int uid, String modele, String methode, List<Object> arguments) {
        List<Object> args = new ArrayList<>(List.of(
                reglage(target, "database"), uid, reglage(target, "apiKey"), modele, methode));
        args.addAll(arguments);
        return appelle(target, "object", "execute_kw", args);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> appelle(
            CrmTarget target, String service, String methode, List<Object> args) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("service", service);
        params.put("method", methode);
        params.put("args", args);

        Map<String, Object> enveloppe = new LinkedHashMap<>();
        enveloppe.put("jsonrpc", "2.0");
        enveloppe.put("method", "call");
        enveloppe.put("id", 1);
        enveloppe.put("params", params);

        try {
            return builder.clone()
                    .baseUrl(reglage(target, "baseUrl"))
                    .build()
                    .post()
                    .uri("/jsonrpc")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(enveloppe)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            throw new CrmSyncException(PROVIDER_ID, "Appel Odoo en echec sur " + methode, e);
        }
    }

    /**
     * Extrait {@code result} apres avoir verifie l'absence de {@code error} dans le corps.
     *
     * <p>Seul {@code error.data.message} est repris : {@code error.message} ne dit que
     * « Odoo Server Error », et {@code error.data.debug} contient la trace Python complete,
     * qui n'a rien a faire dans {@code crm_sync_attempt.error_message}.
     */
    private Object resultat(Map<String, Object> reponse, String operation) {
        if (reponse == null) {
            throw new CrmSyncException(PROVIDER_ID, "Reponse Odoo vide sur " + operation, null);
        }
        Object erreur = reponse.get("error");
        if (erreur instanceof Map<?, ?> details) {
            Object donnees = details.get("data");
            Object message = donnees instanceof Map<?, ?> carte && carte.get("message") != null
                    ? carte.get("message")
                    : details.get("message");
            throw new CrmSyncException(
                    PROVIDER_ID, "Odoo a rejete " + operation + " : " + message, null);
        }
        return reponse.get("result");
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
}
