package dev.ikara.checks.autoclicker;

import dev.ikara.admintools.util.PacketCPS;
import java.util.*;
import static java.lang.Math.*;

public class EntropyCPS implements PacketCPS {
    private final List<Long> intervals = new ArrayList<>();
    private final int minSamples;
    private long last = -1;

    public EntropyCPS(int minSamples) {
        this.minSamples = minSamples;
    }

    @Override public void recordClick(long nanoTime) {
        if (last > 0) intervals.add(nanoTime - last);
        last = nanoTime;
    }
    @Override public int totalClicks() { return intervals.size() + 1; }

    @Override
    public double finish() {
        if (intervals.size() < minSamples) return 0;
        Map<Long, Integer> freq = new HashMap<>();
        for (long dt : intervals) {
            long ms = dt / 1_000_000;
            freq.put(ms, freq.getOrDefault(ms, 0) + 1);
        }
        double ent = 0, n = intervals.size();
        double log2 = log(2);
        for (int c : freq.values()) {
            double p = c / n;
            ent -= p * (log(p) / log2);
        }
        double maxEnt = log(freq.size()) / log2;
        return max(0, (1.0 - min(1.0, ent / maxEnt))) * 100;
    }

    @Override
    public String hoverText() {
        double ratio = finish() / 100.0;
        return String.format("Entropy ratio: %.2f", ratio);
    }

    @Override public String name() { return "entropy"; }
}