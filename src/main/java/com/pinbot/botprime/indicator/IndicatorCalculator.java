package com.pinbot.botprime.indicator;

import com.pinbot.botprime.dto.CandleDto;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Расчёт EMA и TEMA на каждую свечу.
 *
 * - EMA(11), EMA(30), EMA(110), EMA(200) по close
 * - TEMA(9) по HL2, сглаженная через SMA(10), как в Pine
 */
public class IndicatorCalculator {

    /** Результат на один бар. */
    @Data
    @AllArgsConstructor
    public static class IndicatorPoint {
        private long   openTime;     // ms epoch
        private double emaShort;     // EMA(11) по close
        private double emaLong;      // EMA(30)  по close
        private double ema110;       // EMA(110) по close
        private double ema200;       // EMA(200) по close
        private double temaSmoothed; // SMA(10) от TEMA(9) по HL2
    }

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

        double[] emaShort = emaSeries(close, shortLen);
        double[] emaLong  = emaSeries(close, longLen);
        double[] ema110   = emaSeries(close, 110);
        double[] ema200   = emaSeries(close, 200);

        double[] tema       = temaSeries(hl2, temaLen);
        double[] temaSmooth = smaSeries(tema, temaSmaLen);

        int warmup = 3 * (temaLen - 1) + (temaSmaLen - 1);

        List<IndicatorPoint> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new IndicatorPoint(
                    candles.get(i).getStartMs(),
                    emaShort[i],
                    emaLong[i],
                    ema110[i],
                    ema200[i],
                    i < warmup ? Double.NaN : temaSmooth[i]
            ));
        }
        return out;
    }

    private double[] emaSeries(double[] src, int len) {
        int n = src.length;
        double[] out = new double[n];
        if (len <= 0 || n == 0) return fillNaN(n);

        double k = 2.0 / (len + 1);
        double sma = 0.0;
        for (int i = 0; i < len; i++) sma += src[i];
        sma /= len;

        for (int i = 0; i < len - 1; i++) out[i] = Double.NaN;
        out[len - 1] = sma;

        for (int i = len; i < n; i++) out[i] = k * src[i] + (1 - k) * out[i - 1];
        return out;
    }

    private double[] smaSeries(double[] src, int len) {
        int n = src.length;
        double[] out = new double[n];
        if (len <= 0 || n == 0) return fillNaN(n);

        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += src[i];
            if (i >= len) sum -= src[i - len];
            out[i] = i >= len - 1 ? sum / len : Double.NaN;
        }
        return out;
    }

    private double[] temaSeries(double[] src, int len) {
        double[] e1 = emaSeries(src, len);
        double[] e2 = emaSeries(e1, len);
        double[] e3 = emaSeries(e2, len);

        int n = src.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(e1[i]) || Double.isNaN(e2[i]) || Double.isNaN(e3[i])) {
                out[i] = Double.NaN;
            } else {
                out[i] = 3 * (e1[i] - e2[i]) + e3[i];
            }
        }
        return out;
    }

    private double[] fillNaN(int n) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = Double.NaN;
        return out;
    }
}

