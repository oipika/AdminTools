package dev.ikara.checks.autoclicker;

import dev.ikara.admintools.util.PacketCPS;
import java.util.*;
import static java.lang.Math.*;

public class BurstCPS implements PacketCPS {
    private final List<Long> intervals = new ArrayList<>();
    private long last=-1;
    @Override public void recordClick(long nanoTime){ if(last>0) intervals.add(nanoTime-last); last=nanoTime; }
    @Override public int totalClicks(){ return intervals.size()+1; }

    @Override
    public double finish() {
        if(intervals.isEmpty()) return 0;
        int maxSt=0, st=0;
        for(long dt:intervals){ if(dt<25_000_000L){ st++; maxSt=max(maxSt,st); } else st=0; }
        double ratio=maxSt/(double)intervals.size();
        return min(1.0,ratio*2)*100;
    }
    @Override public String hoverText(){ return String.format("Max perfect burst: %d clicks", (int)((finish()/100)*intervals.size()/2)); }
    @Override public String name(){ return "burst"; }
}
