package com.leadflow.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologie du broker. Le webhook publie dans {@link #LEADS_QUEUE} et rend la main
 * immediatement ; la qualification et la synchronisation Dolibarr sont consommees
 * en asynchrone pour absorber les pics de trafic sans perdre de lead.
 */
@Configuration
public class RabbitMQConfig {

    public static final String LEADS_EXCHANGE = "leadflow.leads";
    public static final String LEADS_QUEUE = "leadflow.leads.captured";
    public static final String LEADS_ROUTING_KEY = "lead.captured";

    /** Sortie de la qualification, consommee par le routage (F4). */
    public static final String QUALIFIED_QUEUE = "leadflow.leads.qualified";
    public static final String QUALIFIED_ROUTING_KEY = "lead.qualified";

    /** Sortie du routage, consommee par la synchronisation ERP. */
    public static final String ROUTED_QUEUE = "leadflow.leads.routed";
    public static final String ROUTED_ROUTING_KEY = "lead.routed";

    public static final String DLX_EXCHANGE = "leadflow.leads.dlx";
    public static final String DLQ_QUEUE = "leadflow.leads.dlq";
    public static final String DLQ_ROUTING_KEY = "lead.dead";

    @Bean
    DirectExchange leadsExchange() {
        return new DirectExchange(LEADS_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    Queue leadsQueue() {
        return QueueBuilder.durable(LEADS_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue qualifiedLeadsQueue() {
        return QueueBuilder.durable(QUALIFIED_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue routedLeadsQueue() {
        return QueueBuilder.durable(ROUTED_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    Binding leadsBinding(Queue leadsQueue, DirectExchange leadsExchange) {
        return BindingBuilder.bind(leadsQueue).to(leadsExchange).with(LEADS_ROUTING_KEY);
    }

    @Bean
    Binding qualifiedLeadsBinding(Queue qualifiedLeadsQueue, DirectExchange leadsExchange) {
        return BindingBuilder.bind(qualifiedLeadsQueue)
                .to(leadsExchange)
                .with(QUALIFIED_ROUTING_KEY);
    }

    @Bean
    Binding routedLeadsBinding(Queue routedLeadsQueue, DirectExchange leadsExchange) {
        return BindingBuilder.bind(routedLeadsQueue)
                .to(leadsExchange)
                .with(ROUTED_ROUTING_KEY);
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DLQ_ROUTING_KEY);
    }

    /**
     * Paquets dont une classe peut etre instanciee a la lecture d'un message. Le type
     * voyage dans un en-tete du message : faire confiance a tout laisserait un producteur
     * choisir la classe a instancier chez nous. C'est donc une liste blanche, et la
     * correspondance est exacte — ni prefixe, ni joker. Une future feature qui ajoute un
     * contrat de file dans un autre paquet doit l'ajouter ici.
     */
    private static final String[] PAQUETS_DE_CONFIANCE =
            {"com.leadflow.capture", "com.leadflow.qualification", "com.leadflow.routing"};

    /**
     * Le convertisseur ne fait confiance qu'a {@code java.util} et {@code java.lang} par
     * defaut : nos propres contrats de file seraient refuses a la deserialisation, cote
     * consommateur comme cote test.
     */
    @Bean
    MessageConverter jsonMessageConverter() {
        JacksonJsonMessageConverter converteur = new JacksonJsonMessageConverter();
        DefaultJacksonJavaTypeMapper typeMapper = new DefaultJacksonJavaTypeMapper();
        typeMapper.setTrustedPackages(PAQUETS_DE_CONFIANCE);
        converteur.setJavaTypeMapper(typeMapper);
        return converteur;
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }

    /**
     * Remplace le rejet par defaut a l'epuisement des trois tentatives : Spring AMQP
     * republie lui-meme le message vers la DLX en ajoutant {@code x-exception-message},
     * {@code x-exception-stacktrace}, {@code x-original-exchange} et
     * {@code x-original-routingKey}.
     *
     * <p>Deux gains, tous deux indispensables a l'ecran : la <b>cause</b> de l'echec, que
     * l'en-tete {@code x-death} pose par le broker ne contient pas — il ne dit que
     * « rejected » — et la <b>cle de routage d'origine explicite</b>, celle dont le rejeu a
     * besoin, au lieu d'etre deduite de {@code x-death}.
     *
     * <p>S'applique identiquement aux trois etapes du pipeline, sans changer une ligne de
     * leur code.
     */
    @Bean
    MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate) {
        return new RepublishMessageRecoverer(rabbitTemplate, DLX_EXCHANGE, DLQ_ROUTING_KEY);
    }

    /**
     * Fabrique dediee au consommateur de la DLQ. Elle differe de la fabrique par defaut sur
     * un point : {@code defaultRequeueRejected = true}.
     *
     * <p>La DLQ n'a elle-meme aucune DLX. Acquitter un message qu'on n'a pas su journaliser
     * — Postgres indisponible — le perdrait definitivement ; le remettre en file le fera
     * reprendre quand la base reviendra. Le risque de boucle chaude est assume : si Postgres
     * est a terre, l'application entiere l'est.
     */
    @Bean
    SimpleRabbitListenerContainerFactory deadLetterListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory fabrique = new SimpleRabbitListenerContainerFactory();
        fabrique.setConnectionFactory(connectionFactory);
        fabrique.setDefaultRequeueRejected(true);
        // Un seul consommateur : le journal n'est pas un goulot, et la sequence des morts
        // reste lisible.
        fabrique.setConcurrentConsumers(1);
        fabrique.setMaxConcurrentConsumers(1);
        return fabrique;
    }
}
