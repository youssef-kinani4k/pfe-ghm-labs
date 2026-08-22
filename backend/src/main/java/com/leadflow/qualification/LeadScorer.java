package com.leadflow.qualification;

import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Bareme additif borne : chaque critere satisfait apporte ses points, et le total est
 * ramene dans {@code [0, 100]}.
 *
 * <p>Le bornage n'est pas defensif : il rend le score comparable entre deux clients dont
 * les baremes different, ce dont le dashboard de F6 aura besoin.
 */
@Component
public class LeadScorer {

    private static final int MINIMUM = 0;
    private static final int MAXIMUM = 100;

    public int score(ContactNormalise contact, LeadIntent intention, Map<String, Object> document) {
        ScoringConfig bareme = ScoringConfig.depuis(document);
        int total = 0;

        if (contact.phone() != null) {
            total += bareme.telephonePresent();
        }
        if (contact.companyName() != null) {
            total += bareme.societePresente();
        }
        if (contact.lastName() != null || contact.firstName() != null) {
            total += bareme.nomPresent();
        }
        if (contact.message() != null) {
            total += bareme.messagePresent();
        }
        total += bareme.intention().getOrDefault(intention, 0);
        if (estCible(contact, bareme)) {
            total += bareme.bonusCible();
        }

        return Math.max(MINIMUM, Math.min(MAXIMUM, total));
    }

    /**
     * Le bonus ne se cumule pas : un lead du bon secteur <i>et</i> du bon pays reste un seul
     * lead cible, pas deux fois meilleur.
     */
    private boolean estCible(ContactNormalise contact, ScoringConfig bareme) {
        boolean secteur = contact.sector() != null
                && bareme.secteursCibles().contains(contact.sector().toLowerCase(Locale.ROOT));
        boolean pays = contact.countryCode() != null
                && bareme.paysCibles().contains(contact.countryCode());
        return secteur || pays;
    }
}
