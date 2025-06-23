package com.pinbot.botprime.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class CandleDto {

    /** время открытия в мс unix */
    @JsonProperty("start")
    private long startMs;

    /** время закрытия в мс unix */
    @JsonProperty("end")
    private long endMs;

    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;

    /** объём контрактов */
    private BigDecimal volume;

    public Instant getOpenTime()  { return Instant.ofEpochMilli(startMs); }
    public Instant getCloseTime() { return Instant.ofEpochMilli(endMs);  }
}