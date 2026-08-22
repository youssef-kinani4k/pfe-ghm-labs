package com.leadflow.qualification;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Analyseur lexical : c'est le mode degrade du pipeline.
 *
 * <p>Il ne sera jamais aussi juste que Gemini, et ce n'est pas ce qu'on lui demande. Ce
 * qu'on lui demande, c'est de ne jamais echouer — d'ou l'absence totale d'entree/sortie,
 * de reseau et d'etat. C'est la condition pour qu'il serve de repli.
 *
 * <p>Le texte est decoupe sur tout ce qui n'est pas une lettre ou un chiffre, puis compare
 * mot a mot : un lexique compare par sous-chaine ferait correspondre {@code prix} dans
 * {@code prixe}.
 */
@Component
public class RuleBasedIntentAnalyzer implements IntentAnalyzer {

    /**
     * Ordre d'insertion signifiant : a egalite de correspondances, aucune intention ne
     * gagne et le resultat est {@code AUTRE}. La carte est ordonnee pour que le parcours
     * soit reproductible d'une execution a l'autre.
     */
    private static final Map<LeadIntent, Set<String>> LEXIQUE = new LinkedHashMap<>();

    static {
        LEXIQUE.put(LeadIntent.DEVIS, Set.of(
                "devis", "tarif", "tarifs", "prix", "combien", "cout", "couts", "coute",
                "coutent", "estimation", "budget", "chiffrage", "quotation"));
        LEXIQUE.put(LeadIntent.ACHAT, Set.of(
                "commander", "commande", "commandes", "acheter", "achat", "livraison",
                "livrer", "payer", "paiement", "order"));
        LEXIQUE.put(LeadIntent.INFORMATION, Set.of(
                "information", "informations", "renseignement", "renseignements",
                "documentation", "catalogue", "brochure", "savoir", "presentation"));
        LEXIQUE.put(LeadIntent.SUPPORT, Set.of(
                "probleme", "panne", "bug", "reclamation", "sav", "assistance",
                "depannage", "erreur", "defectueux"));
    }

    @Override
    public IntentAnalysis analyse(String message) {
        return new IntentAnalysis(intention(message), IntentSource.RULES);
    }

    private LeadIntent intention(String message) {
        if (message == null || message.isBlank()) {
            return LeadIntent.AUTRE;
        }
        Set<String> mots = mots(message);
        LeadIntent meilleure = LeadIntent.AUTRE;
        int meilleurScore = 0;
        boolean egalite = false;

        for (Map.Entry<LeadIntent, Set<String>> entree : LEXIQUE.entrySet()) {
            int score = 0;
            for (String mot : entree.getValue()) {
                if (mots.contains(mot)) {
                    score++;
                }
            }
            if (score > meilleurScore) {
                meilleurScore = score;
                meilleure = entree.getKey();
                egalite = false;
            } else if (score == meilleurScore && score > 0) {
                egalite = true;
            }
        }
        return (meilleurScore == 0 || egalite) ? LeadIntent.AUTRE : meilleure;
    }

    private Set<String> mots(String message) {
        String sansAccents = Normalizer.normalize(message, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String[] decoupe = sansAccents.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        return new HashSet<>(Arrays.asList(decoupe));
    }
}
