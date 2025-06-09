package dev.ikara.admintools.commands;

import dev.ikara.admintools.AdminTools;
import dev.ikara.admintools.util.MessageHandler;
import dev.ikara.admintools.util.PacketCPS;
import dev.ikara.admintools.util.PacketInterceptor;
import dev.ikara.checks.autoclicker.*;
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

    // read from config.yml
    private final int defaultDuration;
    private final int feedbackInterval;
    private final long burstDtThreshold;
    private final double burstRatioScale;
    private final int entropyMinSamples;
    private final int varianceMinSamples;
    private final double varianceCvMultiplier;
    private final double wMean, wStddev, wSkewness, wKurtosis, wEntropy, wLqr;

    public CPSCommand(AdminTools plugin) {
        this.plugin = plugin;

        // CPSCommand settings
        this.defaultDuration = plugin.getConfig().getInt("cps.default-duration", 30);
        this.feedbackInterval = plugin.getConfig().getInt("cps.feedback-interval", 5);

        // burst settings
        this.burstDtThreshold = plugin.getConfig().getLong("cps.burst.dt-threshold-millis", 25);
        this.burstRatioScale = plugin.getConfig().getDouble("cps.burst.ratio-scale", 2.0);

        // entropy settings
        this.entropyMinSamples = plugin.getConfig().getInt("cps.entropy.min-samples", 5);

        // variance settings
        this.varianceMinSamples = plugin.getConfig().getInt("cps.variance.min-samples", 5);
        this.varianceCvMultiplier = plugin.getConfig().getDouble("cps.variance.cv-multiplier", 5.0);

        // metrics weights
        this.wMean = plugin.getConfig().getDouble("cps.metrics.w-mean", 0.0);
        this.wStddev = plugin.getConfig().getDouble("cps.metrics.w-stddev", 2.0);
        this.wSkewness = plugin.getConfig().getDouble("cps.metrics.w-skewness", 0.5);
        this.wKurtosis = plugin.getConfig().getDouble("cps.metrics.w-kurtosis", 0.5);
        this.wEntropy = plugin.getConfig().getDouble("cps.metrics.w-entropy", 2.0);
        this.wLqr = plugin.getConfig().getDouble("cps.metrics.w-lqr", 1.0);

        plugin.getCommand("cpstest").setExecutor(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // permission
        if (!sender.hasPermission("admintools.cps")) {
            MessageHandler.sendErrorFmt(sender, "no-permission");
            return true;
        }
        // usage
        if (args.length < 1) {
            MessageHandler.sendErrorFmt(sender, "cps-usage");
            return true;
        }

        // target
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            MessageHandler.sendErrorFmt(sender, "player-not-found", args[0]);
            return true;
        }

        // determine check type & test length
        String checkName = "metrics";
        boolean timeTest = true;
        int amount = defaultDuration;  // default seconds

        if (args.length >= 2 && !args[1].matches("\\d+.*")) {
            checkName = args[1].toLowerCase();
        }
        String numArg = (args.length >= 2 && args[1].matches("\\d+.*"))
            ? args[1]
            : (args.length >= 3 ? args[2] : null);
        if (numArg != null) {
            numArg = numArg.toLowerCase();
            int parsed = Integer.parseInt(numArg.replaceAll("\\D", ""));
            if (numArg.endsWith("cps")) {
                amount = parsed;
                timeTest = false;
            } else {
                amount = parsed;
                timeTest = true;
            }
        }

        // instantiate the proper check with config values
        PacketCPS check;
        switch (checkName) {
            case "variance":
                check = new VarianceCPS(varianceMinSamples, varianceCvMultiplier);
                break;
            case "entropy":
                check = new EntropyCPS(entropyMinSamples);
                break;
            case "burst":
                check = new BurstCPS(burstDtThreshold, burstRatioScale);
                break;
            default:
                check = new MetricsCPS(
                    wMean, wStddev, wSkewness,
                    wKurtosis, wEntropy, wLqr
                );
                break;
        }

        // prevent double-tests
        UUID uid = target.getUniqueId();
        if (sessions.containsKey(uid)) {
            MessageHandler.sendErrorFmt(sender, "cps-already-running", target.getName());
            return true;
        }

        // start session
        TestSession sess = new TestSession(target, sender, check, timeTest, amount);
        sessions.put(uid, sess);
        sess.start();

        MessageHandler.sendInfoFmt(sender, "cps-started",
            target.getName(), amount,
            timeTest ? "seconds" : "clicks",
            check.name()
        );
        return true;
    }

    public void cancelAll() {
        sessions.values().forEach(TestSession::terminate);
        sessions.clear();
    }

    private class TestSession {
        final Player player;
        final CommandSender sender;
        final PacketCPS check;
        final boolean timeTest;
        final int amount;
        final String handlerName;
        final ChannelDuplexHandler handler;

        List<Integer> global = new ArrayList<>();
        Deque<Integer> window = new ArrayDeque<>();
        int prevClicks = 0;

        BukkitTask perSecondTask, feedbackTask, endTask;

        TestSession(Player player, CommandSender sender,
                   PacketCPS check, boolean timeTest, int amount) {
            this.player = player;
            this.sender = sender;
            this.check = check;
            this.timeTest = timeTest;
            this.amount = amount;
            this.handlerName = "cps_" +
                player.getUniqueId().toString().substring(0,5) +
                "_" + check.name();

            this.handler = new ChannelDuplexHandler() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object pkt) throws Exception {
                    boolean clicked = false;
                    if (pkt instanceof PacketPlayInUseEntity) {
                        if (((PacketPlayInUseEntity)pkt).a() == EnumEntityUseAction.ATTACK)
                            clicked = true;
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
                @Override public void run() {
                    int total = check.totalClicks();
                    int delta = total - prevClicks;
                    prevClicks = total;
                    global.add(delta);
                    window.addLast(delta);
                    if (window.size() > feedbackInterval) window.removeFirst();
                }
            }.runTaskTimer(plugin, 20L, 20L);

            feedbackTask = new BukkitRunnable() {
                @Override public void run() {
                    MessageHandler.sendInfoFmt(sender, "cps-feedback-header",
                        player.getName(), feedbackInterval
                    );
                    for (int cpsVal : window) {
                        String line = String.format(
                            MessageHandler.get("cps-feedback-line","  * &6%d CPS"),
                            cpsVal
                        );
                        MessageHandler.sendLegacyHover(sender, line, check.hoverText());
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
            // cancel tasks
            if (perSecondTask != null) perSecondTask.cancel();
            if (feedbackTask != null) feedbackTask.cancel();
            if (endTask != null) endTask.cancel();

            PacketInterceptor.remove(player, handlerName);
            sessions.remove(player.getUniqueId());

            // compute stats
            double avg = global.stream().mapToDouble(i->i).average().orElse(0.0);
            int min = global.stream().mapToInt(i->i).min().orElse(0);
            int max = global.stream().mapToInt(i->i).max().orElse(0);
            double pct = check.finish();

            // title & total clicks
            MessageHandler.sendInfoFmt(sender, "cps-finish-title",
                player.getName(), check.totalClicks()
            );

            // inline stats
            MessageHandler.sendInfoFmt(sender, "cps-finish-stats",
                avg, min, max
            );

            // verdict + percent inline with hover for metrics
            String verdictKey  = pct >= 85.0 ? "verdict-suspicious" : "verdict-clean";
            String verdictText = MessageHandler.get(verdictKey, verdictKey);
            String tpl         = MessageHandler.get(
                "cps-finish-verdict","&fResult&7: %s (%.1f%% sure)"
            );
            String formatted = String.format(tpl, verdictText, pct);

            BaseComponent[] comps = TextComponent.fromLegacyText(
                ChatColor.translateAlternateColorCodes('&', formatted)
            );
            for (BaseComponent c : comps) {
                if (c instanceof TextComponent) {
                    ((TextComponent)c).setHoverEvent(new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        new ComponentBuilder(check.hoverText()).create()
                    ));
                }
            }
            if (sender instanceof Player) {
                ((Player)sender).spigot().sendMessage(comps);
            } else {
                sender.sendMessage(TextComponent.toLegacyText(comps));
            }
        }

        void terminate() {
            if (perSecondTask != null) perSecondTask.cancel();
            if (feedbackTask != null) feedbackTask.cancel();
            if (endTask != null) endTask.cancel();
            PacketInterceptor.remove(player, handlerName);
        }
    }
}
