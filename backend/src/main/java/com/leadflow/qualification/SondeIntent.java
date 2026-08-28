package com.leadflow.qualification;

import com.leadflow.config.IntentProperties;
import java.util.Locale;
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

    /** Un message dont l'intention ne fait pas de doute : le diagnostic verifie la chaine. */
    private static final String EXEMPLE = "Bonjour, je souhaite un devis pour 50 unites.";

    private final GeminiClient client;
    private final IntentSettings reglages;

    public SondeIntent(IntentProperties proprietes, IntentSettings reglages) {
        this.client = new GeminiClient(proprietes.gemini(),
                RestClient.builder().requestFactory(requestFactory(proprietes.gemini())));
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
            return echec(cause(refus.getStatusCode()), refus.getStatusCode().toString());
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
        return statut.is5xxServerError() ? CauseIntent.ERREUR_SERVEUR : CauseIntent.CLE_REFUSEE;
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
