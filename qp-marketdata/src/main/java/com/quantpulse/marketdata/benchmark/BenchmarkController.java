package com.quantpulse.marketdata.benchmark;

import com.quantpulse.marketdata.ingestion.BenchmarkIngestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class BenchmarkController {

    private final BenchmarkComparisonService comparisons;
    private final BenchmarkIngestionService ingestion;

    public BenchmarkController(BenchmarkComparisonService comparisons, BenchmarkIngestionService ingestion) {
        this.comparisons = comparisons;
        this.ingestion = ingestion;
    }

    /** MASI vs world benchmarks, all in MAD. Reads stored data only. */
    @GetMapping("/benchmarks/compare")
    public BenchmarkComparisonService.ComparisonView compare(@RequestParam(defaultValue = "1Y") String range) {
        return comparisons.compare(range);
    }

    /** Run the nightly benchmark job now. One Alpha Vantage call per symbol in live mode. */
    @PostMapping("/ops/benchmarks/refresh")
    public Map<String, Object> refresh() {
        return Map.of("sessionsInserted", ingestion.refresh());
    }
}
