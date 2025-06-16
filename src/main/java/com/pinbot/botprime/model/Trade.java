package com.pinbot.botprime.model;

import jakarta.persistence.*;
import lombok.Data;          // ★ подключаем Lombok

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data                       // ★ генерирует getters/setters, toString, equals, hashCode
@Entity
@Table(name = "trades")
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal price;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal volume;

    @Column(length = 5, nullable = false)
    private String direction;

    @Column(length = 15, nullable = false)
    private String status;

    @Column(precision = 18, scale = 8)
    private BigDecimal profit;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}
