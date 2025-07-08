package com.pinbot.botprime.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Embeddable
@Getter
@Setter
public class CandlePk implements Serializable {
    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "timeframe", nullable = false)
    private String timeframe;

    @Column(name = "open_time", nullable = false)
    private Instant openTime;

    public CandlePk() {}

    public CandlePk(String symbol, String timeframe, Instant openTime) {
        this.symbol = symbol;
        this.timeframe = timeframe;
        this.openTime = openTime;
    }

    // геттеры/сеттеры, equals() и hashCode()

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CandlePk)) return false;
        CandlePk that = (CandlePk) o;
        return Objects.equals(symbol, that.symbol)
                && Objects.equals(timeframe, that.timeframe)
                && Objects.equals(openTime, that.openTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol, timeframe, openTime);
    }

    // getters/setters...
}

