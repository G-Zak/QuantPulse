package com.quantpulse.marketdata.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantpulse.common.event.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ setup, written out explicitly.
 *
 * This service declares the exchange and the dead-letter exchange.
 * Each consumer declares its own queues and bindings.
 */
@Configuration
public class RabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

    /**
     * Topic exchange: price.tick.# gets all instruments, price.tick.ATW only one.
     * A direct exchange would need one binding per instrument (81), and fanout would send
     * every event to everyone.
     */
    @Bean
    public TopicExchange marketExchange() {
        return new TopicExchange(Topology.MARKET_EXCHANGE, true, false);
    }

    /** Durable so it survives a broker restart. */
    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(Topology.DLX, true, false);
    }

    /**
     * Messages that failed for good. Nothing consumes this queue: someone checks it
     * and uses the replay endpoint.
     */
    @Bean
    public Queue parkingLot() {
        return QueueBuilder.durable(Topology.Q_PARKING_LOT).build();
    }

    @Bean
    public Binding parkingLotBinding(Queue parkingLot, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(parkingLot).to(deadLetterExchange).with("#");
    }

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * Publisher confirms and returns.
     *
     * Without confirms send() returns as soon as the bytes are written, so if the broker
     * dies before saving the message, it's lost but the outbox row says published.
     * mandatory + returns catches messages that match no queue (e.g. a typo in a routing key).
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setMandatory(true);

        template.setConfirmCallback((correlation, ack, cause) -> {
            if (!ack) {
                log.error("[RABBIT] broker NACKed publish: correlation={} cause={}",
                        correlation != null ? correlation.getId() : "n/a", cause);
            }
        });

        template.setReturnsCallback(returned -> log.error(
                "[RABBIT] message returned as unroutable: exchange={} routingKey={} reply={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));

        return template;
    }
}
