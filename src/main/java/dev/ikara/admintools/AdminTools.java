package dev.ikara.admintools;

import dev.ikara.admintools.commands.*;
import org.bukkit.plugin.java.JavaPlugin;

public class AdminTools extends JavaPlugin {
    @Override
    public void onEnable() {
        getCommand("velocitytest").setExecutor(new VelocityCommand(this));
        getCommand("nofalltest").setExecutor(new NoFallCommand(this));
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
