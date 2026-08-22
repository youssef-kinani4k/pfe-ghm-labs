package com.leadflow.qualification;

import com.leadflow.config.QualificationProperties;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deduplication metier : meme client, meme email, dans une fenetre temporelle.
 *
 * <p>Sert l'index {@code idx_lead_client_email_created} pose par la migration V2. La
 * comparaison porte sur l'email <b>normalise</b>, ce qui impose l'ordre des etapes du
 * service : {@code Karim@ACME.test} et {@code karim@acme.test } sont le meme prospect mais
 * deux chaines differentes.
 *
 * <p>Distincte de l'idempotence sur {@code raw_event_id} : celle-ci empeche de traiter deux
 * fois le meme evenement, celle-la empeche de traiter deux evenements distincts decrivant
 * le meme prospect.
 */
@Component
public class DuplicateGuard {

    private final LeadRepository leadRepository;
    private final QualificationProperties proprietes;

    public DuplicateGuard(LeadRepository leadRepository, QualificationProperties proprietes) {
        this.leadRepository = leadRepository;
        this.proprietes = proprietes;
    }

    public boolean estDoublon(UUID clientId, String email) {
        Instant depuis = Instant.now().minus(proprietes.dedupWindow());
        return leadRepository.existsByClientIdAndEmailAndCreatedAtAfter(clientId, email, depuis);
    }
}
