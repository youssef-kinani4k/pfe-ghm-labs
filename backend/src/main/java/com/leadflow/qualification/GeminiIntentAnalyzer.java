package com.leadflow.qualification;

import com.leadflow.config.IntentProperties;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Analyse d'intention par l'API Gemini de Google, avec repli sur l'analyseur lexical.
 *
 * <p>Le repli n'est pas un {@code if} au runtime mais un decorateur cable par la
 * configuration : a {@code enabled: false}, ce bean n'existe pas et
 * {@link RuleBasedIntentAnalyzer} devient le seul candidat du port. L'appelant ne s'en
 * apercoit pas.
 *
 * <p><b>Cette classe ne propage aucune exception.</b> Timeout, 5xx, quota depasse, reponse
 * hors vocabulaire, corps illisible : tout retombe sur le repli avec
 * {@link IntentSource#RULES}. C'est ce qui garantit qu'une panne de l'API ne perd aucun
 * lead, et la colonne {@code intent_source} rend le basculement observable.
 *
 * <p><b>Injection de prompt.</b> Le message du prospect entre dans le prompt : il peut donc
 * contenir des instructions. La parade n'est pas de filtrer le texte mais de contraindre la
 * sortie — seule une reponse appartenant a {@link LeadIntent} est acceptee. Le pire qu'une
 * injection obtienne est une intention mal etiquetee sur son propre lead.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "leadflow.intent.gemini", name = "enabled", havingValue = "true")
public class GeminiIntentAnalyzer implements IntentAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GeminiIntentAnalyzer.class);

    private final IntentAnalyzer repli;
    private final IntentProperties.Gemini config;
    private final ReglageIntent reglage;
    private final GeminiClient client;

    /**
     * Le repli est injecte par son type concret et non par le port : cette classe est
     * elle-meme un {@code IntentAnalyzer} et elle est {@code @Primary}, si bien qu'un
     * parametre de type {@code IntentAnalyzer} demanderait a Spring de l'injecter dans son
     * propre constructeur — reference circulaire au demarrage.
     */
    @Autowired
    public GeminiIntentAnalyzer(
            RuleBasedIntentAnalyzer repli, IntentProperties proprietes, ReglageIntent reglage) {
        this(repli, proprietes.gemini(), reglage, RestClient.builder()
                .requestFactory(requestFactory(proprietes.gemini())));
    }

    /** Constructeur des tests : un builder nu, branche sur {@code MockRestServiceServer}. */
    GeminiIntentAnalyzer(
            IntentAnalyzer repli,
            IntentProperties.Gemini config,
            ReglageIntent reglage,
            RestClient.Builder builder) {
        this.repli = repli;
        this.config = config;
        this.reglage = reglage;
        this.client = new GeminiClient(config, builder);
    }

    private static ClientHttpRequestFactory requestFactory(IntentProperties.Gemini config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.connectTimeout());
        factory.setReadTimeout(config.readTimeout());
        return factory;
    }

    @Override
    public IntentAnalysis analyse(String message) {
        // Cle et interrupteur sont relus a chaque analyse : c'est ce qui fait qu'un
        // changement dans la console prend effet au lead suivant, sans redemarrage.
        String cle = reglage.cleEffective();
        if (!reglage.actif() || cle == null || cle.isBlank()
                || message == null || message.isBlank()) {
            return repli.analyse(message);
        }
        try {
            LeadIntent intention = interprete(client.classe(tronque(message), cle));
            if (intention == null) {
                log.warn("Gemini a repondu hors du vocabulaire attendu : repli sur les regles");
                return repli.analyse(message);
            }
            return new IntentAnalysis(intention, IntentSource.GEMINI);
        } catch (Exception echec) {
            // Volontairement large : aucune defaillance de l'analyse ne doit perdre un lead.
            log.warn("Analyse Gemini indisponible, repli sur les regles : {}", echec.getMessage());
            return repli.analyse(message);
        }
    }

    private String tronque(String message) {
        return message.length() > config.maxMessageChars()
                ? message.substring(0, config.maxMessageChars())
                : message;
    }

    /** @return {@code null} si la reponse n'appartient pas au vocabulaire ferme */
    private LeadIntent interprete(String texte) {
        String candidat = texte.trim().toUpperCase(Locale.ROOT);
        for (LeadIntent intention : LeadIntent.values()) {
            if (intention.name().equals(candidat)) {
                return intention;
            }
        }
        return null;
    }
}
