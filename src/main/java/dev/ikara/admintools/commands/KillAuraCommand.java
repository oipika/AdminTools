package dev.ikara.admintools.commands;

import com.mojang.authlib.GameProfile;
import dev.ikara.admintools.AdminTools;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class KillAuraCommand implements CommandExecutor, Listener {

    private final JavaPlugin plugin;
    private final Map<UUID, TestSession> sessions = new HashMap<>();

    private static final int MAX_NPC_HITS = 5;
    private static final int MAX_GAZE_COUNT = 10;
    private static final double MAX_REACH = 3.1;
    private static final double GAZE_THRESHOLD = 0.95;

    public KillAuraCommand(AdminTools plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private boolean isLookingAt(Player p, Location loc) {
        Vector dir = p.getEyeLocation().getDirection().normalize();
        Vector toTarget = loc.toVector().subtract(p.getEyeLocation().toVector()).normalize();
        return dir.dot(toTarget) > GAZE_THRESHOLD;
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

        ArmorStand stand = (ArmorStand) bWorld.spawnEntity(base, org.bukkit.entity.EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setSmall(false);
        stand.setBasePlate(false);
        stand.setArms(false);
        stand.setMarker(false);
        stand.setCustomNameVisible(false);

        MinecraftServer nmsServer = ((CraftServer) Bukkit.getServer()).getServer();
        net.minecraft.server.v1_8_R3.WorldServer nmsWorld = ((CraftWorld) bWorld).getHandle();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "");
        EntityPlayer npc = new EntityPlayer(nmsServer, nmsWorld, profile, new PlayerInteractManager(nmsWorld));
        npc.setLocation(base.getX(), base.getY(), base.getZ(), 0, 0);

        PacketPlayOutPlayerInfo addInfo = new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, npc);
        PacketPlayOutNamedEntitySpawn spawn = new PacketPlayOutNamedEntitySpawn(npc);
        for (Player p : Arrays.asList(tester, target)) {
            CraftPlayer cp = (CraftPlayer) p;
            cp.getHandle().playerConnection.sendPacket(addInfo);
            cp.getHandle().playerConnection.sendPacket(spawn);
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

                double angle = Math.toRadians(ticks * 10);
                double r = 2.5, h = Math.sin(ticks * 0.1) * 0.5;
                Location baseLoc = target.getLocation().add(0, 1.5, 0);
                double x = baseLoc.getX() + Math.cos(angle) * r;
                double y = baseLoc.getY() + h;
                double z = baseLoc.getZ() + Math.sin(angle) * r;
                Location loc = new Location(bWorld, x, y, z);

                stand.teleport(loc);
                sess.bStand.teleport(loc);
                npc.setLocation(x, y, z, 0, 0);

                // NPC looks at target
                Location npcLoc = new Location(bWorld, x, y, z);
                Location playerLoc = target.getLocation().add(0, 1.5, 0);
                double dx = playerLoc.getX() - npcLoc.getX();
                double dz = playerLoc.getZ() - npcLoc.getZ();
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                npc.setLocation(x, y, z, yaw, 0);

                PacketPlayOutEntityTeleport tp = new PacketPlayOutEntityTeleport(npc);
                for (Player p : Arrays.asList(tester, target)) {
                    ((CraftPlayer) p).getHandle().playerConnection.sendPacket(tp);
                }

                if (isLookingAt(target, loc)) sess.gazeCount++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void endTest(Player target) {
        TestSession sess = sessions.remove(target.getUniqueId());
        if (sess == null) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sess.bStand != null && !sess.bStand.isDead()) sess.bStand.remove();
            if (sess.bNpc != null && sess.bNpc.isValid()) sess.bNpc.remove();

            PacketPlayOutEntityDestroy destroy = new PacketPlayOutEntityDestroy(sess.fakeId);
            PacketPlayOutPlayerInfo removeInfo = new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, sess.nmsNpc);
            for (Player p : Arrays.asList(sess.tester, sess.target)) {
                CraftPlayer cp = (CraftPlayer) p;
                cp.getHandle().playerConnection.sendPacket(destroy);
                cp.getHandle().playerConnection.sendPacket(removeInfo);
            }

            boolean suspicious = sess.npcHits > MAX_NPC_HITS || sess.gazeCount > MAX_GAZE_COUNT || sess.playerHits > 0 || sess.reachViolations > 0;
            sess.tester.sendMessage(ChatColor.GOLD + "— KillAura Results for " + sess.target.getName() + " —");
            sess.tester.sendMessage(ChatColor.GRAY + "NPC Hits:    " + sess.npcHits);
            sess.tester.sendMessage(ChatColor.GRAY + "Gazes:       " + sess.gazeCount);
            sess.tester.sendMessage(ChatColor.GRAY + "Player Hits: " + sess.playerHits);
            sess.tester.sendMessage(ChatColor.GRAY + "Reach Fails: " + sess.reachViolations);
            sess.tester.sendMessage(ChatColor.GRAY + "Total Score: " + (sess.npcHits + sess.gazeCount + sess.playerHits + sess.reachViolations));
            sess.tester.sendMessage(suspicious ? ChatColor.RED + "Verdict: Suspicious" : ChatColor.GREEN + "Verdict: Clean");
        });
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        Player dam = (Player) e.getDamager();
        TestSession sess = sessions.get(dam.getUniqueId());
        if (sess == null) return;

        double dist = dam.getLocation().distance(e.getEntity().getLocation());
        if (dist > MAX_REACH) sess.reachViolations++;

        int eid = e.getEntity().getEntityId();
        if (eid == sess.standId) {
            sess.npcHits++;

            // Swing and hurt animation
            PacketPlayOutAnimation swing = new PacketPlayOutAnimation(sess.nmsNpc, 0);
            PacketPlayOutEntityStatus hurt = new PacketPlayOutEntityStatus(sess.nmsNpc, (byte) 2);

            // NPC turns toward damager
            Location npcLoc = sess.bNpc.getLocation();
            Location damLoc = dam.getLocation();
            double dx = damLoc.getX() - npcLoc.getX();
            double dz = damLoc.getZ() - npcLoc.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            sess.nmsNpc.setLocation(npcLoc.getX(), npcLoc.getY(), npcLoc.getZ(), yaw, 0);
            PacketPlayOutEntityTeleport tp = new PacketPlayOutEntityTeleport(sess.nmsNpc);

            sess.bNpc.getWorld().playSound(sess.bNpc.getLocation(), Sound.HURT_FLESH, 1f, 1f);

            for (Player p : Arrays.asList(sess.tester, sess.target)) {
                PlayerConnection conn = ((CraftPlayer) p).getHandle().playerConnection;
                conn.sendPacket(swing);
                conn.sendPacket(hurt);
                conn.sendPacket(tp);
            }
        } else if (e.getEntity() instanceof Player) {
            sess.playerHits++;
        }
    }

    private static class TestSession {
        final Player tester, target;
        final int standId, fakeId;
        ArmorStand bStand;
        EntityPlayer nmsNpc;
        org.bukkit.entity.Entity bNpc;
        int npcHits = 0;
        int gazeCount = 0;
        int playerHits = 0;
        int reachViolations = 0;

        TestSession(Player tester, Player target, int standId, int fakeId) {
            this.tester = tester;
            this.target = target;
            this.standId = standId;
            this.fakeId = fakeId;
        }
    }
}
