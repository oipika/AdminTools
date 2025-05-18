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
import org.bukkit.potion.PotionEffect;
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

        // Launch player upward
        double launchVelocity = 1.2;
        target.setVelocity(new Vector(0, launchVelocity, 0));

        // Calculate expected fall distance based on physics: d = v² / (2 * g)
        // g (gravity) ~ 0.08 in Minecraft
        double fallDistance = (launchVelocity * launchVelocity) / (2 * 0.08);

        // Calculate expected vanilla fall damage (integer-based)
        int fallDamageHearts = (int) Math.floor(fallDistance - 3);
        double expectedDamage = fallDamageHearts * 0.5; // 0.5 damage per half heart

        // Calculate armor & enchantment reduction multiplier (vanilla)
        double damageMultiplier = getArmorAndEnchantmentsDamageMultiplier(target);

        // Apply damage multiplier
        expectedDamage *= damageMultiplier;

        // Remove jump potion effect modifier because vanilla does not reduce fall damage from jump boosts
        // You can uncomment if you want to experiment:
        /*
        if (target.hasPotionEffect(PotionEffectType.JUMP)) {
            expectedDamage *= 0.8;
        }
        */

        // TPS adjustment if you want (optional, vanilla does not do this)
        double tps = getServerTPS();
        if (tps < 18) expectedDamage *= 0.85;

        testingPlayers.put(uuid, new TestInfo(expectedDamage, issuer));
        wasOnGroundMap.put(uuid, target.isOnGround()); // Initialize onGround state

        issuer.sendMessage(ChatColor.YELLOW + "Launched " + target.getName() + " into the air for no-fall test.");
        issuer.sendMessage(ChatColor.GRAY + "Expected fall damage: " + String.format("%.2f", expectedDamage));

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

        // Check if landed on a block that reduces or negates fall damage
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
            test.issuer.sendMessage(ChatColor.RED + "Suspicious: " + player.getName() + " took only " + actualDamage + " damage but expected at least " + String.format("%.2f", expected));
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

    /**
     * Calculate the damage multiplier based on armor points and protection enchantment,
     * following vanilla Minecraft 1.8 damage reduction formula.
     *
     * @param player the player being tested
     * @return damage multiplier [0.2, 1.0] where 1.0 means no reduction
     */
    private double getArmorAndEnchantmentsDamageMultiplier(Player player) {
        // Sum up total armor points (max 20)
        int totalArmorPoints = 0;
        int totalProtectionLevels = 0;

        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor == null) continue;

            Material type = armor.getType();
            totalArmorPoints += getArmorPoints(type);
            totalProtectionLevels += armor.getEnchantmentLevel(Enchantment.PROTECTION_ENVIRONMENTAL);
        }

        if (totalArmorPoints > 20) totalArmorPoints = 20;

        // Armor damage reduction (each armor point = 4%)
        double armorMultiplier = 1.0 - (totalArmorPoints * 0.04);

        // Protection enchantment reduces damage by 4% per level, capped at 20%
        int protLevelsCapped = Math.min(totalProtectionLevels, 5); // max 5 levels in vanilla
        double protectionReduction = protLevelsCapped * 0.04;

        double finalMultiplier = armorMultiplier * (1.0 - protectionReduction);

        // Clamp multiplier between 0.2 and 1.0 (max 80% reduction)
        if (finalMultiplier < 0.2) finalMultiplier = 0.2;
        if (finalMultiplier > 1.0) finalMultiplier = 1.0;

        return finalMultiplier;
    }

    private int getArmorPoints(Material type) {
        switch (type) {
            case DIAMOND_HELMET: return 3;
            case DIAMOND_CHESTPLATE: return 8;
            case DIAMOND_LEGGINGS: return 6;
            case DIAMOND_BOOTS: return 3;

            case IRON_HELMET: return 2;
            case IRON_CHESTPLATE: return 6;
            case IRON_LEGGINGS: return 5;
            case IRON_BOOTS: return 2;

            case GOLD_HELMET: return 2;
            case GOLD_CHESTPLATE: return 5;
            case GOLD_LEGGINGS: return 3;
            case GOLD_BOOTS: return 1;

            case LEATHER_HELMET: return 1;
            case LEATHER_CHESTPLATE: return 3;
            case LEATHER_LEGGINGS: return 2;
            case LEATHER_BOOTS: return 1;

            case CHAINMAIL_HELMET: return 2;
            case CHAINMAIL_CHESTPLATE: return 5;
            case CHAINMAIL_LEGGINGS: return 4;
            case CHAINMAIL_BOOTS: return 1;

            default: return 0;
        }
    }

    private double getServerTPS() {
        return 20.0; // fallback, replace with real TPS check if available
    }
}
