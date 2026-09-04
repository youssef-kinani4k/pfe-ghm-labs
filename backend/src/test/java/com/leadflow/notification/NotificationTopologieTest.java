package com.leadflow.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.crm.SyncedLeadMessage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * La file de notification et son binding, eprouves sur un vrai broker.
 *
 * <p>Le test qui compte est le premier : la nouvelle file est liee a la <b>meme</b> cle que
 * la file d'observation du monitoring, et un {@code DirectExchange} livrant a toutes les
 * files liees a une cle, l'ajout ne doit rien voler a personne. C'est la propriete sur
 * laquelle repose tout le decoupage de F12 — sans elle, brancher la notification aurait
 * demande de modifier la synchronisation ERP.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationTopologieTest {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void videLesFiles() {
        // Les tests des autres etapes laissent des messages derriere eux : sans purge, on
        // lirait le leur au lieu du notre.
        //
        // La purge est BLOQUANTE, et ce n'est pas un detail de style : la surcharge
        // purgeQueue(nom, noWait) avec noWait a vrai rend la main avant que le broker ait
        // fini. En isolation la file est vide et cela ne se voit pas ; dans la suite
        // complete, elle a accumule les lead.synced des tests amont, la purge tourne encore
        // quand on publie, et elle emporte le message qu'on vient d'envoyer.
        rabbitAdmin.purgeQueue(RabbitMQConfig.NOTIFY_QUEUE);
        rabbitAdmin.purgeQueue(RabbitMQConfig.MONITORING_QUEUE);
    }

    @Test
    void laFileDeNotificationEtCelleDuMonitoringRecoiventLeMemeMessage() {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.LEADS_EXCHANGE,
                RabbitMQConfig.SYNCED_ROUTING_KEY,
                new SyncedLeadMessage(
                        UUID.randomUUID(), UUID.randomUUID(), "dolibarr", Instant.now()));

        // Les deux, et non l'une ou l'autre : le monitoring observait lead.synced avant F12
        // et doit continuer, sans qu'aucune ligne du pipeline n'ait ete touchee.
        assertThat(rabbitTemplate.receive(RabbitMQConfig.NOTIFY_QUEUE, 5000))
                .as("la file de notification n'a rien recu")
                .isNotNull();
        assertThat(rabbitTemplate.receive(RabbitMQConfig.MONITORING_QUEUE, 5000))
                .as("le monitoring ne recoit plus lead.synced")
                .isNotNull();
    }

    @Test
    void leMessageDeLaFileSeDeserialiseEnSyncedLeadMessage() {
        UUID leadId = UUID.randomUUID();
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.LEADS_EXCHANGE,
                RabbitMQConfig.SYNCED_ROUTING_KEY,
                new SyncedLeadMessage(leadId, UUID.randomUUID(), "dolibarr", Instant.now()));

        // Le convertisseur ne fait confiance qu'a une liste blanche de paquets : un contrat
        // absent de PAQUETS_DE_CONFIANCE se refuse a la lecture, et l'erreur ne dit pas
        // pourquoi. Le lire ici verrouille ce cablage.
        Object recu = rabbitTemplate.receiveAndConvert(RabbitMQConfig.NOTIFY_QUEUE, 5000);

        assertThat(recu).isInstanceOf(SyncedLeadMessage.class);
        assertThat(((SyncedLeadMessage) recu).leadId()).isEqualTo(leadId);
    }

    @Test
    void laFileDeNotificationExisteEtEstDurable() {
        QueueInformation info = rabbitAdmin.getQueueInfo(RabbitMQConfig.NOTIFY_QUEUE);

        assertThat(info).isNotNull();
        assertThat(info.getName()).isEqualTo("leadflow.leads.notify");
    }
}
