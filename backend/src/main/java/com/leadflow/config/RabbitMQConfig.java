package com.leadflow.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
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
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    Binding leadsBinding(Queue leadsQueue, DirectExchange leadsExchange) {
        return BindingBuilder.bind(leadsQueue).to(leadsExchange).with(LEADS_ROUTING_KEY);
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DLQ_ROUTING_KEY);
    }

    @Bean
    MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }
}
