package com.pinbot.botprime.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "candles")
public class Candle {

    /** millis UTC ― Primary Key */
    @Id
    private long time;

    @Column(precision = 18, scale = 8, nullable = false)
    private BigDecimal open;

    @Column(precision = 18, scale = 8, nullable = false)
    private BigDecimal high;

    @Column(precision = 18, scale = 8, nullable = false)
    private BigDecimal low;

    @Column(precision = 18, scale = 8, nullable = false)
    private BigDecimal close;

    @Column(precision = 18, scale = 8, nullable = false)
    private BigDecimal volume;

    @Column(name = "quote_volume", precision = 18, scale = 8, nullable = false)
    private BigDecimal quoteVolume;

    /* удобные методы */

    public LocalDateTime asLocalDateTime() {
        return LocalDateTime.ofEpochSecond(time / 1_000, 0, ZoneOffset.UTC);
    }

    public BigDecimal hl2() {                     // (H+L)/2
        return high.add(low).divide(BigDecimal.valueOf(2));
    }

    public BigDecimal typicalPrice() {            // (H+L+C)/3
        return high.add(low).add(close)
                .divide(BigDecimal.valueOf(3));
    }
}