package dev.ikara.checks.autoclicker;

import dev.ikara.admintools.util.PacketCPS;
import static java.lang.Math.*;
import java.util.*;

public class VarianceCPS implements PacketCPS {
    private final List<Long> intervals = new ArrayList<>();
    private final int minSamples;
    private final double cvMultiplier;
    private long last = -1;

    public VarianceCPS(int minSamples, double cvMultiplier) {
        this.minSamples = minSamples;
        this.cvMultiplier = cvMultiplier;
    }

    @Override public void recordClick(long nanoTime) {
        if (last > 0) intervals.add(nanoTime - last);
        last = nanoTime;
    }
    @Override public int totalClicks() { return intervals.size() + 1; }

    @Override
    public double finish() {
        if (intervals.size() < minSamples) return 0;
        double avg = intervals.stream() .mapToDouble(i -> i).average().orElse(0);
        double var = intervals.stream().mapToDouble(i -> (i - avg) * (i - avg)).average().orElse(0);
        double cv  = sqrt(var) / avg;
        return max(0, (1.0 - min(1.0, cv * cvMultiplier))) * 100;
    }

    @Override
    public String hoverText() {
        double pct = finish() / 100.0 * cvMultiplier;
        return String.format("StdDev/Mean CV: %.2f%%", pct * 100);
    }

    @Override public String name() { return "variance"; }
}