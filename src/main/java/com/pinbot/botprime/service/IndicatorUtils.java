package com.pinbot.botprime.service;

import java.util.ArrayList;
import java.util.List;

public final class IndicatorUtils {

    private IndicatorUtils() {
        // utility class
    }

    /* ---------- EMA ---------- */
    public static List<Double> ema(List<Double> series, int period) {
        int n = series.size();
        List<Double> out = new ArrayList<>(n);
        if (period <= 1 || n == 0) {
            out.addAll(series);
            return out;
        }

        for (int i = 0; i < period - 1 && i < n; i++) out.add(-1.0);

        double alpha = 2.0 / (period + 1.0);
        double sma = 0.0;
        for (int i = 0; i < period && i < n; i++) sma += series.get(i);
        sma /= period;

        double prev = sma;
        out.add(prev);

        for (int i = period; i < n; i++) {
            prev = alpha * series.get(i) + (1 - alpha) * prev;
            out.add(prev);
        }
        return out;
    }

    /* ---------- SMA ---------- */
    public static List<Double> sma(List<Double> series, int period) {
        List<Double> out = new ArrayList<>(series.size());
        double sum = 0.0;

        for (int i = 0; i < series.size(); i++) {
            sum += series.get(i);
            if (i >= period) sum -= series.get(i - period);
            out.add(i >= period - 1 ? sum / period : -1.0);
        }
        return out;
    }

    /* ---------- TEMA ---------- */
    public static List<Double> tema(List<Double> series, int period) {
        List<Double> e1 = ema(series, period);
        List<Double> e2 = ema(replaceNulls(e1), period);
        List<Double> e3 = ema(replaceNulls(e2), period);

        List<Double> out = new ArrayList<>(series.size());
        for (int i = 0; i < series.size(); i++) {
            double v1 = safe(e1.get(i));
            double v2 = safe(e2.get(i));
            double v3 = safe(e3.get(i));
            out.add(3 * v1 - 3 * v2 + v3);
        }
        return out;
    }

    /* ---------- RSI ---------- */
    public static List<Double> rsi(List<Double> series, int period) {
        List<Double> out = new ArrayList<>(series.size());
        double prevClose = -1;
        double avgGain = 0.0, avgLoss = 0.0;

        for (int i = 0; i < series.size(); i++) {
            double close = series.get(i);

            if (prevClose < 0) {
                prevClose = close;
                out.add(-1.0);
                continue;
            }

            double change = close - prevClose;
            double gain = Math.max(0, change);
            double loss = Math.max(0, -change);

            if (i < period) {
                avgGain += gain;
                avgLoss += loss;
                out.add(-1.0);
            } else if (i == period) {
                avgGain /= period;
                avgLoss /= period;
                double rs = avgLoss == 0 ? 100 : avgGain / avgLoss;
                out.add(100 - (100 / (1 + rs)));
            } else {
                avgGain = (avgGain * (period - 1) + gain) / period;
                avgLoss = (avgLoss * (period - 1) + loss) / period;
                double rs = avgLoss == 0 ? 100 : avgGain / avgLoss;
                out.add(100 - (100 / (1 + rs)));
            }

            prevClose = close;
        }
        return out;
    }

    /* ---------- helpers ---------- */
    private static List<Double> replaceNulls(List<Double> list) {
        List<Double> out = new ArrayList<>(list.size());
        for (Double v : list) out.add(safe(v));
        return out;
    }

    public static double safe(Double v) {
        return v == null ? 0.0 : v;
    }
}