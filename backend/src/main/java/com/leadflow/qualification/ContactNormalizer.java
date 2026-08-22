package com.leadflow.qualification;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Nettoie et valide l'identite du prospect.
 *
 * <p><b>Seul l'email peut faire echouer la qualification.</b> Un telephone illisible met le
 * champ a {@code null}, il ne rejette pas le lead : c'est le seul comportement coherent avec
 * l'invariant de capture, qui ne valide ni email ni telephone.
 *
 * <p>La troncature n'est pas cosmetique. Sans elle, un formulaire mal borne envoyant 300
 * caracteres dans {@code first_name VARCHAR(80)} provoquerait une erreur Postgres, donc
 * trois tentatives puis un passage en DLQ, pour une donnee parfaitement exploitable.
 */
@Component
public class ContactNormalizer {

    /** Volontairement souple : valider un email par expression reguliere stricte est vain. */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PAYS = Pattern.compile("^[A-Za-z]{2}$");
    private static final Pattern ESPACES = Pattern.compile("\\s+");

    private static final int EMAIL_MAX = 255;
    private static final int TELEPHONE_MAX = 32;
    private static final int NOM_MAX = 80;
    private static final int SOCIETE_MAX = 160;
    private static final int TELEPHONE_CHIFFRES_MIN = 6;

    /** @return vide si aucun email exploitable : l'appelant n'ecrira alors aucun lead. */
    public Optional<ContactNormalise> normalise(ChampsBruts bruts) {
        String email = email(bruts.email());
        if (email == null) {
            return Optional.empty();
        }
        return Optional.of(new ContactNormalise(
                email,
                telephone(bruts.phone()),
                message(bruts.message()),
                texte(bruts.companyName(), SOCIETE_MAX),
                texte(bruts.firstName(), NOM_MAX),
                texte(bruts.lastName(), NOM_MAX),
                pays(bruts.countryCode()),
                texte(bruts.sector(), NOM_MAX)));
    }

    private String email(String brut) {
        if (brut == null) {
            return null;
        }
        String candidat = brut.trim().toLowerCase(Locale.ROOT);
        if (candidat.length() > EMAIL_MAX || !EMAIL.matcher(candidat).matches()) {
            return null;
        }
        return candidat;
    }

    private String telephone(String brut) {
        if (brut == null) {
            return null;
        }
        boolean international = brut.trim().startsWith("+");
        String chiffres = brut.replaceAll("[^0-9]", "");
        if (!international && chiffres.startsWith("00")) {
            international = true;
            chiffres = chiffres.substring(2);
        }
        if (chiffres.length() < TELEPHONE_CHIFFRES_MIN) {
            return null;
        }
        String normalise = (international ? "+" : "") + chiffres;
        return normalise.length() > TELEPHONE_MAX
                ? normalise.substring(0, TELEPHONE_MAX)
                : normalise;
    }

    private String pays(String brut) {
        if (brut == null || !PAYS.matcher(brut.trim()).matches()) {
            return null;
        }
        return brut.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Le message est le seul champ libre : il part tel quel vers l'analyseur d'intention et
     * vers l'ERP, donc seuls ses bords sont rognes. Ecraser ses sauts de ligne comme le fait
     * {@link #texte} aplatirait irreversiblement un texte en plusieurs paragraphes.
     */
    private String message(String brut) {
        if (brut == null) {
            return null;
        }
        String propre = brut.trim();
        return propre.isEmpty() ? null : propre;
    }

    private String texte(String brut, int maximum) {
        if (brut == null) {
            return null;
        }
        String propre = ESPACES.matcher(brut.trim()).replaceAll(" ");
        if (propre.isEmpty()) {
            return null;
        }
        return propre.length() > maximum ? propre.substring(0, maximum) : propre;
    }
}
