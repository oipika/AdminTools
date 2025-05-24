package dev.ikara.checks.autoclicker;

import dev.ikara.admintools.util.PacketCPS;
import dev.ikara.admintools.util.CPSMetrics;
import java.util.*;

public class MetricsCPS implements PacketCPS {
    private final List<Long> timestamps = new ArrayList<>();
    private static final double W_MEAN      = 0.0;
    private static final double W_STDDEV    = 2.0;
    private static final double W_SKEWNESS  = 0.5;
    private static final double W_KURTOSIS  = 0.5;
    private static final double W_ENTROPY   = 2.0;
    private static final double W_LQR       = 1.0;
    private static final double TOTAL_WEIGHT= W_MEAN+W_STDDEV+W_SKEWNESS+W_KURTOSIS+W_ENTROPY+W_LQR;

    @Override
    public void recordClick(long nanoTime) {
        timestamps.add(nanoTime);
    }

    @Override
    public int totalClicks() {
        return timestamps.size();
    }

    @Override
    public double finish() {
        if (timestamps.size() < 2) return 0.0;
        List<Integer> cps = new ArrayList<>();
        long windowStart = timestamps.get(0);
        int count = 0;
        for (long t : timestamps) {
            if (t - windowStart < 1_000_000_000L) {
                count++;
            } else {
                cps.add(count);
                count = 1;
                windowStart = t;
            }
        }
        cps.add(count);

        Map<String, Double> contrib = new LinkedHashMap<>();
        contrib.put("Mean",     CPSMetrics.mean(cps)    * W_MEAN    / 20.0);
        contrib.put("StdDev",   CPSMetrics.std(cps)     * W_STDDEV  / 10.0);
        contrib.put("Skewness", Math.abs(CPSMetrics.skewness(cps)) * W_SKEWNESS / 2.0);
        contrib.put("Kurtosis", Math.abs(CPSMetrics.kurtosis(cps)-3.0) * W_KURTOSIS / 2.0);
        contrib.put("Entropy",  (2.0-CPSMetrics.entropy(cps))       * W_ENTROPY / 2.0);
        contrib.put("LQR",      (30.0-CPSMetrics.lqr(cps))           * W_LQR     / 30.0);

        double raw = contrib.values().stream().mapToDouble(d -> d).sum();
        return Math.max(0, Math.min(100, raw / TOTAL_WEIGHT * 100));
    }

    @Override
    public String hoverText() {
        if (timestamps.size() < 2) {
            return "Insufficient data";
        }
        // reuse finish logic to build cps list and contributions
        List<Integer> cps = new ArrayList<>();
        long windowStart = timestamps.get(0);
        int count = 0;
        for (long t : timestamps) {
            if (t - windowStart < 1_000_000_000L) {
                count++;
            } else {
                cps.add(count);
                count = 1;
                windowStart = t;
            }
        }
        cps.add(count);

        Map<String, Double> contrib = new LinkedHashMap<>();
        contrib.put("Mean",     CPSMetrics.mean(cps)    * W_MEAN    / 20.0);
        contrib.put("StdDev",   CPSMetrics.std(cps)     * W_STDDEV  / 10.0);
        contrib.put("Skewness", Math.abs(CPSMetrics.skewness(cps)) * W_SKEWNESS / 2.0);
        contrib.put("Kurtosis", Math.abs(CPSMetrics.kurtosis(cps)-3.0) * W_KURTOSIS / 2.0);
        contrib.put("Entropy",  (2.0-CPSMetrics.entropy(cps))       * W_ENTROPY / 2.0);
        contrib.put("LQR",      (30.0-CPSMetrics.lqr(cps))           * W_LQR     / 30.0);

        return String.format(
            "Mean: %.2f (%.1f)%n" +
            "StdDev: %.2f (%.1f)%n" +
            "Skewness: %.2f (%.1f)%n" +
            "Kurtosis: %.2f (%.1f)%n" +
            "Entropy: %.2f (%.1f)%n" +
            "LQR: %.2f",
            CPSMetrics.mean(cps),     contrib.get("Mean"),
            CPSMetrics.std(cps),      contrib.get("StdDev"),
            CPSMetrics.skewness(cps), contrib.get("Skewness"),
            CPSMetrics.kurtosis(cps), contrib.get("Kurtosis"),
            CPSMetrics.entropy(cps),  contrib.get("Entropy"),
            CPSMetrics.lqr(cps)
        );
    }

    @Override
    public String name() {
        return "metrics";
    }
}
