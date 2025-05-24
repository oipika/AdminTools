package dev.ikara.checks.autoclicker;

import dev.ikara.admintools.util.PacketCPS;
import java.util.*;
import static java.lang.Math.*;

public class VarianceCPS implements PacketCPS {
    private final List<Long> intervals = new ArrayList<>();
    private long last = -1;

    @Override
    public void recordClick(long nanoTime) {
        if (last>0) intervals.add(nanoTime - last);
        last = nanoTime;
    }
    @Override public int totalClicks() { return intervals.size()+1; }

    @Override
    public double finish() {
        if (intervals.size()<5) return 0;
        double avg = intervals.stream().mapToDouble(i->i).average().orElse(0);
        double var = intervals.stream().mapToDouble(i->(i-avg)*(i-avg)).average().orElse(0);
        double cv  = sqrt(var)/avg;
        return max(0,(1.0-min(1.0,cv*5))*100);
    }
    @Override public String hoverText() {
        double pct= finish()/100*5;
        return String.format("StdDev/Mean CV: %.2f%%", pct*100);
    }
    @Override public String name(){ return "variance"; }
}
