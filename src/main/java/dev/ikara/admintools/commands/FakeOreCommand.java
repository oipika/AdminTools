package dev.ikara.admintools.commands;

import dev.ikara.admintools.AdminTools;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * /fakeore <player> [material] [amount] [radius] [durationSeconds]
 * • Default: diamond_ore, amount=5, radius=10, duration=30s
 * • Spawns each fake ore ≥5 blocks away, hidden underground
 * • Advanced LOS: casts 9 rays (yaw±10°, pitch±10°)
 * • Analyzes view‑variation & time‑to‑mine for verdict
 * Permission: admintools.fakeore
 */
public class FakeOreCommand implements CommandExecutor, Listener {

    private final AdminTools plugin;
    private final Map<UUID, OreTestInfo> testingPlayers = new HashMap<>();

    private static class OreTestInfo {
        Player issuer;
        Material oreType;
        int amount, radius, durationSeconds;
        long startTime;
        List<Location> fakeLocations = new ArrayList<>();
        Map<Location, BlockInfo> originalBlocks = new HashMap<>();
        List<MovementInfo> movements = new ArrayList<>();
        BukkitTask timeoutTask;
    }

    private static class BlockInfo {
        final Material type;
        final byte data;
        BlockInfo(Material type, byte data) {
            this.type = type;
            this.data = data;
        }
    }

    private static class MovementInfo {
        final double yaw, pitch;
        MovementInfo(PlayerMoveEvent e) {
            this.yaw = e.getTo().getYaw();
            this.pitch = e.getTo().getPitch();
        }
    }

    private static class ViewStats {
        final double yawRange, pitchRange;
        ViewStats(double yawRange, double pitchRange) {
            this.yawRange = yawRange;
            this.pitchRange = pitchRange;
        }
    }

