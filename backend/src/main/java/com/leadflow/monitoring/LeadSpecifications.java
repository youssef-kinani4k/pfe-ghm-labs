package com.leadflow.monitoring;

import com.leadflow.qualification.Lead;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Un predicat par filtre present, et rien pour les filtres absents.
 *
 * <p>Le « rien » est la regle qui compte : un filtre non renseigne ne doit produire aucune
 * clause, sans quoi une recherche vide deviendrait un {@code like '%%'} sur toute la table.
 */
final class LeadSpecifications {

    private LeadSpecifications() {}

    static Specification<Lead> depuis(LeadFilter filtre) {
        return (racine, requete, constructeur) -> {
            List<Predicate> predicats = new ArrayList<>();

            if (filtre.clientId() != null) {
                predicats.add(constructeur.equal(racine.get("clientId"), filtre.clientId()));
            }
            if (filtre.statuts() != null && !filtre.statuts().isEmpty()) {
                predicats.add(racine.get("status").in(filtre.statuts()));
            }
            if (filtre.intent() != null) {
                predicats.add(constructeur.equal(racine.get("detectedIntent"), filtre.intent()));
            }
            if (filtre.intentSource() != null) {
                predicats.add(
                        constructeur.equal(racine.get("intentSource"), filtre.intentSource()));
            }
            if (filtre.salesRepId() != null) {
                predicats.add(
                        constructeur.equal(racine.get("assignedSalesRepId"), filtre.salesRepId()));
            }
            if (filtre.minScore() != null) {
                predicats.add(
                        constructeur.greaterThanOrEqualTo(racine.get("score"), filtre.minScore()));
            }
            if (filtre.from() != null) {
                predicats.add(
                        constructeur.greaterThanOrEqualTo(racine.get("createdAt"), filtre.from()));
            }
            if (filtre.to() != null) {
                // Borne haute exclue : deux plages consecutives ne comptent jamais deux fois
                // la meme ligne.
                predicats.add(constructeur.lessThan(racine.get("createdAt"), filtre.to()));
            }
            if (filtre.recherche() != null && !filtre.recherche().isBlank()) {
                String motif = "%" + filtre.recherche().toLowerCase() + "%";
                predicats.add(constructeur.or(
                        constructeur.like(constructeur.lower(racine.get("email")), motif),
                        constructeur.like(
                                constructeur.lower(racine.get("companyName")), motif)));
            }
            return constructeur.and(predicats.toArray(Predicate[]::new));
        };
    }
}
