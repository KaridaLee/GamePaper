package com.gamepaper.tictactoe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

final class Messages {

    private static final Component PREFIX = Component.text("[", NamedTextColor.DARK_GRAY)
            .append(Component.text("休闲小游戏", NamedTextColor.AQUA))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY));

    private Messages() {
    }

    static Component prefixed(String text) {
        return prefixed(Component.text(text, NamedTextColor.WHITE));
    }

    static Component prefixed(Component text) {
        return PREFIX.append(text);
    }

    static void send(CommandSender sender, String text) {
        sender.sendMessage(prefixed(text));
    }

    static void send(CommandSender sender, Component text) {
        sender.sendMessage(prefixed(text));
    }
}
