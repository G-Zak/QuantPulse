package com.quantpulse.portfolio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantpulse.common.event.Topology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Queues and bindings for this consumer.
 *
 * The consumer declares its own queue, so qp-marketdata doesn't need to know who listens.
 *
 * Retry flow:
 *
 *   portfolio.valuation --(reject)--> portfolio.valuation.retry --(after 10s)--+
 *          ^                                                                    |
 *          +--------------------------------------------------------------------+
 *                          after N attempts --> quantpulse.market.dlx --> parking lot
 *
 * Nothing consumes the retry queue. When the TTL expires RabbitMQ sends the message back
 * to the main queue. That gives a delay without blocking a consumer thread.
 */
@Configuration
public class RabbitConfig {

    /** How long a failed message waits in the retry queue (ms). */
    private static final long RETRY_TTL_MS = 10_000;

    @Bean
    public TopicExchange marketExchange() {
        return new TopicExchange(Topology.MARKET_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(Topology.DLX, true, false);
    }

    /**
     * Main queue. Failed messages go to the retry queue instead of being requeued right away,
     * which would loop on a bad message at full CPU.
     */
    @Bean
    public Queue valuationQueue() {
        return QueueBuilder.durable(Topology.Q_PORTFOLIO_VALUATION)
                .deadLetterExchange("")  // default exchange, routes by queue name
                .deadLetterRoutingKey(Topology.retryQueueFor(Topology.Q_PORTFOLIO_VALUATION))
                .build();
    }

    /** Messages come back from here when their TTL expires. */
    @Bean
    public Queue valuationRetryQueue() {
        return QueueBuilder.durable(Topology.retryQueueFor(Topology.Q_PORTFOLIO_VALUATION))
                .ttl((int) RETRY_TTL_MS)
                .deadLetterExchange("")
                .deadLetterRoutingKey(Topology.Q_PORTFOLIO_VALUATION)
                .build();
    }

    /** All price ticks (price.tick.#), not one binding per instrument. */
    @Bean
    public Binding valuationBinding(Queue valuationQueue, TopicExchange marketExchange) {
        return BindingBuilder.bind(valuationQueue).to(marketExchange)
                .with(Topology.RK_PRICE_TICK + ".#");
    }

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * Listener container.
     *
     * prefetch limits how many unacked messages one consumer holds. The default (250) lets
     * one consumer take everything while the others wait.
     *
     * 3 attempts in process, then the message is rejected without requeue, so it goes to
     * the retry queue and finally the parking lot.
     */
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
