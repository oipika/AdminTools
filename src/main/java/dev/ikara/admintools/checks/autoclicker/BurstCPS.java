package dev.ikara.checks.autoclicker;

import dev.ikara.admintools.util.PacketCPS;
import static java.lang.Math.*;
import java.util.*;

public class BurstCPS implements PacketCPS {
    private final List<Long> intervals = new ArrayList<>();
    private final long dtThreshold;
    private final double ratioScale;
    private long last = -1;

    // Constructor takes config values
    public BurstCPS(long dtThresholdMillis, double ratioScale) {
        this.dtThreshold = dtThresholdMillis * 1_000_000L;
        this.ratioScale = ratioScale;
    }

    @Override public void recordClick(long nanoTime) {
        if (last > 0) intervals.add(nanoTime - last);
        last = nanoTime;
    }
    @Override public int totalClicks() { return intervals.size() + 1; }

    @Override
    public double finish() {
        if (intervals.isEmpty()) return 0;
        int maxSt = 0, st = 0;
        for (long dt : intervals) {
            if (dt < dtThreshold) {
                st++;
                maxSt = max(maxSt, st);
            } else st = 0;
        }
        double ratio = maxSt / (double) intervals.size();
        return min(1.0, ratio * ratioScale) * 100;
    }

    @Override
    public String hoverText() {
        double rawPct = finish() / 100.0;
        int burstClicks = (int) (rawPct * intervals.size() / ratioScale);
        return String.format("Max perfect burst: %d clicks", burstClicks);
    }

    @Override public String name() { return "burst"; }
}