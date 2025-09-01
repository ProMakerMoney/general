package com.pinbot.botprime.trade;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "mainpro_backtest_trades")
public class MainProBacktestTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pair_id", nullable = false)
    private Long pairId;

    @Column(name = "role", nullable = false, length = 6)
    private String role; // MAIN | HEDGE

    @Column(name = "side", nullable = false, length = 5)
    private String side; // LONG | SHORT

    @Column(name = "entry_time", nullable = false)
    private Instant entryTime;

    @Column(name = "entry_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal entryPrice;

    @Column(name = "stop_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal stopPrice;

    @Column(name = "qty_btc", precision = 18, scale = 3, nullable = false)
    private BigDecimal qtyBtc;

    @Column(name = "exit_time", nullable = false)
    private Instant exitTime;

    @Column(name = "exit_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal exitPrice;

    @Column(name = "reason", length = 32, nullable = false)
    private String reason;

    // Getters / Setters
    public Long getId() { return id; }

    public Long getPairId() { return pairId; }
    public void setPairId(Long pairId) { this.pairId = pairId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public Instant getEntryTime() { return entryTime; }
    public void setEntryTime(Instant entryTime) { this.entryTime = entryTime; }

    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }

    public BigDecimal getStopPrice() { return stopPrice; }
    public void setStopPrice(BigDecimal stopPrice) { this.stopPrice = stopPrice; }

    public BigDecimal getQtyBtc() { return qtyBtc; }
    public void setQtyBtc(BigDecimal qtyBtc) { this.qtyBtc = qtyBtc; }

    public Instant getExitTime() { return exitTime; }
    public void setExitTime(Instant exitTime) { this.exitTime = exitTime; }

    public BigDecimal getExitPrice() { return exitPrice; }
    public void setExitPrice(BigDecimal exitPrice) { this.exitPrice = exitPrice; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
