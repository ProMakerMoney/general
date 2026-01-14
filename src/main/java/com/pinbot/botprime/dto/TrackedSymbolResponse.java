package com.pinbot.botprime.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class TrackedSymbolResponse {
    Long id;
    String symbol;
    String name;
    BybitInterval timeframe;
}
