package com.quantpulse.alerts.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantpulse.common.event.Topology;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Our own queue on the shared exchange.
 *
 * It has a different name from qp-portfolio's queue, so both services get every tick.
 * With the same name they would share the messages between them instead.
 */
@Configuration
public class RabbitConfig {

    private static final long RETRY_TTL_MS = 10_000;

    @Bean
    public TopicExchange marketExchange() {
        return new TopicExchange(Topology.MARKET_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(Topology.DLX, true, false);
    }

    @Bean
    public Queue alertsQueue() {
        return QueueBuilder.durable(Topology.Q_ALERTS_EVALUATION)
                .deadLetterExchange("")
                .deadLetterRoutingKey(Topology.retryQueueFor(Topology.Q_ALERTS_EVALUATION))
                .build();
    }

    @Bean
    public Queue alertsRetryQueue() {
        return QueueBuilder.durable(Topology.retryQueueFor(Topology.Q_ALERTS_EVALUATION))
                .ttl((int) RETRY_TTL_MS)
                .deadLetterExchange("")
                .deadLetterRoutingKey(Topology.Q_ALERTS_EVALUATION)
                .build();
    }

    @Bean
    public Binding alertsBinding(Queue alertsQueue, TopicExchange marketExchange) {
        return BindingBuilder.bind(alertsQueue).to(marketExchange)
                .with(Topology.RK_PRICE_TICK + ".#");
    }

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitListenerContainerFactory<?> rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setPrefetchCount(10);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
