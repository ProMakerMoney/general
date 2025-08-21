package com.pinbot.botprime.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "indicator_values")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@IdClass(IndicatorValueId.class)
public class IndicatorValue {

    @Id
    @Column(name = "symbol", length = 32, nullable = false)
    private String symbol;

    @Id
    @Column(name = "timeframe", length = 8, nullable = false)
    private String timeframe;


    @Id
    @Column(name = "open_time", nullable = false)
    private Instant openTime;

    @Column(name = "ema_short")
    private Double emaShort;

    @Column(name = "ema_long")
    private Double emaLong;

    @Column(name = "ema110")
    private Double ema110;

    @Column(name = "ema200")
    private Double ema200;

    @Column(name = "tema_smoothed")
    private Double temaSmoothed;
}

