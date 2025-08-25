package com.pinbot.botprime.repository;

import com.pinbot.botprime.persistence.IndicatorValueEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class IndicatorValueRepositoryImpl implements IndicatorValueRepositoryCustom {

    private final JdbcTemplate jdbc;

    private static final String UPSERT_SQL = """
        INSERT INTO indicator_values
          (symbol, timeframe, open_time,
           open, high, low, close,
           volume, quote_volume,
           ema11, ema30, ema110, ema200,
           tema9, rsi2h, sma_rsi2h)
        SELECT *
        FROM unnest(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          AS t(symbol, timeframe, open_time,
               open, high, low, close,
               volume, quote_volume,
               ema11, ema30, ema110, ema200,
               tema9, rsi2h, sma_rsi2h)
        ON CONFLICT (symbol, timeframe, open_time) DO UPDATE
        SET open         = EXCLUDED.open,
            high         = EXCLUDED.high,
            low          = EXCLUDED.low,
            close        = EXCLUDED.close,
            volume       = EXCLUDED.volume,
            quote_volume = EXCLUDED.quote_volume,
            ema11        = EXCLUDED.ema11,
            ema30        = EXCLUDED.ema30,
            ema110       = EXCLUDED.ema110,
            ema200       = EXCLUDED.ema200,
            tema9        = EXCLUDED.tema9,
            rsi2h        = EXCLUDED.rsi2h,
            sma_rsi2h    = EXCLUDED.sma_rsi2h
        """;

    @Override
    @Transactional
    public void upsertBatchArrays(List<IndicatorValueEntity> rows) {
        if (rows == null || rows.isEmpty()) return;

        final int CHUNK = 2000; // ~16 * 2000 = 32k параметров если бы внезапно снова развернуло
        for (int off = 0; off < rows.size(); off += CHUNK) {
            List<IndicatorValueEntity> part = rows.subList(off, Math.min(off + CHUNK, rows.size()));

            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(UPSERT_SQL);

                // text[]
                ps.setArray(1,  con.createArrayOf("text", part.stream().map(IndicatorValueEntity::getSymbol).toArray(String[]::new)));
                ps.setArray(2,  con.createArrayOf("text", part.stream().map(IndicatorValueEntity::getTimeframe).toArray(String[]::new)));

                // timestamp[]
                ps.setArray(3,  con.createArrayOf("timestamp",
                        part.stream().map(IndicatorValueEntity::getOpen_time).map(Timestamp::from).toArray(Timestamp[]::new)));

                // numeric[]
                ps.setArray(4,  con.createArrayOf("numeric", part.stream().map(IndicatorValueEntity::getOpen).toArray()));
                ps.setArray(5,  con.createArrayOf("numeric", part.stream().map(IndicatorValueEntity::getHigh).toArray()));
                ps.setArray(6,  con.createArrayOf("numeric", part.stream().map(IndicatorValueEntity::getLow).toArray()));
                ps.setArray(7,  con.createArrayOf("numeric", part.stream().map(IndicatorValueEntity::getClose).toArray()));
                ps.setArray(8,  con.createArrayOf("numeric", part.stream().map(IndicatorValueEntity::getVolume).toArray()));
                ps.setArray(9,  con.createArrayOf("numeric", part.stream().map(IndicatorValueEntity::getQuoteVolume).toArray()));

                // float8[] (double precision)
                ps.setArray(10, con.createArrayOf("float8", part.stream().map(IndicatorValueEntity::getEma11).toArray()));
                ps.setArray(11, con.createArrayOf("float8", part.stream().map(IndicatorValueEntity::getEma30).toArray()));
                ps.setArray(12, con.createArrayOf("float8", part.stream().map(IndicatorValueEntity::getEma110).toArray()));
                ps.setArray(13, con.createArrayOf("float8", part.stream().map(IndicatorValueEntity::getEma200).toArray()));
                ps.setArray(14, con.createArrayOf("float8", part.stream().map(IndicatorValueEntity::getTema9).toArray()));
                ps.setArray(15, con.createArrayOf("float8", part.stream().map(IndicatorValueEntity::getRsi2h).toArray()));
                ps.setArray(16, con.createArrayOf("float8", part.stream().map(IndicatorValueEntity::getSmaRsi2h).toArray()));

                return ps;
            });
        }
    }
}
