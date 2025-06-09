package dev.ikara.admintools.commands;

import dev.ikara.admintools.AdminTools;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class FakeBaseCommand implements CommandExecutor, Listener {
    // --- Configurable Constants ---
    private static final String PERM = "admintools.fakebase";
    private static final int DIST_DEF = 10; // Distance away from target
    private static final int TIME_DEF = 60; // Seconds till despawn
    private static final int SHELL_H = 4; // Fake Base Template Height
    private static final int RECT_L = 10, RECT_W = 6; // Rectangle Base Dimensions
    private static final int SQR_L  = 8, SQR_W  = 8; // Square Base Dimensions
    private static final int GAZE_STREAK_REQ = 3;
    private static final double RAY_STEP = 0.5;

    private final AdminTools plugin;
    private final Random random = new Random();
    private final Map<UUID, Test> active = new HashMap<>();

    private static class Test {
        Player target, issuer;
        int distance, duration;
        long startTime;
        int gazeCount, gazeStreak;
        BukkitRunnable gazeTask, endTask;
        Set<Location> watched = new HashSet<>();
        Map<Location, BlockData> originals = new HashMap<>();
    }

    private static class BlockData {
        final Material mat; final byte data;
        BlockData(Material m, byte d) { mat = m; data = d; }
    }

    public FakeBaseCommand(AdminTools plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // Called by AdminTools.onDisable() 
    public void cancelAllTests() {
        for (Test t : active.values()) {
            if (t.gazeTask != null) t.gazeTask.cancel();
            if (t.endTask  != null) t.endTask.cancel();
            cancelTest(t);
        }
        active.clear();
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player)) {
            s.sendMessage(ChatColor.RED + "Only players.");
            return true;
        }
        Player issuer = (Player) s;
        if (!issuer.hasPermission(PERM)) {
            issuer.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        if (args.length < 1) {
            issuer.sendMessage(ChatColor.YELLOW + "/fakebase <player> [distance]");
            return true;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            issuer.sendMessage(ChatColor.RED + "Player not online.");
            return true;
        }

        // parse distance
        int dist = DIST_DEF;
        if (args.length >= 2) {
            try {
                dist = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                issuer.sendMessage(ChatColor.RED + "Distance must be a number.");
                return true;
            }
        }

        // parse time (seconds)
        int time = TIME_DEF;
        if (args.length >= 3) {
            try {
                time = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                issuer.sendMessage(ChatColor.RED + "Time must be a number.");
                return true;
            }
        }

        startTest(issuer, target, dist, time);
        return true;
    }

    // now accepts duration, uses BukkitRunnable
    private void startTest(Player issuer, Player target, int distance, int duration) {
        UUID id = target.getUniqueId();
        if (active.containsKey(id)) {
            Test old = active.remove(id);
            if (old.gazeTask != null) old.gazeTask.cancel();
            if (old.endTask  != null) old.endTask.cancel();
        }

        Test t = new Test();
        t.issuer = issuer;
        t.target = target;
        t.distance = distance;
        t.duration = duration;
        t.startTime = System.currentTimeMillis();
        active.put(id, t);

        Location p = target.getLocation();
        int ox = p.getBlockX() + distance,
            oy = Math.max(p.getBlockY() - 20, 5),
            oz = p.getBlockZ();
        World w = p.getWorld();

        buildBase(w, ox, oy, oz, t);

        // clickable creation message
        TextComponent msg = new TextComponent("Successfully created a fake base for ");
        TextComponent nameComp = new TextComponent(target.getName());
        nameComp.setColor(net.md_5.bungee.api.ChatColor.GOLD);
        nameComp.setHoverEvent(new HoverEvent(
            HoverEvent.Action.SHOW_TEXT,
            new ComponentBuilder("Teleport to player.").create()
        ));
        nameComp.setClickEvent(new ClickEvent(
            ClickEvent.Action.RUN_COMMAND,
            "/tp " + target.getName()
        ));
        msg.addExtra(nameComp);
        msg.addExtra(new TextComponent(" at "));
        String coords = ox + " " + oy + " " + oz;
        TextComponent coordComp = new TextComponent(coords);
        coordComp.setColor(net.md_5.bungee.api.ChatColor.GOLD);
        coordComp.setHoverEvent(new HoverEvent(
            HoverEvent.Action.SHOW_TEXT,
            new ComponentBuilder("Teleport to base.").create()
        ));
        coordComp.setClickEvent(new ClickEvent(
            ClickEvent.Action.RUN_COMMAND,
            "/tp " + coords
        ));
        msg.addExtra(coordComp);
        issuer.spigot().sendMessage(msg);

        // gaze tracking via BukkitRunnable
        t.gazeTask = new BukkitRunnable() {
            @Override
            public void run() {
                trackGaze(t);
            }
        };
        t.gazeTask.runTaskTimer(plugin, 1, 1);

        // auto‑cleanup on timeout via BukkitRunnable
        t.endTask = new BukkitRunnable() {
            @Override
            public void run() {
                cancelTest(t);
            }
        };
        t.endTask.runTaskLater(plugin, t.duration * 20L);
    }

    private void buildBase(World w, int ox, int oy, int oz, Test t) {
        boolean isRect = random.nextBoolean();
        int len = isRect ? RECT_L : SQR_L,
            wid = isRect ? RECT_W : SQR_W;
        int rot = random.nextInt(4);

        // Shell
        for (int dx = 0; dx < len; dx++)
            for (int dz = 0; dz < wid; dz++)
                for (int dy = 0; dy <= SHELL_H; dy++) {
                    int[] o = rotate(dx, dz, len, wid, rot);
                    Location loc = new Location(w, ox + o[0], oy + dy, oz + o[1]);
                    boolean border = (dy==0||dy==SHELL_H||dx==0||dx==len-1||dz==0||dz==wid-1);
                    placeFake(loc,
                        border ? Material.SMOOTH_BRICK : Material.AIR,
                        t);
                }

        // Interior – random double‑chest, table, furnace, torches  
        List<int[]> floor = new ArrayList<>();
        for (int dx = 1; dx < len-1; dx++)
            for (int dz = 1; dz < wid-1; dz++)
                floor.add(new int[]{dx,dz});

        // double chest
        List<int[]> chest = new ArrayList<>();
        if (random.nextBoolean()) {
            int cx = random.nextInt(len-3)+1, cz = random.nextInt(wid-2)+1;
            chest.add(new int[]{cx, cz});
            chest.add(new int[]{cx+1, cz});
        } else {
            int cx = random.nextInt(len-2)+1, cz = random.nextInt(wid-3)+1;
            chest.add(new int[]{cx, cz});
            chest.add(new int[]{cx, cz+1});
        }
        floor.removeIf(p -> chest.stream().anyMatch(c->c[0]==p[0]&&c[1]==p[1]));

        // crafting table
        int idx = random.nextInt(floor.size());
        int[] tablePos = floor.remove(idx);

        // furnace
        idx = random.nextInt(floor.size());
        int[] furnacePos = floor.remove(idx);

        // torches **inside** walls
        List<int[]> walls = new ArrayList<>();
        for (int dz = 1; dz < wid-1; dz++) {
            walls.add(new int[]{1, dz});
            walls.add(new int[]{len-2, dz});
        }
        for (int dx = 1; dx < len-1; dx++) {
            walls.add(new int[]{dx, 1});
            walls.add(new int[]{dx, wid-2});
        }
        Collections.shuffle(walls);
        List<int[]> torches = walls.subList(0, 4);

        // place them
        for (int[] c : chest) {
            int[] o = rotate(c[0], c[1], len, wid, rot);
            placeFake(new Location(w, ox+o[0], oy+1, oz+o[1]), Material.CHEST, t);
        }
        {
            int[] o = rotate(tablePos[0], tablePos[1], len, wid, rot);
            placeFake(new Location(w, ox+o[0], oy+1, oz+o[1]), Material.WORKBENCH, t);
        }
        {
            int[] o = rotate(furnacePos[0], furnacePos[1], len, wid, rot);
            placeFake(new Location(w, ox+o[0], oy+1, oz+o[1]), Material.FURNACE, t);
        }
        for (int[] tc : torches) {
            int[] o = rotate(tc[0], tc[1], len, wid, rot);
            placeFake(new Location(w, ox+o[0], oy+1, oz+o[1]), Material.TORCH, t);
        }
    }

    private void trackGaze(Test t) {
        if (!t.target.isOnline()) return;
        Vector dir  = t.target.getEyeLocation().getDirection().normalize();
        Location eye = t.target.getEyeLocation();
        Block   hit = null;
        for (double d = 0; d <= 50; d += RAY_STEP) {
            Block b = eye.clone().add(dir.clone().multiply(d)).getBlock();
            if (b.getType() != Material.AIR) { hit = b; break; }
        }
        if (hit != null && t.watched.contains(hit.getLocation())) {
            t.gazeStreak++;
            if (t.gazeStreak >= GAZE_STREAK_REQ) {
                t.gazeCount++;
                t.gazeStreak = 0;
            }
        } else {
            t.gazeStreak = 0;
        }
    }

    /** Clean end (timeout) */
    private void cancelTest(Test t) {
        if (t.gazeTask != null) t.gazeTask.cancel();
        if (t.endTask  != null) t.endTask.cancel();
        t.originals.forEach((loc, bd) ->
            t.target.sendBlockChange(loc, bd.mat, bd.data)
        );
        active.remove(t.target.getUniqueId());

        long elapsedMs = System.currentTimeMillis() - t.startTime;
        int  secs = (int)(elapsedMs / 1000);

        TextComponent verdict = new TextComponent("Fakebase Test: ");
        verdict.setColor(net.md_5.bungee.api.ChatColor.GOLD);

        TextComponent result = new TextComponent(t.target.getName() + " is CLEAN");
        result.setColor(net.md_5.bungee.api.ChatColor.GREEN);
        verdict.addExtra(result);

        TextComponent details = new TextComponent("\n Duration: " + secs + "s | Gazes: " + t.gazeCount);
        details.setColor(net.md_5.bungee.api.ChatColor.GRAY);
        verdict.addExtra(details);

        t.issuer.spigot().sendMessage(verdict);
    }

    /** Guilty end (left‑click) */
    private void endTestGuilty(Test t) {
        if (t.gazeTask != null) t.gazeTask.cancel();
        if (t.endTask != null) t.endTask.cancel();
        t.originals.forEach((loc, bd) ->
            t.target.sendBlockChange(loc, bd.mat, bd.data)
        );
        active.remove(t.target.getUniqueId());

        long elapsedMs = System.currentTimeMillis() - t.startTime;
        int secs = (int)(elapsedMs / 1000);

        TextComponent verdict = new TextComponent("Fakebase Test: ");
        verdict.setColor(net.md_5.bungee.api.ChatColor.GOLD);

        TextComponent result = new TextComponent(t.target.getName() + " is SUSPICIOUS");
        result.setColor(net.md_5.bungee.api.ChatColor.RED);
        verdict.addExtra(result);

        TextComponent details = new TextComponent("\n Duration: " + secs + "s | Gazes: " + t.gazeCount);
        details.setColor(net.md_5.bungee.api.ChatColor.GRAY);
        verdict.addExtra(details);

        t.issuer.spigot().sendMessage(verdict);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Test t = active.get(e.getPlayer().getUniqueId());
        if (t == null) return;
        Location loc = e.getClickedBlock().getLocation();
        if (!t.watched.contains(loc)) return;
        endTestGuilty(t);
        e.setCancelled(true);
    }

    // record & send one fake block
    private void placeFake(Location loc, Material mat, Test t) {
        Block real = loc.getBlock();
        t.originals.put(loc, new BlockData(real.getType(), real.getData()));
        t.watched.add(loc);
        t.target.sendBlockChange(loc, mat, (byte)0);
    }

    /** Rotate (dx,dz) by rot*90° in a len×wid box */
    private int[] rotate(int dx, int dz, int len, int wid, int rot) {
        switch (rot) {
            case 1: return new int[]{ wid - 1 - dz, dx };
            case 2: return new int[]{ len - 1 - dx, wid - 1 - dz };
            case 3: return new int[]{ dz, len - 1 - dx };
            default:return new int[]{ dx, dz };
        }
    }
}
