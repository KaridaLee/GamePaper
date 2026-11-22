package com.gamepaper.tictactoe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TicTacToeCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "invite", "accept", "deny", "board", "leave", "cancel", "help"
    );

    private final TicTacToeManager manager;
    private final GomokuManager gomokuManager;

    TicTacToeCommand(TicTacToeManager manager, GomokuManager gomokuManager) {
        this.manager = manager;
        this.gomokuManager = gomokuManager;
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

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "invite", "邀请" -> invite(player, args);
            case "accept", "接受" -> accept(player, args);
            case "deny", "拒绝" -> deny(player, args);
            case "board", "open", "棋盘", "打开" -> openBoard(player);
            case "leave", "退出" -> leave(player);
            case "cancel", "取消" -> cancel(player);
            default -> {
                Messages.send(player, "未知子命令。使用 /ttt help 查看帮助。");
                yield true;
            }
        };
    }

    private boolean invite(Player inviter, String[] args) {
        if (args.length < 2) {
            Messages.send(inviter, "用法：/game ttt invite <玩家>");
            return true;
        }
        if (gomokuManager.isBusy(inviter.getUniqueId())) {
            Messages.send(inviter, "你正在进行一局井字棋，不能再次发起邀请。");
            return true;
        }

        Player target = findOnlinePlayer(args[1]);
        if (target == null) {
            Messages.send(inviter, "找不到在线玩家：" + args[1]);
            return true;
        }
        if (target.equals(inviter)) {
            Messages.send(inviter, "不能邀请自己进行井字棋。");
            return true;
        }
        if (gomokuManager.isBusy(target.getUniqueId())) {
            Messages.send(inviter, target.getName() + " 正在进行另一局井字棋。");
            return true;
        }
        if (manager.invitationFor(target.getUniqueId()) != null) {
            Messages.send(inviter, target.getName() + " 已经有一条待处理的井字棋邀请。");
            return true;
        }

        PendingInvitation invitation = new PendingInvitation(inviter.getUniqueId(), target.getUniqueId());
        if (!manager.addInvitation(invitation)) {
            Messages.send(inviter, "暂时无法向该玩家发送邀请。");
            return true;
        }

        manager.sendInvitation(inviter, target);
        Messages.send(inviter, "已向 " + target.getName() + " 发送邀请，有效期 "
                + manager.inviteTimeoutSeconds() + " 秒。");
        return true;
    }

    private boolean accept(Player target, String[] args) {
        PendingInvitation invitation = manager.invitationFor(target.getUniqueId());
        if (invitation == null) {
            Messages.send(target, "你没有待处理的井字棋邀请。");
            return true;
        }

        Player inviter = Bukkit.getPlayer(invitation.inviterId());
        if (args.length >= 2 && (inviter == null || !inviter.getName().equalsIgnoreCase(args[1]))) {
            Messages.send(target, "这不是来自该玩家的待处理邀请。");
            return true;
        }
        if (inviter == null) {
            manager.removeInvitationForTarget(target.getUniqueId());
            Messages.send(target, "邀请方已经离线，这条邀请已取消。");
            return true;
        }
        if (gomokuManager.isBusy(target.getUniqueId()) || gomokuManager.isBusy(inviter.getUniqueId())) {
            manager.removeInvitationForTarget(target.getUniqueId());
            Messages.send(target, "其中一位玩家已经在另一局井字棋中。");
            return true;
        }

        manager.removeInvitationForTarget(target.getUniqueId());
        TicTacToeGame game = manager.createGame(inviter, target);
        if (game == null) {
            Messages.send(target, "无法创建对局，请稍后再试。");
            return true;
        }

        game.start();
        return true;
    }

    private boolean deny(Player target, String[] args) {
        PendingInvitation invitation = manager.invitationFor(target.getUniqueId());
        if (invitation == null) {
            Messages.send(target, "你没有待处理的井字棋邀请。");
            return true;
        }

        Player inviter = Bukkit.getPlayer(invitation.inviterId());
        if (args.length >= 2 && (inviter == null || !inviter.getName().equalsIgnoreCase(args[1]))) {
            Messages.send(target, "这不是来自该玩家的待处理邀请。");
            return true;
        }

        manager.removeInvitationForTarget(target.getUniqueId());
        Messages.send(target, "你拒绝了井字棋邀请。");
        if (inviter != null) {
            Messages.send(inviter, target.getName() + " 拒绝了你的井字棋邀请。");
        }
        return true;
    }

    private boolean openBoard(Player player) {
        TicTacToeGame game = manager.gameFor(player.getUniqueId());
        if (game == null) {
            Messages.send(player, "你当前没有进行中的井字棋。先使用 /game ttt invite <玩家> 发起邀请。");
            return true;
        }
        game.openFor(player);
        return true;
    }

    private boolean leave(Player player) {
        TicTacToeGame game = manager.gameFor(player.getUniqueId());
        if (game == null) {
            Messages.send(player, "你当前没有进行中的井字棋。");
            return true;
        }
        game.forfeit(player.getUniqueId());
        return true;
    }

    private boolean cancel(Player player) {
        List<PendingInvitation> cancelled = manager.cancelInvitationsFrom(player.getUniqueId());
        if (cancelled.isEmpty()) {
            Messages.send(player, "你没有正在发送的井字棋邀请。");
            return true;
        }

        for (PendingInvitation invitation : cancelled) {
            Player target = Bukkit.getPlayer(invitation.targetId());
            if (target != null) {
                Messages.send(target, player.getName() + " 取消了井字棋邀请。");
            }
        }
        Messages.send(player, "已取消 " + cancelled.size() + " 条井字棋邀请。");
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Messages.prefixed(Component.text("井字棋命令", NamedTextColor.AQUA)));
        Messages.send(sender, "/game ttt invite <玩家>  - 邀请玩家对弈");
        Messages.send(sender, "/game ttt accept [玩家] - 接受待处理邀请");
        Messages.send(sender, "/game ttt deny [玩家]   - 拒绝待处理邀请");
        Messages.send(sender, "/game ttt board          - 重新打开共享棋盘");
        Messages.send(sender, "/game ttt leave          - 退出当前对局并认输");
        Messages.send(sender, "/game ttt cancel         - 取消自己发出的邀请");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return partialMatches(args[0], SUBCOMMANDS);
        }
        if (args.length == 2 && sender instanceof Player player) {
            String subcommand = args[0].toLowerCase(Locale.ROOT);
            if (subcommand.equals("invite") || subcommand.equals("邀请")) {
                List<String> names = Bukkit.getOnlinePlayers().stream()
                        .filter(online -> !online.equals(player))
                        .map(Player::getName)
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
                return partialMatches(args[1], names);
            }
            if (subcommand.equals("accept") || subcommand.equals("接受")
                    || subcommand.equals("deny") || subcommand.equals("拒绝")) {
                PendingInvitation invitation = manager.invitationFor(player.getUniqueId());
                if (invitation != null) {
                    Player inviter = Bukkit.getPlayer(invitation.inviterId());
                    if (inviter != null && inviter.getName().toLowerCase(Locale.ROOT)
                            .startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        return List.of(inviter.getName());
                    }
                }
            }
        }
        return List.of();
    }

    private static List<String> partialMatches(String input, List<String> candidates) {
        String lowerInput = input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lowerInput)) {
                matches.add(candidate);
            }
        }
        return matches;
    }

    private static Player findOnlinePlayer(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) {
            return exact;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }
}
