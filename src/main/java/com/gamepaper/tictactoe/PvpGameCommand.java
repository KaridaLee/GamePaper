package com.gamepaper.tictactoe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PvpGameCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("join", "start", "leave", "status", "help");

    private final PvpGameManager manager;

    PvpGameCommand(PvpGameManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equals("帮助")) {
            sendHelp(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "这个指令只能由玩家使用。" );
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "join", "加入" -> manager.join(player);
            case "start", "开始" -> manager.start(player);
            case "leave", "退出" -> manager.leave(player);
            case "status", "状态" -> {
                manager.sendStatus(player);
                yield true;
            }
            default -> {
                sendHelp(player);
                yield true;
            }
        };
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Messages.prefixed(Component.text("PVP 占点游戏", NamedTextColor.AQUA)));
        Messages.send(sender, "/game pvp join    - 加入占点游戏等待区（背包必须为空）" );
        Messages.send(sender, "/game pvp start   - 开始游戏" );
        Messages.send(sender, "/game pvp leave   - 离开等待区或当前游戏" );
        Messages.send(sender, "/game pvp status  - 查看占点游戏状态" );
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return partialMatches(args[0], SUBCOMMANDS);
        }
        return List.of();
    }

    private static List<String> partialMatches(String input, List<String> candidates) {
        String lowerInput = input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.startsWith(lowerInput)) {
                matches.add(candidate);
            }
        }
        return matches;
    }
}
