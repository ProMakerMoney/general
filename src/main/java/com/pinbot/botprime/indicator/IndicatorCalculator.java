package com.pinbot.botprime.indicator;

import com.pinbot.botprime.dto.CandleDto;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Расчёт EMA и TEMA на каждую свечу.
 *
 * Что считаем:
 *  - EMA(shortLen)  по close
 *  - EMA(longLen)   по close
 *  - EMA(110)       по close
 *  - EMA(200)       по close
 *  - TEMA(temaLen)  по HL2 = (high+low)/2, затем сглаживаем SMA(temaSmaLen), как в Pine.
 *
 * Правила:
 *  - Входные свечи должны быть в хронологическом порядке (от старой к новой).
 */
public class IndicatorCalculator {

    /** Результат на один бар. */
    @Data
    @AllArgsConstructor
    public static class IndicatorPoint {
        private long   openTime;     // ms epoch
        private double emaShort;     // EMA(shortLen) по close
        private double emaLong;      // EMA(longLen)  по close
        private double ema110;       // EMA(110)      по close
        private double ema200;       // EMA(200)      по close
        private double temaSmoothed; // SMA(temaSmaLen) от TEMA(temaLen) по HL2
    }

    /**
     * Основной метод расчёта.
     */
    public List<IndicatorPoint> calculate(List<CandleDto> candles,
                                          int shortLen,
                                          int longLen,
                                          int temaLen,
                                          int temaSmaLen) {
        int n = candles.size();
        double[] close = new double[n];
        double[] hl2   = new double[n];

        for (int i = 0; i < n; i++) {
            close[i] = candles.get(i).getClose();
            hl2[i]   = (candles.get(i).getHigh() + candles.get(i).getLow()) / 2.0;
        }

        // EMA по close
        double[] emaShort = emaSeriesPineStyle(close, shortLen);
        double[] emaLong  = emaSeriesPineStyle(close, longLen);
        double[] ema110   = emaSeriesPineStyle(close, 110);
        double[] ema200   = emaSeriesPineStyle(close, 200);

        // TEMA по HL2 + SMA(temaSmaLen), как в Pine
        double[] tema        = temaSeriesPineStyle(hl2, temaLen);
        double[] temaSmooth  = smaSeries(tema, temaSmaLen);

        // Собираем результат
        List<IndicatorPoint> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new IndicatorPoint(
                    candles.get(i).getStartMs(),
                    emaShort[i],
                    emaLong[i],
                    ema110[i],
                    ema200[i],
                    temaSmooth[i]
            ));
        }
        return out;
    }

    // ========================= Pine-compatible EMA =========================

    /** EMA Pine-style: ema[0] = src[0], без SMA в начале. */
    private double[] emaSeriesPineStyle(double[] src, int len) {
        int n = src.length;
        double[] out = new double[n];
        double alpha = 2.0 / (len + 1.0);

        if (n == 0) return out;

        out[0] = src[0];
        for (int i = 1; i < n; i++) {
            out[i] = alpha * src[i] + (1 - alpha) * out[i - 1];
        }
        return out;
    }

    /** TEMA = 3*(EMA1 - EMA2) + EMA3, все EMA по Pine-алгоритму. */
    private double[] temaSeriesPineStyle(double[] src, int len) {
        double[] e1 = emaSeriesPineStyle(src, len);
        double[] e2 = emaSeriesPineStyle(e1, len);
        double[] e3 = emaSeriesPineStyle(e2, len);

        int n = src.length;
        double[] out = new double[n];

        for (int i = 0; i < n; i++) {
            out[i] = 3 * (e1[i] - e2[i]) + e3[i];
        }
        return out;
    }

    /** SMA серия. */
    private double[] smaSeries(double[] src, int len) {
        int n = src.length;
        double[] out = new double[n];
        if (len <= 0 || n == 0) return out;

        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += src[i];
            if (i >= len) sum -= src[i - len];
            if (i >= len - 1) out[i] = sum / len;
            else out[i] = Double.NaN;
        }
        return out;
    }
}
