package com.pinbot.botprime.mapper;

import com.pinbot.botprime.dto.CandleDto;
import com.pinbot.botprime.persistence.CandleEntity;
import com.pinbot.botprime.persistence.CandlePk;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

@Component
public class CandleMapper {

    public CandleEntity toEntity(String symbol, String interval, CandleDto dto) {
        Instant ts = Instant.ofEpochMilli(dto.getStartMs());

        CandlePk pk = new CandlePk(symbol, interval, ts);

        CandleEntity e = new CandleEntity();
        e.setId(pk);
        // open_time в БД из PK, close_time дублируем start
        e.setCloseTime(ts);

        e.setOpen(BigDecimal.valueOf(dto.getOpen()));
        e.setHigh(BigDecimal.valueOf(dto.getHigh()));
        e.setLow(BigDecimal.valueOf(dto.getLow()));
        e.setClose(BigDecimal.valueOf(dto.getClose()));
        e.setVolume(BigDecimal.valueOf(dto.getVolume()));
        e.setQuoteVolume(BigDecimal.valueOf(dto.getQuoteVolume()));

        return e;
    }
}

