package dev.ikara.admintools.commands;

import dev.ikara.admintools.AdminTools;
import dev.ikara.admintools.util.MessageHandler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import java.util.*;

public class NoFallCommand implements CommandExecutor, Listener {

    private final AdminTools plugin;
    private final Map<UUID, TestInfo> testingPlayers = new HashMap<>();
    private final Map<UUID, Boolean> wasOnGroundMap  = new HashMap<>();

    // config values
    private final double launchVelocity;
    private final double gravity;
    private final int minTps;
    private final double tpsAdjustFactor;
    private final double invalidationFactor;
    private final Set<Material> safeBlocks;

    private static class TestInfo {
        final double expectedDamage;
        final Player issuer;
        TestInfo(double expectedDamage, Player issuer) {
            this.expectedDamage = expectedDamage;
            this.issuer = issuer;
        }
    }

    public NoFallCommand(AdminTools plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // load config
        launchVelocity = plugin.getConfig().getDouble("nofall.launch-velocity", 1.2);
        gravity = plugin.getConfig().getDouble("nofall.gravity", 0.08);
        minTps = plugin.getConfig().getInt("nofall.min-tps", 18);
        tpsAdjustFactor = plugin.getConfig().getDouble("nofall.tps-adjust-factor", 0.85);
        invalidationFactor = plugin.getConfig().getDouble("nofall.invalidation-factor", 0.85);
        List<String> list = plugin.getConfig().getStringList("nofall.safe-blocks");
        safeBlocks = new HashSet<>();
        for (String name : list) safeBlocks.add(Material.valueOf(name));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            MessageHandler.sendErrorFmt(sender, "only-players");
            return true;
        }
        Player issuer = (Player) sender;
        if (!issuer.hasPermission("admintools.nofall")) {
            MessageHandler.sendErrorFmt(issuer, "no-permission", "admintools.nofall");
            return true;
        }
        Player target;
        if (args.length >= 1) {
            target = Bukkit.getPlayer(args[0]);
            if (target == null || !target.isOnline()) {
                MessageHandler.sendErrorFmt(issuer, "player-not-found", args[0]);
                return true;
            }
        } else {
            target = issuer;
        }
        UUID uuid = target.getUniqueId();
        if (testingPlayers.containsKey(uuid)) {
            MessageHandler.sendErrorFmt(issuer, "nofall-already-running", target.getName());
            return true;
        }

        // launch up
        target.setVelocity(new Vector(0, launchVelocity, 0));
        double fallDistance = (launchVelocity * launchVelocity) / (2 * gravity);
        double expectedHearts = Math.floor(fallDistance - 3);
        double expectedDamage = expectedHearts * 0.5;

        // armor/enchant
        expectedDamage *= getDamageMultiplier(target);
        // TPS adjust
        double tps = getServerTPS();
        if (tps < minTps) expectedDamage *= tpsAdjustFactor;

        testingPlayers.put(uuid, new TestInfo(expectedDamage, issuer));
        wasOnGroundMap.put(uuid, target.isOnGround());

        MessageHandler.sendInfoFmt(issuer, "nofall-started", target.getName());
        MessageHandler.sendInfoFmt(issuer, "nofall-expected-damage", expectedDamage);
        return true;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!testingPlayers.containsKey(id)) return;
        boolean was = wasOnGroundMap.getOrDefault(id, p.isOnGround());
        boolean now = p.isOnGround();
        if (!was && now) {
            TestInfo t = testingPlayers.remove(id);
            MessageHandler.sendInfoFmt(t.issuer, "nofall-landed", p.getName());
            wasOnGroundMap.remove(id);
        } else {
            wasOnGroundMap.put(id, now);
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        UUID id = p.getUniqueId();
        if (!testingPlayers.containsKey(id)) return;
        TestInfo t = testingPlayers.remove(id);
        wasOnGroundMap.remove(id);

        if (e.getCause() != DamageCause.FALL) {
            MessageHandler.sendInfoFmt(t.issuer, "nofall-invalidated",
                p.getName(), e.getCause().toString()
            );
            return;
        }

        // safe block
        Material mat = p.getLocation().subtract(0,1,0).getBlock().getType();
        if (safeBlocks.contains(mat)) {
            MessageHandler.sendInfoFmt(t.issuer, "nofall-invalidated",
                p.getName(), mat.name()
            );
            return;
        }

        double actual = e.getDamage();
        double expected = t.expectedDamage;
        if (actual < expected * invalidationFactor) {
            MessageHandler.sendInfoFmt(t.issuer, "nofall-suspicious",
                p.getName(), actual, expected
            );
        } else {
            MessageHandler.sendInfoFmt(t.issuer, "nofall-pass", p.getName(), actual);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (testingPlayers.containsKey(id)) {
            TestInfo t = testingPlayers.remove(id);
            MessageHandler.sendInfoFmt(t.issuer, "nofall-quit", e.getPlayer().getName());
            wasOnGroundMap.remove(id);
        }
    }

    private boolean landedOnSafeBlock(Material m) {
        return safeBlocks.contains(m);
    }

    private double getDamageMultiplier(Player p) {
        int armor=0, prot=0;
        for (ItemStack it : p.getInventory().getArmorContents()) {
            if (it==null) continue;
            armor += getArmorPoints(it.getType());
            prot += it.getEnchantmentLevel(Enchantment.PROTECTION_ENVIRONMENTAL);
        }
        armor = Math.min(armor,20);
        double am = 1.0 - armor*0.04;
        double pr = Math.min(prot,5)*0.04;
        double mult = am*(1-pr);
        return Math.max(0.2, Math.min(1.0, mult));
    }

    private int getArmorPoints(Material t) {
        switch(t) {
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

    private double getServerTPS() { return 20.0; }
}