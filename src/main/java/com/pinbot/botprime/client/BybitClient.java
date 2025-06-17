package com.pinbot.botprime.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pinbot.botprime.config.BybitProperties;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/** Мини-обёртка над REST v5 API Bybit. */
@Component
@RequiredArgsConstructor
@Slf4j
public class BybitClient {

    private static final String HMAC_ALGO = "HmacSHA256";

    private final BybitProperties props;
    private final ObjectMapper mapper;

    private WebClient webClient;
    private Mac mac;

    @PostConstruct
    void init() throws Exception {
        webClient = WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(
                props.getApiSecret().getBytes(StandardCharsets.UTF_8),
                HMAC_ALGO
        ));
    }

    /* ---------- Публичные методы ---------- */

    /** Получить свечи */
    public Map<String, Object> getCandles(String symbol, String interval, int limit) {
        MultiValueMap<String,String> q = new LinkedMultiValueMap<>();
        q.add("category", "linear");
        q.add("symbol", symbol);
        q.add("interval", interval);
        q.add("limit", String.valueOf(limit));

        return get("/v5/market/kline", q, new TypeReference<>(){});
    }

    /** Маркет-ордер */
    public Map<String, Object> placeMarketOrder(String symbol,
                                                String side,
                                                String qty) {
        MultiValueMap<String,String> body = new LinkedMultiValueMap<>();
        body.add("category", "linear");
        body.add("symbol",   symbol);
        body.add("side",     side);
        body.add("orderType","Market");
        body.add("qty",      qty);
        return post("/v5/order/create", body, new TypeReference<>(){});
    }

    /* ---------- low-level wrappers ---------- */

    private <T> T get(String path,
                      MultiValueMap<String,String> query,
                      TypeReference<T> type) {
        long ts = Instant.now().toEpochMilli();
        String sign = sign(ts + props.getApiKey());

        return webClient.get().uri(uriBuilder ->
                        uriBuilder.path(path).queryParams(query).build())
                .headers(h -> authHeaders(h, ts, sign))
                .retrieve()
                .bodyToMono(String.class)
                .map(json -> read(json, type))
                .block();
    }

    // BybitClient.java
    private <T> T post(String path,
                       MultiValueMap<String,String> body,
                       TypeReference<T> type) {

        long ts = Instant.now().toEpochMilli();

        String payload;
        try {
            payload = mapper.writeValueAsString(body.toSingleValueMap());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to build Bybit payload", e);
        }

        String sign = sign(ts + props.getApiKey() + payload);

        return webClient.post().uri(path)
                .headers(h -> authHeaders(h, ts, sign))
                .body(BodyInserters.fromValue(payload))
                .retrieve()
                .bodyToMono(String.class)
                .map(json -> read(json, type))
                .block();
    }

    private String sign(String prehash) {
        byte[] raw = mac.doFinal(prehash.getBytes(StandardCharsets.UTF_8));
        return Hex.encodeHexString(raw);
    }

    private void authHeaders(HttpHeaders h, long ts, String sign) {
        h.set("X-BAPI-API-KEY", props.getApiKey());
        h.set("X-BAPI-TIMESTAMP", String.valueOf(ts));
        h.set("X-BAPI-SIGN", sign);
        h.set("X-BAPI-RECV-WINDOW", "5000");
    }

    private <T> T read(String json, TypeReference<T> t) {
        try { return mapper.readValue(json, t); }
        catch (Exception e) { throw new IllegalStateException("Bad JSON: "+json, e); }
    }
}
