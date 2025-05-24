package dev.ikara.admintools;

import dev.ikara.admintools.commands.*;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public class AdminTools extends JavaPlugin {
    // Command instances that need cleanup
    private CPSCommand      cpsCommand;
    private FakeOreCommand  fakeOreCommand;
    private FakeBaseCommand fakeBaseCommand;

    @Override
    public void onEnable() {
        // Initialize command instances
        cpsCommand      = new CPSCommand(this);
        fakeOreCommand  = new FakeOreCommand(this);
        fakeBaseCommand = new FakeBaseCommand(this);

        // Register all commands
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
        // Unregister every listener to avoid ghost handlers
        HandlerList.unregisterAll(this);

        // Cancel any in‑progress CPS tests
        if (cpsCommand != null) {
            cpsCommand.cancelAll();
        }

        // Cancel any fake‑ore or fake‑base tasks so no ghost blocks linger
        if (fakeOreCommand != null) {
            fakeOreCommand.cancelAllTests();
        }
        if (fakeBaseCommand != null) {
            fakeBaseCommand.cancelAllTests();
        }
    }

    /**
     * Helper to reduce boilerplate when registering commands.
     */
    private void registerCommand(String name, org.bukkit.command.CommandExecutor executor) {
        if (getCommand(name) != null) {
            getCommand(name).setExecutor(executor);
        } else {
            getLogger().warning("Command '/" + name + "' is not defined in plugin.yml");
        }
    }
}
