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
    public void init() throws Exception {
        webClient = WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(
                props.getApiSecret().getBytes(StandardCharsets.UTF_8),
                HMAC_ALGO));
    }

    /** Публичный GET без подписи */
    public Map<String, Object> publicGet(String path, Map<String, String> params) {
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        query.setAll(params);
        return get(path, query, new TypeReference<Map<String, Object>>() {});
    }

    /** Размещение Market-ордера */
    public Map<String, Object> placeMarketOrder(String symbol, String side, String qty) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("category",  "linear");
        body.add("symbol",    symbol);
        body.add("side",      side);
        body.add("orderType", "Market");
        body.add("qty",       qty);
        return post("/v5/order/create", body, new TypeReference<Map<String, Object>>() {});
    }

    /** Сырые данные свечей */
    public Map<String, Object> getCandlesRaw(String symbol, String interval, int limit) {
        MultiValueMap<String, String> q = new LinkedMultiValueMap<>();
        q.add("category", "linear");
        q.add("symbol",   symbol);
        q.add("interval", interval);
        q.add("limit",    String.valueOf(limit));
        return get("/v5/market/kline", q, new TypeReference<Map<String, Object>>() {});
    }

    /** Преобразование в DTO */
    @SuppressWarnings("unchecked")
    public List<CandleDto> getCandles(String symbol, String interval, int limit) {
        // Получаем «сырые» данные
        Map<String, Object> raw = getCandlesRaw(symbol, interval, limit);
        List<List<String>> rows =
                (List<List<String>>) ((Map<?, ?>) raw.get("result")).get("list");

        // Маппим каждую строку согласно новому DTO
        return rows.stream()
                .map(r -> new CandleDto(
                        // 0: start time in ms
                        Long.parseLong(r.get(0)),
                        // 1–6: open, high, low, close, volume, quoteVolume
                        new BigDecimal(r.get(1)).doubleValue(),
                        new BigDecimal(r.get(2)).doubleValue(),
                        new BigDecimal(r.get(3)).doubleValue(),
                        new BigDecimal(r.get(4)).doubleValue(),
                        new BigDecimal(r.get(5)).doubleValue(),
                        new BigDecimal(r.get(6)).doubleValue()
                ))
                .toList();
    }

    /* ——— НИЗКОУРОВНЕВЫЕ МЕТОДЫ ——— */

    private <T> T get(String path,
                      MultiValueMap<String, String> query,
                      TypeReference<T> type) {
        long ts = Instant.now().toEpochMilli();
        String sign = sign(ts + props.getApiKey());

        return webClient.get()
                .uri(u -> u.path(path).queryParams(query).build())
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
            throw new IllegalStateException("Unable to build payload", e);
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

    /** HMAC-SHA256 подпись */
    private String sign(String prehash) {
        try {
            Mac clone = (Mac) mac.clone();
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
        h.set("X-BAPI-API-KEY",     props.getApiKey());
        h.set("X-BAPI-TIMESTAMP",   String.valueOf(ts));
        h.set("X-BAPI-SIGN",        sign);
        h.set("X-BAPI-RECV-WINDOW", String.valueOf(props.getRecvWindow()));
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Bad JSON: " + json, e);
        }
    }
}
