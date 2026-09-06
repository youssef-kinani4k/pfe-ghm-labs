package com.leadflow.crm.odoo;

import com.leadflow.crm.CrmHttpConfig;
import com.leadflow.crm.DestinationRefuseeException;
import com.leadflow.crm.model.CrmCheck;
import com.leadflow.crm.model.CrmCheckCause;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmTarget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
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
     * Met a jour un enregistrement existant. Symetrique de {@link #cree} : meme transport,
     * meme authentification, meme lecture du corps.
     *
     * <p>L'identifiant voyage en {@code String} comme partout dans le pivot, et redevient un
     * entier ici : Odoo n'accepte pas une chaine dans la liste d'identifiants d'un
     * {@code write}, et c'est exactement le genre de traduction qui appartient a
     * l'adaptateur.
     *
     * <p>Odoo rend {@code true} sur un write accepte. Tout le reste est un echec, y compris
     * le {@code false} d'un appel refuse et l'absence de {@code result} d'une reponse
     * tronquee — sans ce controle, une correction jamais appliquee passerait pour un succes.
     */
    public void ecrit(
            CrmTarget target, int uid, String modele, String identifiant,
            Map<String, Object> champs) {
        long id;
        try {
            id = Long.parseLong(identifiant);
        } catch (NumberFormatException erreur) {
            throw new CrmSyncException(
                    PROVIDER_ID, "Reference Odoo illisible sur " + modele + " : " + identifiant,
                    erreur);
        }
        Map<String, Object> reponse =
                executeKw(target, uid, modele, "write", List.of(List.of(id), champs));
        Object resultat = resultat(reponse, modele + ".write");
        if (!Boolean.TRUE.equals(resultat)) {
            throw new CrmSyncException(
                    PROVIDER_ID, "Odoo a refuse " + modele + ".write sur " + identifiant, null);
        }
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

    /**
     * Sonde d'acces : l'authentification JSON-RPC valide d'un seul appel l'adresse, la base,
     * l'utilisateur et la cle. Rien n'est cree.
     *
     * <p>Odoo repond 200 meme en cas de refus — l'echec vit dans le corps — donc la
     * distinction se fait sur le contenu et non sur le code HTTP.
     */
    public CrmCheck verifieAcces(CrmTarget target) {
        for (String cle : List.of("baseUrl", "database", "username", "apiKey")) {
            String valeur = target.settings() == null ? null : target.settings().get(cle);
            if (valeur == null || valeur.isBlank()) {
                return CrmCheck.echec(
                        CrmCheckCause.REPONSE_INATTENDUE, "Reglage '" + cle + "' absent");
            }
        }
        try {
            int uid = authentifie(target);
            return uid > 0
                    ? CrmCheck.joignable(null)
                    : CrmCheck.echec(CrmCheckCause.IDENTIFIANTS_REFUSES, null);
        } catch (CrmSyncException echec) {
            // `appelle` enveloppe toute RestClientException, injoignabilite comprise : la
            // distinction se lit dans la cause et non dans un catch separe. Verifie sur le
            // code d'OdooClient, pas suppose.
            if (echec.getCause() instanceof ResourceAccessException reseau) {
                return CrmCheck.echec(CrmCheckCause.INJOIGNABLE, reseau.getMessage());
            }
            String message = echec.getMessage() == null ? "" : echec.getMessage().toLowerCase();
            if (message.contains("database")) {
                return CrmCheck.echec(CrmCheckCause.CIBLE_INCONNUE, echec.getMessage());
            }
            return CrmCheck.echec(CrmCheckCause.IDENTIFIANTS_REFUSES, echec.getMessage());
        } catch (DestinationRefuseeException e) {
            // Contrairement a une RestClientException, le garde n'est pas enveloppe par
            // `appelle` : il n'est pas une RestClientException, donc il traverse jusqu'ici
            // tel quel. Son message est deja ecrit pour un humain, contrairement au filet
            // generique plus bas qui recopierait String.valueOf(e).
            return CrmCheck.echec(CrmCheckCause.DESTINATION_REFUSEE, e.getMessage());
        } catch (RuntimeException e) {
            // Meme filet que la sonde Dolibarr : une adresse fournie par l'operateur peut
            // faire echouer la pile HTTP avant Spring, et la sonde promet de ne jamais lever.
            return CrmCheck.echec(CrmCheckCause.REPONSE_INATTENDUE, String.valueOf(e));
        }
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
