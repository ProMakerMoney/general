package com.pinbot.botprime.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pinbot.botprime.config.BybitProperties;
import com.pinbot.botprime.dto.CandleDto;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Мини-обёртка над REST v5 API Bybit. */
@Component
@RequiredArgsConstructor
@Slf4j
public class BybitClient {

    private static final String HMAC_ALGO = "HmacSHA256";

    private final BybitProperties props;
    private final ObjectMapper    mapper;

    private WebClient webClient;
    private Mac       mac;

    @PostConstruct
    void init() throws Exception {
        webClient = WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(props.getApiSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
    }

    /* ---------- PUBLIC API ---------- */

    /**
     * Публичный GET-запрос без подписей.
     *
     * @param path   эндпоинт, начиная с «/»
     * @param params map с query-string параметрами
     * @return разобранный JSON как Map<String, Object>
     */
    public Map<String, Object> publicGet(String path,
                                         Map<String, String> params) {

        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        query.setAll(params);

        // TypeReference позволяет Jackson корректно десериализовать generic-Map
        return get(path, query, new TypeReference<>() {});
    }


    /** Маркет-ордер. */
    public Map<String, Object> placeMarketOrder(String symbol, String side, String qty) {

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("category",  "linear");
        body.add("symbol",    symbol);
        body.add("side",      side);          // Buy / Sell
        body.add("orderType", "Market");
        body.add("qty",       qty);

        return post("/v5/order/create", body, new TypeReference<>() {});
    }

    /** Сырые свечи (raw JSON → Map). */
    public Map<String, Object> getCandlesRaw(String symbol, String interval, int limit) {

        MultiValueMap<String, String> q = new LinkedMultiValueMap<>();
        q.add("category", "linear");
        q.add("symbol",   symbol);
        q.add("interval", interval);                // 1, 3, 5, 15, 60…
        q.add("limit",    String.valueOf(limit));   // 1-1000

        return get("/v5/market/kline", q, new TypeReference<>() {});
    }

    /** Конвертируем в DTO со *BigDecimal*. */
    @SuppressWarnings("unchecked")
    public List<CandleDto> getCandles(String symbol, String interval, int limit) {

        Map<String, Object> raw = getCandlesRaw(symbol, interval, limit);

        List<List<String>> rows =
                (List<List<String>>) ((Map<?, ?>) raw.get("result")).get("list");

        return rows.stream().map(r -> {
            CandleDto c = new CandleDto();
            c.setStartMs(Long.parseLong(r.get(0)));
            c.setEndMs  (Long.parseLong(r.get(1)));
            c.setOpen  (new BigDecimal(r.get(2)));
            c.setHigh  (new BigDecimal(r.get(3)));
            c.setLow   (new BigDecimal(r.get(4)));
            c.setClose (new BigDecimal(r.get(5)));
            c.setVolume(new BigDecimal(r.get(6)));
            return c;
        }).toList();
    }

    /* ---------- LOW-LEVEL ---------- */

    private <T> T get(String path,
                      MultiValueMap<String, String> query,
                      TypeReference<T> type) {
        long ts   = Instant.now().toEpochMilli();
        String sign = sign(ts + props.getApiKey());

        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path(path)
                        .queryParams(query)
                        .build())
                .headers(h -> authHeaders(h, ts, sign))
                .retrieve()
                .bodyToMono(String.class)
                .map(json -> read(json, type))
                .block();
    }

    private <T> T post(String path,
                       MultiValueMap<String, String> body,
                       TypeReference<T> type) {

        long ts = Instant.now().toEpochMilli();

        String payload;
        try {
            payload = mapper.writeValueAsString(body.toSingleValueMap());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to build Bybit payload", e);
        }

        String sign = sign(ts + props.getApiKey() + payload);

        return webClient.post()
                .uri(path)
                .headers(h -> authHeaders(h, ts, sign))
                .body(BodyInserters.fromValue(payload))
                .retrieve()
                .bodyToMono(String.class)
                .map(json -> read(json, type))
                .block();
    }

    /* ---------- HELPERS ---------- */

    /** Потокобезопасная HMAC-подпись. */
    private String sign(String prehash) {
        try {
            Mac clone = (Mac) mac.clone();              // Mac не thread-safe
            byte[] raw = clone.doFinal(prehash.getBytes(StandardCharsets.UTF_8));
            return Hex.encodeHexString(raw);
        } catch (CloneNotSupportedException e) {
            synchronized (mac) {
                byte[] raw = mac.doFinal(prehash.getBytes(StandardCharsets.UTF_8));
                return Hex.encodeHexString(raw);
            }
        }
    }

    private void authHeaders(HttpHeaders h, long ts, String sign) {
        h.set("X-BAPI-API-KEY", props.getApiKey());
        h.set("X-BAPI-TIMESTAMP", String.valueOf(ts));
        h.set("X-BAPI-SIGN", sign);
        h.set("X-BAPI-RECV-WINDOW", String.valueOf(props.getRecvWindow()));
    }

    private <T> T read(String json, TypeReference<T> t) {
        try { return mapper.readValue(json, t); }
        catch (Exception e) {
            throw new IllegalStateException("Bad JSON: " + json, e);
        }
    }
}