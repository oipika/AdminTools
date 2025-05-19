package dev.ikara.admintools.commands;

import com.mojang.authlib.GameProfile;
import dev.ikara.admintools.AdminTools;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class KillAuraCommand implements CommandExecutor {
    private final AdminTools plugin;

    public KillAuraCommand(AdminTools plugin) {
        this.plugin = plugin;
    }

    private boolean isLookingAt(Player player, Location target) {
        Vector direction = player.getLocation().getDirection().normalize();
        Vector toTarget = target.toVector().subtract(player.getLocation().toVector()).normalize();
        double dot = direction.dot(toTarget);
        return dot > 0.98; // ~11 degrees field of view
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players may use this command.");
            return true;
        }

        Player tester = (Player) sender;

        if (!tester.hasPermission("admintools.killaura")) {
            tester.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length != 1) {
            tester.sendMessage(ChatColor.RED + "Usage: /killauratest <player>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            tester.sendMessage(ChatColor.RED + "That player is not online.");
            return true;
        }

        Location center = target.getLocation().add(0, 1.5, 0);
        World world = target.getWorld();

        // Create NPC
        MinecraftServer nmsServer = ((CraftServer) Bukkit.getServer()).getServer();
        WorldServer nmsWorld = ((CraftWorld) world).getHandle();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "§r");
        EntityPlayer npc = new EntityPlayer(nmsServer, nmsWorld, profile, new PlayerInteractManager(nmsWorld));

        npc.setLocation(center.getX(), center.getY(), center.getZ(), 0, 0);

        PacketPlayOutPlayerInfo addInfo = new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, npc);
        PacketPlayOutNamedEntitySpawn spawn = new PacketPlayOutNamedEntitySpawn(npc);

        for (Player online : Bukkit.getOnlinePlayers()) {
            ((CraftPlayer) online).getHandle().playerConnection.sendPacket(addInfo);
            ((CraftPlayer) online).getHandle().playerConnection.sendPacket(spawn);
        }

        List<Boolean> suspiciousHits = new ArrayList<>();
        List<Boolean> gazeChecks = new ArrayList<>();

        new BukkitRunnable() {
            int ticks = 0;
            final int duration = 100; // 5 seconds

            @Override
            public void run() {
                if (ticks >= duration || !target.isOnline()) {
                    this.cancel();

                    // Final verdict
                    boolean suspicious = suspiciousHits.stream().filter(b -> b).count() > 1 || gazeChecks.stream().filter(b -> b).count() > 50;
                    String verdict = suspicious
                            ? ChatColor.RED + target.getName() + " might be using Kill Aura."
                            : ChatColor.GREEN + target.getName() + " appears clean.";

                    tester.sendMessage(verdict);

                    // Remove NPC
                    PacketPlayOutEntityDestroy destroy = new PacketPlayOutEntityDestroy(npc.getId());
                    PacketPlayOutPlayerInfo removeInfo = new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, npc);

                    for (Player online : Bukkit.getOnlinePlayers()) {
                        ((CraftPlayer) online).getHandle().playerConnection.sendPacket(destroy);
                        ((CraftPlayer) online).getHandle().playerConnection.sendPacket(removeInfo);
                    }

                    return;
                }

                double angle = Math.toRadians(ticks * 10);
                double radius = 2.5;
                double height = Math.sin(ticks * 0.1) * 0.5;

                double x = center.getX() + Math.cos(angle) * radius;
                double y = center.getY() + height;
                double z = center.getZ() + Math.sin(angle) * radius;

                npc.setLocation(x, y, z, 0, 0);
                PacketPlayOutEntityTeleport teleport = new PacketPlayOutEntityTeleport(npc);
                for (Player online : Bukkit.getOnlinePlayers()) {
                    ((CraftPlayer) online).getHandle().playerConnection.sendPacket(teleport);
                }

                // Check for suspicious behavior
                if (target.getLocation().distance(new Location(world, x, y, z)) < 3.01) {
                    suspiciousHits.add(true);
                } else {
                    suspiciousHits.add(false);
                }

                gazeChecks.add(isLookingAt(target, new Location(world, x, y, z)));

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L); // Every tick

        tester.sendMessage(ChatColor.GREEN + "Kill Aura test started on " + target.getName() + ".");

        return true;
    }
}
