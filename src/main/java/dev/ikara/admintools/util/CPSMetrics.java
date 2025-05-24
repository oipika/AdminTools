package dev.ikara.admintools.util;

import java.util.*;
import java.util.stream.Collectors;

public final class CPSMetrics {
    private CPSMetrics() {}

    public static double mean(List<Integer> list) {
        return list.stream().mapToDouble(i -> i).average().orElse(0.0);
    }

    public static double std(List<Integer> list) {
        double avg = mean(list);
        return Math.sqrt(list.stream()
                             .mapToDouble(i -> (i - avg) * (i - avg))
                             .average()
                             .orElse(0.0));
    }

    public static double skewness(List<Integer> list) {
        double avg = mean(list), sd = std(list);
        int n = list.size();
        if (sd == 0 || n < 3) return 0.0;
        double sum = list.stream()
                         .mapToDouble(i -> Math.pow((i - avg) / sd, 3))
                         .sum();
        return sum * n / ((n - 1.0) * (n - 2));
    }

    public static double kurtosis(List<Integer> list) {
        double avg = mean(list), sd = std(list);
        int n = list.size();
        if (sd == 0 || n < 4) return 0.0;
        double sum4 = list.stream()
                          .mapToDouble(i -> Math.pow((i - avg) / sd, 4))
                          .sum();
        return (n * (n + 1) * sum4 - 3 * Math.pow(n - 1, 2))
               / ((n - 1.0) * (n - 2) * (n - 3));
    }

    public static double entropy(List<Integer> list) {
        Map<Integer, Long> freq = list.stream()
                                      .collect(Collectors.groupingBy(i -> i, Collectors.counting()));
        double log2 = Math.log(2), ent = 0.0;
        int n = list.size();
        for (long count : freq.values()) {
            double p = (double) count / n;
            ent -= p * (Math.log(p) / log2);
        }
        return ent;
    }

    public static double lqr(List<Integer> list) {
        List<Integer> sorted = new ArrayList<>(list);
        Collections.sort(sorted);
        int q1 = sorted.get(sorted.size() / 4);
        int q3 = sorted.get((sorted.size() * 3) / 4);
        return q3 - q1;
    }
}
