"""
Tests for the dividend income code.
"""
import pandas as pd
import pytest

from app.income import (
    annual_dividends, growth_analysis, payout_ratio, project_income,
    trailing_twelve_months, yield_trap_check,
)


def divs(pairs):
    """[(year, amount)] -> the API's dividend shape, mid-year ex-dates."""
    return [{"exDate": f"{y}-06-15", "paymentDate": f"{y}-06-25",
             "amount": a, "currency": "MAD"} for y, a in pairs]


class TestTrailingTwelveMonths:
    def test_sums_only_the_last_365_days(self):
        rows = divs([(2026, 22), (2025, 19), (2024, 16.5)])
        # Anchored so only 2026 falls inside the window.
        assert trailing_twelve_months(rows, as_of=pd.Timestamp("2026-08-05").date()) == 22

    def test_handles_two_payments_in_one_window(self):
        rows = [{"exDate": "2026-01-10", "amount": 5},
                {"exDate": "2026-06-15", "amount": 7}]
        assert trailing_twelve_months(rows, as_of=pd.Timestamp("2026-08-05").date()) == 12

    def test_no_dividends_is_zero_not_an_error(self):
        assert trailing_twelve_months([]) == 0.0


class TestGrowthAnalysis:
    def test_flawless_payer_is_called_strong(self):
        g = growth_analysis(annual_dividends(divs(
            [(y, 10 * 1.05 ** (y - 2016)) for y in range(2016, 2027)])))
        assert g["cuts"] == 0
        assert g["quality"] == "strong"
        assert g["cagr"] == pytest.approx(0.05, abs=0.005)

    def test_one_shock_with_recovery_is_resilient_not_weak(self):
        """
        ATW's real pattern: big COVID cut, then back to a new high.
        Just counting cuts would treat this like a company that cuts often.
        """
        g = growth_analysis(annual_dividends(divs([
            (2017, 12), (2018, 12.5), (2019, 13),
            (2020, 6.75),                      # 48% cut
            (2021, 14), (2022, 15), (2023, 16),
        ])))
        assert g["cuts"] == 1
        assert g["worstCut"]["year"] == 2020
        assert g["worstCut"]["dropPct"] == pytest.approx(0.48, abs=0.01)
        assert g["recoveredToHigh"] is True
        assert g["quality"] == "resilient"

    def test_chronic_cutter_is_graded_weak(self):
        g = growth_analysis(annual_dividends(divs([
            (2020, 20), (2021, 16), (2022, 12), (2023, 9), (2024, 7),
        ])))
        assert g["cuts"] == 4
        assert g["recoveredToHigh"] is False
        assert g["quality"] == "weak"

    def test_skipped_years_are_detected(self):
        g = growth_analysis(annual_dividends(divs([
            (2020, 10), (2021, 10), (2024, 10), (2025, 10),
        ])))
        assert g["skippedYears"] == 2      # 2022 and 2023
        assert g["spanYears"] == 6

    def test_catch_up_artefact_is_flagged_not_hidden(self):
        # A payment slipping into the next calendar year looks like a spike then a cut.
        g = growth_analysis(annual_dividends(divs([
            (2020, 10), (2021, 22), (2022, 11), (2023, 11),
        ])))
        assert g["dataCaveat"] is not None
        assert "delayed payment" in g["dataCaveat"]


class TestPayoutRatio:
    def test_derives_eps_from_price_and_pe(self):
        # price 100, PE 10 -> EPS 10. DPS 4 -> payout 40%.
        r = payout_ratio(dps=4, price=100, pe_ratio=10)
        assert r["available"]
        assert r["impliedEps"] == pytest.approx(10)
        assert r["payoutRatio"] == pytest.approx(0.4)

    @pytest.mark.parametrize("dps,expected", [
        (3.0, "low"),        # 30%, well covered
        (5.0, "moderate"),   # 50%
        (8.0, "elevated"),   # 80%, little room
        (12.0, "high"),      # 120%, more than earnings
    ])
    def test_risk_bands(self, dps, expected):
        """
        Uses values away from the band limits. An older version used exactly 40%, right on
        the low/moderate limit, so the test depended on > vs >=.
        """
        assert payout_ratio(dps=dps, price=100, pe_ratio=10)["risk"] == expected

    def test_flags_paying_more_than_earnings(self):
        r = payout_ratio(dps=12, price=100, pe_ratio=10)   # EPS 10, DPS 12
        assert r["payoutRatio"] > 1.0
        assert r["risk"] == "high"
        assert "not sustainable" in r["verdict"]

    def test_reports_unavailable_rather_than_guessing(self):
        # Better no number than a made-up one.
        assert payout_ratio(dps=4, price=100, pe_ratio=None)["available"] is False
        assert payout_ratio(dps=4, price=0, pe_ratio=10)["available"] is False


