package com.leadflow.qualification;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Traduit le payload JSON libre du webhook vers les champs du pivot.
 *
 * <p>Les cles sont normalisees avant comparaison — minuscules, accents retires, separateurs
 * supprimes — si bien que {@code Adresse-Email}, {@code adresse_email} et
 * {@code ADRESSE EMAIL} tombent sur le meme alias. Sans cette normalisation, la table
 * devrait enumerer chaque variante typographique et en oublierait toujours une.
 *
 * <p><b>Deux limites assumees.</b> Le mapping ne descend pas dans le JSON : un objet ou un
 * tableau imbrique est ignore, seul le premier niveau est lu. Et le premier alias trouve
 * dans l'ordre declare gagne, donc un payload portant a la fois {@code email} et
 * {@code mail} retient {@code email}.
 *
 * <p>{@code source} n'est volontairement pas mappe : il est deja porte par
 * {@code raw_lead_event.source}.
 */
@Component
public class PayloadFieldMapper {

    private static final List<String> EMAIL =
            List.of("email", "mail", "courriel", "adresseemail", "emailaddress");
    private static final List<String> PHONE =
            List.of("telephone", "phone", "tel", "mobile", "gsm", "numero");
    private static final List<String> MESSAGE =
            List.of("message", "commentaire", "demande", "besoin", "comment", "body");
    private static final List<String> COMPANY =
            List.of("societe", "entreprise", "company", "raisonsociale", "organisation");
    private static final List<String> FIRSTNAME =
            List.of("prenom", "firstname", "givenname");
    private static final List<String> LASTNAME =
            List.of("nom", "lastname", "nomfamille", "surname");
    private static final List<String> COUNTRY =
            List.of("pays", "country", "countrycode");
    private static final List<String> SECTOR =
            List.of("secteur", "sector", "industrie", "activite");

    public ChampsBruts extrait(Map<String, Object> payload) {
        Map<String, String> scalaires = scalairesParCleNormalisee(payload);
        return new ChampsBruts(
                premier(scalaires, EMAIL),
                premier(scalaires, PHONE),
                premier(scalaires, MESSAGE),
                premier(scalaires, COMPANY),
                premier(scalaires, FIRSTNAME),
                premier(scalaires, LASTNAME),
                premier(scalaires, COUNTRY),
                premier(scalaires, SECTOR));
    }

    /**
     * Deux cles distinctes peuvent se normaliser en la meme : {@code e-mail} et
     * {@code email} donnent tous deux {@code email}. La premiere rencontree gagne, ce qui
     * rend le resultat stable pour un payload donne.
     */
    private Map<String, String> scalairesParCleNormalisee(Map<String, Object> payload) {
        Map<String, String> scalaires = new LinkedHashMap<>();
        if (payload == null) {
            return scalaires;
        }
        for (Map.Entry<String, Object> entree : payload.entrySet()) {
            if (entree.getKey() == null || !estScalaire(entree.getValue())) {
                continue;
            }
            scalaires.putIfAbsent(
                    normaliseCle(entree.getKey()), String.valueOf(entree.getValue()));
        }
        return scalaires;
    }

    /** Un objet ou un tableau n'a pas de traduction evidente vers un champ texte. */
    private boolean estScalaire(Object valeur) {
        return valeur instanceof String || valeur instanceof Number || valeur instanceof Boolean;
    }

    private String normaliseCle(String cle) {
        String sansAccents = Normalizer.normalize(cle, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return sansAccents.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String premier(Map<String, String> scalaires, List<String> alias) {
        for (String candidat : alias) {
            String valeur = scalaires.get(candidat);
            if (valeur != null && !valeur.isBlank()) {
                return valeur;
            }
        }
        return null;
    }
}
