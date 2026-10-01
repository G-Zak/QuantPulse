"""
Tests for the analytics functions. No network, no framework.
"""
import numpy as np
import pandas as pd
import pytest

from app.analytics import (
    average_pairwise_correlation, backtest_calibration, cluster_order,
    correlation_matrix, detect_anomalies, efficient_frontier,
    monte_carlo_forecast, pca_factors,
)


def gbm_series(n=300, s0=100.0, mu=0.0003, sigma=0.015, seed=1):
    rng = np.random.default_rng(seed)
    steps = (mu - 0.5 * sigma ** 2) + sigma * rng.standard_normal(n)
    return pd.Series(s0 * np.exp(np.cumsum(steps)))


class TestCorrelation:
    def test_identical_series_correlate_at_one(self):
        s = gbm_series()
        rets = pd.DataFrame({"A": np.log(s / s.shift(1)), "B": np.log(s / s.shift(1))}).dropna()
        corr = correlation_matrix(rets)
        assert corr.loc["A", "B"] == pytest.approx(1.0, abs=1e-9)

    def test_independent_series_correlate_near_zero(self):
        a = gbm_series(seed=1)
        b = gbm_series(seed=99)
        rets = pd.DataFrame({"A": np.log(a / a.shift(1)), "B": np.log(b / b.shift(1))}).dropna()
        assert abs(correlation_matrix(rets).loc["A", "B"]) < 0.2

    def test_clustering_groups_correlated_names_together(self):
        # Two blocks: A/B driven by one factor, C/D by another.
        rng = np.random.default_rng(7)
        f1 = rng.standard_normal(400) * 0.01
        f2 = rng.standard_normal(400) * 0.01
        noise = lambda: rng.standard_normal(400) * 0.002
        rets = pd.DataFrame({
            "A": f1 + noise(), "B": f1 + noise(),
            "C": f2 + noise(), "D": f2 + noise(),
        })
        order, clusters = cluster_order(correlation_matrix(rets))
        pos = {t: i for i, t in enumerate(order)}
        # A next to B, C next to D.
        assert abs(pos["A"] - pos["B"]) == 1
        assert abs(pos["C"] - pos["D"]) == 1

    def test_average_pairwise_ignores_the_diagonal(self):
        rng = np.random.default_rng(3)
        rets = pd.DataFrame(rng.standard_normal((300, 4)) * 0.01, columns=list("ABCD"))
        avg = average_pairwise_correlation(correlation_matrix(rets))
        # Including the diagonal of 1.0 would pull this toward 0.25.
        assert abs(avg) < 0.15


class TestPCA:
    def test_pc1_captures_a_dominant_common_factor(self):
        rng = np.random.default_rng(11)
        market = rng.standard_normal(400) * 0.012
        cols = {f"S{i}": market + rng.standard_normal(400) * 0.004 for i in range(8)}
        result = pca_factors(pd.DataFrame(cols))
        assert result["available"]
        # Every name is mostly the market factor, so PC1 should dominate.
        assert result["explainedVariance"][0] > 0.6
        # PC1 loadings should all have the same sign (market factor).
        loadings = list(result["pc1Loadings"].values())
        assert all(v > 0 for v in loadings) or all(v < 0 for v in loadings)

    def test_reports_unavailable_rather_than_crashing_on_thin_data(self):
        assert pca_factors(pd.DataFrame({"A": [0.01, 0.02]}))["available"] is False


class TestForecast:
    def test_returns_a_distribution_not_a_point(self):
        r = monte_carlo_forecast(gbm_series(), horizon_days=30, n_sims=4000)
        assert r["available"]
        assert set(r["bands"]) == {"p5", "p10", "p25", "p50", "p75", "p90", "p95"}
        assert len(r["bands"]["p50"]) == 30

    def test_bands_are_ordered_and_widen_with_horizon(self):
        r = monte_carlo_forecast(gbm_series(), horizon_days=60, n_sims=8000)
        p5, p50, p95 = r["bands"]["p5"], r["bands"]["p50"], r["bands"]["p95"]
        for i in range(60):
            assert p5[i] <= p50[i] <= p95[i]
        # The band must get wider over time.
        assert (p95[-1] - p5[-1]) > (p95[0] - p5[0])

    def test_is_deterministic_for_a_given_seed(self):
        a = monte_carlo_forecast(gbm_series(), horizon_days=20, n_sims=2000)
        b = monte_carlo_forecast(gbm_series(), horizon_days=20, n_sims=2000)
        assert a["bands"]["p50"] == b["bands"]["p50"]

    def test_ito_correction_is_applied(self):
        # With GBM the median final price is s0*exp((mu - sigma^2/2)*T).
        # Forgetting -sigma^2/2 biases it up, this test catches it.
        s = gbm_series(n=500, mu=0.0, sigma=0.02, seed=5)
        r = monte_carlo_forecast(s, horizon_days=50, n_sims=40_000)
        log_ret = np.log(s / s.shift(1)).dropna()
        mu, sigma = log_ret.mean(), log_ret.std(ddof=1)
        expected_median = r["spot"] * np.exp((mu - 0.5 * sigma ** 2) * 50)
        assert r["terminal"]["median"] == pytest.approx(expected_median, rel=0.03)

    def test_probabilities_are_coherent(self):
        r = monte_carlo_forecast(gbm_series(), horizon_days=40, n_sims=20_000)
        t = r["terminal"]
        assert 0 <= t["probAboveSpot"] <= 1
        # P(up 10%) must not exceed P(up at all).
        assert t["probUp10Pct"] <= t["probAboveSpot"]