    public FakeOreCommand(AdminTools plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Revert all active tests on shutdown.
     */
    public void cancelAllTests() {
        for (Map.Entry<UUID, OreTestInfo> entry : testingPlayers.entrySet()) {
            OreTestInfo info = entry.getValue();
            if (info.timeoutTask != null) info.timeoutTask.cancel();
            Player suspect = Bukkit.getPlayer(entry.getKey());
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
            issuer.sendMessage(ChatColor.RED + "That player is already in a fake‑ore test.");
            return true;
        }

        // parse args with defaults
        Material oreType    = (args.length >= 2 ? parseMaterial(issuer, args[1]) : Material.DIAMOND_ORE);
        int amount          = (args.length >= 3 ? parseInt(issuer, args[2], 5) : 5);
        int radius          = (args.length >= 4 ? parseInt(issuer, args[3], 10) : 10);
        int durationSeconds = (args.length >= 5 ? parseInt(issuer, args[4], 30) : 30);

        if (oreType == null || amount < 0 || radius < 0 || durationSeconds < 0) {
            return true;  // parse errors already messaged
        }

        OreTestInfo info = new OreTestInfo();
        info.issuer          = issuer;
        info.oreType         = oreType;
        info.amount          = amount;
        info.radius          = radius;
        info.durationSeconds = durationSeconds;
        info.startTime       = System.currentTimeMillis();
        testingPlayers.put(tid, info);

        spawnFakes(target, info);

        // schedule automatic “clean” verdict
        info.timeoutTask = plugin.getServer().getScheduler().runTaskLater(
            plugin,
            () -> handleClean(tid),
            durationSeconds * 20L
        );

        issuer.sendMessage(ChatColor.YELLOW +
            "Fake‑ore test started on " + target.getName() +
            " → " + amount + "×" + oreType.name() +
            " in " + radius + " blocks for " + durationSeconds + "s.");
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

    private void spawnFakes(Player target, OreTestInfo info) {
        Random rand = new Random();
        Location center = target.getLocation();
        int placed = 0, attempts = 0;

        while (placed < info.amount && attempts++ < info.amount * 10) {
            double angle = rand.nextDouble() * 2 * Math.PI;
            double r     = 5 + rand.nextDouble() * (info.radius - 5);  // ≥5 blocks away
            Location loc = center.clone()
                                 .add(Math.cos(angle) * r, 0, Math.sin(angle) * r)
                                 .getBlock()
                                 .getLocation();
            Block block = loc.getBlock();

            // must be solid and not used
            if (!block.getType().isSolid() || info.originalBlocks.containsKey(loc)) {
                continue;
            }
            // hidden underground: block above must be solid
            if (!block.getRelative(0, 1, 0).getType().isSolid()) {
                continue;
            }
            // advanced LOS: skip if any ray hits it
            if (isVisible(loc, target, info.radius)) {
                continue;
            }
            // ensure chunk loaded
            loc.getWorld().loadChunk(loc.getChunk());

            // store & send fake
            info.originalBlocks.put(loc, new BlockInfo(block.getType(), block.getData()));
            info.fakeLocations.add(loc);
            target.sendBlockChange(loc, info.oreType, (byte) 0);

            placed++;
        }
    }

    private boolean isVisible(Location loc, Player player, int maxDistance) {
        Location eye = player.getEyeLocation();
        float[] yawOffsets   = {0f, 10f, -10f};
        float[] pitchOffsets = {0f, 10f, -10f};

        for (float yo : yawOffsets) {
            for (float po : pitchOffsets) {
                Location tempEye = eye.clone();
                tempEye.setYaw(tempEye.getYaw() + yo);
                tempEye.setPitch(tempEye.getPitch() + po);

                Vector dir = tempEye.getDirection();
                BlockIterator iter = new BlockIterator(
                    tempEye.getWorld(),
                    tempEye.toVector(),
                    dir,
                    0.0,
                    maxDistance + 1
                );

                while (iter.hasNext()) {
                    Block b = iter.next();
                    if (b.getLocation().equals(loc)) {
                        return true;   // visible
                    }
                    if (b.getType().isSolid()) {
                        break;         // ray blocked
                    }
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        OreTestInfo info = testingPlayers.get(e.getPlayer().getUniqueId());
        if (info != null) {
            info.movements.add(new MovementInfo(e));
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
        revertFakeBlocks(e.getPlayer(), info);
    }

    private void handleGuilty(UUID pid) {
        OreTestInfo info = testingPlayers.remove(pid);
        if (info == null) return;
        if (info.timeoutTask != null) info.timeoutTask.cancel();

        Player suspect = Bukkit.getPlayer(pid);
        if (suspect != null) {
            revertFakeBlocks(suspect, info);
        }

        double timeTaken = (System.currentTimeMillis() - info.startTime) / 1000.0;
        ViewStats vs = analyzeViewMovements(info);
        String name = (suspect != null ? suspect.getName() : "Unknown");

        info.issuer.sendMessage(
            ChatColor.GREEN + name + " → GUILTY (mined fake ore).\n" +
            ChatColor.YELLOW + String.format(
                "Time: %.2fs  |  ΔYaw: %.1f°  ΔPitch: %.1f°",
                timeTaken, vs.yawRange, vs.pitchRange
            )
        );
        if (suspect != null) {
            suspect.sendMessage(
                ChatColor.RED + "X‑ray detected! You mined fake ore after " +
                String.format("%.2fs", timeTaken) +
                " with minimal view change."
            );
        }
    }

    private void handleClean(UUID pid) {
        OreTestInfo info = testingPlayers.remove(pid);
        if (info == null) return;

        Player suspect = Bukkit.getPlayer(pid);
        if (suspect != null) {
            revertFakeBlocks(suspect, info);
        }

        double duration = (System.currentTimeMillis() - info.startTime) / 1000.0;
        ViewStats vs = analyzeViewMovements(info);
        String name = Bukkit.getOfflinePlayer(pid).getName();

        info.issuer.sendMessage(
            ChatColor.GREEN + name + " → CLEAN (no fake mined).\n" +
            ChatColor.YELLOW + String.format(
                "Duration: %.2fs  |  ΔYaw: %.1f°  ΔPitch: %.1f°",
                duration, vs.yawRange, vs.pitchRange
            )
        );
    }

    private void revertFakeBlocks(Player player, OreTestInfo info) {
        for (Location L : info.fakeLocations) {
            BlockInfo O = info.originalBlocks.get(L);
            player.sendBlockChange(L, O.type, O.data);
        }
    }

    private ViewStats analyzeViewMovements(OreTestInfo info) {
        if (info.movements.isEmpty()) {
            return new ViewStats(0, 0);
        }
        double minYaw = Double.MAX_VALUE, maxYaw = Double.MIN_VALUE;
        double minPitch = Double.MAX_VALUE, maxPitch = Double.MIN_VALUE;
        for (MovementInfo m : info.movements) {
            minYaw   = Math.min(minYaw, m.yaw);
            maxYaw   = Math.max(maxYaw, m.yaw);
            minPitch = Math.min(minPitch, m.pitch);
            maxPitch = Math.max(maxPitch, m.pitch);
        }
        return new ViewStats(maxYaw - minYaw, maxPitch - minPitch);
    }
}
