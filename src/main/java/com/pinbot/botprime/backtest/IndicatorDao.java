package com.pinbot.botprime.backtest;


import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;


import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;


@Repository
public class IndicatorDao {
    private final JdbcTemplate jdbc;


    public IndicatorDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }


    public List<Bar> fetchAllBarsAsc() {
// ВАЖНО: названия колонок должны совпадать с вашей таблицей indicator_values
        final String sql = "SELECT open_time, open, high, low, close, ema11, ema30, ema110, tema9, rsi2h, sma_rsi2h FROM indicator_values ORDER BY open_time ASC";
        RowMapper<Bar> rm = (ResultSet rs, int rowNum) -> new Bar(
                rs.getTimestamp("open_time").toInstant(),
                rs.getBigDecimal("open"),
                rs.getBigDecimal("high"),
                rs.getBigDecimal("low"),
                rs.getBigDecimal("close"),
                rs.getBigDecimal("ema11"),
                rs.getBigDecimal("ema30"),
                rs.getBigDecimal("ema110"),
                rs.getBigDecimal("tema9"),
                rs.getBigDecimal("rsi2h"),
                rs.getBigDecimal("sma_rsi2h")
        );
        return jdbc.query(sql, rm);
    }


    public record Bar(
            Instant openTime,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal ema11,
            BigDecimal ema30,
            BigDecimal ema110,
            BigDecimal tema9,
            BigDecimal rsi2h,
            BigDecimal smaRsi2h
    ) {}
}
