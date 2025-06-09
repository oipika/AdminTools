package dev.ikara.admintools.commands;

import dev.ikara.admintools.AdminTools;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;

import java.util.*;

public class FakeOreCommand implements CommandExecutor, Listener {

    private final AdminTools plugin;
    private final Map<UUID, OreTestInfo> testingPlayers = new HashMap<>();

    private static class OreTestInfo {
        Player issuer;
        Material oreType;
        int amount, radius, durationSeconds;
        long startTime;
        int placed = 0, attempts = 0;
        double minYaw = Double.MAX_VALUE, maxYaw = Double.MIN_VALUE;
        double minPitch = Double.MAX_VALUE, maxPitch = Double.MIN_VALUE;
        List<Location> fakeLocations = new ArrayList<>();
        Map<Location, BlockInfo> originalBlocks = new HashMap<>();
        BukkitTask timeoutTask;
        BukkitTask spawnTask;
    }

    private static class BlockInfo {
        final Material type;
        final byte data;
        BlockInfo(Material type, byte data) {
            this.type = type;
            this.data = data;
        }
    }

    public FakeOreCommand(AdminTools plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Cancel and revert all active tests on plugin shutdown */
    public void cancelAllTests() {
        for (Map.Entry<UUID, OreTestInfo> e : testingPlayers.entrySet()) {
            OreTestInfo info = e.getValue();
            if (info.timeoutTask != null) info.timeoutTask.cancel();
            if (info.spawnTask != null) info.spawnTask.cancel();
            Player suspect = Bukkit.getPlayer(e.getKey());
            if (suspect != null) revertFakeBlocks(suspect, info);
        }
        testingPlayers.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can run this.");
            return true;
        }
        Player issuer = (Player) sender;
        if (!issuer.hasPermission("admintools.fakeore")) {
            issuer.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        if (args.length < 1) {
            issuer.sendMessage(ChatColor.RED +
                "Usage: /fakeore <player> [material] [amount] [radius] [durationSeconds]");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            issuer.sendMessage(ChatColor.RED + "Player not found.");
            return true;
        }
        UUID tid = target.getUniqueId();
        if (testingPlayers.containsKey(tid)) {
            issuer.sendMessage(ChatColor.RED + "Already testing that player.");
            return true;
        }

        // parse with defaults
        Material oreType = (args.length >= 2 ? parseMaterial(issuer, args[1]) : Material.DIAMOND_ORE);
        int amount = (args.length >= 3 ? parseInt(issuer, args[2], 5) : 5);
        int radius = (args.length >= 4 ? parseInt(issuer, args[3], 10) : 10);
        int durationSeconds = (args.length >= 5 ? parseInt(issuer, args[4], 30) : 30);

        if (oreType == null || amount < 0 || radius < 0 || durationSeconds < 0) {
            return true; // errors already messaged
        }

        OreTestInfo info = new OreTestInfo();
        info.issuer = issuer;
        info.oreType = oreType;
        info.amount = amount;
        info.radius = radius;
        info.durationSeconds = durationSeconds;
        info.startTime = System.currentTimeMillis();
        testingPlayers.put(tid, info);

        scheduleSpawns(target, info);

        // schedule “clean” verdict
        info.timeoutTask = plugin.getServer().getScheduler().runTaskLater(
            plugin,
            () -> handleClean(tid),
            durationSeconds * 20L
        );

        issuer.sendMessage(ChatColor.YELLOW +
            "Started fake‑ore test on " + target.getName() +
            " → " + amount + "× " + oreType.name() +
            " in a" + radius + " block radius for " + durationSeconds + "seconds.");
        return true;
    }

    private Material parseMaterial(Player p, String name) {
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException ex) {
            p.sendMessage(ChatColor.RED + "Invalid material: " + name);
            return null;
        }
    }

