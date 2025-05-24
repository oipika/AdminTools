package dev.ikara.admintools.commands;

import dev.ikara.admintools.AdminTools;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * /aimanalysis <player> [durationSeconds]
 * Permission: admintools.aimanalysis
 */
public class AimAnalysisCommand implements CommandExecutor, Listener {

    private final JavaPlugin plugin;
    private final Map<UUID, AimSession> sessions = new HashMap<>();

    // defaults & thresholds
    private static final int    DEFAULT_DURATION       = 60;    // seconds
    private static final float  BASE_MAX_YAW_THRESHOLD = 20.0F; // degrees per tick
    private static final float  BASE_MIN_STDDEV        = 0.5F;  // too‑steady aim
    private static final double OUTLIER_PERCENT        = 0.05;  // 5%

    public AimAnalysisCommand(AdminTools plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can run this.");
            return true;
        }
        Player tester = (Player) sender;
        if (!tester.hasPermission("admintools.aimanalysis")) {
            tester.sendMessage(ChatColor.RED + "You lack permission: admintools.aimanalysis");
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            tester.sendMessage(ChatColor.RED + "Usage: /aimanalysis <player> [durationSeconds]");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            tester.sendMessage(ChatColor.RED + "Player not found or not online.");
            return true;
        }
        int duration = DEFAULT_DURATION;
        if (args.length == 2) {
            try {
                duration = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
                tester.sendMessage(ChatColor.RED + "Invalid duration. Using default " + DEFAULT_DURATION + "s.");
            }
        }
        startAnalysis(tester, target, duration);
        return true;
    }

    private void startAnalysis(Player tester, Player target, int durationSec) {
        UUID tid = target.getUniqueId();
        if (sessions.containsKey(tid)) {
            tester.sendMessage(ChatColor.RED + "Analysis already running for " + target.getName() + ".");
            return;
        }
        AimSession sess = new AimSession(tester, target);
        sessions.put(tid, sess);
        tester.sendMessage(ChatColor.GREEN + "Starting aim analysis on " + target.getName()
            + " for " + durationSec + " seconds...");
        new BukkitRunnable() {
            @Override
            public void run() {
                endAnalysis(target);
            }
        }.runTaskLater(plugin, durationSec * 20L);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        AimSession sess = sessions.get(p.getUniqueId());
        if (sess == null) return;
        EntityPlayer nms = ((CraftPlayer) p).getHandle();
        float yaw = nms.yaw;
        float pitch = nms.pitch;
        int ping = nms.ping;
        if (sess.init) {
            sess.lastYaw = yaw;
            sess.lastPitch = pitch;
            sess.init = false;
        } else {
            float dYaw = Math.abs(yaw - sess.lastYaw);
            if (dYaw > 180) dYaw = 360 - dYaw;
            float dPitch = Math.abs(pitch - sess.lastPitch);
            if (dPitch > 180) dPitch = 360 - dPitch;
            sess.yawDeltas.add(dYaw);
            sess.pitchDeltas.add(dPitch);
            sess.pingSamples.add(ping);
            sess.lastYaw = yaw;
            sess.lastPitch = pitch;
        }
    }

    private void endAnalysis(Player target) {
        UUID tid = target.getUniqueId();
        AimSession sess = sessions.remove(tid);
        if (sess == null) return;
        if (sess.yawDeltas.isEmpty()) {
            sess.tester.sendMessage(ChatColor.RED + "Not enough data to analyze aim.");
            return;
        }
        // compute ping stats
        double sumPing = 0;
        Collections.sort(sess.pingSamples);
        int maxPing = sess.pingSamples.get(sess.pingSamples.size() - 1);
        for (int p : sess.pingSamples) sumPing += p;
        double avgPing = sumPing / sess.pingSamples.size();
        double medianPing;
        int mid = sess.pingSamples.size() / 2;
        if (sess.pingSamples.size() % 2 == 0) {
            medianPing = (sess.pingSamples.get(mid - 1) + sess.pingSamples.get(mid)) / 2.0;
        } else {
            medianPing = sess.pingSamples.get(mid);
        }

        // filter outliers
        List<Float> fYaw = filterOutliers(sess.yawDeltas);
        List<Float> fPitch = filterOutliers(sess.pitchDeltas);

        // compute stats (yaw)
        double sum = 0, min = Double.MAX_VALUE, max = 0;
        for (float v : fYaw) { sum += v; min = Math.min(min, v); max = Math.max(max, v); }
        double avg = sum / fYaw.size();
        double var = 0;
        for (float v : fYaw) var += (v - avg) * (v - avg);
        double std = Math.sqrt(var / fYaw.size());

        // compute stats (pitch)
        double sumP = 0, minP = Double.MAX_VALUE, maxP = 0;
        for (float v : fPitch) { sumP += v; minP = Math.min(minP, v); maxP = Math.max(maxP, v); }
        double avgP = sumP / fPitch.size();
        double varP = 0;
        for (float v : fPitch) varP += (v - avgP) * (v - avgP);
        double stdP = Math.sqrt(varP / fPitch.size());

        // estimate resolution
        float resYaw = fYaw.stream().filter(d -> d > 0).min(Float::compare).orElse(0f);
        float resPitch = fPitch.stream().filter(d -> d > 0).min(Float::compare).orElse(0f);

        // adjust thresholds by ping
        float adjYawThresh = (float) (BASE_MAX_YAW_THRESHOLD * (1 + avgPing / 200));
        float adjStdThresh = (float) (BASE_MIN_STDDEV * (1 + avgPing / 200));

        // verdict
        boolean suspicious = max > adjYawThresh && std < adjStdThresh;

        // report
        sess.tester.sendMessage(ChatColor.GOLD + "Aim Analysis for " + sess.target.getName() + ":");
        sess.tester.sendMessage(ChatColor.GRAY + "Samples: " + sess.yawDeltas.size()
            + " → " + fYaw.size() + " after outlier filter");
        sess.tester.sendMessage(ChatColor.GRAY + "Ping (avg/med/max): "
            + String.format("%.0f", avgPing) + " / "
            + String.format("%.0f", medianPing) + " / "
            + maxPing + " ms");
        sess.tester.sendMessage(ChatColor.GRAY + "Yaw Δ (avg/min/max/std): "
            + String.format("%.2f", avg) + " / "
            + String.format("%.2f", min) + " / "
            + String.format("%.2f", max) + " / "
            + String.format("%.2f", std));
        sess.tester.sendMessage(ChatColor.GRAY + "Pitch Δ (avg/min/max/std): "
            + String.format("%.2f", avgP) + " / "
            + String.format("%.2f", minP) + " / "
            + String.format("%.2f", maxP) + " / "
            + String.format("%.2f", stdP));
        sess.tester.sendMessage(ChatColor.GRAY + "Resolutions (Yaw / Pitch): "
            + String.format("%.3f", resYaw) + "° / "
            + String.format("%.3f", resPitch) + "°");
        sess.tester.sendMessage(ChatColor.GRAY + "Verdict: " + (suspicious ? ChatColor.RED + "Suspicious" : ChatColor.GREEN + "Clean"));
    }

    private List<Float> filterOutliers(List<Float> data) {
        if (data.size() < 20) return new ArrayList<>(data);
        List<Float> sorted = new ArrayList<>(data);
        Collections.sort(sorted);
        int remove = (int) (sorted.size() * OUTLIER_PERCENT);
        return sorted.subList(remove, sorted.size() - remove);
    }

    private static class AimSession {
        final Player tester, target;
        final List<Float> yawDeltas = new ArrayList<>();
        final List<Float> pitchDeltas = new ArrayList<>();
        final List<Integer> pingSamples = new ArrayList<>();
        boolean init = true;
        float lastYaw, lastPitch;
        AimSession(Player tester, Player target) { this.tester = tester; this.target = target; }
    }
}
