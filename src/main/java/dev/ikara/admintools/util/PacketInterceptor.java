package dev.ikara.admintools.util;

import io.netty.channel.ChannelDuplexHandler;
import dev.ikara.admintools.util.CPSMetrics;
import io.netty.channel.ChannelPipeline;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.NetworkManager;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

public class PacketInterceptor {
    public static void inject(Player player, ChannelDuplexHandler handler, String name) {
        EntityPlayer ep = ((CraftPlayer) player).getHandle();
        NetworkManager nm = ep.playerConnection.networkManager;
        ChannelPipeline pipeline = nm.channel.pipeline();
        if (pipeline.get(name) != null) pipeline.remove(name);
        pipeline.addBefore("packet_handler", name, handler);
    }

    public static void remove(Player player, String name) {
        EntityPlayer ep = ((CraftPlayer) player).getHandle();
        NetworkManager nm = ep.playerConnection.networkManager;
        ChannelPipeline pipeline = nm.channel.pipeline();
        if (pipeline.get(name) != null) pipeline.remove(name);
    }
}