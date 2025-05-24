package dev.ikara.admintools.commands;

import dev.ikara.admintools.AdminTools;
import dev.ikara.admintools.util.PacketCPS;
import dev.ikara.admintools.util.PacketInterceptor;
import dev.ikara.checks.autoclicker.BurstCPS;
import dev.ikara.checks.autoclicker.EntropyCPS;
import dev.ikara.checks.autoclicker.MetricsCPS;
import dev.ikara.checks.autoclicker.VarianceCPS;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.server.v1_8_R3.PacketPlayInArmAnimation;
import net.minecraft.server.v1_8_R3.PacketPlayInUseEntity;
import net.minecraft.server.v1_8_R3.PacketPlayInUseEntity.EnumEntityUseAction;
import net.md_5.bungee.api.chat.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.*;

import java.util.*;

public class CPSCommand implements CommandExecutor {
    private final AdminTools plugin;
    private final Map<UUID, TestSession> sessions = new HashMap<>();
    private final int feedbackInterval = 5; // seconds

    public CPSCommand(AdminTools plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("admintools.cps")) {
            sender.sendMessage(color("&cYou do not have permission."));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(color("&cUsage: /cpstest <player> [check] [#s|#cps]"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(color("&cThat player is not online."));
            return true;
        }

        String checkName = "metrics";
        boolean timeTest = true;
        int amount = 30;
        if (args.length >= 2 && !args[1].matches("\\d+.*")) {
            checkName = args[1].toLowerCase();
        }
        String numArg = (args.length >= 2 && args[1].matches("\\d+.*")) ? args[1]
                : (args.length >= 3 ? args[2] : null);
        if (numArg != null) {
            numArg = numArg.toLowerCase();
            if (numArg.endsWith("cps")) {
                amount = Integer.parseInt(numArg.replaceAll("[^0-9]", ""));
                timeTest = false;
            } else {
                amount = Integer.parseInt(numArg.replaceAll("[^0-9]", ""));
                timeTest = true;
            }
        }

        PacketCPS check;
        switch (checkName) {
            case "variance": check = new VarianceCPS(); break;
            case "entropy":  check = new EntropyCPS();  break;
            case "burst":    check = new BurstCPS();    break;
            case "metrics":
            default:         check = new MetricsCPS();  break;
        }

        UUID uuid = target.getUniqueId();
        if (sessions.containsKey(uuid)) {
            sender.sendMessage(color("&cThat player is already in a test."));
            return true;
        }

        TestSession session = new TestSession(target, sender, check, timeTest, amount);
        sessions.put(uuid, session);
        session.start();

        sender.sendMessage(color(
            "&aStarted CPS test on &6" + target.getName() +
            " &afor &6" + amount + (timeTest ? " seconds" : " clicks") +
            " &7[check: " + check.name() + "]"
        ));
        return true;
    }

    public void cancelAll() {
        for (TestSession ts : sessions.values()) ts.terminate();
        sessions.clear();
    }

    private String color(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    private class TestSession {
        final Player player;
        final CommandSender sender;
        final PacketCPS check;
        final boolean timeTest;
        final int amount;
        final String handlerName;
        final ChannelDuplexHandler handler;

        BukkitTask perSecondTask, feedbackTask, endTask;
        final List<Integer> global = new ArrayList<>();
        final Deque<Integer> window = new ArrayDeque<>();
        int prevClicks = 0;

        TestSession(Player player, CommandSender sender, PacketCPS check, boolean timeTest, int amount) {
            this.player = player;
            this.sender = sender;
            this.check = check;
            this.timeTest = timeTest;
            this.amount = amount;
            this.handlerName = "cps_" + player.getUniqueId().toString().substring(0,5) + "_" + check.name();

            this.handler = new ChannelDuplexHandler() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object pkt) throws Exception {
                    boolean clicked = false;
                    if (pkt instanceof PacketPlayInUseEntity) {
                        PacketPlayInUseEntity p = (PacketPlayInUseEntity)pkt;
                        if (p.a() == EnumEntityUseAction.ATTACK) clicked = true;
                    } else if (pkt instanceof PacketPlayInArmAnimation) {
                        clicked = true;
                    }
                    if (clicked) {
                        check.recordClick(System.nanoTime());
                        if (!timeTest && check.totalClicks() >= amount) {
                            new BukkitRunnable() {
                                @Override public void run() { finish(); }
                            }.runTask(plugin);
                        }
                    }
                    super.channelRead(ctx, pkt);
                }
            };
        }

        void start() {
            PacketInterceptor.inject(player, handler, handlerName);

            perSecondTask = new BukkitRunnable() {
                @Override
                public void run() {
                    int total = check.totalClicks();
                    int delta = total - prevClicks;
                    prevClicks = total;
                    global.add(delta);
                    window.addLast(delta);
                    if (window.size() > feedbackInterval) window.removeFirst();
                }
            }.runTaskTimer(plugin, 20L, 20L);

            feedbackTask = new BukkitRunnable() {
                @Override
                public void run() {
                    List<Integer> snap = new ArrayList<>(window);
                    String headerHover = check.hoverText();

                    TextComponent header = new TextComponent(color(
                        String.format("&8[&6CPS&8] &f%s's Last %d Seconds",
                                      player.getName(), feedbackInterval)
                    ));
                    header.setHoverEvent(new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        new ComponentBuilder(headerHover).create()
                    ));
                    if (sender instanceof Player) {
                        ((Player)sender).spigot().sendMessage(header);
                    } else {
                        sender.sendMessage(header.toLegacyText());
                    }

                    // each bullet now uses check.hoverText() AND reflects cumulative progress
                    for (int i = 0; i < snap.size(); i++) {
                        int cpsVal = snap.get(i);
                        TextComponent line = new TextComponent(
                            color("  * &6" + cpsVal + " CPS")
                        );
                        // check.hoverText() accumulates all clicks so far in this test
                        line.setHoverEvent(new HoverEvent(
                            HoverEvent.Action.SHOW_TEXT,
                            new ComponentBuilder(check.hoverText()).create()
                        ));
                        if (sender instanceof Player) {
                            ((Player)sender).spigot().sendMessage(line);
                        } else {
                            sender.sendMessage(line.toLegacyText());
                        }
                    }
                }
            }.runTaskTimer(plugin, feedbackInterval * 20L, feedbackInterval * 20L);

            if (timeTest) {
                endTask = new BukkitRunnable() {
                    @Override public void run() { finish(); }
                }.runTaskLater(plugin, amount * 20L);
            }
        }

