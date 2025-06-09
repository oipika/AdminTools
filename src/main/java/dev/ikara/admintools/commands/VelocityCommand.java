package dev.ikara.admintools.commands;

import dev.ikara.admintools.AdminTools;
import dev.ikara.admintools.util.MessageHandler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.GameMode;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VelocityCommand implements CommandExecutor {

    private final AdminTools plugin;
    private final double baseThreshold;
    private final double slowPenalty;
    private final double speedBonus;
    private final double lowTpsFloor;
    private final double lowTpsMultPerTps;
    private final long timeoutTicks;
    private final Set<Material> slowBlocks;

    public VelocityCommand(AdminTools plugin) {
        this.plugin = plugin;

        baseThreshold = plugin.getConfig().getDouble("velocity.base-threshold", 1.20);
        slowPenalty = plugin.getConfig().getDouble("velocity.slow-penalty", 0.15);
        speedBonus = plugin.getConfig().getDouble("velocity.speed-bonus", 0.25);
        lowTpsFloor = plugin.getConfig().getDouble("velocity.low-tps-floor", 17.0);
        lowTpsMultPerTps = plugin.getConfig().getDouble("velocity.low-tps-multiplier-per-tps", 0.05);
        timeoutTicks = plugin.getConfig().getLong("velocity.timeout-ticks", 100L);

        // build slow-blocks set
        List<String> list = plugin.getConfig().getStringList("velocity.slow-blocks");
        slowBlocks = new HashSet<>();
        for (String name : list) {
            try {
                slowBlocks.add(Material.valueOf(name));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Invalid material in velocity.slow-blocks: " + name);
            }
        }

        plugin.getCommand("velocitytest").setExecutor(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            MessageHandler.sendErrorFmt(sender, "only-players");
            return true;
        }
        Player user = (Player) sender;

        if (!user.hasPermission("admintools.velocity")) {
            MessageHandler.sendErrorFmt(user, "no-permission");
            return true;
        }

        if (args.length != 1) {
            MessageHandler.sendErrorFmt(user, "velocity-usage");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            MessageHandler.sendErrorFmt(user, "player-not-found", args[0]);
            return true;
        }

        if (target.getGameMode() != GameMode.SURVIVAL || target.isFlying()) {
            MessageHandler.sendErrorFmt(user, "velocity-invalid-mode", target.getName());
            return true;
        }

        Block under = target.getLocation().getBlock();
        if (slowBlocks.contains(under.getType())) {
            MessageHandler.sendErrorFmt(user, "velocity-slow-block", under.getType().name());
            return true;
        }

        // apply knockback
        Vector knock = new Vector(0, 0.5, 0)
            .add(target.getLocation().getDirection().multiply(-1));
        target.setVelocity(knock);
        MessageHandler.sendInfoFmt(user, "velocity-started", target.getName());

        // capture potion levels
        int slowLvl = 0;
        int speedLvl = 0;
        for (PotionEffect pe : target.getActivePotionEffects()) {
            if (pe.getType() == PotionEffectType.SLOW) {
                slowLvl = pe.getAmplifier() + 1;
            }
            if (pe.getType() == PotionEffectType.SPEED) {
                speedLvl = pe.getAmplifier() + 1;
            }
        }

        double threshold = baseThreshold - slowLvl * slowPenalty + speedLvl * speedBonus;
        long startTime = System.currentTimeMillis();
        Player fTarget = target;
        CommandSender fUser = user;

        new BukkitRunnable() {
            final List<Location> airLocs = new java.util.ArrayList<>();
            boolean landed = false;
            long ticks = 0;

            @Override
            public void run() {
                ticks++;
                if (ticks > timeoutTicks || landed) {
                    cancel();
                    if (airLocs.isEmpty()) {
                        MessageHandler.sendErrorFmt(fUser, "velocity-no-movement");
                        return;
                    }

                    Location first = airLocs.get(0);
                    Location last = airLocs.get(airLocs.size() - 1);
                    double horiz = Math.hypot(last.getX() - first.getX(), last.getZ() - first.getZ());
                    double vert  = Math.abs(last.getY() - first.getY());

                    double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                    double tps = getCurrentTPS();
                    double adjThreshold = threshold;
                    if (tps < lowTpsFloor) {
                        adjThreshold *= 1 + (lowTpsFloor - tps) * lowTpsMultPerTps;
                    }

                    MessageHandler.sendInfoFmt(fUser, "velocity-stats", elapsed, tps);
                    MessageHandler.sendInfoFmt(fUser, "velocity-movements", horiz, vert, adjThreshold);

                    if (horiz < adjThreshold) {
                        MessageHandler.sendErrorFmt(fUser, "velocity-suspicious", fTarget.getName());
                    } else {
                        MessageHandler.sendInfoFmt(fUser, "velocity-clean", fTarget.getName());
                    }
                    return;
                }

                if (!fTarget.isOnGround()) {
                    airLocs.add(fTarget.getLocation().clone());
                } else if (!airLocs.isEmpty()) {
                    landed = true;
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return true;
    }

    private double getCurrentTPS() {
        try {
            Object mc = Bukkit.getServer().getClass()
              .getMethod("getServer").invoke(Bukkit.getServer());
            Field f = mc.getClass().getField("recentTps");
            return ((double[]) f.get(mc))[0];
        } catch (Exception e) {
            return 20.0;
        }
    }
}
