package dev.ikara.admintools.util;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Centralized message handling with:
 *  - '&'→'§' translation
 *  - String.format placeholders
 *  - hover/click support
 *  - pagination
 *  - fluent builder
 */
public final class MessageHandler {
    private static FileConfiguration messages;
    private static final String FILE = "messages.yml";

    private MessageHandler() {}

    /** Load or reload messages.yml */
    public static void init(JavaPlugin plugin) {
        File f = new File(plugin.getDataFolder(), FILE);
        if (!f.exists()) plugin.saveResource(FILE, false);
        messages = YamlConfiguration.loadConfiguration(f);
    }

    /** Fetch &-translated template from messages.yml */
    public static String get(String key, String def) {
        String raw = messages.getString(key, def);
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    /** Internal: send components to player or console */
    private static void sendRaw(CommandSender to, BaseComponent... comps) {
        if (to instanceof Player) {
            ((Player) to).spigot().sendMessage(comps);
        } else {
            StringBuilder sb = new StringBuilder();
            for (BaseComponent c : comps) sb.append(c.toLegacyText());
            to.sendMessage(sb.toString());
        }
    }

    /** Convert a legacy-formatted string into components */
    private static BaseComponent[] translate(String raw) {
        return TextComponent.fromLegacyText(raw);
    }

    /** Simple send (no placeholders) */
    public static void sendInfo(CommandSender to, String raw) {
        sendRaw(to, translate(ChatColor.translateAlternateColorCodes('&', raw)));
    }
    public static void sendError(CommandSender to, String raw) {
        sendRaw(to, translate(ChatColor.translateAlternateColorCodes('&', raw)));
    }
    public static void sendDebug(CommandSender to, String raw) {
        sendRaw(to, translate(ChatColor.translateAlternateColorCodes('&', raw)));
    }

    /** Formatted send with placeholders */
    public static void sendInfoFmt(CommandSender to, String key, Object... args) {
        String tmpl = get(key, "%s");
        String msg  = String.format(tmpl, args);
        sendRaw(to, translate(msg));
    }
    public static void sendErrorFmt(CommandSender to, String key, Object... args) {
        String tmpl = get(key, "%s");
        String msg  = String.format(tmpl, args);
        sendRaw(to, translate(msg));
    }
    public static void sendDebugFmt(CommandSender to, String key, Object... args) {
        String tmpl = get(key, "%s");
        String msg  = String.format(tmpl, args);
        sendRaw(to, translate(msg));
    }

    /** Hover-only legacy text (no placeholders) */
    public static void sendLegacyHover(CommandSender to, String legacy, String hover) {
        String msg = ChatColor.translateAlternateColorCodes('&', legacy);
        String hv  = ChatColor.translateAlternateColorCodes('&', hover);
        BaseComponent[] comps = TextComponent.fromLegacyText(msg);
        for (BaseComponent c : comps) {
            if (c instanceof TextComponent) {
                ((TextComponent) c).setHoverEvent(
                    new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(hv).create())
                );
            }
        }
        sendRaw(to, comps);
    }

    /** Clickable command with hover */
    public static void sendHoverClick(CommandSender to, String text, String hover, String cmd) {
        String msg = ChatColor.translateAlternateColorCodes('&', text);
        String hv  = ChatColor.translateAlternateColorCodes('&', hover);
        BaseComponent[] comps = TextComponent.fromLegacyText(msg);
        for (BaseComponent c : comps) {
            if (c instanceof TextComponent) {
                TextComponent t = (TextComponent) c;
                t.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(hv).create()));
                t.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
            }
        }
        sendRaw(to, comps);
    }

    /** Clickable URL */
    public static void sendClickUrl(CommandSender to, String text, String url) {
        String msg = ChatColor.translateAlternateColorCodes('&', text);
        BaseComponent[] comps = TextComponent.fromLegacyText(msg);
        for (BaseComponent c : comps) {
            if (c instanceof TextComponent) {
                ((TextComponent) c).setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            }
        }
        sendRaw(to, comps);
    }

    /** Paginated list */
    public static void sendPaginatedList(CommandSender to,
                                         List<String> items,
                                         int page,
                                         int size,
                                         int totalPages,
                                         String baseCmd) {
        sendInfoFmt(to, "pagination-header", page, totalPages);
        int start = (page - 1) * size;
        for (int i = start; i < Math.min(start + size, items.size()); i++) {
            sendDebugFmt(to, "pagination-item", items.get(i));
        }
        List<BaseComponent> nav = new ArrayList<>();
        if (page > 1) {
            TextComponent prev = new TextComponent(get("pagination-prev", "[Previous]"));
            prev.setColor(net.md_5.bungee.api.ChatColor.RED);
            prev.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, baseCmd + " " + (page-1)));
            nav.add(prev);
        }
        if (page < totalPages) {
            TextComponent next = new TextComponent(get("pagination-next", "[Next]"));
            next.setColor(net.md_5.bungee.api.ChatColor.GREEN);
            next.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, baseCmd + " " + (page+1)));
            nav.add(next);
        }
        if (!nav.isEmpty()) sendRaw(to, nav.toArray(new BaseComponent[0]));
    }

    /** Fluent builder */
    public static class MessageBuilder {
        private final List<BaseComponent> parts = new ArrayList<>();

        public MessageBuilder append(String text) {
            String msg = ChatColor.translateAlternateColorCodes('&', text);
            for (BaseComponent c : TextComponent.fromLegacyText(msg)) parts.add(c);
            return this;
        }

        public MessageBuilder appendFmt(String key, Object... args) {
            String tmpl = get(key, "%s");
            String msg  = String.format(tmpl, args);
            for (BaseComponent c : TextComponent.fromLegacyText(msg)) parts.add(c);
            return this;
        }

        public MessageBuilder color(net.md_5.bungee.api.ChatColor color) {
            if (!parts.isEmpty() && parts.get(parts.size()-1) instanceof TextComponent) {
                ((TextComponent)parts.get(parts.size()-1)).setColor(color);
            }
            return this;
        }

        public MessageBuilder hover(String hover) {
            if (!parts.isEmpty() && parts.get(parts.size()-1) instanceof TextComponent) {
                String hv = ChatColor.translateAlternateColorCodes('&', hover);
                ((TextComponent)parts.get(parts.size()-1))
                    .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(hv).create()));
            }
            return this;
        }

        public MessageBuilder clickCommand(String cmd) {
            if (!parts.isEmpty() && parts.get(parts.size()-1) instanceof TextComponent) {
                ((TextComponent)parts.get(parts.size()-1))
                    .setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
            }
            return this;
        }

        public MessageBuilder clickUrl(String url) {
            if (!parts.isEmpty() && parts.get(parts.size()-1) instanceof TextComponent) {
                ((TextComponent)parts.get(parts.size()-1))
                    .setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            }
            return this;
        }

        public void send(CommandSender to) {
            sendRaw(to, parts.toArray(new BaseComponent[0]));
        }
    }
}