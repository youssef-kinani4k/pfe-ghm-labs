package com.leadflow.qualification;

import com.leadflow.capture.CapturedLeadMessage;
import com.leadflow.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Traduit le protocole AMQP, et rien d'autre.
 *
 * <p>Classe distincte de {@link LeadQualificationService} a dessein : le metier ne connait
 * pas RabbitMQ, ce qui permet de tester toute la qualification sans broker.
 *
 * <p>Le message ne porte qu'une reference : la base reste l'unique source de verite et le
 * service relit {@code raw_lead_event.payload}. Un rejeu depuis la DLQ travaille donc
 * forcement sur la donnee a jour.
 *
 * <p>Le bean est conditionnel plutot que simplement arrete par
 * {@code spring.rabbitmq.listener.simple.auto-startup} : depuis Spring Framework 6.2, le
 * cache de contextes de test met un contexte en pause puis le redemarre, et
 * {@code ApplicationContext.start()} demarre tous les beans {@code Lifecycle} sans regarder
 * {@code autoStartup}. Un consommateur ainsi ressuscite volerait aux tests de la couche
 * capture le message qu'ils viennent de publier. Un bean absent, lui, ne peut pas redemarrer.
 *
 * <p>Aucune exception n'est rattrapee ici : ce qui remonte du service est infrastructurel
 * — base ou broker injoignable — et doit provoquer les trois tentatives puis la DLQ. Les
 * echecs deterministes, eux, sont absorbes par le service qui rend un {@code Optional} vide.
 */
@Component
@ConditionalOnProperty(name = "leadflow.qualification.listener.enabled", matchIfMissing = true)
public class LeadQualificationListener {

    private final LeadQualificationService service;

    public LeadQualificationListener(LeadQualificationService service) {
        this.service = service;
    }

    @RabbitListener(queues = RabbitMQConfig.LEADS_QUEUE)
    public void recoit(CapturedLeadMessage message) {
        service.qualifie(message.eventId());
    }
}
