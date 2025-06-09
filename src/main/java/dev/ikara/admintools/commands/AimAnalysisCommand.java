package dev.ikara.admintools.commands;

import dev.ikara.admintools.AdminTools;
import dev.ikara.admintools.util.MessageHandler;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * /aimanalysis <player> [durationSeconds]
 * Permission: admintools.aimanalysis
 */
public class AimAnalysisCommand implements CommandExecutor, Listener, TabCompleter {

    private final AdminTools plugin;
    private final Map<UUID, AimSession> sessions = new HashMap<>();

    private final int defaultDuration;
    private final float maxYawThreshold;
    private final float minStddev;
    private final double outlierPercent;

    public AimAnalysisCommand(AdminTools plugin) {
        this.plugin = plugin;
        FileConfiguration cfg = plugin.getConfig();
        defaultDuration = cfg.getInt("aimanalysis.default-duration", 60);
        maxYawThreshold = (float)cfg.getDouble("aimanalysis.base-max-yaw-threshold", 20.0);
        minStddev = (float)cfg.getDouble("aimanalysis.base-min-stddev", 0.5);
        outlierPercent = cfg.getDouble("aimanalysis.outlier-percent", 0.05);

        plugin.getCommand("aimanalysis").setExecutor(this);
        plugin.getCommand("aimanalysis").setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            MessageHandler.sendErrorFmt(sender, "only-players");
            return true;
        }
        Player tester = (Player)sender;
        if (!tester.hasPermission("admintools.aimanalysis")) {
            MessageHandler.sendErrorFmt(tester, "no-permission", "admintools.aimanalysis");
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            MessageHandler.sendErrorFmt(tester, "usage-aimanalysis");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            MessageHandler.sendErrorFmt(tester, "player-not-found");
            return true;
        }

        UUID tid = target.getUniqueId();
        if (sessions.containsKey(tid)) {
            MessageHandler.sendErrorFmt(tester, "already-running", target.getName());
            return true;
        }

        final int duration = args.length == 2
            ? parseDuration(tester, args[1])
            : defaultDuration;

        MessageHandler.sendInfoFmt(tester, "start-analysis", target.getName(), duration);

        sessions.put(tid, new AimSession(tester, target));
        new BukkitRunnable() {
            int elapsed = 0;
            @Override public void run() {
                if (elapsed++ >= duration || !target.isOnline()) {
                    endAnalysis(target);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);

        return true;
    }

    private int parseDuration(Player tester, String arg) {
        try {
            return Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            MessageHandler.sendInfoFmt(tester, "invalid-duration", defaultDuration);
            return defaultDuration;
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent ev) {
        AimSession sess = sessions.get(ev.getPlayer().getUniqueId());
        if (sess == null) return;
        EntityPlayer ep = ((CraftPlayer)ev.getPlayer()).getHandle();
        float yaw = ev.getTo().getYaw();
        float pitch = ev.getTo().getPitch();
        if (sess.initialized) {
            sess.yawDeltas.add(Math.abs(yaw - sess.lastYaw));
            sess.pitchDeltas.add(Math.abs(pitch - sess.lastPitch));
            sess.pingSamples.add(ep.ping);
        } else {
            sess.initialized = true;
        }
        sess.lastYaw = yaw;
        sess.lastPitch = pitch;
    }

    private void endAnalysis(Player target) {
        AimSession sess = sessions.remove(target.getUniqueId());
        if (sess == null || sess.yawDeltas.isEmpty()) {
            MessageHandler.sendErrorFmt(sess.tester, "not-enough-data");
            return;
        }

        double avgPing = sess.pingSamples.stream().mapToInt(i->i).average().orElse(0);
        float adjThresh = (float)(maxYawThreshold * (1 + avgPing/200.0));

        List<Float> fYaw = filter(sess.yawDeltas);
        List<Float> fPitch = filter(sess.pitchDeltas);

        double meanYaw = fYaw.stream().mapToDouble(d->d).average().orElse(0);
        double meanPitch = fPitch.stream().mapToDouble(d->d).average().orElse(0);

        double stdYaw = Math.sqrt(fYaw.stream().mapToDouble(d->Math.pow(d-meanYaw,2)).sum()/fYaw.size());
        double stdPitch = Math.sqrt(fPitch.stream().mapToDouble(d->Math.pow(d-meanPitch,2)).sum()/fPitch.size());

        MessageHandler.sendInfoFmt(sess.tester, "results-title", target.getName());
        MessageHandler.sendInfoFmt(sess.tester, "results-ping", avgPing);
        MessageHandler.sendInfoFmt(sess.tester, "results-mean", meanYaw, meanPitch);
        MessageHandler.sendInfoFmt(sess.tester, "results-std", stdYaw, stdPitch);

        boolean suspicious = stdYaw < minStddev || stdYaw > adjThresh;
        MessageHandler.sendInfoFmt(sess.tester,
            suspicious ? "verdict-suspicious" : "verdict-clean"
        );
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(partial)) out.add(p.getName());
            }
            return out;
        }
        return Collections.emptyList();
    }

    private List<Float> filter(List<Float> data) {
        if (data.size() < 20) return new ArrayList<>(data);
        Collections.sort(data);
        int cut = (int)(data.size()*outlierPercent);
        return new ArrayList<>(data.subList(cut, data.size()-cut));
    }

    private static class AimSession {
        final Player tester, target;
        final List<Float> yawDeltas = new ArrayList<>();
        final List<Float> pitchDeltas = new ArrayList<>();
        final List<Integer> pingSamples = new ArrayList<>();
        boolean initialized = false;
        float lastYaw, lastPitch;
        AimSession(Player tester, Player target) {
            this.tester = tester;
            this.target = target;
        }
    }
}