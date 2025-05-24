package dev.ikara.admintools.util;

public interface PacketCPS {
    void recordClick(long nanoTime);
    int totalClicks();
    double finish();
    String hoverText();
    String name();
}