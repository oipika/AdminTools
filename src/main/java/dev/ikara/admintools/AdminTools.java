package dev.ikara.admintools;

import dev.ikara.admintools.util.MessageHandler;
import dev.ikara.admintools.commands.*;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public class AdminTools extends JavaPlugin {
    private CPSCommand      cpsCommand;
    private FakeOreCommand  fakeOreCommand;
    private FakeBaseCommand fakeBaseCommand;

    @Override
    public void onEnable() {
        // Ensure config.yml and messages.yml are created
        this.saveDefaultConfig();
        MessageHandler.init(this);

        // Initialize command instances
        cpsCommand      = new CPSCommand(this);
        fakeOreCommand  = new FakeOreCommand(this);
        fakeBaseCommand = new FakeBaseCommand(this);

        // Register commands and tab completers
        registerCommand("velocitytest", new VelocityCommand(this));
        registerCommand("nofalltest",   new NoFallCommand(this));
        registerCommand("killauratest", new KillAuraCommand(this));
        registerCommand("aimanalysis",  new AimAnalysisCommand(this));
        registerCommand("fakeore",      fakeOreCommand);
        registerCommand("fakebase",     fakeBaseCommand);
        registerCommand("cpstest",      cpsCommand);
    }

    @Override
    public void onDisable() {
        // Unregister all listeners
        HandlerList.unregisterAll(this);
    }

    /**
     * Helper to register executor and tab completer (if implemented)
     */
    private void registerCommand(String name, org.bukkit.command.CommandExecutor executor) {
        if (getCommand(name) != null) {
            getCommand(name).setExecutor(executor);
            if (executor instanceof org.bukkit.command.TabCompleter) {
                getCommand(name).setTabCompleter((org.bukkit.command.TabCompleter) executor);
            }
        } else {
            getLogger().warning("Command '/" + name + "' is not defined in plugin.yml");
        }
    }
}