package com.quantpulse.portfolio;

import com.quantpulse.common.money.Money;
import com.quantpulse.portfolio.service.FxRateClient;
import com.quantpulse.portfolio.service.RiskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PortfolioCurrencyTest {

    private static final RiskService.FxApplied MAD_USD = new RiskService.FxApplied(
            "MAD", "USD", new BigDecimal("0.108879"), LocalDate.of(2026, 9, 25), "ALPHAVANTAGE", false);

    private static RiskService.PortfolioRisk inMad() {
        return new RiskService.PortfolioRisk(UUID.randomUUID(),
                Money.mad("10000"), Money.mad("9000"), Money.mad("1000"), Money.mad("-250"),
                new BigDecimal("11.111111"), Map.of("ATW", new BigDecimal("0.600000")), Map.of(),
                new BigDecimal("0.52"), new BigDecimal("1.0"), 2, null);
    }

    @Test
    @DisplayName("money amounts convert at the stated rate and carry the rate with them")
    void convertsAmountsAndAttachesTheRate() {
        var usd = inMad().inCurrency(MAD_USD);

        assertThat(usd.totalValue()).isEqualTo(Money.of("1088.79", "USD"));
        assertThat(usd.totalCost()).isEqualTo(Money.of("979.911", "USD"));
        assertThat(usd.realizedPnl()).isEqualTo(Money.of("-27.21975", "USD"));
        assertThat(usd.fx()).isEqualTo(MAD_USD);
    }

    @Test
    @DisplayName("ratios are unchanged: scaling both sides by one rate cannot move a percentage")
    void ratiosDoNotConvert() {
        var mad = inMad();
        var usd = mad.inCurrency(MAD_USD);

        assertThat(usd.unrealizedPnlPercent()).isEqualTo(mad.unrealizedPnlPercent());
        assertThat(usd.weights()).isEqualTo(mad.weights());
        assertThat(usd.herfindahl()).isEqualTo(mad.herfindahl());
    }

    @Test
    @DisplayName("qp-marketdata down means an explicit error, never an unconverted amount")
    void marketDataDownIsExplicit() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://marketdata");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://marketdata/api/v1/fx/rates?pairs=MAD-USD"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> new FxRateClient(builder.build()).latest("MAD", "USD"))
                .isInstanceOf(FxRateClient.FxUnavailableException.class)
                .hasMessageContaining("MAD/USD");
    }

    @Test
    @DisplayName("a pair with no stored rate is an explicit error too")
    void noStoredRate() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://marketdata");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://marketdata/api/v1/fx/rates?pairs=MAD-GBP"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new FxRateClient(builder.build()).latest("MAD", "GBP"))
                .isInstanceOf(FxRateClient.FxUnavailableException.class)
                .hasMessageContaining("no stored rate");
    }
}
