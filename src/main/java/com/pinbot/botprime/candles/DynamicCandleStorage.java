package com.pinbot.botprime.candles;

import com.pinbot.botprime.dto.CandleDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class DynamicCandleStorage {

    private final JdbcTemplate jdbc;

    public Instant findMaxOpenTime(String tableName) {
        String sql = "SELECT MAX(open_time) FROM " + quoteIdent(tableName);
        Timestamp ts = jdbc.queryForObject(sql, Timestamp.class);
        return ts == null ? null : ts.toInstant();
    }

    public Instant findMinOpenTime(String tableName) {
        String sql = "SELECT MIN(open_time) FROM " + quoteIdent(tableName);
        Timestamp ts = jdbc.queryForObject(sql, Timestamp.class);
        return ts == null ? null : ts.toInstant();
    }

    public int upsertBatch(String tableName, List<CandleDto> candles) {
        if (candles == null || candles.isEmpty()) {
            return 0;
        }

        String sql = ("""
                INSERT INTO %s (
                    open_time, close_time, open, high, low, close, volume, quote_volume
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (open_time) DO UPDATE SET
                    close_time = EXCLUDED.close_time,
                    open = EXCLUDED.open,
                    high = EXCLUDED.high,
                    low = EXCLUDED.low,
                    close = EXCLUDED.close,
                    volume = EXCLUDED.volume,
                    quote_volume = EXCLUDED.quote_volume
                """).formatted(quoteIdent(tableName));

        // ВАЖНО: эта перегрузка возвращает int[][]
        int[][] updated = jdbc.batchUpdate(sql, candles, 500, (PreparedStatement ps, CandleDto c) -> {
            Instant t = Instant.ofEpochMilli(c.getStartMs());
            Timestamp ts = Timestamp.from(t);

            ps.setTimestamp(1, ts);
            ps.setTimestamp(2, ts); // close_time: сохраняем текущее поведение проекта

            ps.setBigDecimal(3, BigDecimal.valueOf(c.getOpen()));
            ps.setBigDecimal(4, BigDecimal.valueOf(c.getHigh()));
            ps.setBigDecimal(5, BigDecimal.valueOf(c.getLow()));
            ps.setBigDecimal(6, BigDecimal.valueOf(c.getClose()));
            ps.setBigDecimal(7, BigDecimal.valueOf(c.getVolume()));
            ps.setBigDecimal(8, BigDecimal.valueOf(c.getQuoteVolume()));
        });

        int sum = 0;
        for (int[] batch : updated) {
            for (int x : batch) {
                // JDBC returns either 0/1 or SUCCESS_NO_INFO (-2)
                if (x > 0) sum += x;
                else if (x == PreparedStatement.SUCCESS_NO_INFO) sum += 1;
            }
        }
        return sum;
    }

    private String quoteIdent(String ident) {
        return '"' + ident + '"';
    }
}

