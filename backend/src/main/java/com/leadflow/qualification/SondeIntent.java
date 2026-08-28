package com.leadflow.qualification;

import com.leadflow.config.IntentProperties;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Eprouve une cle d'API sans la mettre en service.
 *
 * <p>Meme role que {@code CrmConnector.verifieAcces} pour un ERP : l'operateur doit pouvoir
 * savoir <b>avant</b> d'enregistrer si ce qu'il vient de coller fonctionne. La sonde
 * n'ecrit rien et ne lit pas la base au-dela de la cle en service quand la demande n'en
 * fournit pas.
 *
 * <p>Elle ne reutilise pas {@link GeminiIntentAnalyzer} : celui-ci avale toute defaillance
 * pour ne perdre aucun lead, ce qui est exactement le contraire de ce qu'un diagnostic doit
 * faire. Les deux partagent {@link GeminiClient}, donc le meme appel.
 */
@Service
public class SondeIntent {

    private static final Logger log = LoggerFactory.getLogger(SondeIntent.class);

    /** Un message dont l'intention ne fait pas de doute : le diagnostic verifie la chaine. */
    private static final String EXEMPLE = "Bonjour, je souhaite un devis pour 50 unites.";

    /** Ce qu'on garde du motif rendu par le fournisseur. Assez pour agir, pas un roman. */
    private static final int DETAIL_MAX = 140;

    private final GeminiClient client;
    private final ReglageIntent reglages;

    /**
     * {@code @Autowired} est obligatoire : la classe a deux constructeurs depuis que les
     * tests en ont un a eux, et Spring irait chercher un constructeur sans argument.
     */
    @Autowired
    public SondeIntent(IntentProperties proprietes, IntentSettings reglages) {
        this(proprietes.gemini(), reglages,
                RestClient.builder().requestFactory(requestFactory(proprietes.gemini())));
    }

    /** Constructeur des tests : un builder nu, branche sur {@code MockRestServiceServer}. */
    SondeIntent(
            IntentProperties.Gemini config, ReglageIntent reglages, RestClient.Builder builder) {
        this.client = new GeminiClient(config, builder);
        this.reglages = reglages;
    }

    private static ClientHttpRequestFactory requestFactory(IntentProperties.Gemini config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.connectTimeout());
        factory.setReadTimeout(config.readTimeout());
        return factory;
    }

    public IntentTestResult eprouve(String cleProposee) {
        String cle = cleProposee != null && !cleProposee.isBlank()
                ? cleProposee.trim()
                : reglages.cleEffective();
        if (cle == null || cle.isBlank()) {
            return echec(CauseIntent.CLE_ABSENTE, "Aucune cle a eprouver");
        }
        try {
            String texte = client.classe(EXEMPLE, cle);
            LeadIntent intention = vocabulaire(texte);
            return intention == null
                    ? echec(CauseIntent.REPONSE_INATTENDUE, "Reponse hors vocabulaire")
                    : new IntentTestResult(true, CauseIntent.OK, "Cle valide", intention.name());
        } catch (HttpStatusCodeException refus) {
            return echec(cause(refus.getStatusCode()), motif(refus));
        } catch (ResourceAccessException injoignable) {
            return echec(CauseIntent.INJOIGNABLE, injoignable.getMessage());
        } catch (Exception illisible) {
            return echec(CauseIntent.REPONSE_INATTENDUE, illisible.getMessage());
        }
    }

    private static CauseIntent cause(HttpStatusCode statut) {
        if (statut.value() == 429) {
            return CauseIntent.QUOTA_DEPASSE;
        }
        if (statut.value() == 404) {
            return CauseIntent.MODELE_INCONNU;
        }
        return statut.is5xxServerError() ? CauseIntent.ERREUR_SERVEUR : CauseIntent.CLE_REFUSEE;
    }

    /**
     * Le motif du fournisseur, reduit a ce qui dit quoi reparer.
     *
     * <p><b>La reponse brute ne va pas a l'ecran.</b> Un corps d'erreur peut faire plusieurs
     * lignes — invitation a changer de modele, lien vers la documentation, recommandation
     * d'API — et le recopier transforme un diagnostic en decharge de texte. On garde la
     * premiere phrase, plafonnee ; le corps entier part dans les journaux, ou un operateur
     * technique peut aller le chercher.
     *
     * <p>Le motif ne contient pas la cle : le fournisseur renvoie la raison du refus, jamais
     * le secret qu'on lui a presente. Un test le verrouille.
     */
    private String motif(HttpStatusCodeException refus) {
        String corps = refus.getResponseBodyAsString();
        String message = extraitLeMessage(corps);
        log.warn("Diagnostic de cle refuse par le fournisseur ({}) : {}",
                refus.getStatusCode(), corps);
        String texte = message != null ? message : corps;
        if (texte == null || texte.isBlank()) {
            return refus.getStatusCode().toString();
        }
        return refus.getStatusCode().value() + " — " + premierePhrase(texte);
    }

    /**
     * La premiere phrase, plafonnee. Le point suivi d'une espace suffit a decouper : les
     * messages des fournisseurs sont des phrases anglaises, et un decoupage trop savant
     * echouerait aussi souvent qu'il reussirait.
     */
    private static String premierePhrase(String texte) {
        int fin = texte.indexOf(". ");
        String phrase = fin > 0 ? texte.substring(0, fin + 1) : texte;
        return phrase.length() > DETAIL_MAX
                ? phrase.substring(0, DETAIL_MAX) + "…"
                : phrase;
    }

    /**
     * Extraction textuelle et non deserialisation : le corps d'erreur d'un fournisseur n'a
     * aucun contrat, et un document inattendu ne doit pas faire echouer le diagnostic
     * lui-meme.
     */
    private static String extraitLeMessage(String corps) {
        if (corps == null) {
            return null;
        }
        int debut = corps.indexOf("\"message\"");
        if (debut < 0) {
            return null;
        }
        int ouvrante = corps.indexOf('"', corps.indexOf(':', debut) + 1);
        int fermante = ouvrante < 0 ? -1 : corps.indexOf('"', ouvrante + 1);
        return fermante < 0 ? null : corps.substring(ouvrante + 1, fermante);
    }

    private static LeadIntent vocabulaire(String texte) {
        String candidat = texte.trim().toUpperCase(Locale.ROOT);
        for (LeadIntent intention : LeadIntent.values()) {
            if (intention.name().equals(candidat)) {
                return intention;
            }
        }
        return null;
    }

    private static IntentTestResult echec(CauseIntent cause, String detail) {
        return new IntentTestResult(false, cause, detail, null);
    }
}