class TestYieldTrap:
    def test_high_yield_plus_falling_price_plus_overpayment_is_flagged(self):
        trap = yield_trap_check(
            current_yield=0.11,
            growth={"cuts": 2, "quality": "weak",
                    "worstCut": {"year": 2024, "dropPct": 0.3}},
            price_change_1y=-0.42,
            payout={"available": True, "payoutRatio": 1.4},
            market_median_yield=0.038,
        )
        assert trap["level"] == "high"
        assert len(trap["signals"]) >= 3

    def test_healthy_high_yielder_is_not_flagged(self):
        trap = yield_trap_check(
            current_yield=0.05,
            growth={"cuts": 0, "quality": "strong"},
            price_change_1y=0.08,
            payout={"available": True, "payoutRatio": 0.45},
            market_median_yield=0.038,
        )
        assert trap["level"] == "low"
        assert trap["signals"] == []

    def test_a_recovered_one_off_cut_does_not_raise_a_flag(self):
        """One shock followed by recovery isn't a fragile dividend."""
        trap = yield_trap_check(
            current_yield=0.04,
            growth={"cuts": 1, "quality": "resilient",
                    "worstCut": {"year": 2020, "dropPct": 0.48}},
            price_change_1y=0.02,
            payout={"available": True, "payoutRatio": 0.47},
            market_median_yield=0.038,
        )
        assert trap["level"] == "low"


class TestProjection:
    INSTRUMENT = {"ticker": "ATW", "name": "Attijariwafa Bank",
                  "sector": "BANQU", "price": 685.0, "peRatio": 14.76}

    def test_buys_whole_shares_only(self):
        # 100,000 / 685 = 145.98 -> 145 shares. The exchange doesn't trade fractions.
        r = project_income(100_000, "MAD", self.INSTRUMENT, divs([(2026, 22)]))
        assert r["available"]
        assert r["investment"]["shares"] == 145
        assert r["investment"]["uninvestedMad"] > 0

    def test_converts_through_the_supplied_fx_rate(self):
        r = project_income(10_000, "EUR", self.INSTRUMENT, divs([(2026, 22)]), fx_rate=0.09)
        assert r["investment"]["amountMad"] == pytest.approx(10_000 / 0.09, abs=1)
        assert r["investment"]["fxSource"] == "caller-supplied"

    def test_flags_fx_risk_for_a_foreign_investor_only(self):
        foreign = project_income(10_000, "EUR", self.INSTRUMENT, divs([(2026, 22)]), fx_rate=0.09)
        local = project_income(100_000, "MAD", self.INSTRUMENT, divs([(2026, 22)]))
        assert any("FX risk" in r for r in foreign["risks"])
        assert not any("FX risk" in r for r in local["risks"])

    def test_returns_three_scenarios_not_one_number(self):
        r = project_income(100_000, "MAD", self.INSTRUMENT,
                           divs([(y, 10 * 1.06 ** (y - 2018)) for y in range(2018, 2027)]))
        assert set(r["scenarios"]) == {"flat", "historical", "cut"}
        # Growth > flat > cut, otherwise the math is wrong.
        assert (r["scenarios"]["historical"]["cumulativeMad"]
                > r["scenarios"]["flat"]["cumulativeMad"]
                > r["scenarios"]["cut"]["cumulativeMad"])

    def test_rejects_an_amount_below_one_share(self):
        r = project_income(100, "MAD", self.INSTRUMENT, divs([(2026, 22)]))
        assert r["available"] is False
        assert "below" in r["reason"]

    def test_yield_is_computed_from_dividends_not_taken_from_the_feed(self):
        # No dividendYield given, the code has to compute it.
        r = project_income(100_000, "MAD", self.INSTRUMENT, divs([(2026, 22)]))
        assert r["dividend"]["currentYield"] == pytest.approx(22 / 685, abs=1e-6)

    def test_a_non_payer_yields_zero_income_without_crashing(self):
        r = project_income(100_000, "MAD", self.INSTRUMENT, [])
        assert r["available"]
        assert r["dividend"]["year1IncomeMad"] == 0
