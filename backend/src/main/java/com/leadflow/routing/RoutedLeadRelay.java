package com.leadflow.routing;

import com.leadflow.config.MonitoringProperties;
import com.leadflow.monitoring.deadletter.DeadLetterRepository;
import com.leadflow.monitoring.deadletter.DeadLetterStatus;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Filet de republication de {@code lead.routed}, jumeau de
 * {@code QualifiedLeadRelay} et dette contractee par F4.
 *
 * <p>Il vit dans {@code routing/} et non dans {@code monitoring/} : il publie sur le
 * pipeline, donc il appartient au package qui possede cette publication.
 *
 * <p><b>Garde-fou.</b> Un lead qui porte deja une mort {@code PENDING} n'est pas republie :
 * un echec deja constate et presente a un humain n'est plus un echec transitoire. Sans cela,
 * un ERP durablement injoignable ferait boucler le filet a chaque tour de balayage en
 * fabriquant une ligne de journal a chaque fois. Le lead redevient eligible des que
 * l'operateur a rejoue ou ecarte la mort.
 *
 * <p><b>Mono-instance</b>, comme {@code PendingEventRelay}, et pour la meme raison.
 *
 * <p>Le couplage vers une table de {@code monitoring/} est assume, pour les memes raisons
 * qu'en {@code qualification/}.
 */
@Component
public class RoutedLeadRelay {

    private static final Logger log = LoggerFactory.getLogger(RoutedLeadRelay.class);

    private final LeadRepository leads;
    private final DeadLetterRepository morts;
    private final RoutedLeadPublisher publieur;
    private final MonitoringProperties properties;

    public RoutedLeadRelay(
            LeadRepository leads,
            DeadLetterRepository morts,
            RoutedLeadPublisher publieur,
            MonitoringProperties properties) {
        this.leads = leads;
        this.morts = morts;
        this.publieur = publieur;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${leadflow.monitoring.relay.interval}")
    public void republieLesNonSynchronises() {
        Instant limite = Instant.now().minus(properties.relay().routedAfter());
        List<Lead> candidats = leads.findByStatusAndCreatedAtBefore(LeadStatus.ROUTED, limite);

        for (Lead lead : candidats) {
            if (morts.existsByLeadIdAndStatus(lead.getId(), DeadLetterStatus.PENDING)) {
                continue;
            }
            try {
                publieur.publie(lead);
                log.info("Lead route {} republie par le filet", lead.getId());
            } catch (RuntimeException echec) {
                // Un lead empoisonne n'emporte pas le lot : le filet s'active precisement
                // quand l'environnement va mal.
                log.warn("Republication du lead {} en echec, on continue", lead.getId(), echec);
            }
        }
    }
}
