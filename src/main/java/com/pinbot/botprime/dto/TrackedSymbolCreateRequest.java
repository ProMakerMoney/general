package com.pinbot.botprime.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TrackedSymbolCreateRequest {

    @NotBlank
    @Size(max = 50)
    private String symbol;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotNull
    private BybitInterval timeframe;
}
