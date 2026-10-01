package com.quantpulse.common.event;

/**
 * RabbitMQ exchange, queue and routing key names, in one place so producers and consumers match.
 *
 * Routing keys look like price.tick.ATW. With a topic exchange a consumer can bind
 * price.tick.# for all instruments or price.tick.ATW for one.
 */
public final class Topology {

    private Topology() {
    }

    /** Topic exchange for all market events. */
    public static final String MARKET_EXCHANGE = "quantpulse.market";

    /** Dead-letter exchange, used after retries run out. */
    public static final String DLX = "quantpulse.market.dlx";

    /** Routing key patterns. */
    public static final String RK_PRICE_TICK = "price.tick";
    public static final String RK_OHLCV_BAR = "ohlcv.bar";
    public static final String RK_INDEX_TICK = "index.tick";

    /** One queue per service and concern. */
    public static final String Q_PORTFOLIO_VALUATION = "portfolio.valuation";
    public static final String Q_ALERTS_EVALUATION = "alerts.evaluation";

    /** Failed messages end up here to be checked and replayed by hand. */
    public static final String Q_PARKING_LOT = "quantpulse.parking-lot";

    public static String dlqFor(String queue) {
        return queue + ".dlq";
    }

    public static String retryQueueFor(String queue) {
        return queue + ".retry";
    }

    /** Full routing key for one instrument, e.g. price.tick.ATW */
    public static String routingKey(String prefix, String ticker) {
        return prefix + "." + ticker;
    }
}