    private int parseInt(Player p, String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            p.sendMessage(ChatColor.RED + "Invalid number: " + s);
            return -1;
        }
    }

    /** Spawns one candidate per tick, syncing chunk-load & block-change on main thread */
    private void scheduleSpawns(Player target, OreTestInfo info) {
        info.spawnTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            () -> {
                if (info.placed >= info.amount || info.attempts >= info.amount * 10) {
                    info.spawnTask.cancel();
                    if (info.placed < info.amount) {
                        info.issuer.sendMessage(ChatColor.RED +
                            "Warning: placed " + info.placed + "/" + info.amount +
                            " fake ores for " + target.getName());
                    }
                    return;
                }
                if (!target.isOnline()) {
                    info.spawnTask.cancel();
                    return;
                }

                info.attempts++;
                double angle = Math.random() * Math.PI * 2;
                double r     = 5 + Math.random() * (info.radius - 5);
                Location loc = target.getLocation()
                    .clone()
                    .add(Math.cos(angle) * r, 0, Math.sin(angle) * r)
                    .getBlock()
                    .getLocation();
                Block block = loc.getBlock();

                if (!block.getType().isSolid() ||
                    info.originalBlocks.containsKey(loc) ||
                    !block.getRelative(0,1,0).getType().isSolid() ||
                    isVisible(loc, target, info.radius)) {
                    return;
                }

                // synchronous chunk load
                loc.getWorld()
                   .loadChunk(loc.getChunk().getX(), loc.getChunk().getZ());

                // store & fake
                info.originalBlocks.put(loc,
                    new BlockInfo(block.getType(), block.getData()));
                info.fakeLocations.add(loc);
                target.sendBlockChange(loc, info.oreType, (byte) 0);

                info.placed++;
            },
            1L,
            1L
        );
    }

    private boolean isVisible(Location loc, Player player, int maxDistance) {
        Location eye = player.getEyeLocation();
        for (float yo : new float[]{0f,10f,-10f}) {
            for (float po : new float[]{0f,10f,-10f}) {
                Location e2 = eye.clone();
                e2.setYaw(e2.getYaw() + yo);
                e2.setPitch(e2.getPitch() + po);
                Vector dir = e2.getDirection();
                BlockIterator iter = new BlockIterator(
                    e2.getWorld(), e2.toVector(), dir, 0.0, maxDistance + 1
                );
                while (iter.hasNext()) {
                    Block b = iter.next();
                    if (b.getLocation().equals(loc)) return true;
                    if (b.getType().isSolid()) break;
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        OreTestInfo info = testingPlayers.get(e.getPlayer().getUniqueId());
        if (info != null) {
            double yaw = e.getTo().getYaw();
            double pitch = e.getTo().getPitch();
            info.minYaw = Math.min(info.minYaw,   yaw);
            info.maxYaw = Math.max(info.maxYaw,   yaw);
            info.minPitch = Math.min(info.minPitch, pitch);
            info.maxPitch = Math.max(info.maxPitch, pitch);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        UUID pid = e.getPlayer().getUniqueId();
        OreTestInfo info = testingPlayers.get(pid);
        if (info == null) return;
        Location clicked = e.getClickedBlock().getLocation();
        if (!info.fakeLocations.contains(clicked)) return;

        e.setCancelled(true);
        handleGuilty(pid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID pid = e.getPlayer().getUniqueId();
        OreTestInfo info = testingPlayers.remove(pid);
        if (info == null) return;
        if (info.timeoutTask != null) info.timeoutTask.cancel();
        if (info.spawnTask != null) info.spawnTask.cancel();
        revertFakeBlocks(e.getPlayer(), info);
    }

    private void handleGuilty(UUID pid) {
        OreTestInfo info = testingPlayers.remove(pid);
        if (info == null) return;
        if (info.timeoutTask != null) info.timeoutTask.cancel();
        if (info.spawnTask != null) info.spawnTask.cancel();

        // revert fake blocks to the suspect
        Player suspect = Bukkit.getPlayer(pid);
        if (suspect != null) revertFakeBlocks(suspect, info);

        // compute stats
        double timeTaken = (System.currentTimeMillis() - info.startTime) / 1000.0;
        double dyaw = info.maxYaw - info.minYaw;
        double dpitch = info.maxPitch - info.minPitch;
        String name = (suspect != null ? suspect.getName() : "Unknown");

        // send only to issuer
        info.issuer.sendMessage(
            ChatColor.RED + name + " → GUILTY (mined fake ore)\n" +
            ChatColor.GRAY + String.format(
                "Time: %.2fs | Yaw: %.1f° | Pitch: %.1f°",
                timeTaken, dyaw, dpitch
            )
        );
    }

    private void handleClean(UUID pid) {
        OreTestInfo info = testingPlayers.remove(pid);
        if (info == null) return;
        if (info.spawnTask != null) info.spawnTask.cancel();

        Player suspect = Bukkit.getPlayer(pid);
        if (suspect != null) revertFakeBlocks(suspect, info);

        double duration = (System.currentTimeMillis() - info.startTime) / 1000.0;
        double dyaw = info.maxYaw - info.minYaw;
        double dpitch = info.maxPitch - info.minPitch;
        String name = Bukkit.getOfflinePlayer(pid).getName();

        info.issuer.sendMessage(
            ChatColor.GREEN + name + " → CLEAN (no fake ore mined)\n" +
            ChatColor.GRAY + String.format(
                "Duration: %.2fs | Yaw: %.1f° | Pitch: %.1f°",
                duration, dyaw, dpitch
            )
        );
    }

    private void revertFakeBlocks(Player player, OreTestInfo info) {
        for (Location loc : info.fakeLocations) {
            BlockInfo orig = info.originalBlocks.get(loc);
            player.sendBlockChange(loc, orig.type, orig.data);
        }
    }
}
