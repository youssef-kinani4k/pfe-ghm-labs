package com.leadflow.qualification;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Insere le lead qualifie dans sa <b>propre</b> transaction.
 *
 * <p>Meme raison qu'en F2 pour {@code RawLeadEventWriter} : la violation de la contrainte
 * unique sur {@code raw_event_id} marque la transaction courante rollback-only, et
 * l'appelant ne pourrait donc pas la rattraper s'il partageait la sienne. Isolee ici, seule
 * cette insertion est annulee, et {@code LeadQualificationService} peut relire la ligne
 * gagnante et acquitter le message comme pour un rejeu ordinaire.
 *
 * <p>Bean distinct et non methode privee : Spring ne proxie pas l'auto-invocation, la
 * propagation serait silencieusement ignoree.
 *
 * <p>Effet de bord utile : quand cette methode rend la main, le lead est <b>commite</b>.
 * C'est ce qui permet a l'orchestrateur de publier vers le broker par un appel direct,
 * sans passer par {@code @TransactionalEventListener}.
 */
@Component
public class LeadWriter {

    private final LeadRepository leadRepository;

    public LeadWriter(LeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }

    /**
     * @throws org.springframework.dao.DataIntegrityViolationException si un lead existe deja
     *     pour ce {@code raw_event_id}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Lead insere(Lead lead) {
        return leadRepository.saveAndFlush(lead);
    }
}
