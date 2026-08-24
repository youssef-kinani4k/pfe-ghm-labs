package com.leadflow.qualification;

import com.leadflow.config.MonitoringProperties;
import com.leadflow.monitoring.deadletter.DeadLetterRepository;
import com.leadflow.monitoring.deadletter.DeadLetterStatus;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Filet de republication de {@code lead.qualified}, dette contractee par F3 et echue ici.
 *
 * <p>Il vit dans {@code qualification/} et non dans {@code monitoring/} : il publie sur le
 * pipeline, donc il appartient au package qui possede cette publication. L'observateur
 * n'ecrit rien et ne publie rien d'autre qu'un rejeu de mort.
 *
 * <p><b>Garde-fou.</b> Un lead qui porte deja une mort {@code PENDING} n'est pas republie :
 * un echec deja constate et presente a un humain n'est plus un echec transitoire. Sans cela,
 * un client sans commercial actif ferait boucler le filet toutes les 30 secondes en
 * fabriquant une ligne de journal a chaque tour. Le lead redevient eligible des que
 * l'operateur a rejoue ou ecarte la mort — meme distinction que F2 entre {@code FAILED} et
 * {@code DISCARDED}.
 *
 * <p><b>Mono-instance</b>, comme {@code PendingEventRelay}, et pour la meme raison.
 *
 * <p>Le couplage vers une table de {@code monitoring/} est a contre-sens de la regle qui
 * veut que l'observateur ne soit lu par personne ; il est assume. L'alternative — un
 * marqueur d'echec definitif sur la ligne {@code lead} — demanderait une colonne de plus et
 * une transition d'etat que personne n'ecrit aujourd'hui.
 */
@Component
public class QualifiedLeadRelay {

    private static final Logger log = LoggerFactory.getLogger(QualifiedLeadRelay.class);

    private final LeadRepository leads;
    private final DeadLetterRepository morts;
    private final QualifiedLeadPublisher publieur;
    private final MonitoringProperties properties;

    public QualifiedLeadRelay(
            LeadRepository leads,
            DeadLetterRepository morts,
            QualifiedLeadPublisher publieur,
            MonitoringProperties properties) {
        this.leads = leads;
        this.morts = morts;
        this.publieur = publieur;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${leadflow.monitoring.relay.interval}")
    public void republieLesNonRoutes() {
        Instant limite = Instant.now().minus(properties.relay().qualifiedAfter());
        List<Lead> candidats =
                leads.findByStatusAndCreatedAtBefore(LeadStatus.QUALIFIED, limite);

        for (Lead lead : candidats) {
            if (morts.existsByLeadIdAndStatus(lead.getId(), DeadLetterStatus.PENDING)) {
                continue;
            }
            try {
                publieur.publie(lead);
                log.info("Lead qualifie {} republie par le filet", lead.getId());
            } catch (RuntimeException echec) {
                // Un lead empoisonne n'emporte pas le lot : le filet s'active precisement
                // quand l'environnement va mal.
                log.warn("Republication du lead {} en echec, on continue", lead.getId(), echec);
            }
        }
    }
}
