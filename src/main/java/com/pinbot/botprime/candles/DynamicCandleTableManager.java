package com.pinbot.botprime.candles;

import com.pinbot.botprime.dto.BybitInterval;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DynamicCandleTableManager {

    private final JdbcTemplate jdbc;

    @Transactional
    public void createTable(String symbol, BybitInterval interval) {
        String table = CandleTableName.of(symbol, interval);
        // Table schema mirrors existing candles table (after migrations V4/V5), but without symbol/timeframe columns.
        String sql = """
                CREATE TABLE IF NOT EXISTS %s (
                    open_time    TIMESTAMP   NOT NULL,
                    close_time   TIMESTAMP   NOT NULL,
                    open         NUMERIC(18,8) NOT NULL,
                    high         NUMERIC(18,8) NOT NULL,
                    low          NUMERIC(18,8) NOT NULL,
                    close        NUMERIC(18,8) NOT NULL,
                    volume       NUMERIC(18,8) NOT NULL,
                    quote_volume NUMERIC(18,8) NOT NULL DEFAULT 0,
                    PRIMARY KEY (open_time)
                )
                """.formatted(quoteIdent(table));

        jdbc.execute(sql);
    }

    @Transactional
    public void dropTable(String symbol, BybitInterval interval) {
        String table = CandleTableName.of(symbol, interval);
        String sql = "DROP TABLE IF EXISTS %s".formatted(quoteIdent(table));
        jdbc.execute(sql);
    }

    public boolean tableExists(String symbol, BybitInterval interval) {
        String table = CandleTableName.of(symbol, interval);
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name=?",
                Integer.class,
                table.toLowerCase(Locale.ROOT)
        );
        return cnt != null && cnt > 0;
    }

    private String quoteIdent(String ident) {
        // ident is already validated (letters/digits/underscore only through our generator)
        return '"' + ident + '"';
    }
}
