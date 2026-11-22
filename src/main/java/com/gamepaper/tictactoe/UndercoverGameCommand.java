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

final class UndercoverGameCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("join", "start", "leave", "status", "debug", "help");

    private final UndercoverGameManager manager;

    UndercoverGameCommand(UndercoverGameManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")
                || args[0].equals("帮助")) {
            sendHelp(sender);
            return true;
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("debug") || args[0].equals("调试")) {
            int amount = 5;
            if (args.length >= 2) {
                try {
                    amount = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {
                    Messages.send(sender, "数量必须是 1 至 20 的整数。");
                    return true;
                }
            }
            if (amount < 1 || amount > 20) {
                Messages.send(sender, "数量必须在 1 至 20 之间。");
                return true;
            }
            manager.debugGenerateWords(sender, amount);
            return true;
        }
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "这个命令只能由玩家使用。");
            return true;
        }

        return switch (subcommand) {
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
        sender.sendMessage(Messages.prefixed(
                Component.text("谁是卧底", NamedTextColor.GOLD)));
        Messages.send(sender, "/game undercover join   - 加入等待区（背包与装备必须为空）");
        Messages.send(sender, "/game undercover start  - 至少 3 人时开始游戏");
        Messages.send(sender, "/game undercover leave  - 离开等待区或当前游戏");
        Messages.send(sender, "/game undercover status - 查看等待区或游戏状态");
        if (sender.hasPermission("gamepaper.undercover.debug")) {
            Messages.send(sender, "/game undercover debug [数量] - 测试生成 1–20 组 AI 词语");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")
                && sender.hasPermission("gamepaper.undercover.debug")) {
            return List.of("1", "5", "10", "20").stream()
                    .filter(value -> value.startsWith(args[1])).toList();
        }
        if (args.length != 1) {
            return List.of();
        }
        String input = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String subcommand : SUBCOMMANDS) {
            if (subcommand.equals("debug")
                    && !sender.hasPermission("gamepaper.undercover.debug")) {
                continue;
            }
            if (subcommand.startsWith(input)) {
                matches.add(subcommand);
            }
        }
        return matches;
    }
}
