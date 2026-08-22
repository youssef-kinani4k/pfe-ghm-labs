package com.leadflow.capture;

import com.leadflow.config.WebhookProperties;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Filet, pas chemin normal. Reprend ce que la publication apres commit n'a pas reussi a
 * envoyer : broker indisponible, ou processus tue entre le commit et la publication.
 *
 * <p>Ne reprend que les lignes plus vieilles que {@code relay-after}, delai volontairement
 * plus long qu'une publication normale, pour ne jamais doubler un envoi en cours.
 *
 * <p><b>Mono-instance.</b> Deux instances balaieraient les memes lignes et publieraient
 * deux fois. Le projet est deploye en une seule instance ; le jour ou ca change, la reponse
 * est un {@code SELECT ... FOR UPDATE SKIP LOCKED}, pas un verrou applicatif.
 */
@Component
public class PendingEventRelay {

    private static final Logger log = LoggerFactory.getLogger(PendingEventRelay.class);

    private final RawLeadEventRepository rawLeadEventRepository;
    private final LeadEventPublisher publieur;
    private final WebhookProperties properties;

    public PendingEventRelay(
            RawLeadEventRepository rawLeadEventRepository,
            LeadEventPublisher publieur,
            WebhookProperties properties) {
        this.rawLeadEventRepository = rawLeadEventRepository;
        this.publieur = publieur;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${leadflow.webhook.relay-interval}")
    public void republieLesEnAttente() {
        Instant limite = Instant.now().minus(properties.relayAfter());
        List<RawLeadEvent> enAttente = rawLeadEventRepository.findByStatusInAndReceivedAtBefore(
                List.of(RawLeadEventStatus.RECEIVED, RawLeadEventStatus.FAILED), limite);

        if (enAttente.isEmpty()) {
            return;
        }
        log.info("Republication de {} evenement(s) restes non publies", enAttente.size());

        for (RawLeadEvent evenement : enAttente) {
            // Appel a travers le proxy : c'est ce qui donne au publieur sa transaction
            // REQUIRES_NEW, exactement comme lorsqu'il est declenche par le listener.
            publieur.publie(new LeadCapturedEvent(
                    evenement.getId(),
                    evenement.getClientId(),
                    evenement.getSource(),
                    evenement.getReceivedAt()));
        }
    }
}
