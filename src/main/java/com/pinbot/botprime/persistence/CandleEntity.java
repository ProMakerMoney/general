package com.pinbot.botprime.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "candles")
public class CandleEntity {

    @EmbeddedId
    private CandlePk id;

    @Column(name = "open_time", insertable = false, updatable = false)
    private Instant openTime;

    @Column(name = "close_time", nullable = false)
    private Instant closeTime;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal open;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal high;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal low;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal close;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal volume;

    @Column(name = "quote_volume", nullable = false, precision = 18, scale = 8)
    private BigDecimal quoteVolume;

    public CandleEntity() {}

    // getters / setters

    public CandlePk getId() {
        return id;
    }

    public void setId(CandlePk id) {
        this.id = id;
    }

    public Instant getOpenTime() {
        return openTime;
    }

    public Instant getCloseTime() {
        return closeTime;
    }

    public void setCloseTime(Instant closeTime) {
        this.closeTime = closeTime;
    }

    public BigDecimal getOpen() {
        return open;
    }

    public void setOpen(BigDecimal open) {
        this.open = open;
    }

    public BigDecimal getHigh() {
        return high;
    }

    public void setHigh(BigDecimal high) {
        this.high = high;
    }

    public BigDecimal getLow() {
        return low;
    }

    public void setLow(BigDecimal low) {
        this.low = low;
    }

    public BigDecimal getClose() {
        return close;
    }

    public void setClose(BigDecimal close) {
        this.close = close;
    }

    public BigDecimal getVolume() {
        return volume;
    }

    public void setVolume(BigDecimal volume) {
        this.volume = volume;
    }

    public BigDecimal getQuoteVolume() {
        return quoteVolume;
    }

    public void setQuoteVolume(BigDecimal quoteVolume) {
        this.quoteVolume = quoteVolume;
    }
}
