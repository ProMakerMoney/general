package com.pinbot.botprime.trade;


import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;


@Entity
@Table(name = "backtest_trades")
public class BacktestTrade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    @Column(name = "entry_time", nullable = false)
    private Instant entryTime; // UTC


    @Column(name = "side", nullable = false, length = 5)
    private String side; // LONG / SHORT


    @Column(name = "entry_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal entryPrice;


    @Column(name = "stop_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal stopPrice;


    @Column(name = "qty_btc", precision = 18, scale = 3, nullable = false)
    private BigDecimal qtyBtc;


    @Column(name = "exit_time", nullable = false)
    private Instant exitTime; // UTC


    @Column(name = "exit_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal exitPrice;


    public Long getId() { return id; }
    public Instant getEntryTime() { return entryTime; }
    public String getSide() { return side; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public BigDecimal getStopPrice() { return stopPrice; }
    public BigDecimal getQtyBtc() { return qtyBtc; }
    public Instant getExitTime() { return exitTime; }
    public BigDecimal getExitPrice() { return exitPrice; }


    public void setEntryTime(Instant entryTime) { this.entryTime = entryTime; }
    public void setSide(String side) { this.side = side; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public void setStopPrice(BigDecimal stopPrice) { this.stopPrice = stopPrice; }
    public void setQtyBtc(BigDecimal qtyBtc) { this.qtyBtc = qtyBtc; }
    public void setExitTime(Instant exitTime) { this.exitTime = exitTime; }
    public void setExitPrice(BigDecimal exitPrice) { this.exitPrice = exitPrice; }
}