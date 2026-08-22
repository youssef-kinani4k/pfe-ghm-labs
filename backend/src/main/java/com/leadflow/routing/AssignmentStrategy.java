package com.leadflow.routing;

import com.leadflow.qualification.Lead;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import java.util.Optional;

/**
 * Choix du commercial destinataire.
 *
 * <p>Une implementation par valeur de {@link AssignmentStrategyType}, chacune un
 * {@code @Component} decouvert par {@link AssignmentStrategyRegistry} : ajouter une
 * strategie, c'est ecrire une classe, jamais completer un {@code switch}.
 *
 * <p><b>Fonction pure.</b> {@code eligibles} arrive deja filtree sur les commerciaux actifs
 * du client et <b>ordonnee du moins recemment servi au plus recemment servi</b> par
 * {@link RotationOrder}. Une strategie ne lit donc jamais la base : elle filtre selon son
 * critere et prend la tete de ce qui reste. C'est ce qui rend le departage equitable dans
 * les trois strategies sans le reecrire trois fois.
 */
public interface AssignmentStrategy {

    AssignmentStrategyType type();

    /** @return vide si aucun commercial ne convient ; c'est a l'appelant de lever. */
    Optional<SalesRep> choisit(Lead lead, List<SalesRep> eligibles);
}
