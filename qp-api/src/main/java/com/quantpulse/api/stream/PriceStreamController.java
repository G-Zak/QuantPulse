package com.quantpulse.api.stream;

import com.quantpulse.common.event.PriceTickEvent;
import com.quantpulse.common.event.Topology;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live prices over Server-Sent Events.
 *
 * The server only pushes, the browser never sends anything back, so SSE is enough.
 * It's plain HTTP and the browser reconnects by itself. WebSocket would only be needed
 * for two-way traffic. Polling would send requests even when nothing changed.
 */
@Controller
@RequestMapping("/api/v1/stream")
public class PriceStreamController {

    private static final Logger log = LoggerFactory.getLogger(PriceStreamController.class);

    /** No timeout, stays open until the client leaves. */
    private static final long NO_TIMEOUT = 0L;

    /**
     * Read on every tick, written only when a client connects or leaves.
     * CopyOnWriteArrayList lets us remove a client while broadcasting without errors.
     */
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicInteger subscriberGauge = new AtomicInteger();

    public PriceStreamController(MeterRegistry meterRegistry) {
        Gauge.builder("quantpulse.stream.subscribers", subscriberGauge, AtomicInteger::get)
                .description("Open SSE connections").register(meterRegistry);
    }

    private record Subscriber(SseEmitter emitter, Set<String> tickers) {
        boolean wants(String ticker) {
            return tickers.isEmpty() || tickers.contains(ticker);
        }
    }

    /**
     * Opens a stream.
     *
     * @param tickers optional comma-separated filter, empty means all instruments
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String tickers) {
        Set<String> filter = ConcurrentHashMap.newKeySet();
        if (tickers != null && !tickers.isBlank()) {
            for (String t : tickers.split(",")) {
                filter.add(t.trim().toUpperCase());
            }
        }

        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        Subscriber subscriber = new Subscriber(emitter, filter);
        subscribers.add(subscriber);
        subscriberGauge.set(subscribers.size());

        // Remove the subscriber in all three cases, or dead emitters pile up in memory.
        emitter.onCompletion(() -> remove(subscriber));
        emitter.onTimeout(() -> remove(subscriber));
        emitter.onError(e -> remove(subscriber));

        try {
            emitter.send(SseEmitter.event().name("connected")
                    .data(Map.of("filter", filter.isEmpty() ? "ALL" : filter)));
        } catch (IOException e) {
            remove(subscriber);
        }
        return emitter;
    }

    /**
     * Sends a price tick to every subscriber that wants it.
     * The queue is auto-delete: if the API is down we just miss ticks, which is fine here.
     */
    @RabbitListener(queues = "#{priceStreamQueue.name}")
    public void onPriceTick(PriceTickEvent event) {
        if (subscribers.isEmpty()) {
            return;
        }
        for (Subscriber subscriber : subscribers) {
            if (!subscriber.wants(event.ticker())) {
                continue;
            }
            try {
                subscriber.emitter().send(SseEmitter.event()
                        .name("price")
                        .data(Map.of(
                                "ticker", event.ticker(),
                                "price", event.price(),
                                "currency", event.currency(),
                                "sector", event.sector() == null ? "" : event.sector(),
                                "source", event.source(),
                                "at", event.occurredAt().toString())));
            } catch (Exception e) {
                // Send failed, the client is gone. Drop it so it doesn't slow down the others.
                remove(subscriber);
            }
        }
    }

    private void remove(Subscriber subscriber) {
        subscribers.remove(subscriber);
        subscriberGauge.set(subscribers.size());
    }
}
