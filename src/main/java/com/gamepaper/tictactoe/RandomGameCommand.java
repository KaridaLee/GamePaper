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

final class RandomGameCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("join", "start", "leave", "status", "help");

    private final RandomGameManager manager;

    RandomGameCommand(RandomGameManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equals("帮助")) {
            sendHelp(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "这个命令只能由玩家使用。");
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
        sender.sendMessage(Messages.prefixed(Component.text("随机物品柱子生存", NamedTextColor.AQUA)));
        Messages.send(sender, "/game random join   - 加入等待区（背包和装备必须为空）");
        Messages.send(sender, "/game random start  - 开始游戏，满员时会自动开始");
        Messages.send(sender, "/game random leave  - 离开等待区或退出当前游戏");
        Messages.send(sender, "/game random status - 查看等待区或游戏状态");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String input = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String subcommand : SUBCOMMANDS) {
            if (subcommand.startsWith(input)) {
                matches.add(subcommand);
            }
        }
        return matches;
    }
}
