package com.quantpulse.insights;

import com.quantpulse.insights.brief.BriefValidator;
import com.quantpulse.insights.brief.TemplateBriefWriter;
import com.quantpulse.insights.llm.BriefDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BriefValidatorTest {

    private final BriefValidator validator = new BriefValidator();

    private BriefValidator.Verdict check(String headline, String body, boolean sample) {
        return validator.validate(new BriefDraft(headline, body, List.of("indices[0].changePercent", "nonsense.path"),
                "m", 0, 0, 0), Facts.tree(Facts.sample(sample)));
    }

    private static final String GOOD = "The MASI fell 0.35% to 17,839.02 while the MASI20 slipped 0.41%. "
            + "Decliners outnumbered advancers 44 to 31, with MANAGEM up 3.21% and ITISSALAT AL-MAGHRIB down 2.10%. "
            + "One dollar bought 9.1845 dirhams on 25 September 2026. Over the year to 31 July 2026 the MASI "
            + "lost 8.7% in dirhams against a 4.2% gain for the S&P 500 ETF. 2 alerts fired in the last 24 hours.";

    @Nested
    @DisplayName("numbers")
    class Numbers {

        @Test
        void aBriefThatOnlyRestatesFactsIsAccepted() {
            var v = check("MASI down 0.35% as miners lead", GOOD, false);
            assertThat(v.problems()).isEmpty();
            assertThat(v.accepted()).isTrue();
        }

        @Test
        void anInventedNumberIsRefusedAndNamed() {
            var v = check("MASI down 0.35%", GOOD + " Volume reached 1.2 billion dirhams.", false);
            assertThat(v.accepted()).isFalse();
            assertThat(v.problems()).anySatisfy(p -> assertThat(p).contains("1.2"));
        }

        @Test
        void arithmeticTheModelWasToldNotToDoIsRefused() {
            // 4.2 - (-8.7) = 12.9 is true, but the model computed it, so it's refused.
            var v = check("MASI lags", GOOD + " The gap was 12.9 points.", false);
            assertThat(v.accepted()).isFalse();
            assertThat(v.problems()).anySatisfy(p -> assertThat(p).contains("12.9"));
        }

        @Test
        void roundingAFactToFewerDecimalsIsAllowed() {
            assertThat(check("Dollar at 9.18 dirhams", GOOD, false).accepted()).isTrue();
        }

        @Test
        void digitsInsideNamesAreNotFigures() {
            // MASI20 is a name, and "S&P 500" is fine because it's in the facts.
            var v = check("MASI20 and the S&P 500", GOOD, false);
            assertThat(v.accepted()).isTrue();
        }
    }

    @Nested
    @DisplayName("content rules")
    class Content {

        @Test
        void adviceIsRefused() {
            var v = check("MASI down 0.35%", GOOD + " Investors should buy the dip.", false);
            assertThat(v.accepted()).isFalse();
            assertThat(v.problems()).anySatisfy(p -> assertThat(p).contains("should buy"));
        }

        @Test
        void forecastsAreRefused() {
            var v = check("MASI down 0.35%", GOOD + " The index will rebound next week.", false);
            assertThat(v.accepted()).isFalse();
        }

        @Test
        void sampleDataMustBeDisclosed() {
            var v = check("MASI down 0.35%", GOOD, true);
            assertThat(v.accepted()).isFalse();
            assertThat(v.problems()).anySatisfy(p -> assertThat(p).contains("sample data"));

            assertThat(check("MASI down 0.35%", GOOD + " FX figures are synthetic sample data.", true).accepted()).isTrue();
        }

        @Test
        void onlyResolvableFactPathsAreKept() {
            assertThat(check("MASI down 0.35%", GOOD, false).factsUsed()).containsExactly("indices[0].changePercent");
        }
    }

    @Test
    @DisplayName("the template fallback passes the same validator the model must pass")
    void templatePassesItsOwnRules() {
        for (boolean sample : new boolean[]{false, true}) {
            var facts = Facts.sample(sample);
            var w = new TemplateBriefWriter().write(facts);
            var v = validator.validate(new BriefDraft(w.headline(), w.body(), w.factsUsed(), "template", 0, 0, 0),
                    Facts.tree(facts));
            assertThat(v.problems()).as("sample=" + sample + ": " + w.body()).isEmpty();
        }
    }
}