class TestCalibration:
    def test_backtest_runs_out_of_sample_and_reports_coverage(self):
        r = backtest_calibration(gbm_series(n=400), horizon_days=20, train_window=120)
        assert r["available"]
        assert r["trials"] > 0
        for level in (50, 80, 90):
            assert 0.0 <= r["coverage"][f"nominal{level}"]["empirical"] <= 1.0

    def test_is_well_calibrated_on_data_that_matches_its_assumptions(self):
        # The data really is GBM, so the GBM interval should be close to its nominal level.
        # If this fails, the interval math is wrong.
        r = backtest_calibration(gbm_series(n=600, seed=21), horizon_days=20, train_window=150)
        empirical = r["coverage"]["nominal80"]["empirical"]
        assert 0.65 <= empirical <= 0.95

    def test_reports_insufficient_data_instead_of_guessing(self):
        assert backtest_calibration(gbm_series(n=50))["available"] is False


class TestOptimisation:
    def test_frontier_produces_weights_that_sum_to_one(self):
        rng = np.random.default_rng(4)
        rets = pd.DataFrame(rng.standard_normal((300, 5)) * 0.01, columns=list("ABCDE"))
        r = efficient_frontier(rets, n_portfolios=2000)
        assert r["available"]
        for key in ("maxSharpe", "minVariance"):
            assert sum(r[key]["weights"].values()) == pytest.approx(1.0, abs=0.02)

    def test_min_variance_has_lower_vol_than_max_sharpe(self):
        rng = np.random.default_rng(6)
        rets = pd.DataFrame(rng.standard_normal((300, 6)) * 0.01, columns=list("ABCDEF"))
        r = efficient_frontier(rets, n_portfolios=3000)
        assert r["minVariance"]["volatility"] <= r["maxSharpe"]["volatility"]


class TestAnomalies:
    def test_flags_an_injected_shock(self):
        s = gbm_series(n=200, sigma=0.008, seed=8).tolist()
        s[150] = s[149] * 1.35  # a 35% jump
        df = pd.DataFrame({
            "date": pd.date_range("2025-01-01", periods=200, freq="B"),
            "close": s,
            "volume": [100_000] * 200,
        })
        r = detect_anomalies(df)
        assert r["available"]
        assert any(e["priceZ"] > 3 for e in r["anomalies"])

    def test_robust_to_outliers_inflating_the_threshold(self):
        # With mean/sigma, three huge moves raise sigma and hide themselves.
        # Median/MAD should still flag all three.
        s = gbm_series(n=300, sigma=0.006, seed=12).tolist()
        for i in (100, 150, 200):
            s[i] = s[i - 1] * 1.30
        df = pd.DataFrame({
            "date": pd.date_range("2025-01-01", periods=300, freq="B"),
            "close": s, "volume": [50_000] * 300,
        })
        flagged = {e["date"] for e in detect_anomalies(df)["anomalies"]}
        assert len(flagged) >= 3

    def test_handles_zero_volume_sessions(self):
        s = gbm_series(n=120, seed=15).tolist()
        volumes = [0 if i % 10 == 0 else 80_000 for i in range(120)]
        df = pd.DataFrame({
            "date": pd.date_range("2025-01-01", periods=120, freq="B"),
            "close": s, "volume": volumes,
        })
        r = detect_anomalies(df)
        assert r["available"]
        assert r["zeroVolumeSessions"] == 12


class TestSectorStatistics:
    def test_drawdown_is_scale_free_across_members(self):
        """
        A sector with a 1,300 MAD stock and a 1.00 MAD stock: the drawdown must not
        follow the expensive one. Averaging prices gave 90.7% for MINES.
        """
        from app.analytics import sector_statistics

        rng = np.random.default_rng(31)
        n = 200
        shared = rng.standard_normal(n) * 0.01
        rets = pd.DataFrame({"BIG": shared, "SMALL": shared})   # identical return paths

        # Same returns, wildly different price levels.
        closes = pd.DataFrame({
            "BIG": 1300 * np.exp(np.cumsum(shared)),
            "SMALL": 1.0 * np.exp(np.cumsum(shared)),
        })
        stats = sector_statistics(rets, {"BIG": "MINES", "SMALL": "MINES"}, closes)
        assert len(stats) == 1
        # Both move the same, so the sector drawdown must equal one stock's drawdown.
        expected = float(((closes["SMALL"].cummax() - closes["SMALL"]) / closes["SMALL"].cummax()).max())
        assert stats[0]["maxDrawdown"] == pytest.approx(expected, abs=0.01)
