package com.leadflow.capture;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Insere l'evenement brut dans sa <b>propre</b> transaction.
 *
 * <p>C'est ce qui rend l'idempotence utilisable : la violation de
 * {@code uk_raw_lead_event_client_signature} marque la transaction courante rollback-only,
 * et l'appelant ne pourrait donc pas la rattraper s'il partageait la sienne. Isolee ici,
 * seule cette insertion est annulee, et {@code LeadCaptureService} peut relire la ligne
 * gagnante et repondre {@code 202} comme pour un rejeu ordinaire.
 *
 * <p>Bean distinct et non methode privee : Spring ne proxie pas l'auto-invocation, la
 * propagation serait silencieusement ignoree.
 */
@Component
public class RawLeadEventWriter {

    private final RawLeadEventRepository rawLeadEventRepository;

    public RawLeadEventWriter(RawLeadEventRepository rawLeadEventRepository) {
        this.rawLeadEventRepository = rawLeadEventRepository;
    }

    /**
     * @throws org.springframework.dao.DataIntegrityViolationException si un evenement de
     *     meme {@code (client_id, signature)} existe deja.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RawLeadEvent insere(RawLeadEvent evenement) {
        return rawLeadEventRepository.saveAndFlush(evenement);
    }
}
