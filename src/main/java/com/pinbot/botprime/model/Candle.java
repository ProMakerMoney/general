package com.pinbot.botprime.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 1-минутная (или любая другая) свеча из Bybit.
 */
@Getter
@Setter
@Builder                // <-- даёт Candle.builder()
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "candles",
        indexes = {
                @Index(name = "idx_candle_symbol_interval_time",
                        columnList = "symbol, interval, timestamp", unique = true)
        })
public class Candle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** BTCUSDT, ETHUSDT … */
    @Column(length = 20, nullable = false)
    private String symbol;

    /** ТФ: 1, 3, 5, 15, 60 … (Bybit формат) */
    @Column(length = 5, nullable = false)
    private String interval;

    /** Время открытия свечи (ms since epoch, UTC). */
    @Column(nullable = false)
    private Long timestamp;

    /* OHLCV в BigDecimal для финансовой точности */
    @Column(precision = 18, scale = 8, nullable = false) private BigDecimal open;
    @Column(precision = 18, scale = 8, nullable = false) private BigDecimal high;
    @Column(precision = 18, scale = 8, nullable = false) private BigDecimal low;
    @Column(precision = 18, scale = 8, nullable = false) private BigDecimal close;
    @Column(precision = 24, scale = 8, nullable = false) private BigDecimal volume;
    @Column(nullable = false) private String timeframe;
    @Column(nullable = false) private Instant openTime;
    @Column(nullable = false) private Instant closeTime;
}