package com.gamepaper.tictactoe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TicTacToeManager {

    private final JavaPlugin plugin;
    private int inviteTimeoutSeconds;
    private int endDisplaySeconds;
    private final Map<UUID, TicTacToeGame> gamesByPlayer = new HashMap<>();
    private final Map<UUID, PendingInvitation> invitationsByTarget = new HashMap<>();

    TicTacToeManager(JavaPlugin plugin, int inviteTimeoutSeconds, int endDisplaySeconds) {
        this.plugin = plugin;
        this.inviteTimeoutSeconds = inviteTimeoutSeconds;
        this.endDisplaySeconds = endDisplaySeconds;
    }

    JavaPlugin plugin() {
        return plugin;
    }

    int inviteTimeoutSeconds() {
        return inviteTimeoutSeconds;
    }

    int endDisplaySeconds() {
        return endDisplaySeconds;
    }

    void reloadConfig() {
        inviteTimeoutSeconds = Math.max(10,
                plugin.getConfig().getInt("invite-timeout-seconds", 60));
        endDisplaySeconds = Math.max(1,
                plugin.getConfig().getInt("end-display-seconds", 3));
    }

    TicTacToeGame gameFor(UUID playerId) {
        return gamesByPlayer.get(playerId);
    }

    PendingInvitation invitationFor(UUID targetId) {
        return invitationsByTarget.get(targetId);
    }

    boolean addInvitation(PendingInvitation invitation) {
        if (invitationsByTarget.containsKey(invitation.targetId())) {
            return false;
        }

        invitationsByTarget.put(invitation.targetId(), invitation);
        long delay = Math.max(1L, inviteTimeoutSeconds * 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> expireInvitation(invitation), delay);
        return true;
    }

    PendingInvitation removeInvitationForTarget(UUID targetId) {
        return invitationsByTarget.remove(targetId);
    }

    List<PendingInvitation> cancelInvitationsFrom(UUID inviterId) {
        List<PendingInvitation> cancelled = new ArrayList<>();
        invitationsByTarget.values().removeIf(invitation -> {
            if (invitation.inviterId().equals(inviterId)) {
                cancelled.add(invitation);
                return true;
            }
            return false;
        });
        return cancelled;
    }

    private void expireInvitation(PendingInvitation invitation) {
        if (!invitation.equals(invitationsByTarget.get(invitation.targetId()))) {
            return;
        }

        invitationsByTarget.remove(invitation.targetId());
        Player target = Bukkit.getPlayer(invitation.targetId());
        Player inviter = Bukkit.getPlayer(invitation.inviterId());
        if (target != null) {
            Messages.send(target, "来自 " + nameOf(inviter, "对方") + " 的井字棋邀请已过期。");
        }
        if (inviter != null) {
            Messages.send(inviter, "你发给 " + nameOf(target, "对方") + " 的井字棋邀请已过期。");
        }
    }

    TicTacToeGame createGame(Player redPlayer, Player bluePlayer) {
        UUID redId = redPlayer.getUniqueId();
        UUID blueId = bluePlayer.getUniqueId();
        if (gamesByPlayer.containsKey(redId) || gamesByPlayer.containsKey(blueId)) {
            return null;
        }

        TicTacToeGame game = new TicTacToeGame(this, redId, blueId);
        gamesByPlayer.put(redId, game);
        gamesByPlayer.put(blueId, game);
        return game;
    }

    void removeGame(TicTacToeGame game) {
        gamesByPlayer.remove(game.redPlayerId(), game);
        gamesByPlayer.remove(game.bluePlayerId(), game);
    }

    void handleQuit(Player player) {
        UUID playerId = player.getUniqueId();

        PendingInvitation received = removeInvitationForTarget(playerId);
        if (received != null) {
            Player inviter = Bukkit.getPlayer(received.inviterId());
            if (inviter != null) {
                Messages.send(inviter, player.getName() + " 已离线，井字棋邀请已取消。");
            }
        }

        List<PendingInvitation> sent = cancelInvitationsFrom(playerId);
        for (PendingInvitation invitation : sent) {
            Player target = Bukkit.getPlayer(invitation.targetId());
            if (target != null) {
                Messages.send(target, player.getName() + " 已离线，井字棋邀请已取消。");
            }
        }

        TicTacToeGame game = gameFor(playerId);
        if (game != null) {
            game.forfeit(playerId);
        }
    }

    void shutdown() {
        List<TicTacToeGame> games = new ArrayList<>(gamesByPlayer.values());
        gamesByPlayer.clear();
        invitationsByTarget.clear();
        for (TicTacToeGame game : games) {
            game.shutdown();
        }
    }

    void sendInvitation(Player inviter, Player target) {
        Component accept = Component.text("[接受]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/game ttt accept " + inviter.getName()));
        Component deny = Component.text("[拒绝]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/game ttt deny " + inviter.getName()));

        Component message = Messages.prefixed(Component.text(inviter.getName() + " 邀请你进行井字棋！", NamedTextColor.YELLOW)
                .append(Component.text(" ", NamedTextColor.WHITE))
                .append(accept)
                .append(Component.text(" ", NamedTextColor.WHITE))
                .append(deny));
        target.sendMessage(message);
    }

    private static String nameOf(Player player, String fallback) {
        return player == null ? fallback : player.getName();
    }
}
