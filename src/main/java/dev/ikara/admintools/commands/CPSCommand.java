package dev.ikara.admintools.commands;

import dev.ikara.admintools.AdminTools;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class CPSCommand implements CommandExecutor {
    private final AdminTools plugin;
    private final Map<UUID, List<Long>> clickData = new HashMap<>();
    private final Map<UUID, BukkitRunnable> activeTests = new HashMap<>();

    // weights for cheating‐detection contributions
    private static final double W_MEAN      = 0.0;
    private static final double W_STDDEV    = 2.0;
    private static final double W_SKEWNESS  = 0.5;
    private static final double W_KURTOSIS  = 0.5;
    private static final double W_ENTROPY   = 2.0;
    private static final double W_LQR       = 1.0;
    private static final double TOTAL_WEIGHT = W_MEAN + W_STDDEV + W_SKEWNESS + W_KURTOSIS + W_ENTROPY + W_LQR;

    public CPSCommand(AdminTools plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("admintools.cps")) {
            sender.sendMessage(color("&cYou do not have permission to use this command."));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(color("&cUsage: /cpstest <player> [amount][s|cps]"));
            sender.sendMessage(color("&cExample: /cpstest oipika 10s (10 seconds) or /cpstest oipika 100cps (100 clicks)"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(color("&cThat player is not online."));
            return true;
        }

        // default: 10 seconds
        boolean isTimeTest = true;
        int amount = 10;

        if (args.length >= 2) {
            String arg = args[1].trim().toLowerCase();
            try {
                // check "cps" *before* generic "s"
                if (arg.endsWith("cps")) {
                    String numStr = arg.substring(0, arg.length() - 3);
                    if (numStr.isEmpty()) throw new NumberFormatException();
                    amount = Integer.parseInt(numStr);
                    if (amount < 1 || amount > 10000) {
                        sender.sendMessage(color("&cClicks must be between 1 and 10000."));
                        return true;
                    }
                    isTimeTest = false;

                } else if (arg.endsWith("s")) {
                    String numStr = arg.substring(0, arg.length() - 1);
                    if (numStr.isEmpty()) throw new NumberFormatException();
                    amount = Integer.parseInt(numStr);
                    if (amount < 1 || amount > 60) {
                        sender.sendMessage(color("&cDuration must be between 1 and 60 seconds."));
                        return true;
                    }
                    isTimeTest = true;

                } else {
                    amount = Integer.parseInt(arg);
                    if (amount < 1 || amount > 60) {
                        sender.sendMessage(color("&cDuration must be between 1 and 60 seconds."));
                        return true;
                    }
                    isTimeTest = true;
                }
            } catch (NumberFormatException ex) {
                sender.sendMessage(color("&cInvalid value. Must be a number followed by 's' or 'cps'."));
                return true;
            }
        }

        UUID uuid = target.getUniqueId();
        if (activeTests.containsKey(uuid)) {
            sender.sendMessage(color("&cThat player is already being tested."));
            return true;
        }

        clickData.put(uuid, new ArrayList<>());

        final Player t = target;
        final CommandSender s = sender;
        final boolean timeTest = isTimeTest;
        final int testAmount = amount;

        BukkitRunnable testTask = new BukkitRunnable() {
            private int secondsLeft = testAmount;
            private int previousClicks = 0;
            private final List<Integer> cpsList = new ArrayList<>();

            private void sendHoverMessage(int cps) {
                String stats = String.format(
                    "Mean: %.2f%nStdDev: %.2f%nSkewness: %.2f%nKurtosis: %.2f%nEntropy: %.2f%nLQR: %.2f",
                    mean(cpsList), std(cpsList), skewness(cpsList),
                    kurtosis(cpsList), entropy(cpsList), lqr(cpsList)
                );
                TextComponent base = new TextComponent(color(
                    String.format("&8[&6CPS&8] &f%s&7: &6%d CPS", t.getName(), cps)
                ));
                base.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder(stats).create()));
                if (s instanceof Player) {
                    ((Player) s).spigot().sendMessage(base);
                } else {
                    s.sendMessage(base.toLegacyText());
                }
            }

            private void finishTest() {
                final List<Integer> snapshot = new ArrayList<>(cpsList);
                final int finalClicks = clickData.getOrDefault(t.getUniqueId(), Collections.emptyList()).size();

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    Map<String, Double> contrib = computeContributions(snapshot);
                    double rawTotal = contrib.values().stream().mapToDouble(Double::doubleValue).sum();
                    final double pct = Math.max(0, Math.min(100, rawTotal / TOTAL_WEIGHT * 100));

                    StringBuilder hoverText = new StringBuilder();
                    hoverText.append(String.format("Mean: %.2f (%.1f)%n", mean(snapshot), contrib.get("Mean")));
                    hoverText.append(String.format("StdDev: %.2f (%.1f)%n", std(snapshot), contrib.get("StdDev")));
                    hoverText.append(String.format("Skewness: %.2f (%.1f)%n", skewness(snapshot), contrib.get("Skewness")));
                    hoverText.append(String.format("Kurtosis: %.2f (%.1f)%nEntropy: %.2f (%.1f)%nLQR: %.2f",
                        kurtosis(snapshot), contrib.get("Kurtosis"),
                        entropy(snapshot), contrib.get("Entropy"),
                        lqr(snapshot)));

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        s.sendMessage("");
                        s.sendMessage(color("&a&lCPS TEST FINISHED &7– &f" + t.getName() + " &7[" + finalClicks + " clicks]"));
                        s.sendMessage("");

                        if (timeTest) {
                            s.sendMessage(color(String.format(
                                "&7Average: &6%.2f CPS   &7Minimum: &6%d CPS   &7Maximum: &6%d CPS",
                                mean(snapshot), Collections.min(snapshot), Collections.max(snapshot)
                            )));
                            s.sendMessage("");
                        } else {
                            s.sendMessage(color(String.format(
                                "&7Total Clicks: &6%d", finalClicks
                            )));
                            s.sendMessage("");
                        }

                        TextComponent verdict = new TextComponent(color(
                            "&fResult&7: " + (pct >= 85 ? "&cSuspicious" : "&aClean")
                            + String.format(" (%.1f%% sure)", pct)
                        ));
                        BaseComponent[] hoverComp = new ComponentBuilder(hoverText.toString()).create();
                        verdict.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverComp));

                        if (s instanceof Player) {
                            ((Player) s).spigot().sendMessage(verdict);
                        } else {
                            s.sendMessage(verdict.toLegacyText());
                        }

                        s.sendMessage("");
                        cleanup(t.getUniqueId());
                    });
                });
            }

            @Override
            public void run() {
                if (!t.isOnline()) {
                    s.sendMessage(color("&cThe player went offline."));
                    cleanup(t.getUniqueId());
                    cancel();
                    return;
                }

                List<Long> raw = clickData.getOrDefault(t.getUniqueId(), Collections.emptyList());
                int currClicks = raw.size();
                int delta = currClicks - previousClicks;
                previousClicks = currClicks;

                cpsList.add(delta);
                sendHoverMessage(delta);

                if (timeTest) {
                    if (--secondsLeft <= 0) {
                        finishTest();
                        cancel();
                    }
                } else {
                    if (currClicks >= testAmount) {
                        finishTest();
                        cancel();
                    }
                }
            }
        };

        activeTests.put(uuid, testTask);
        testTask.runTaskTimer(plugin, 20L, 20L);

        String typeStr = isTimeTest ? (amount + " seconds") : (amount + " clicks");
        sender.sendMessage(color("&aStarted CPS test on &6" + target.getName() + " &afor &6" + typeStr + "&a."));
        return true;
    }

    public void recordClick(Player player) {
        List<Long> list = clickData.get(player.getUniqueId());
        if (list != null) list.add(System.currentTimeMillis());
    }

    private void cleanup(UUID uuid) {
        activeTests.remove(uuid);
        clickData.remove(uuid);
    }

    private String color(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    private double mean(List<Integer> list) {
        return list.stream().mapToDouble(i -> i).average().orElse(0.0);
    }

    private double std(List<Integer> list) {
        double avg = mean(list);
        return Math.sqrt(list.stream().mapToDouble(i -> (i - avg) * (i - avg)).average().orElse(0.0));
    }

    private double skewness(List<Integer> list) {
        double avg = mean(list);
        double stddev = std(list);
        int n = list.size();
        return stddev == 0 || n < 3 ? 0.0 : list.stream().mapToDouble(i -> Math.pow((i - avg) / stddev, 3)).sum() * n / ((n - 1.0) * (n - 2));
    }

    private double kurtosis(List<Integer> list) {
        double avg = mean(list);
        double stddev = std(list);
        int n = list.size();
        return stddev == 0 || n < 4 ? 0.0 :
            (n * (n + 1) * list.stream().mapToDouble(i -> Math.pow((i - avg) / stddev, 4)).sum()
            - 3 * Math.pow(n - 1, 2)) / ((n - 1.0) * (n - 2) * (n - 3));
    }

    private double entropy(List<Integer> list) {
        Map<Integer, Integer> freq = new HashMap<>();
        for (int val : list) freq.put(val, freq.getOrDefault(val, 0) + 1);
        double log2 = Math.log(2);
        double entropy = 0.0;
        int n = list.size();
        for (int count : freq.values()) {
            double p = (double) count / n;
            entropy -= p * (Math.log(p) / log2);
        }
        return entropy;
    }

    private double lqr(List<Integer> list) {
        List<Integer> sorted = new ArrayList<>(list);
        Collections.sort(sorted);
        int q1 = sorted.get(sorted.size() / 4);
        int q3 = sorted.get((sorted.size() * 3) / 4);
        return q3 - q1;
    }

    private Map<String, Double> computeContributions(List<Integer> cps) {
        Map<String, Double> map = new HashMap<>();
        map.put("Mean", mean(cps) * W_MEAN / 20.0);
        map.put("StdDev", std(cps) * W_STDDEV / 10.0);
        map.put("Skewness", Math.abs(skewness(cps)) * W_SKEWNESS / 2.0);
        map.put("Kurtosis", Math.abs(kurtosis(cps) - 3.0) * W_KURTOSIS / 2.0);
        map.put("Entropy", (2.0 - entropy(cps)) * W_ENTROPY / 2.0);
        map.put("LQR", (30.0 - lqr(cps)) * W_LQR / 30.0);
        return map;
    }
}
