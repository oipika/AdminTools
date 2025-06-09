package dev.ikara.checks.autoclicker;

import dev.ikara.admintools.util.PacketCPS;
import dev.ikara.admintools.util.CPSMetrics;
import java.util.*;
import static java.lang.Math.*;

public class MetricsCPS implements PacketCPS {
    private final List<Long> timestamps = new ArrayList<>();
    private final double wMean, wStd, wSkew, wKurt, wEnt, wLqr;
    private final double totalWeight;

    public MetricsCPS(double wMean,
                      double wStd,
                      double wSkew,
                      double wKurt,
                      double wEnt,
                      double wLqr) {
        this.wMean = wMean;
        this.wStd = wStd;
        this.wSkew = wSkew;
        this.wKurt = wKurt;
        this.wEnt = wEnt;
        this.wLqr = wLqr;
        this.totalWeight = wMean + wStd + wSkew + wKurt + wEnt + wLqr;
    }

    @Override public void recordClick(long nanoTime) {
        timestamps.add(nanoTime);
    }

    @Override public int totalClicks() {
        return timestamps.size();
    }

    @Override
    public double finish() {
        if (timestamps.size() < 2) return 0;
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
        contrib.put("Mean", CPSMetrics.mean(cps) * wMean / 20.0);
        contrib.put("StdDev", CPSMetrics.std(cps) * wStd / 10.0);
        contrib.put("Skewness", abs(CPSMetrics.skewness(cps)) * wSkew / 2.0);
        contrib.put("Kurtosis", abs(CPSMetrics.kurtosis(cps)-3.0) * wKurt / 2.0);
        contrib.put("Entropy", (2.0 - CPSMetrics.entropy(cps)) * wEnt / 2.0);
        contrib.put("LQR", (30.0 - CPSMetrics.lqr(cps)) * wLqr / 30.0);

        double raw = contrib.values().stream().mapToDouble(d -> d).sum();
        return max(0, min(100, raw / totalWeight * 100));
    }

    @Override
    public String hoverText() {
        if (timestamps.size() < 2) {
            return "Insufficient data";
        }

        // rebuild the same CPS windows and contributions:
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
        contrib.put("Mean", CPSMetrics.mean(cps) * wMean / 20.0);
        contrib.put("StdDev", CPSMetrics.std(cps) * wStd / 10.0);
        contrib.put("Skewness", abs(CPSMetrics.skewness(cps)) * wSkew / 2.0);
        contrib.put("Kurtosis", abs(CPSMetrics.kurtosis(cps)-3.0) * wKurt / 2.0);
        contrib.put("Entropy", (2.0 - CPSMetrics.entropy(cps)) * wEnt / 2.0);
        contrib.put("LQR", (30.0 - CPSMetrics.lqr(cps)) * wLqr / 30.0);

        return String.format(
            "Mean: %.2f (%.1f)%n" +
            "StdDev: %.2f (%.1f)%n" +
            "Skewness: %.2f (%.1f)%n" +
            "Kurtosis: %.2f (%.1f)%n" +
            "Entropy: %.2f (%.1f)%n" +
            "LQR: %.2f",
            CPSMetrics.mean(cps), contrib.get("Mean"),
            CPSMetrics.std(cps), contrib.get("StdDev"),
            CPSMetrics.skewness(cps), contrib.get("Skewness"),
            CPSMetrics.kurtosis(cps), contrib.get("Kurtosis"),
            CPSMetrics.entropy(cps), contrib.get("Entropy"),
            CPSMetrics.lqr(cps)
        );
    }

    @Override public String name() {
        return "metrics";
    }
}