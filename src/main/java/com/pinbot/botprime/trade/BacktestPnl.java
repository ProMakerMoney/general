package com.pinbot.botprime.trade;


import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;


@Entity
@Table(name = "backtest_pnl")
public class BacktestPnl {
    @Id
    @Column(name = "trade_id")
    private Long tradeId; // PK = FK на backtest_trades.id


    @Column(name = "entry_time", nullable = false)
    private Instant entryTime;


    @Column(name = "exit_time", nullable = false)
    private Instant exitTime;


    @Column(name = "side", nullable = false, length = 5)
    private String side; // LONG/SHORT


    @Column(name = "entry_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal entryPrice;


    @Column(name = "exit_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal exitPrice;


    @Column(name = "qty_btc", precision = 18, scale = 3, nullable = false)
    private BigDecimal qtyBtc;


    @Column(name = "fee_total", precision = 18, scale = 2, nullable = false)
    private BigDecimal feeTotal;


    @Column(name = "gross", precision = 18, scale = 2, nullable = false)
    private BigDecimal gross;


    @Column(name = "net", precision = 18, scale = 2, nullable = false)
    private BigDecimal net;


    public Long getTradeId() { return tradeId; }
    public void setTradeId(Long tradeId) { this.tradeId = tradeId; }
    public Instant getEntryTime() { return entryTime; }
    public void setEntryTime(Instant entryTime) { this.entryTime = entryTime; }
    public Instant getExitTime() { return exitTime; }
    public void setExitTime(Instant exitTime) { this.exitTime = exitTime; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public void setExitPrice(BigDecimal exitPrice) { this.exitPrice = exitPrice; }
    public BigDecimal getQtyBtc() { return qtyBtc; }
    public void setQtyBtc(BigDecimal qtyBtc) { this.qtyBtc = qtyBtc; }
    public BigDecimal getFeeTotal() { return feeTotal; }
    public void setFeeTotal(BigDecimal feeTotal) { this.feeTotal = feeTotal; }
    public BigDecimal getGross() { return gross; }
    public void setGross(BigDecimal gross) { this.gross = gross; }
    public BigDecimal getNet() { return net; }
    public void setNet(BigDecimal net) { this.net = net; }
}
