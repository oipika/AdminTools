package dev.ikara.admintools;

import dev.ikara.admintools.commands.*;
import dev.ikara.admintools.listeners.ClickListener;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public class AdminTools extends JavaPlugin {

    private FakeOreCommand  fakeOreCommand;
    private FakeBaseCommand fakeBaseCommand;

    @Override
    public void onEnable() {
        // Shared instance for CPSCommand
        CPSCommand cpsCommand = new CPSCommand(this);

        // Register commands
        getCommand("velocitytest").setExecutor(new VelocityCommand(this));
        getCommand("nofalltest").setExecutor(new NoFallCommand(this));
        getCommand("killauratest").setExecutor(new KillAuraCommand(this));

        // keep a reference so we can clean up on disable
        fakeOreCommand = new FakeOreCommand(this);
        getCommand("fakeore").setExecutor(fakeOreCommand);

        fakeBaseCommand = new FakeBaseCommand(this);
        getCommand("fakebase").setExecutor(fakeBaseCommand);

        getCommand("cpstest").setExecutor(cpsCommand);

        // Register listener for click tracking
        getServer().getPluginManager().registerEvents(new ClickListener(cpsCommand), this);
    }

    @Override
    public void onDisable() {
        // Unregister all event handlers belonging to this plugin
        HandlerList.unregisterAll(this);

        // Cancel any pending fake‑ore or fake‑base tests so no ghost blocks linger
        if (fakeOreCommand != null) {
            fakeOreCommand.cancelAllTests();
        }
        if (fakeBaseCommand != null) {
            fakeBaseCommand.cancelAllTests();
        }
    }
}
