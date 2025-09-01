package com.pinbot.botprime.trade;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "main_backtest_trades")
public class MainBacktestTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_time", nullable = false)
    private Instant entryTime;

    @Column(name = "side", nullable = false, length = 5)
    private String side; // LONG/SHORT

    @Column(name = "entry_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal entryPrice;

    @Column(name = "stop_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal stopPrice; // исходный стоп (после выбора источника)

    @Column(name = "qty_btc", precision = 18, scale = 3, nullable = false)
    private BigDecimal qtyBtc;

    // --- TP1/TP2 ---
    @Column(name = "tp1_price", precision = 18, scale = 2)
    private BigDecimal tp1Price; // nullable

    @Column(name = "exit_time", nullable = false)
    private Instant exitTime;

    @Column(name = "exit_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal exitPrice;

    @Column(name = "tp2_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal tp2Price; // = exitPrice

    @Column(name = "reason", length = 32, nullable = false)
    private String reason; // STOP_LOSS | ONLY_TP_1 | RSI_CROSS | RSI_75_35 | REVERSAL_CLOSE

    // --- Новые поля для дебага выбора стопа ---
    @Column(name = "stop_source", length = 12)
    private String stopSource; // TEMA9 | EMA110 | CROSS

    @Column(name = "impulse")
    private Boolean impulse; // true/false

    // getters/setters
    public Long getId() { return id; }

    public Instant getEntryTime() { return entryTime; }
    public void setEntryTime(Instant entryTime) { this.entryTime = entryTime; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }

    public BigDecimal getStopPrice() { return stopPrice; }
    public void setStopPrice(BigDecimal stopPrice) { this.stopPrice = stopPrice; }

    public BigDecimal getQtyBtc() { return qtyBtc; }
    public void setQtyBtc(BigDecimal qtyBtc) { this.qtyBtc = qtyBtc; }

    public BigDecimal getTp1Price() { return tp1Price; }
    public void setTp1Price(BigDecimal tp1Price) { this.tp1Price = tp1Price; }

    public Instant getExitTime() { return exitTime; }
    public void setExitTime(Instant exitTime) { this.exitTime = exitTime; }

    public BigDecimal getExitPrice() { return exitPrice; }
    public void setExitPrice(BigDecimal exitPrice) { this.exitPrice = exitPrice; }

    public BigDecimal getTp2Price() { return tp2Price; }
    public void setTp2Price(BigDecimal tp2Price) { this.tp2Price = tp2Price; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getStopSource() { return stopSource; }
    public void setStopSource(String stopSource) { this.stopSource = stopSource; }

    public Boolean getImpulse() { return impulse; }
    public void setImpulse(Boolean impulse) { this.impulse = impulse; }
}