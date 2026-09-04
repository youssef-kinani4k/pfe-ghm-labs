package com.leadflow.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.stream.Stream;

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

    /**
     * Sortie de la synchronisation ERP. <b>Deux files y sont liees</b> depuis F12 : celle du
     * monitoring, qui observe, et celle de la notification, qui previent le commercial. Un
     * DirectExchange livrant a toutes les files liees a une cle, la seconde n'a rien vole a
     * la premiere et le publieur n'a pas eu a changer.
     */
    public static final String SYNCED_ROUTING_KEY = "lead.synced";

    /**
     * Entree de la notification du commercial. Elle porte une DLX, contrairement a la file
     * d'observation : un relais injoignable est un echec reparable par un humain, qui merite
     * le journal des morts et un rejeu, la ou un echec d'affichage ne le merite pas.
     */
    public static final String NOTIFY_QUEUE = "leadflow.leads.notify";

    /**
     * File d'observation du monitoring. Distincte des files metier : un DirectExchange
     * livre a TOUTES les files liees a une cle, et la concurrence entre consommateurs ne
     * joue qu'au sein d'une meme file. Le monitoring observe donc sans qu'aucune ligne du
     * routage ou de la qualification ne bouge.
     */
    public static final String MONITORING_QUEUE = "leadflow.monitoring.events";

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
    Queue notifyQueue() {
        return QueueBuilder.durable(NOTIFY_QUEUE)
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

    /**
     * Liee a la meme cle que la file d'observation, et c'est tout l'interet : brancher la
     * notification n'a demande aucune modification de la synchronisation ERP ni de son
     * publieur.
     */
    @Bean
    Binding notifyBinding(Queue notifyQueue, DirectExchange leadsExchange) {
        return BindingBuilder.bind(notifyQueue).to(leadsExchange).with(SYNCED_ROUTING_KEY);
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
            {"com.leadflow.capture", "com.leadflow.qualification", "com.leadflow.routing",
                    "com.leadflow.crm", "com.leadflow.notification"};

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

    /**
     * Expose l'administration du broker sous son type concret. Spring Boot en declare bien
     * une, mais sous le type {@code AmqpAdmin}, qui ne porte pas {@code getQueueInfo} : le
     * monitoring lit la profondeur et le nombre de consommateurs par un {@code
     * queue.declare} passif, et a donc besoin du type concret.
     */
    @Bean
    RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    /**
     * <b>Pas de DLX.</b> Un echec d'affichage n'est pas un echec de lead et n'a rien a faire
     * dans le journal des morts.
     */
    @Bean
    Queue monitoringEventsQueue() {
        return QueueBuilder.durable(MONITORING_QUEUE).build();
    }

    /**
     * Un {@code Declarables} et non une {@code List<Binding>} : RabbitAdmin ne parcourt que
     * le premier type a la declaration, une liste nue serait ignoree en silence — et la
     * file d'observation resterait vide sans qu'aucune erreur ne le dise.
     */
    @Bean
    Declarables monitoringEventsBindings(
            Queue monitoringEventsQueue, DirectExchange leadsExchange) {
        return new Declarables(Stream.of(
                        LEADS_ROUTING_KEY,
                        QUALIFIED_ROUTING_KEY,
                        ROUTED_ROUTING_KEY,
                        SYNCED_ROUTING_KEY)
                .map(cle -> BindingBuilder.bind(monitoringEventsQueue).to(leadsExchange).with(cle))
                .toList());
    }
}
