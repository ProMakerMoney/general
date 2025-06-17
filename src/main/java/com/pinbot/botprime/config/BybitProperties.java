package com.pinbot.botprime.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Данные доступа к REST-API Bybit (v5). */
@Getter @Setter
@Validated
@ConfigurationProperties(prefix = "bybit")
public class BybitProperties {

    /** Публичный ключ. */
    @NotBlank private String apiKey;

    /** Приватный ключ. */
    @NotBlank private String apiSecret;

    /** Базовый URL, по умолчанию prod REST-endpoint. */
    private String baseUrl = "https://api.bybit.com";
}
