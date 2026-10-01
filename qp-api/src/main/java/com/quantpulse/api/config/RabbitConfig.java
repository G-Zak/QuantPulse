package com.quantpulse.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantpulse.common.event.Topology;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The API's own queue for live prices (used by the SSE stream).
 *
 * Unlike the portfolio and alerts queues, it's non-durable and auto-delete: missing a tick
 * here doesn't matter, the next one replaces it. If the API is down nothing piles up,
 * and each API instance gets its own queue since each has its own browser connections.
 */
@Configuration
public class RabbitConfig {

    @Bean
    public TopicExchange marketExchange() {
        return new TopicExchange(Topology.MARKET_EXCHANGE, true, false);
    }

    @Bean
    public Queue priceStreamQueue() {
        // durable=false, exclusive=false, autoDelete=true
        return new Queue("api.price-stream." + java.util.UUID.randomUUID(), false, false, true);
    }

    @Bean
    public Binding priceStreamBinding(Queue priceStreamQueue, TopicExchange marketExchange) {
        return BindingBuilder.bind(priceStreamQueue).to(marketExchange)
                .with(Topology.RK_PRICE_TICK + ".#");
    }

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
