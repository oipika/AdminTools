package dev.ikara.admintools.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import dev.ikara.admintools.AdminTools;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.*;
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
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class KillAuraCommand implements CommandExecutor, Listener {

    private final JavaPlugin plugin;
    private final Map<UUID, TestSession> sessions = new HashMap<>();

    private static final int MAX_NPC_HITS = 15;
    private static final int MAX_GAZE_COUNT = 15;
    private static final int MAX_PLAYER_HITS = 15;
    private static final int MAX_REACH_VIOLATIONS = 3;
    private static final int MAX_TOTAL_SCORE = 30;
    private static final double MAX_REACH = 3.05;
    private static final double MAX_YAW_DIFF = 45.0;
    private static final double MAX_PITCH_DIFF = 45.0;
    private static final double AIM_THRESHOLD = 30.0;

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

        org.bukkit.World bWorld = target.getWorld();
        Location base = target.getLocation().add(0, 1.5, 0);

        // Spawn the NPC behind the player
        Location spawnLocation = target.getLocation().add(target.getLocation().getDirection().multiply(-1).normalize().multiply(2)).add(0, 1.5, 0);

        ArmorStand stand = (ArmorStand) bWorld.spawnEntity(spawnLocation, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setSmall(false);
        stand.setBasePlate(false);
        stand.setArms(false);
        stand.setMarker(false);
        stand.setCustomNameVisible(false);

        MinecraftServer nmsServer = ((CraftServer) Bukkit.getServer()).getServer();
        WorldServer nmsWorld = ((CraftWorld) bWorld).getHandle();

        GameProfile originalProfile = ((CraftPlayer) target).getProfile();
        GameProfile profile = new GameProfile(UUID.randomUUID(), " ");
        Property texture = originalProfile.getProperties().get("textures").iterator().next();
        profile.getProperties().put("textures", texture);

        EntityPlayer npc = new EntityPlayer(nmsServer, nmsWorld, profile, new PlayerInteractManager(nmsWorld));
        npc.setLocation(spawnLocation.getX(), spawnLocation.getY(), spawnLocation.getZ(), 0, 0);
        npc.getBukkitEntity().getInventory().setItemInHand(new ItemStack(Material.DIAMOND_SWORD));

        PacketPlayOutPlayerInfo addInfo = new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, npc);
        PacketPlayOutNamedEntitySpawn spawnPacket = new PacketPlayOutNamedEntitySpawn(npc);

        for (Player p : Arrays.asList(tester, target)) {
            CraftPlayer cp = (CraftPlayer) p;
            cp.getHandle().playerConnection.sendPacket(addInfo);
            cp.getHandle().playerConnection.sendPacket(spawnPacket);
        }

        TestSession sess = new TestSession(tester, target, stand.getEntityId(), npc.getId());
        sess.bStand = stand;
        sess.nmsNpc = npc;
        sess.bNpc = npc.getBukkitEntity();
        sessions.put(tid, sess);

        tester.sendMessage(ChatColor.GREEN + "KillAura test started on " + target.getName() + ".");

        new BukkitRunnable() {
            int ticks = 0, duration = 100;

            @Override
            public void run() {
                if (!tester.isOnline() || !target.isOnline() || ticks++ >= duration) {
                    endTest(target);
                    cancel();
                    return;
                }

                // NPC Movement
                double angle = Math.toRadians(ticks * 12); // Faster movement
                double radius = 2.5;
                double jumpHeight = Math.abs(Math.sin(ticks * 0.25)) * 0.75; 

                Location baseLoc = target.getLocation().add(0, 1.0, 0);
                double x = baseLoc.getX() + Math.cos(angle) * radius;
                double y = baseLoc.getY() + jumpHeight;
                double z = baseLoc.getZ() + Math.sin(angle) * radius;

                Location moveLoc = new Location(bWorld, x, y, z);

                // Teleport ArmorStand and NPC
                stand.teleport(moveLoc);
                sess.bStand.teleport(moveLoc);

                Vector direction = baseLoc.clone().toVector().subtract(moveLoc.toVector());
                float yaw = (float) Math.toDegrees(Math.atan2(-direction.getZ(), direction.getX())) - 90;
                float pitch = (float) Math.toDegrees(-Math.atan2(direction.getY(), Math.sqrt(direction.getX() * direction.getX() + direction.getZ() * direction.getZ())));

                npc.setLocation(x, y, z, yaw, pitch);

                // Update NPC's position
                PacketPlayOutEntityTeleport tp = new PacketPlayOutEntityTeleport(npc);
                for (Player p : Arrays.asList(tester, target)) {
                    ((CraftPlayer) p).getHandle().playerConnection.sendPacket(tp);
                }

                // NPC Animation for swinging sword
                if (ticks % 10 == 0) {
                    PacketPlayOutAnimation swing = new PacketPlayOutAnimation(npc, 0);
                    for (Player p : Arrays.asList(tester, target)) {
                        ((CraftPlayer) p).getHandle().playerConnection.sendPacket(swing);
                    }
                }

                // Aim check
                checkAim(tester, target, sess);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void checkAim(Player tester, Player target, TestSession sess) {
        // Check if the angle between the target's view direction and the NPC's location is too small
        Location targetEye = target.getEyeLocation();
        Vector directionToNpc = sess.bNpc.getLocation().subtract(targetEye).toVector().normalize();
        Vector playerViewDirection = targetEye.getDirection().normalize();
        
        double dotProduct = playerViewDirection.dot(directionToNpc);
        double angle = Math.toDegrees(Math.acos(dotProduct));

        if (angle < AIM_THRESHOLD) {
            sess.gazeCount++;  // Increment gaze count if the angle is too small, indicating a potential aim assist
        }
    }

    private void endTest(Player target) {
        UUID tid = target.getUniqueId();
        TestSession sess = sessions.remove(tid);
        if (sess == null) return;

        for (Player p : Arrays.asList(sess.tester, sess.target)) {
            EntityPlayer npc = sess.nmsNpc;
            PacketPlayOutEntityDestroy destroy = new PacketPlayOutEntityDestroy(npc.getId());
            ((CraftPlayer) p).getHandle().playerConnection.sendPacket(destroy);
        }

        if (sess.bStand != null) sess.bStand.remove();
        if (sess.bNpc != null) sess.bNpc.remove();

        int score = sess.npcHits * 2 + sess.gazeCount + sess.reachViolations * 3 - sess.playerHits * 2;

        boolean suspicious = sess.npcHits > MAX_NPC_HITS ||
                             sess.reachViolations > MAX_REACH_VIOLATIONS ||
                             score > MAX_TOTAL_SCORE ||
                             sess.gazeCount > 15;

        // Ensure the player hits are included in the final verdict
        sess.tester.sendMessage(ChatColor.YELLOW + "Test result for " + sess.target.getName() + ":");
        sess.tester.sendMessage(ChatColor.GRAY + "Hits on NPC: " + sess.npcHits);
        sess.tester.sendMessage(ChatColor.GRAY + "Gaze Count: " + sess.gazeCount);
        sess.tester.sendMessage(ChatColor.GRAY + "Reach Violations: " + sess.reachViolations);
        sess.tester.sendMessage(ChatColor.GRAY + "Hits on Player: " + sess.playerHits); // Player hits
        sess.tester.sendMessage(ChatColor.GRAY + "Score: " + score + "/" + MAX_TOTAL_SCORE);
        sess.tester.sendMessage("");
        sess.tester.sendMessage(suspicious ? ChatColor.RED + "Verdict: Suspicious" : ChatColor.GREEN + "Verdict: Clean");
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        Player p = (Player) e.getDamager();
        UUID pid = p.getUniqueId();
        TestSession sess = sessions.get(pid);
        if (sess == null) return;

        // Lag compensated reach check using multiple historical positions
        Location pastDamagerLoc = getPastLocation(p, 2);
        if (pastDamagerLoc == null) return;

        if (e.getEntity().getEntityId() == sess.npcId) {
            sess.npcHits++;
            double reach = pastDamagerLoc.distance(e.getEntity().getLocation());
            if (reach > MAX_REACH) sess.reachViolations++;
        } else if (e.getEntity() instanceof Player) {  // Target hits any player
            sess.playerHits++;  // Increment player hit count for any player hit
        }
    }

    private Location getPastLocation(Player p, int ticksAgo) {
        if (!p.hasMetadata("lag_comp_history")) return null;
        List<Location> history = (List<Location>) p.getMetadata("lag_comp_history").get(0).value();
        if (history.size() <= ticksAgo) return null;
        return history.get(history.size() - ticksAgo - 1);
    }

    private static class TestSession {
        final Player tester, target;
        final int standId, npcId;
        int npcHits = 0, gazeCount = 0, playerHits = 0, reachViolations = 0;
        ArmorStand bStand;
        org.bukkit.entity.Player bNpc;
        EntityPlayer nmsNpc;

        TestSession(Player tester, Player target, int standId, int npcId) {
            this.tester = tester;
            this.target = target;
            this.standId = standId;
            this.npcId = npcId;
        }
    }
}
