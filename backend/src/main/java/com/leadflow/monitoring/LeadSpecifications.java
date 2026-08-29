package com.leadflow.monitoring;

import com.leadflow.qualification.Lead;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Un predicat par filtre present, et rien pour les filtres absents.
 *
 * <p>Le « rien » est la regle qui compte : un filtre non renseigne ne doit produire aucune
 * clause, sans quoi une recherche vide deviendrait un {@code like '%%'} sur toute la table.
 */
final class LeadSpecifications {

    private LeadSpecifications() {}

    /**
     * @param seuils seuil de chaleur par boutique, deja resolu par l'appelant. Le passer en
     *     argument plutot que d'aller le chercher ici garde cette classe sans dependance : une
     *     {@code Specification} qui lirait une autre table serait un aller-retour cache au
     *     milieu d'une requete.
     */
    static Specification<Lead> depuis(LeadFilter filtre, Map<UUID, Integer> seuils) {
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
            // Chaque boutique a son seuil : le predicat est une disjonction de couples
            // (boutique, seuil). Une clause SQL sur scoring_config n'est pas possible ici —
            // Lead ne porte pas d'association vers Client, et le document est chiffre au
            // repos, donc illisible par Postgres.
            if (Boolean.TRUE.equals(filtre.chaud())) {
                if (seuils.isEmpty()) {
                    // Aucune boutique connue : aucun lead ne peut etre chaud. Sans ce cas,
                    // le `or` vide vaudrait `false` chez Hibernate mais la lecture du code
                    // laisserait croire l'inverse.
                    return constructeur.disjunction();
                }
                List<Predicate> parBoutique = seuils.entrySet().stream()
                        .map(entree -> constructeur.and(
                                constructeur.equal(racine.get("clientId"), entree.getKey()),
                                constructeur.greaterThanOrEqualTo(
                                        racine.get("score"), entree.getValue())))
                        .toList();
                predicats.add(constructeur.or(parBoutique.toArray(Predicate[]::new)));
            }
            return constructeur.and(predicats.toArray(Predicate[]::new));
        };
    }
}
