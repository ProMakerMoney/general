package com.pinbot.botprime.trade;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "main_backtest_pnl")
public class MainBacktestPnl {
    @Id
    @Column(name = "trade_id")
    private Long tradeId; // PK = FK на main_backtest_trades.id

    @Column(name = "entry_time", nullable = false)
    private Instant entryTime;

    @Column(name = "exit_time", nullable = false)
    private Instant exitTime;

    @Column(name = "side", nullable = false, length = 5)
    private String side; // LONG/SHORT

    @Column(name = "entry_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal entryPrice;

    @Column(name = "stop_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal stopPrice;

    @Column(name = "qty_btc", precision = 18, scale = 3, nullable = false)
    private BigDecimal qtyBtc;

    @Column(name = "tp1_price", precision = 18, scale = 2)
    private BigDecimal tp1Price;   // nullable если TP1 не было

    @Column(name = "tp2_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal tp2Price;   // финальный выход

    @Column(name = "pnl_tp1", precision = 18, scale = 2, nullable = false)
    private BigDecimal pnlTp1;

    @Column(name = "pnl_tp2", precision = 18, scale = 2, nullable = false)
    private BigDecimal pnlTp2;

    @Column(name = "fee_total", precision = 18, scale = 2, nullable = false)
    private BigDecimal feeTotal;

    @Column(name = "net_total", precision = 18, scale = 2, nullable = false)
    private BigDecimal netTotal;

    @Column(name = "reason", length = 32, nullable = false)
    private String reason;

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
    public BigDecimal getStopPrice() { return stopPrice; }
    public void setStopPrice(BigDecimal stopPrice) { this.stopPrice = stopPrice; }
    public BigDecimal getQtyBtc() { return qtyBtc; }
    public void setQtyBtc(BigDecimal qtyBtc) { this.qtyBtc = qtyBtc; }
    public BigDecimal getTp1Price() { return tp1Price; }
    public void setTp1Price(BigDecimal tp1Price) { this.tp1Price = tp1Price; }
    public BigDecimal getTp2Price() { return tp2Price; }
    public void setTp2Price(BigDecimal tp2Price) { this.tp2Price = tp2Price; }
    public BigDecimal getPnlTp1() { return pnlTp1; }
    public void setPnlTp1(BigDecimal pnlTp1) { this.pnlTp1 = pnlTp1; }
    public BigDecimal getPnlTp2() { return pnlTp2; }
    public void setPnlTp2(BigDecimal pnlTp2) { this.pnlTp2 = pnlTp2; }
    public BigDecimal getFeeTotal() { return feeTotal; }
    public void setFeeTotal(BigDecimal feeTotal) { this.feeTotal = feeTotal; }
    public BigDecimal getNetTotal() { return netTotal; }
    public void setNetTotal(BigDecimal netTotal) { this.netTotal = netTotal; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}

