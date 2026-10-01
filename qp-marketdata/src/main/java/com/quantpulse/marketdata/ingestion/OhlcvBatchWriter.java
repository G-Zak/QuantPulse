package com.quantpulse.marketdata.ingestion;

import com.quantpulse.marketdata.upstream.DrahmiDtos;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Saves OHLCV bars in bulk with plain JDBC batching.
 *
 * With JPA saveAll, 245 bars means a SELECT plus an INSERT/UPDATE for each row (~490
 * queries). A JDBC batch sends one statement with 245 parameter sets.
 *
 * ON CONFLICT DO UPDATE makes re-runs safe (retries, overlapping ranges) without
 * checking first which rows exist.
 */
@Component
public class OhlcvBatchWriter {

    private static final String UPSERT = """
            insert into ohlcv_bar (ticker, session_date, open, high, low, close, volume, ingested_at)
            values (?, ?, ?, ?, ?, ?, ?, now())
            on conflict (ticker, session_date) do update set
                open   = excluded.open,
                high   = excluded.high,
                low    = excluded.low,
                close  = excluded.close,
                volume = excluded.volume,
                ingested_at = now()
            where ohlcv_bar.close is distinct from excluded.close
               or ohlcv_bar.volume is distinct from excluded.volume
            """;

    private final JdbcTemplate jdbc;

    public OhlcvBatchWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return rows inserted or changed. Thanks to IS DISTINCT FROM, re-running on the
     *         same data returns 0, so the caller doesn't resend events.
     */
    @Transactional
    public int upsertAll(String ticker, List<DrahmiDtos.OhlcvPoint> points) {
        List<DrahmiDtos.OhlcvPoint> valid = points.stream()
                .filter(p -> p.date() != null && p.close() != null
                        && p.open() != null && p.high() != null && p.low() != null)
                .toList();
        if (valid.isEmpty()) {
            return 0;
        }

        int[] results = jdbc.batchUpdate(UPSERT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                DrahmiDtos.OhlcvPoint p = valid.get(i);
                ps.setString(1, ticker);
                ps.setObject(2, p.date());
                ps.setBigDecimal(3, p.open());
                ps.setBigDecimal(4, p.high());
                ps.setBigDecimal(5, p.low());
                ps.setBigDecimal(6, p.close());
                // Volume comes as a decimal (it's an average), the column is a share count, so we truncate.
                ps.setLong(7, p.volume() == null ? 0L : p.volume().setScale(0, java.math.RoundingMode.DOWN).longValue());
            }

            @Override
            public int getBatchSize() {
                return valid.size();
            }
        });

        int changed = 0;
        for (int r : results) {
            // 0 means nothing changed. SUCCESS_NO_INFO (-2) means the driver doesn't know, count it as changed.
            if (r > 0 || r == java.sql.Statement.SUCCESS_NO_INFO) {
                changed++;
            }
        }
        return changed;
    }
}
