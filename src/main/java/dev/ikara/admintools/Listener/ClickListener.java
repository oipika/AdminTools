package dev.ikara.admintools.listeners;

import dev.ikara.admintools.commands.CPSCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;

public class ClickListener implements Listener {

    private final CPSCommand cpsCommand;

    public ClickListener(CPSCommand cpsCommand) {
        this.cpsCommand = cpsCommand;
    }

    @EventHandler
    public void onPlayerClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            cpsCommand.recordClick(event.getPlayer());
        }
    }
}
