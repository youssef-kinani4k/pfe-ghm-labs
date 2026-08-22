package com.leadflow.routing;

import com.leadflow.config.RabbitMQConfig;
import com.leadflow.qualification.QualifiedLeadMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Traduit le protocole AMQP, et rien d'autre.
 *
 * <p>Classe distincte de {@link LeadRoutingService} a dessein : le metier ne connait pas
 * RabbitMQ, ce qui permet de tester tout le routage sans broker.
 *
 * <p>Le bean est conditionnel plutot que simplement arrete par
 * {@code spring.rabbitmq.listener.simple.auto-startup} : le cache de contextes de test met
 * un contexte en pause puis le redemarre, et {@code ApplicationContext.start()} demarre tous
 * les beans {@code Lifecycle} sans regarder {@code autoStartup}. Un consommateur ainsi
 * ressuscite volerait aux tests de la qualification le message qu'ils viennent de publier.
 *
 * <p>Aucune exception n'est rattrapee ici. {@code AssignmentException} doit provoquer les
 * trois tentatives puis la DLQ : un humain peut activer un commercial et rejouer. Les echecs
 * deterministes, eux, sont absorbes par le service qui rend un {@code Optional} vide.
 */
@Component
@ConditionalOnProperty(name = "leadflow.routing.listener.enabled", matchIfMissing = true)
public class LeadRoutingListener {

    private final LeadRoutingService service;

    public LeadRoutingListener(LeadRoutingService service) {
        this.service = service;
    }

    @RabbitListener(queues = RabbitMQConfig.QUALIFIED_QUEUE)
    public void recoit(QualifiedLeadMessage message) {
        service.route(message.leadId());
    }
}
