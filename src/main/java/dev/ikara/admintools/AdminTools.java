package dev.ikara.admintools;

import dev.ikara.admintools.commands.*;
import dev.ikara.admintools.listeners.ClickListener;
import org.bukkit.plugin.java.JavaPlugin;

public class AdminTools extends JavaPlugin {

    @Override
    public void onEnable() {
        // Shared instance for CPSCommand
        CPSCommand cpsCommand = new CPSCommand(this);

        // Register commands
        getCommand("velocitytest").setExecutor(new VelocityCommand(this));
        getCommand("nofalltest").setExecutor(new NoFallCommand(this));
        getCommand("cpstest").setExecutor(cpsCommand);

        // Register listener for click tracking
        getServer().getPluginManager().registerEvents(new ClickListener(cpsCommand), this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
