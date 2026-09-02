package com.leadflow.routing;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ecrit une ligne du journal d'actions, en transaction propre.
 *
 * <p>Classe distincte pour la meme raison que {@code DeadLetterJournal} et
 * {@code LeadWriter} : l'appelant n'est pas transactionnel, et un {@code REQUIRES_NEW} rend
 * la frontiere explicite — quand cette methode rend la main, la ligne est commitee.
 */
@Component
public class LeadActionJournal {

    private final LeadActionRepository repository;

    public LeadActionJournal(LeadActionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LeadAction enregistre(LeadAction action) {
        return repository.saveAndFlush(action);
    }
}
