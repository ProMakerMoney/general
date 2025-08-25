package com.pinbot.botprime.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "indicator_values",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_indicator_values", columnNames = {"symbol","timeframe","open_time"})
        })
@IdClass(IndicatorValuePk.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorValueEntity {

    @Id
    @Column(name = "symbol", nullable = false, length = 50)
    private String symbol;

    @Id
    @Column(name = "timeframe", nullable = false, length = 16)
    private String timeframe;

    @Id
    @Column(name = "open_time", nullable = false)
    private Instant open_time;

    // OHLCV — BigDecimal
    @Column(name = "open",  nullable = false, precision = 18, scale = 8)
    private BigDecimal open;

    @Column(name = "high",  nullable = false, precision = 18, scale = 8)
    private BigDecimal high;

    @Column(name = "low",   nullable = false, precision = 18, scale = 8)
    private BigDecimal low;

    @Column(name = "close", nullable = false, precision = 18, scale = 8)
    private BigDecimal close;

    @Column(name = "volume",       nullable = false, precision = 18, scale = 8)
    private BigDecimal volume;

    @Column(name = "quote_volume", nullable = false, precision = 18, scale = 8)
    private BigDecimal quoteVolume;

    // Индикаторы — Double (double precision)
    @Column(name = "ema11",    nullable = false)
    private Double ema11;

    @Column(name = "ema30",    nullable = false)
    private Double ema30;

    @Column(name = "ema110",   nullable = false)
    private Double ema110;

    @Column(name = "ema200",   nullable = false)
    private Double ema200;

    @Column(name = "tema9",    nullable = false)
    private Double tema9;

    @Column(name = "rsi2h",    nullable = false)
    private Double rsi2h;

    @Column(name = "sma_rsi2h", nullable = false)
    private Double smaRsi2h;
}
