package com.gamepaper.tictactoe;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.Bukkit;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class GamePaperCommand implements CommandExecutor, TabCompleter {

    private static final List<String> GAME_MODES =
            List.of("ttt", "gomuku", "random", "tnt", "undercover", "pvp", "gui", "reload");

    private final JavaPlugin plugin;
    private final TicTacToeCommand ticTacToeCommand;
    private final GomokuCommand gomokuCommand;
    private final RandomGameCommand randomGameCommand;
    private final TntGameCommand tntGameCommand;
    private final UndercoverGameCommand undercoverGameCommand;
    private final PvpGameCommand pvpGameCommand;
    private final GameGui gameGui;

    GamePaperCommand(JavaPlugin plugin, TicTacToeCommand ticTacToeCommand, GomokuCommand gomokuCommand,
                     RandomGameCommand randomGameCommand, TntGameCommand tntGameCommand,
                     UndercoverGameCommand undercoverGameCommand, PvpGameCommand pvpGameCommand,
                     GameGui gameGui) {
        this.plugin = plugin;
        this.ticTacToeCommand = ticTacToeCommand;
        this.gomokuCommand = gomokuCommand;
        this.randomGameCommand = randomGameCommand;
        this.tntGameCommand = tntGameCommand;
        this.undercoverGameCommand = undercoverGameCommand;
        this.pvpGameCommand = pvpGameCommand;
        this.gameGui = gameGui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equals("帮助")) {
            sendHelp(sender);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (plugin instanceof GamePaperPlugin gamePaperPlugin) {
                gamePaperPlugin.reloadGameConfig(sender);
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("gui")) {
            if (sender instanceof Player player) {
                gameGui.openWhenReady(player);
            } else {
                Messages.send(sender, "这个命令只能由玩家使用。" );
            }
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("gui")) {
            if (!sender.hasPermission("gamepaper.gui.others")) {
                Messages.send(sender, "你没有权限为其他玩家打开小游戏匹配 UI。" );
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null || !target.isOnline()) {
                Messages.send(sender, "找不到在线玩家：" + args[1]);
                return true;
            }
            gameGui.openWhenReady(target);
            Messages.send(sender, "已为 " + target.getName() + " 打开小游戏匹配 UI。" );
            return true;
        }

        String mode = args[0].toLowerCase(Locale.ROOT);
        String[] subcommandArgs = Arrays.copyOfRange(args, 1, args.length);
        if (mode.equals("ttt")) {
            return ticTacToeCommand.onCommand(sender, command, label, subcommandArgs);
        }
        if (mode.equals("gomuku") || mode.equals("gomoku")) {
            return gomokuCommand.onCommand(sender, command, label, subcommandArgs);
        }
        if (mode.equals("random") || mode.equals("pillar")) {
            return randomGameCommand.onCommand(sender, command, label, subcommandArgs);
        }
        if (mode.equals("tnt") || mode.equals("hotpotato") || mode.equals("hot-potato")) {
            return tntGameCommand.onCommand(sender, command, label, subcommandArgs);
        }
        if (mode.equals("undercover") || mode.equals("who")) {
            return undercoverGameCommand.onCommand(sender, command, label, subcommandArgs);
        }
        if (mode.equals("pvp") || mode.equals("capture")) {
            return pvpGameCommand.onCommand(sender, command, label, subcommandArgs);
        }

        sendHelp(sender);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        Messages.send(sender, "/game ttt <invite|accept|deny|board|leave|cancel|help>");
        Messages.send(sender, "/game gomuku <invite|accept|deny|review|board|leave|cancel|help>");
        Messages.send(sender, "/game random <join|start|leave|status|help>");
        Messages.send(sender, "/game tnt <join|start|leave|status|help>");
        Messages.send(sender, "/game undercover <join|start|leave|status|help>");
        Messages.send(sender, "/game pvp <join|start|leave|status|help>");
        if (sender.hasPermission("gamepaper.undercover.debug")) {
            Messages.send(sender, "/game undercover debug [数量]");
        }
        Messages.send(sender, "/game gui");
        Messages.send(sender, "/game reload");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return partialMatches(args[0], GAME_MODES);
        }
        if (args.length > 1) {
            String mode = args[0].toLowerCase(Locale.ROOT);
            String[] subcommandArgs = Arrays.copyOfRange(args, 1, args.length);
            if (mode.equals("ttt")) {
                return ticTacToeCommand.onTabComplete(sender, command, alias, subcommandArgs);
            }
            if (mode.equals("gomuku") || mode.equals("gomoku")) {
                return gomokuCommand.onTabComplete(sender, command, alias, subcommandArgs);
            }
            if (mode.equals("random") || mode.equals("pillar")) {
                return randomGameCommand.onTabComplete(sender, command, alias, subcommandArgs);
            }
            if (mode.equals("tnt") || mode.equals("hotpotato") || mode.equals("hot-potato")) {
                return tntGameCommand.onTabComplete(sender, command, alias, subcommandArgs);
            }
            if (mode.equals("undercover") || mode.equals("who")) {
                return undercoverGameCommand.onTabComplete(sender, command, alias, subcommandArgs);
            }
            if (mode.equals("pvp") || mode.equals("capture")) {
                return pvpGameCommand.onTabComplete(sender, command, alias, subcommandArgs);
            }
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
