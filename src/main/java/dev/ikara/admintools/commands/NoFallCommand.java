package dev.ikara.admintools.commands;

import dev.ikara.admintools.AdminTools;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class NoFallCommand implements CommandExecutor, Listener {

    private final AdminTools plugin;
    private final Map<UUID, TestInfo> testingPlayers = new HashMap<>();
    private final Map<UUID, Boolean> wasOnGroundMap = new HashMap<>();

    private static class TestInfo {
        public final double expectedDamage;
        public final Player issuer;
        public final long startTime;

        public TestInfo(double expectedDamage, Player issuer) {
            this.expectedDamage = expectedDamage;
            this.issuer = issuer;
            this.startTime = System.currentTimeMillis();
        }
    }

    public NoFallCommand(AdminTools plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player issuer = (Player) sender;
        Player target;

        if (args.length >= 1) {
            target = Bukkit.getPlayer(args[0]);
            if (target == null || !target.isOnline()) {
                issuer.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
        } else {
            target = issuer;
        }

        UUID uuid = target.getUniqueId();

        if (testingPlayers.containsKey(uuid)) {
            issuer.sendMessage(ChatColor.RED + "This player is already undergoing a no-fall test.");
            return true;
        }

        double launchVelocity = 1.2;
        target.setVelocity(new Vector(0, launchVelocity, 0));

        double fallDistance = (launchVelocity * launchVelocity) / (2 * 0.08);
        double expectedDamage = Math.max(0, (fallDistance - 3)) * 0.5; // base formula

        // Adjust for Jump potion effect
        if (target.hasPotionEffect(PotionEffectType.JUMP)) {
            expectedDamage *= 0.8;
        }

        // Adjust for armor and enchantments
        expectedDamage *= getArmorReductionMultiplier(target);

        // Adjust for server lag TPS
        double tps = getServerTPS();
        if (tps < 18) expectedDamage *= 0.85;

        testingPlayers.put(uuid, new TestInfo(expectedDamage * 2, issuer));
        wasOnGroundMap.put(uuid, target.isOnGround()); // Initialize onGround state

        issuer.sendMessage(ChatColor.YELLOW + "Launched " + target.getName() + " into the air for no-fall test.");
        issuer.sendMessage(ChatColor.GRAY + "Expected fall damage: " + String.format("%.2f", expectedDamage * 2));

        return true;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!testingPlayers.containsKey(uuid)) return;

        Boolean wasOnGround = wasOnGroundMap.get(uuid);
        boolean isOnGround = player.isOnGround();

        if (wasOnGround == null) {
            wasOnGroundMap.put(uuid, isOnGround);
            return;
        }

        if (!wasOnGround && isOnGround) {
            // Player just landed
            TestInfo test = testingPlayers.get(uuid);
            test.issuer.sendMessage(ChatColor.GREEN + player.getName() + " has landed. No-fall test ended.");
            testingPlayers.remove(uuid);
            wasOnGroundMap.remove(uuid);
        } else {
            wasOnGroundMap.put(uuid, isOnGround);
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        Player player = (Player) event.getEntity();
        UUID uuid = player.getUniqueId();

        if (!testingPlayers.containsKey(uuid)) return;

        TestInfo test = testingPlayers.get(uuid);

        if (event.getCause() != DamageCause.FALL) {
            // Took unrelated damage during test
            test.issuer.sendMessage(ChatColor.RED + player.getName() + " took " + event.getCause() + " damage during the test. Test invalidated.");
            testingPlayers.remove(uuid);
            wasOnGroundMap.remove(uuid);
            return;
        }

        // Check if landed on a safe block that reduces or negates fall damage
        Material landedBlock = player.getLocation().subtract(0, 1, 0).getBlock().getType();
        if (landedOnSafeBlock(landedBlock)) {
            test.issuer.sendMessage(ChatColor.YELLOW + player.getName() + " landed on a block that reduces or nullifies fall damage: " + landedBlock.name());
            testingPlayers.remove(uuid);
            wasOnGroundMap.remove(uuid);
            return;
        }

        double actualDamage = event.getDamage();
        double expected = test.expectedDamage;

        if (actualDamage < expected * 0.85) {
            test.issuer.sendMessage(ChatColor.RED + "Suspicious: " + player.getName() + " took only " + actualDamage + " damage but expected at least " + expected);
        } else {
            test.issuer.sendMessage(ChatColor.GREEN + player.getName() + " took " + actualDamage + " fall damage. Test passed.");
        }

        testingPlayers.remove(uuid);
        wasOnGroundMap.remove(uuid);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (testingPlayers.containsKey(uuid)) {
            Player issuer = testingPlayers.get(uuid).issuer;
            issuer.sendMessage(ChatColor.YELLOW + event.getPlayer().getName() + " quit during the no-fall test.");
            testingPlayers.remove(uuid);
            wasOnGroundMap.remove(uuid);
        }
    }

    private boolean landedOnSafeBlock(Material material) {
        return material == Material.HAY_BLOCK ||
               material == Material.LADDER ||
               material == Material.VINE ||
               material == Material.WATER ||
               material == Material.STATIONARY_WATER ||
               material == Material.SLIME_BLOCK ||
               material == Material.WEB ||
               material == Material.BED_BLOCK ||
               material == Material.LAVA ||
               material == Material.STATIONARY_LAVA;
    }

    private double getArmorReductionMultiplier(Player player) {
        int totalReduction = 0;
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor == null) continue;
            Material type = armor.getType();
            switch (type) {
                case DIAMOND_HELMET:
                case DIAMOND_BOOTS: totalReduction += 3; break;
                case DIAMOND_LEGGINGS: totalReduction += 6; break;
                case DIAMOND_CHESTPLATE: totalReduction += 8; break;
                case IRON_HELMET:
                case IRON_BOOTS: totalReduction += 2; break;
                case IRON_LEGGINGS: totalReduction += 5; break;
                case IRON_CHESTPLATE: totalReduction += 6; break;
                case GOLD_HELMET:
                case GOLD_BOOTS: totalReduction += 1; break;
                case GOLD_LEGGINGS: totalReduction += 3; break;
                case GOLD_CHESTPLATE: totalReduction += 5; break;
                case LEATHER_HELMET:
                case LEATHER_BOOTS: totalReduction += 1; break;
                case LEATHER_LEGGINGS: totalReduction += 2; break;
                case LEATHER_CHESTPLATE: totalReduction += 3; break;
                case CHAINMAIL_HELMET:
                case CHAINMAIL_BOOTS: totalReduction += 2; break;
                case CHAINMAIL_LEGGINGS: totalReduction += 5; break;
                case CHAINMAIL_CHESTPLATE: totalReduction += 6; break;
                default: break;
            }

            int prot = armor.getEnchantmentLevel(Enchantment.PROTECTION_ENVIRONMENTAL);
            if (prot > 0) totalReduction -= prot; // reduce expected damage further
        }
        double multiplier = 1.0 - (totalReduction * 0.04);
        return Math.max(0.2, multiplier); // cap at 80% reduction
    }

    private double getServerTPS() {
        return 20.0; // Fallback for 1.8 servers
    }
}
