package com.pinbot.botprime.trade;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "mainpro_backtest_pnl")
public class MainProBacktestPnl {

    @Id
    @Column(name = "trade_id")
    private Long tradeId; // PK = FK на mainpro_backtest_trades.id

    @Column(name = "pair_id", nullable = false)
    private Long pairId;

    @Column(name = "role", nullable = false, length = 6)
    private String role; // MAIN | HEDGE

    @Column(name = "side", nullable = false, length = 5)
    private String side; // LONG | SHORT

    @Column(name = "entry_time", nullable = false)
    private Instant entryTime;

    @Column(name = "exit_time", nullable = false)
    private Instant exitTime;

    @Column(name = "entry_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal entryPrice;

    @Column(name = "stop_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal stopPrice;

    @Column(name = "qty_btc", precision = 18, scale = 3, nullable = false)
    private BigDecimal qtyBtc;

    @Column(name = "pnl_gross", precision = 18, scale = 2, nullable = false)
    private BigDecimal pnlGross;

    @Column(name = "fee_total", precision = 18, scale = 2, nullable = false)
    private BigDecimal feeTotal;

    @Column(name = "pnl_net", precision = 18, scale = 2, nullable = false)
    private BigDecimal pnlNet;

    @Column(name = "reason", length = 32, nullable = false)
    private String reason;

    // Getters / Setters
    public Long getTradeId() { return tradeId; }
    public void setTradeId(Long tradeId) { this.tradeId = tradeId; }

    public Long getPairId() { return pairId; }
    public void setPairId(Long pairId) { this.pairId = pairId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public Instant getEntryTime() { return entryTime; }
    public void setEntryTime(Instant entryTime) { this.entryTime = entryTime; }

    public Instant getExitTime() { return exitTime; }
    public void setExitTime(Instant exitTime) { this.exitTime = exitTime; }

    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }

    public BigDecimal getStopPrice() { return stopPrice; }
    public void setStopPrice(BigDecimal stopPrice) { this.stopPrice = stopPrice; }

    public BigDecimal getQtyBtc() { return qtyBtc; }
    public void setQtyBtc(BigDecimal qtyBtc) { this.qtyBtc = qtyBtc; }

    public BigDecimal getPnlGross() { return pnlGross; }
    public void setPnlGross(BigDecimal pnlGross) { this.pnlGross = pnlGross; }

    public BigDecimal getFeeTotal() { return feeTotal; }
    public void setFeeTotal(BigDecimal feeTotal) { this.feeTotal = feeTotal; }

    public BigDecimal getPnlNet() { return pnlNet; }
    public void setPnlNet(BigDecimal pnlNet) { this.pnlNet = pnlNet; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
