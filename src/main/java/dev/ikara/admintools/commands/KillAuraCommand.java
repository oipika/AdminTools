package dev.ikara.admintools.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import dev.ikara.admintools.AdminTools;
import dev.ikara.admintools.util.MessageHandler;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.craftbukkit.v1_8_R3.*;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class KillAuraCommand implements CommandExecutor, Listener {

    private final AdminTools plugin;
    private final Map<UUID, TestSession> sessions = new HashMap<>();

    // Configurable thresholds & duration
    private final int defaultDurationTicks;
    private final int maxNpcHits;
    private final int maxGazeCount;
    private final int minGazeThreshold;
    private final int maxReachViolations;
    private final int maxTotalScore;
    private final double maxReach;
    private final double aimThreshold;

    public KillAuraCommand(AdminTools plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getCommand("killauratest").setExecutor(this);

        // Load from config.yml (under "killauratest")
        defaultDurationTicks  = plugin.getConfig().getInt("killauratest.default-duration-ticks", 100);
        maxNpcHits = plugin.getConfig().getInt("killauratest.max-npc-hits", 3);
        maxGazeCount = plugin.getConfig().getInt("killauratest.max-gaze-count", 50);
        minGazeThreshold = plugin.getConfig().getInt("killauratest.min-gaze-threshold", 3);
        maxReachViolations = plugin.getConfig().getInt("killauratest.max-reach-violations", 3);
        maxTotalScore = plugin.getConfig().getInt("killauratest.max-total-score", 50);
        maxReach = plugin.getConfig().getDouble("killauratest.max-reach", 3.05);
        aimThreshold = plugin.getConfig().getDouble("killauratest.aim-threshold", 15.0);
    }

    @Override
    public boolean onCommand(CommandSender snd, Command cmd, String lbl, String[] args) {
        if (!(snd instanceof Player)) {
            MessageHandler.sendErrorFmt(snd, "only-players");
            return true;
        }
        Player tester = (Player) snd;

        if (!tester.hasPermission("admintools.killaura")) {
            MessageHandler.sendErrorFmt(tester, "no-permission", "admintools.killaura");
            return true;
        }
        if (args.length != 1) {
            MessageHandler.sendErrorFmt(tester, "killaura-usage");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            MessageHandler.sendErrorFmt(tester, "player-not-found", args[0]);
            return true;
        }
        if (sessions.containsKey(target.getUniqueId())) {
            MessageHandler.sendErrorFmt(tester, "killaura-already-running", target.getName());
            return true;
        }
        startTest(tester, target);
        return true;
    }

    private void startTest(Player tester, Player target) {
        World bWorld = target.getWorld();

        // Spawn invisible armor stand for visual indicator
        Location spawnLoc = target.getLocation()
            .add(target.getLocation().getDirection().multiply(-2).normalize())
            .add(0, 1.5, 0);
        ArmorStand stand = (ArmorStand) bWorld.spawnEntity(spawnLoc, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setBasePlate(false);
        stand.setArms(false);

        // Create NMS fake player (NPC)
        MinecraftServer nmsServer = ((CraftServer) Bukkit.getServer()).getServer();
        WorldServer nmsWorld = ((CraftWorld) bWorld).getHandle();
        GameProfile orig = ((CraftPlayer) target).getProfile();
        GameProfile profile = new GameProfile(UUID.randomUUID(), " ");
        Property tex = orig.getProperties().get("textures").iterator().next();
        profile.getProperties().put("textures", tex);

        EntityPlayer npc = new EntityPlayer(nmsServer, nmsWorld, profile, new PlayerInteractManager(nmsWorld));
        npc.setLocation(spawnLoc.getX(), spawnLoc.getY(), spawnLoc.getZ(), 0, 0);
        npc.getBukkitEntity().getInventory().setItemInHand(new ItemStack(Material.DIAMOND_SWORD));

        // Send spawn packets
        PacketPlayOutPlayerInfo addInfo = new PacketPlayOutPlayerInfo(
            PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, npc
        );
        PacketPlayOutNamedEntitySpawn spawnPacket = new PacketPlayOutNamedEntitySpawn(npc);
        for (Player p : Arrays.asList(tester, target)) {
            ((CraftPlayer) p).getHandle().playerConnection.sendPacket(addInfo);
            ((CraftPlayer) p).getHandle().playerConnection.sendPacket(spawnPacket);
        }

        // Create session and seed position history
        TestSession sess = new TestSession(tester, target, stand, npc);
        for (int i = 0; i < 3; i++) {
            sess.history.add(target.getLocation().clone());
        }
        sessions.put(target.getUniqueId(), sess);

        MessageHandler.sendInfoFmt(tester, "killaura-started", target.getName());

        // Main loop: orbit NPC, record gaze, track hits
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!tester.isOnline() || !target.isOnline() || ticks++ >= defaultDurationTicks) {
                    endTest(target);
                    cancel();
                    return;
                }

                // record history
                sess.history.add(target.getLocation().clone());
                if (sess.history.size() > 20) sess.history.remove(0);

                // move armor stand and NPC in a circle
                double angle = Math.toRadians(ticks * 12);
                double radius = 2.5;
                double height = Math.abs(Math.sin(ticks * 0.25)) * 0.75;
                Location base = target.getLocation().add(0, 1.0, 0);
                double x = base.getX() + Math.cos(angle) * radius;
                double y = base.getY() + height;
                double z = base.getZ() + Math.sin(angle) * radius;
                Location moveLoc = new Location(bWorld, x, y, z);

                stand.teleport(moveLoc);
                sess.bStand.teleport(moveLoc);

                // compute yaw/pitch toward target’s eye
                Vector dir = base.toVector().subtract(moveLoc.toVector()).normalize();
                float yaw = (float) Math.toDegrees(Math.atan2(-dir.getZ(), dir.getX())) - 90;
                float pitch = (float) Math.toDegrees(
                    -Math.atan2(dir.getY(), Math.hypot(dir.getX(), dir.getZ()))
                );
                npc.setLocation(x, y, z, yaw, pitch);

                PacketPlayOutEntityTeleport tp = new PacketPlayOutEntityTeleport(npc);
                PacketPlayOutAnimation swing = new PacketPlayOutAnimation(npc, 0);
                for (Player p : Arrays.asList(tester, target)) {
                    ((CraftPlayer) p).getHandle().playerConnection.sendPacket(tp);
                    if (ticks % 10 == 0) {
                        ((CraftPlayer) p).getHandle().playerConnection.sendPacket(swing);
                    }
                }

                // record gaze
                checkAim(target, sess);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void checkAim(Player target, TestSession sess) {
        Location eye = target.getEyeLocation();
        Vector toNpc = sess.bStand.getLocation().subtract(eye).toVector().normalize();
        Vector view = eye.getDirection().normalize();
        double dot = Math.max(-1, Math.min(1, view.dot(toNpc)));
        double angle = Math.toDegrees(Math.acos(dot));
        if (angle < aimThreshold) {
            sess.gazeCount++;
        }
    }

    private void endTest(Player target) {
        TestSession sess = sessions.remove(target.getUniqueId());
        if (sess == null) return;

        // destroy NPC
        PacketPlayOutEntityDestroy destroy = new PacketPlayOutEntityDestroy(sess.nmsNpc.getId());
        for (Player p : Arrays.asList(sess.tester, sess.target)) {
            ((CraftPlayer) p).getHandle().playerConnection.sendPacket(destroy);
        }
        sess.bStand.remove();

        // compute final stats
        int totalPlayerHits = sess.playerHitsMap.values().stream().mapToInt(i -> i).sum();
        int score = sess.npcHits * 2
                  + sess.gazeCount
                  + sess.reachViolations * 3
                  - totalPlayerHits * 2;
        score = Math.max(0, score);

        boolean suspicious = sess.npcHits > maxNpcHits
                          || sess.reachViolations > maxReachViolations
                          || score > maxTotalScore
                          || sess.gazeCount > maxGazeCount
                          || sess.gazeCount < minGazeThreshold;

        // send results
        MessageHandler.sendInfoFmt(sess.tester, "killaura-test-result-title", sess.target.getName());
        MessageHandler.sendInfoFmt(sess.tester, "killaura-hits-npc", sess.npcHits);
        MessageHandler.sendInfoFmt(sess.tester, "killaura-gaze-count", sess.gazeCount);
        MessageHandler.sendInfoFmt(sess.tester, "killaura-reach-violations", sess.reachViolations);

        MessageHandler.sendInfoFmt(sess.tester, "killaura-hits-player", totalPlayerHits);
        for (Map.Entry<String,Integer> e : sess.playerHitsMap.entrySet()) {
            MessageHandler.sendInfoFmt(sess.tester, "killaura-hits-player-detail", e.getKey(), e.getValue());
        }

        MessageHandler.sendInfoFmt(sess.tester, "killaura-score", score, maxTotalScore);
        MessageHandler.sendInfoFmt(sess.tester, suspicious ? "verdict-suspicious" : "verdict-clean");

    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        Player damager = (Player) e.getDamager();
        TestSession sess = sessions.get(damager.getUniqueId());
        if (sess == null) return;

        if (e.getEntity().getEntityId() == sess.nmsNpc.getId()) {
            sess.npcHits++;
            Location past = getPastLocation(sess, 2);
            if (past != null && past.distance(e.getEntity().getLocation()) > maxReach) {
                sess.reachViolations++;
            }
        } else if (e.getEntity() instanceof Player) {
            String name = ((Player)e.getEntity()).getName();
            sess.playerHitsMap.merge(name, 1, Integer::sum);
        }
    }

    private Location getPastLocation(TestSession sess, int ticksAgo) {
        List<Location> hist = sess.history;
        return (hist.size() > ticksAgo) ? hist.get(hist.size() - ticksAgo - 1) : null;
    }

    private static class TestSession {
        final Player tester, target;
        final ArmorStand bStand;
        final EntityPlayer nmsNpc;
        final List<Location> history = new ArrayList<>();
        final Map<String,Integer> playerHitsMap = new LinkedHashMap<>();
        int npcHits = 0, gazeCount = 0, reachViolations = 0;

        TestSession(Player tester, Player target, ArmorStand stand, EntityPlayer npc) {
            this.tester = tester;
            this.target = target;
            this.bStand = stand;
            this.nmsNpc = npc;
        }
    }
}