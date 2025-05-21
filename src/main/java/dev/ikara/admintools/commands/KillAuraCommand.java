package dev.ikara.admintools.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import dev.ikara.admintools.AdminTools;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.MinecraftServer;
import net.minecraft.server.v1_8_R3.PacketPlayOutAnimation;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityDestroy;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityTeleport;
import net.minecraft.server.v1_8_R3.PacketPlayOutNamedEntitySpawn;
import net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo;
import net.minecraft.server.v1_8_R3.PlayerInteractManager;
import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class KillAuraCommand implements CommandExecutor, Listener {

    private final JavaPlugin             plugin;
    private final Map<UUID,TestSession>  sessions = new HashMap<>();

    // thresholds
    private static final int     MAX_NPC_HITS         = 3;
    private static final int     MAX_GAZE_COUNT       = 50;
    private static final int     MIN_GAZE_THRESHOLD   = 3;
    private static final int     MAX_REACH_VIOLATIONS = 3;
    private static final int     MAX_TOTAL_SCORE      = 50;
    private static final double  MAX_REACH            = 3.05;
    private static final double  AIM_THRESHOLD        = 15.0;

    public KillAuraCommand(AdminTools plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(CommandSender snd, Command cmd, String lbl, String[] args) {
        if (!(snd instanceof Player)) return false;
        Player tester = (Player) snd;
        if (!tester.hasPermission("admintools.killaura")) {
            tester.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        if (args.length != 1) {
            tester.sendMessage(ChatColor.RED + "Usage: /killauratest <player>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            tester.sendMessage(ChatColor.RED + "Player not found.");
            return true;
        }
        startTest(tester, target);
        return true;
    }

    private void startTest(Player tester, Player target) {
        UUID tid = target.getUniqueId();
        if (sessions.containsKey(tid)) {
            tester.sendMessage(ChatColor.RED + "Test already running.");
            return;
        }

        // Bukkit world
        org.bukkit.World bWorld = target.getWorld();

        // Spawn ArmorStand visual
        Location spawnLoc = target.getLocation()
            .add(target.getLocation().getDirection().multiply(-1).normalize().multiply(2))
            .add(0, 1.5, 0);
        ArmorStand stand = (ArmorStand) bWorld.spawnEntity(spawnLoc, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setBasePlate(false);
        stand.setSmall(false);
        stand.setArms(false);
        stand.setCustomNameVisible(false);

        // NMS fake player
        MinecraftServer nmsServer = ((CraftServer) Bukkit.getServer()).getServer();
        WorldServer      nmsWorld  = ((CraftWorld) bWorld).getHandle();
        GameProfile      orig      = ((CraftPlayer) target).getProfile();
        GameProfile      profile   = new GameProfile(UUID.randomUUID(), " ");
        Property         tex       = orig.getProperties().get("textures").iterator().next();
        profile.getProperties().put("textures", tex);
        EntityPlayer npc = new EntityPlayer(nmsServer, nmsWorld, profile, new PlayerInteractManager(nmsWorld));
        npc.setLocation(spawnLoc.getX(), spawnLoc.getY(), spawnLoc.getZ(), 0, 0);
        npc.getBukkitEntity().getInventory().setItemInHand(new ItemStack(Material.DIAMOND_SWORD));

        // Send ADD + SPAWN packets
        PacketPlayOutPlayerInfo    addInfo     = new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, npc);
        PacketPlayOutNamedEntitySpawn spawnPacket = new PacketPlayOutNamedEntitySpawn(npc);
        for (Player p : Arrays.asList(tester, target)) {
            ((CraftPlayer) p).getHandle().playerConnection.sendPacket(addInfo);
            ((CraftPlayer) p).getHandle().playerConnection.sendPacket(spawnPacket);
        }

        // Create session + seed history
        TestSession sess = new TestSession(tester, target, stand, npc);
        for (int i = 0; i <= 2; i++) sess.history.add(target.getLocation().clone());
        sessions.put(tid, sess);

        tester.sendMessage(ChatColor.GREEN + "KillAura test started on " + target.getName() + ".");

        // Main loop
        new BukkitRunnable() {
            int ticks = 0, duration = 100;
            @Override
            public void run() {
                if (!tester.isOnline() || !target.isOnline() || ticks++ >= duration) {
                    endTest(target);
                    cancel();
                    return;
                }

                // lag‑comp history
                sess.history.add(target.getLocation().clone());
                if (sess.history.size() > 20) sess.history.remove(0);

                // orbit motion
                double angle  = Math.toRadians(ticks * 12);
                double radius = 2.5;
                double height = Math.abs(Math.sin(ticks * 0.25)) * 0.75;
                Location base  = target.getLocation().add(0, 1.0, 0);
                double x = base.getX() + Math.cos(angle) * radius;
                double y = base.getY() + height;
                double z = base.getZ() + Math.sin(angle) * radius;
                Location moveLoc = new Location(bWorld, x, y, z);

                stand.teleport(moveLoc);
                sess.bStand.teleport(moveLoc);

                // compute yaw/pitch
                Vector dir = base.toVector().subtract(moveLoc.toVector());
                float yaw   = (float) Math.toDegrees(Math.atan2(-dir.getZ(), dir.getX())) - 90;
                float pitch = (float) Math.toDegrees(-Math.atan2(
                    dir.getY(),
                    Math.sqrt(dir.getX()*dir.getX() + dir.getZ()*dir.getZ())
                ));

                npc.setLocation(x, y, z, yaw, pitch);
                PacketPlayOutEntityTeleport tp    = new PacketPlayOutEntityTeleport(npc);
                PacketPlayOutAnimation       swing = new PacketPlayOutAnimation(npc, 0);

                for (Player p : Arrays.asList(tester, target)) {
                    ((CraftPlayer) p).getHandle().playerConnection.sendPacket(tp);
                    if (ticks % 10 == 0) {
                        ((CraftPlayer) p).getHandle().playerConnection.sendPacket(swing);
                    }
                }

                checkAim(target, sess);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void checkAim(Player target, TestSession sess) {
        Location eye    = target.getEyeLocation();
        Vector   toNpc  = sess.bStand.getLocation().subtract(eye).toVector().normalize();
        Vector   view   = eye.getDirection().normalize();
        double   dot    = view.dot(toNpc);
        dot = Math.max(-1, Math.min(1, dot));
        double angle   = Math.toDegrees(Math.acos(dot));
        if (angle < AIM_THRESHOLD) sess.gazeCount++;
    }

    private void endTest(Player target) {
        UUID tid = target.getUniqueId();
        TestSession sess = sessions.remove(tid);
        if (sess == null) return;

        // teardown NPC
        for (Player p : Arrays.asList(sess.tester, sess.target)) {
            PacketPlayOutEntityDestroy destroy = new PacketPlayOutEntityDestroy(sess.nmsNpc.getId());
            ((CraftPlayer) p).getHandle().playerConnection.sendPacket(destroy);
        }
        if (sess.bStand != null) sess.bStand.remove();

        int totalPlayerHits = sess.playerHitsMap.values().stream().mapToInt(i->i).sum();

        int score = sess.npcHits * 2
                  + sess.gazeCount
                  + sess.reachViolations * 3
                  - totalPlayerHits * 2;
        if (score < 0) score = 0;

        boolean suspicious = sess.npcHits         > MAX_NPC_HITS
                          || sess.reachViolations > MAX_REACH_VIOLATIONS
                          || score              > MAX_TOTAL_SCORE
                          || sess.gazeCount     > MAX_GAZE_COUNT
                          || sess.gazeCount     < MIN_GAZE_THRESHOLD;

        // verdict
        sess.tester.sendMessage(ChatColor.YELLOW + "Test result for " + sess.target.getName() + ":");
        sess.tester.sendMessage(ChatColor.GRAY   + "Hits on NPC: " + sess.npcHits);
        sess.tester.sendMessage(ChatColor.GRAY   + "Gaze Count: " + sess.gazeCount);
        sess.tester.sendMessage(ChatColor.GRAY   + "Reach Violations: " + sess.reachViolations);

        sess.tester.sendMessage(ChatColor.GRAY   + "Hits on Player: " + totalPlayerHits);
        for (Map.Entry<String,Integer> e : sess.playerHitsMap.entrySet()) {
            sess.tester.sendMessage(ChatColor.GRAY + " * " + e.getKey() + ": " + e.getValue());
        }

        sess.tester.sendMessage(ChatColor.GRAY   + "Score: " + score + "/" + MAX_TOTAL_SCORE);
        sess.tester.sendMessage("");
        sess.tester.sendMessage(suspicious
            ? ChatColor.RED   + "Verdict: Suspicious"
            : ChatColor.GREEN + "Verdict: Clean"
        );
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        Player damager = (Player) e.getDamager();
        TestSession sess = sessions.get(damager.getUniqueId());
        if (sess == null) return;

        if (e.getEntity().getEntityId() == sess.nmsNpc.getId()) {
            sess.npcHits++;
            Location past = getPastLocation(sess,2);
            if (past != null && past.distance(e.getEntity().getLocation()) > MAX_REACH) {
                sess.reachViolations++;
            }
        }
        else if (e.getEntity() instanceof Player) {
            String name = ((Player)e.getEntity()).getName();
            sess.playerHitsMap.merge(name,1,Integer::sum);
        }
    }

    private Location getPastLocation(TestSession sess, int ticksAgo) {
        List<Location> hist = sess.history;
        if (hist.size() <= ticksAgo) return null;
        return hist.get(hist.size() - ticksAgo - 1);
    }

    private static class TestSession {
        final Player               tester, target;
        final ArmorStand           bStand;
        final EntityPlayer         nmsNpc;
        final List<Location>       history         = new ArrayList<>();
        final Map<String,Integer>  playerHitsMap   = new LinkedHashMap<>();
        int npcHits = 0, gazeCount = 0, reachViolations = 0;

        TestSession(Player tester, Player target, ArmorStand stand, EntityPlayer npc) {
            this.tester  = tester;
            this.target  = target;
            this.bStand  = stand;
            this.nmsNpc  = npc;
        }
    }
}
