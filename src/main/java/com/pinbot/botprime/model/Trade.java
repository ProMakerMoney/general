package com.pinbot.botprime.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "trades")
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;            // BTCUSDT

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal price;         // Цена входа

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal volume;        // Кол-во (BTC)

    @Column(length = 5, nullable = false)
    private String direction;         // LONG | SHORT

    @Column(length = 15, nullable = false)
    private String status;            // OPEN | CLOSED | CANCELLED

    @Column(precision = 18, scale = 8)
    private BigDecimal profit;        // PnL (может быть null)

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "closed_at")
    private LocalDateTime closedAt;   // null, пока сделка открыта

    /* getters / setters / (lombok @Data допускается) */
}