        void finish() {
            if (perSecondTask != null) perSecondTask.cancel();
            if (feedbackTask   != null) feedbackTask.cancel();
            PacketInterceptor.remove(player, handlerName);
            sessions.remove(player.getUniqueId());

            double avg = global.stream().mapToDouble(i->i).average().orElse(0.0);
            int min = global.stream().mapToInt(i->i).min().orElse(0);
            int max = global.stream().mapToInt(i->i).max().orElse(0);

            sender.sendMessage("");
            sender.sendMessage(color(String.format(
                "&a&lCPS TEST FINISHED &7– &f%s &7[%d clicks]",
                player.getName(), check.totalClicks()
            )));
            sender.sendMessage("");
            sender.sendMessage(color(String.format(
                "&7Average: &6%.2f CPS   &7Minimum: &6%d CPS   &7Maximum: &6%d CPS",
                avg, min, max
            )));
            sender.sendMessage("");

            double pct = check.finish();
            TextComponent verdict = new TextComponent(color(
                "&fResult&7: " +
                (pct >= 85 ? "&cSuspicious" : "&aClean") +
                String.format(" (%.1f%% sure)", pct)
            ));
            verdict.setHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(check.hoverText()).create()
            ));
            if (sender instanceof Player) {
                ((Player)sender).spigot().sendMessage(verdict);
            } else {
                sender.sendMessage(verdict.toLegacyText());
            }
            sender.sendMessage("");
        }

        void terminate() {
            if (perSecondTask != null) perSecondTask.cancel();
            if (feedbackTask   != null) feedbackTask.cancel();
            PacketInterceptor.remove(player, handlerName);
        }
    }
}
