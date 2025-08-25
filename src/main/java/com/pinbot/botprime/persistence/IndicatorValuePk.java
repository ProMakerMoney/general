package com.pinbot.botprime.persistence;

import lombok.*;
import java.io.Serializable;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorValuePk implements Serializable {
    private String symbol;
    private String timeframe;
    private Instant open_time;
}