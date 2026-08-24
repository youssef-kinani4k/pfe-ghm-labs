package com.leadflow.monitoring.deadletter;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ecriture du journal, en transaction propre.
 *
 * <p>Classe distincte du listener pour la meme raison que {@code LeadWriter} en F3 :
 * l'appelant n'est pas transactionnel, et le listener doit pouvoir distinguer « ecrit » de
 * « pas ecrit » pour decider d'acquitter ou de remettre en file. Un {@code REQUIRES_NEW}
 * rend cette frontiere explicite.
 */
@Component
public class DeadLetterJournal {

    private final DeadLetterRepository repository;

    public DeadLetterJournal(DeadLetterRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeadLetter enregistre(DeadLetter mort) {
        return repository.saveAndFlush(mort);
    }
}
