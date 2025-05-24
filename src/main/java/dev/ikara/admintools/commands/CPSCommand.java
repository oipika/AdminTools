package dev.ikara.admintools.commands;

import dev.ikara.admintools.AdminTools;
import dev.ikara.admintools.util.CPSMetrics;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class CPSCommand implements CommandExecutor {
    private final AdminTools plugin;

    // suggestion 6: use a counter instead of storing every timestamp
    private final Map<UUID, AtomicInteger> clickData   = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> activeTests = new ConcurrentHashMap<>();

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

        boolean isTimeTest = true;
        int amount = 30; // default 30s

        if (args.length >= 2) {
            String arg = args[1].toLowerCase();
            try {
                if (arg.endsWith("cps")) {
                    amount = Integer.parseInt(arg.substring(0, arg.length() - 3));
                    if (amount < 1 || amount > 10000) {
                        sender.sendMessage(color("&cClicks must be between 1 and 10000."));
                        return true;
                    }
                    isTimeTest = false;
                } else if (arg.endsWith("s")) {
                    amount = Integer.parseInt(arg.substring(0, arg.length() - 1));
                    if (amount < 1 || amount > 300) {
                        sender.sendMessage(color("&cDuration must be between 1 and 300 seconds."));
                        return true;
                    }
                    isTimeTest = true;
                } else {
                    amount = Integer.parseInt(arg);
                    if (amount < 1 || amount > 300) {
                        sender.sendMessage(color("&cDuration must be between 1 and 300 seconds."));
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

        // initialize our counter
        clickData.put(uuid, new AtomicInteger(0));

        final Player t       = target;
        final CommandSender s = sender;
        final boolean timeTest = isTimeTest;
        final int testAmount   = amount;

        BukkitRunnable testTask = new BukkitRunnable() {
            private int secondsLeft    = testAmount;
            private int previousClicks = 0;
            private final List<Integer> cpsList = new ArrayList<>();

            private void sendHoverMessage(int cps) {
                String stats = String.format(
                    "Mean: %.2f%nStdDev: %.2f%nSkewness: %.2f%nKurtosis: %.2f%nEntropy: %.2f%nLQR: %.2f",
                    CPSMetrics.mean(cpsList),
                    CPSMetrics.std(cpsList),
                    CPSMetrics.skewness(cpsList),
                    CPSMetrics.kurtosis(cpsList),
                    CPSMetrics.entropy(cpsList),
                    CPSMetrics.lqr(cpsList)
                );

                TextComponent base = new TextComponent(
                  color(String.format("&8[&6CPS&8] &f%s&7: &6%d CPS", t.getName(), cps))
                );
                base.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                  new ComponentBuilder(stats).create()));

                // console fallback
                if (s instanceof Player) {
                    ((Player) s).spigot().sendMessage(base);
                } else {
                    s.sendMessage(TextComponent.toLegacyText(base));
                }
            }

            private void finishTest() {
                final List<Integer> snapshot = new ArrayList<>(cpsList);
                final int finalClicks = clickData.getOrDefault(t.getUniqueId(), new AtomicInteger(0)).get();

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    Map<String, Double> contrib = computeContributions(snapshot);
                    double rawTotal = contrib.values().stream().mapToDouble(d -> d).sum();
                    final double pct = Math.max(0, Math.min(100, rawTotal / TOTAL_WEIGHT * 100));

                    StringBuilder hoverText = new StringBuilder();
                    hoverText.append(String.format("Mean: %.2f (%.1f)%n", CPSMetrics.mean(snapshot), contrib.get("Mean")));
                    hoverText.append(String.format("StdDev: %.2f (%.1f)%n", CPSMetrics.std(snapshot), contrib.get("StdDev")));
                    hoverText.append(String.format("Skewness: %.2f (%.1f)%n", CPSMetrics.skewness(snapshot), contrib.get("Skewness")));
                    hoverText.append(String.format("Kurtosis: %.2f (%.1f)%nEntropy: %.2f (%.1f)%nLQR: %.2f",
                                      CPSMetrics.kurtosis(snapshot), contrib.get("Kurtosis"),
                                      CPSMetrics.entropy(snapshot), contrib.get("Entropy"),
                                      CPSMetrics.lqr(snapshot)));

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        s.sendMessage("");
                        s.sendMessage(color("&a&lCPS TEST FINISHED &7– &f" + t.getName() + " &7[" + finalClicks + " clicks]"));
                        s.sendMessage("");

                        if (timeTest) {
                            s.sendMessage(color(String.format(
                                "&7Average: &6%.2f CPS   &7Minimum: &6%d CPS   &7Maximum: &6%d CPS",
                                CPSMetrics.mean(snapshot),
                                Collections.min(snapshot),
                                Collections.max(snapshot)
                            )));
                            s.sendMessage("");
                        } else {
                            s.sendMessage(color(String.format("&7Total Clicks: &6%d", finalClicks)));
                            s.sendMessage("");
                        }

                        TextComponent verdict = new TextComponent(color(
                            "&fResult&7: " + (pct >= 85 ? "&cSuspicious" : "&aClean")
                            + String.format(" (%.1f%% sure)", pct)
                        ));
                        verdict.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                new ComponentBuilder(hoverText.toString()).create()));

                        if (s instanceof Player) {
                            ((Player) s).spigot().sendMessage(verdict);
                        } else {
                            s.sendMessage(TextComponent.toLegacyText(verdict));
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

                int currClicks = clickData.get(t.getUniqueId()).get();
                int delta      = currClicks - previousClicks;
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

    /** Called by your external ClickListener **/
    public void recordClick(Player player) {
        AtomicInteger ctr = clickData.get(player.getUniqueId());
        if (ctr != null) ctr.incrementAndGet();
    }

    public void cancelAll() {
        activeTests.values().forEach(BukkitRunnable::cancel);
        activeTests.clear();
        clickData.clear();
    }

    private void cleanup(UUID uuid) {
        activeTests.remove(uuid);
        clickData.remove(uuid);
    }

    private String color(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    private Map<String, Double> computeContributions(List<Integer> cps) {
        Map<String, Double> m = new HashMap<>();
        m.put("Mean",     CPSMetrics.mean(cps)     * W_MEAN    / 20.0);
        m.put("StdDev",   CPSMetrics.std(cps)      * W_STDDEV  / 10.0);
        m.put("Skewness", Math.abs(CPSMetrics.skewness(cps)) * W_SKEWNESS / 2.0);
        m.put("Kurtosis", Math.abs(CPSMetrics.kurtosis(cps) - 3.0) * W_KURTOSIS / 2.0);
        m.put("Entropy",  (2.0 - CPSMetrics.entropy(cps)) * W_ENTROPY / 2.0);
        m.put("LQR",      (30.0 - CPSMetrics.lqr(cps)) * W_LQR / 30.0);
        return m;
    }
}