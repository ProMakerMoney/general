package com.pinbot.botprime.model;

import lombok.*;

import java.io.Serializable;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
public class IndicatorValueId implements Serializable {
    private String symbol;
    private String timeframe;
    private Long openTime;
}
