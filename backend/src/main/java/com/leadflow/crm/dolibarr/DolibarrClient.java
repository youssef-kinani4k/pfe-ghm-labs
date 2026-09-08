package com.leadflow.crm.dolibarr;

import com.leadflow.crm.CrmHttpConfig;
import com.leadflow.crm.DestinationRefuseeException;
import com.leadflow.crm.model.CrmCheck;
import com.leadflow.crm.model.CrmCheckCause;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmTarget;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
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
     * Cherche un tiers par son courriel.
     *
     * <p>Pendant de {@link #chercheOpportuniteParRef} pour la premiere etape de la
     * synchronisation, et pour la meme raison : rendre le geste sur. Le tiers etait la seule
     * etape que rien ne protegeait — l'opportunite l'est par sa {@code ref} stable, le contact
     * par la deduplication que Dolibarr applique lui-meme sur le courriel — si bien qu'un
     * prospect revenant par un second formulaire ouvrait un doublon de tiers, et qu'un rejeu
     * apres une reponse perdue en ouvrait un troisieme.
     *
     * <p>Le filtre {@code sqlfilters} est construit par concatenation, comme ailleurs dans
     * cette classe : les apostrophes sont retirees plutot qu'echappees. Le courriel a
     * traverse la normalisation de la qualification, qui est ce qui rend ce traitement
     * suffisant.
     *
     * @return l'identifiant du tiers, ou {@code null} si l'ERP n'en connait aucun
     */
    @SuppressWarnings("unchecked")
    public String chercheTiersParEmail(CrmTarget target, String email) {
        String filtre = "(t.email:=:'" + email.replace("'", "") + "')";
        try {
            List<Map<String, Object>> reponse = restClient(target)
                    .get()
                    .uri(uri -> uri.path("/thirdparties").queryParam("sqlfilters", filtre).build())
                    .retrieve()
                    .body(List.class);
            if (reponse == null || reponse.isEmpty()) {
                return null;
            }
            Object id = reponse.getFirst().get("id");
            return id == null ? null : String.valueOf(id);
        } catch (HttpClientErrorException.NotFound absent) {
            // Dolibarr rend 404 sur une liste vide de tiers : c'est une absence, pas une panne.
            return null;
        } catch (RestClientException e) {
            throw echec("/thirdparties", e);
        }
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
     *
     * <p>Rejouer ce lien — un rejeu depuis le journal des morts, la livraison at-least-once
     * de la synchronisation — heurte Dolibarr non pas sur un doublon propre mais sur un
     * {@code 500} : prouve contre une vraie instance, reposter le meme
     * {@code fk_socpeople}/{@code type_contact} sur une opportunite qui le porte deja rend
     * {@code "Internal Server Error: Error : result :0"}, source
     * {@code api_projects.class.php}. Un lien deja pose est l'etat recherche, pas un echec :
     * {@link #lienDejaPose(HttpServerErrorException.InternalServerError)} reconnait cette
     * signature precise et le traite comme un succes plutot que de le relancer — voir
     * {@link DolibarrConnector} pour la fenetre que ce refus refermait.
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
        } catch (HttpServerErrorException.InternalServerError e) {
            if (!lienDejaPose(e)) {
                throw echec(chemin, e);
            }
            // Lien deja pose : l'etat recherche est atteint, rien a lever.
        } catch (RestClientException e) {
            throw echec(chemin, e);
        }
    }

    /**
     * Reconnait la signature precise du doublon de lien responsable, observee contre une
     * vraie instance Dolibarr : {@code result :0} dans le message d'erreur et
     * {@code api_projects.class.php} comme source. Les deux sont exiges pour ne reconnaitre
     * que ce cas — un autre {@code 500} doit continuer de lever.
     *
     * <p>La reconnaissance porte sur un message d'erreur et un nom de fichier source, pas sur
     * un code documente par Dolibarr : une version future qui reformulerait ce message ferait
     * a nouveau lever ce cas, au pire au meme niveau qu'avant ce correctif.
     */
    private boolean lienDejaPose(HttpServerErrorException.InternalServerError e) {
        String corps = e.getResponseBodyAsString();
        return corps != null
                && corps.contains("result :0")
                && corps.contains("api_projects.class.php");
    }

    /**
     * Retire le lien de chef de projet d'un utilisateur sur une opportunite.
     *
     * <p>Symetrique de {@link #lieResponsable}, et indispensable avec lui : ce dernier
     * <em>ajoute</em> un contact sans en retirer aucun, si bien qu'une reaffectation laissait
     * la fiche Dolibarr avec deux {@code PROJECTLEADER}, dont un qui n'a plus rien a voir avec
     * le lead. Odoo n'a pas besoin de ce geste — son {@code write} sur {@code user_id} remplace
     * la valeur.
     *
     * <p>Le segment {@code contactid} attend l'identifiant de l'<strong>utilisateur</strong>,
     * pas le {@code rowid} de la ligne de liaison. Le Javadoc de Dolibarr affirme l'inverse
     * (« Row key of the contact in the array contact_ids ») et se trompe : la route compare
     * {@code $contact['id']}, releve dans {@code projet/class/api_projects.class.php} d'une
     * vraie instance. C'est ce qui permet a {@link DolibarrConnector#reaffecte} de passer
     * directement l'ancienne reference sans lire d'abord les contacts du projet.
     *
     * <p>Contrairement a la pose, ce retrait est <strong>idempotent chez Dolibarr lui-meme</strong> :
     * eprouve contre une vraie instance, retirer un lien deja absent rend {@code 200} et non le
     * {@code 500} de {@link #lienDejaPose}. La route parcourt ses contacts et sort sans rien
     * faire quand aucun ne correspond. Aucun traitement particulier n'est donc necessaire ici.
     */
    public void retireResponsable(CrmTarget target, String opportuniteRef, String utilisateurRef) {
        String chemin = "/projects/" + opportuniteRef + "/contact/" + utilisateurRef + "/PROJECTLEADER";
        try {
            restClient(target).delete().uri(chemin).retrieve().toBodilessEntity();
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

    /**
     * Sonde d'acces, appelee par l'ecran d'administration avant d'enregistrer une boutique.
     *
     * <p>{@code /status} plutot que {@code /users} : il est concu pour cela, ne lit aucune
     * donnee metier et valide d'un coup l'adresse et la cle d'API.
     *
     * <p>Elle ne leve jamais : un echec de sonde est une reponse, pas un incident. C'est ce
     * qui permet a l'endpoint de test de rendre 200 avec une cause.
     */
    public CrmCheck verifieAcces(CrmTarget target) {
        String base = target.settings() == null ? null : target.settings().get("baseUrl");
        String cle = target.settings() == null ? null : target.settings().get("apiKey");
        if (base == null || base.isBlank() || cle == null || cle.isBlank()) {
            return CrmCheck.echec(
                    CrmCheckCause.REPONSE_INATTENDUE, "Reglage 'baseUrl' ou 'apiKey' absent");
        }
        try {
            restClient(target).get().uri("/status").retrieve().body(Map.class);
            return CrmCheck.joignable(null);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            return CrmCheck.echec(CrmCheckCause.IDENTIFIANTS_REFUSES, e.getMessage());
        } catch (HttpClientErrorException.NotFound e) {
            return CrmCheck.echec(CrmCheckCause.CIBLE_INCONNUE, e.getMessage());
        } catch (DestinationRefuseeException e) {
            // Le garde a refuse la destination avant meme le depart de la requete : son
            // message est deja ecrit pour un humain, contrairement au filet generique plus
            // bas qui recopierait String.valueOf(e).
            return CrmCheck.echec(CrmCheckCause.DESTINATION_REFUSEE, e.getMessage());
        } catch (ResourceAccessException e) {
            return CrmCheck.echec(CrmCheckCause.INJOIGNABLE, e.getMessage());
        } catch (RestClientException e) {
            return CrmCheck.echec(CrmCheckCause.REPONSE_INATTENDUE, e.getMessage());
        } catch (RuntimeException e) {
            // Filet : la sonde promet de ne jamais lever, et une adresse fournie par
            // l'operateur peut faire echouer la pile HTTP avant meme Spring — l'ecran doit
            // rendre une cause, pas une trace.
            return CrmCheck.echec(CrmCheckCause.REPONSE_INATTENDUE, String.valueOf(e));
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
