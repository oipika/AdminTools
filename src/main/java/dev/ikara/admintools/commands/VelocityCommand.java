package dev.ikara.admintools.commands;

import dev.ikara.admintools.AdminTools;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

public class VelocityCommand implements CommandExecutor {

    private final AdminTools plugin;
    public VelocityCommand(AdminTools plugin) { this.plugin = plugin; }

    /* ---- CONFIG ---- */
    private static final double BASE_THRESHOLD       = 1.20;
    private static final double SLOW_PENALTY         = 0.15;
    private static final double SPEED_BONUS          = 0.25;
    private static final double LOW_TPS_FLOOR        = 17.0;
    private static final double LOW_TPS_MULT_PER_TPS = 0.05;
    private static final long   TIMEOUT_TICKS        = 100L; // 5 seconds (20 ticks per second)
    /* ---------------- */

    private final Set<Material> slowBlocks = new HashSet<>(Arrays.asList(
            Material.SOUL_SAND, Material.WEB, Material.WATER,
            Material.LADDER, Material.VINE, Material.ICE, Material.PACKED_ICE
    ));

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can run this command.");
            return true;
        }

        if (!sender.hasPermission("admintools.velocity")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to run this command.");
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <player>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(ChatColor.RED + "Player not found.");
            return true;
        }

        if (target.getGameMode() != GameMode.SURVIVAL || target.isFlying()) {
            sender.sendMessage(ChatColor.RED + "Cannot test this player in their current mode.");
            return true;
        }

        Block under = target.getLocation().getBlock();
        if (slowBlocks.contains(under.getType())) {
            sender.sendMessage(ChatColor.RED + "Target is on a slow block (" + under.getType() + "). Test canceled.");
            return true;
        }

        Location startLoc = target.getLocation().clone();

        // Apply knockback upwards + opposite of player look direction (3D)
        target.setVelocity(new Vector(0, 0.5, 0).add(startLoc.getDirection().multiply(-1)));

        sender.sendMessage(ChatColor.YELLOW + "Knock-back applied to " + target.getName() + ". Evaluating…");

        // Capture current potion effects
        int slowLvl = 0, speedLvl = 0;
        for (PotionEffect pe : target.getActivePotionEffects()) {
            if (pe.getType().equals(PotionEffectType.SLOW)) slowLvl = pe.getAmplifier() + 1;
            if (pe.getType().equals(PotionEffectType.SPEED)) speedLvl = pe.getAmplifier() + 1;
        }

        double threshold = BASE_THRESHOLD
                - slowLvl * SLOW_PENALTY
                + speedLvl * SPEED_BONUS;

        final double finalThreshold = threshold;
        final long startWall = System.currentTimeMillis();
        final Player finalTarget = target;
        final CommandSender finalSender = sender;
        final AdminTools finalPlugin = plugin;

        new BukkitRunnable() {
            final List<Location> airLocations = new ArrayList<>();
            int ticks = 0;
            boolean landed = false;

            @Override
            public void run() {
                ticks++;

                if (ticks > TIMEOUT_TICKS || landed) {
                    this.cancel();

                    if (airLocations.isEmpty()) {
                        finalSender.sendMessage(ChatColor.RED + "Player did not move in the air. Test canceled.");
                        return;
                    }

                    Location first = airLocations.get(0);
                    Location last = airLocations.get(airLocations.size() - 1);
                    double horiz = Math.hypot(last.getX() - first.getX(), last.getZ() - first.getZ());
                    double vert = Math.abs(last.getY() - first.getY());

                    double tps = getCurrentTPS();
                    double adjustedThreshold = finalThreshold;
                    if (tps < LOW_TPS_FLOOR) {
                        adjustedThreshold *= 1.0 + (LOW_TPS_FLOOR - tps) * LOW_TPS_MULT_PER_TPS;
                    }

                    double elapsedSec = (System.currentTimeMillis() - startWall) / 1000.0;
                    finalSender.sendMessage(ChatColor.GRAY + "Δt " + String.format("%.2f", elapsedSec)
                            + " s | TPS " + String.format("%.1f", tps));
                    finalSender.sendMessage(ChatColor.GRAY + "Horiz " + String.format("%.2f", horiz)
                            + " | Vert " + String.format("%.2f", vert)
                            + " | Threshold " + String.format("%.2f", adjustedThreshold));

                    if (horiz < adjustedThreshold) {
                        finalSender.sendMessage(ChatColor.RED + finalTarget.getName() + " is suspicious (too little movement).");
                    } else {
                        finalSender.sendMessage(ChatColor.GREEN + finalTarget.getName() + " appears clean.");
                    }

                    return;
                }

                if (!isOnGround(finalTarget)) {
                    airLocations.add(finalTarget.getLocation().clone());
                } else if (!airLocations.isEmpty()) {
                    landed = true;
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return true;
    }

    private boolean isOnGround(Player player) {
        Location loc = player.getLocation();
        Block below = loc.subtract(0, 1, 0).getBlock();
        return below.getType().isSolid();
    }

    /* --- Reflection helper that compiles with API-only jars --- */
    private double getCurrentTPS() {
        try {
            Object mcServer = Bukkit.getServer().getClass()
                    .getMethod("getServer").invoke(Bukkit.getServer());
            Field recentTps = mcServer.getClass().getField("recentTps");
            return ((double[]) recentTps.get(mcServer))[0];   // 1-minute average
        } catch (Exception e) {
            return 20.0; // fallback
        }
    }
}
