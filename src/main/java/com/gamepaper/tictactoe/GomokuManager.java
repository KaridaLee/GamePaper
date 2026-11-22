package com.gamepaper.tictactoe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class GomokuManager {

    private final JavaPlugin plugin;
    private final TicTacToeManager ticTacToeManager;
    private int inviteTimeoutSeconds;
    private int endDisplaySeconds;
    private final Map<UUID, GomokuGame> gamesByPlayer = new HashMap<>();
    private final Map<UUID, PendingGomokuInvitation> invitationsByTarget = new HashMap<>();

    GomokuManager(JavaPlugin plugin, TicTacToeManager ticTacToeManager,
                  int inviteTimeoutSeconds, int endDisplaySeconds) {
        this.plugin = plugin;
        this.ticTacToeManager = ticTacToeManager;
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

    GomokuGame gameFor(UUID playerId) {
        return gamesByPlayer.get(playerId);
    }

    GomokuGame spectatorGameFor(UUID playerId) {
        Set<GomokuGame> games = new HashSet<>(gamesByPlayer.values());
        for (GomokuGame game : games) {
            if (game.isSpectator(playerId)) {
                return game;
            }
        }
        return null;
    }

    boolean isBusy(UUID playerId) {
        return gamesByPlayer.containsKey(playerId) || ticTacToeManager.gameFor(playerId) != null;
    }

    PendingGomokuInvitation invitationFor(UUID targetId) {
        return invitationsByTarget.get(targetId);
    }

    boolean addInvitation(PendingGomokuInvitation invitation) {
        if (invitationsByTarget.containsKey(invitation.targetId())) {
            return false;
        }

        invitationsByTarget.put(invitation.targetId(), invitation);
        long delay = Math.max(1L, inviteTimeoutSeconds * 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> expireInvitation(invitation), delay);
        return true;
    }

    PendingGomokuInvitation removeInvitationForTarget(UUID targetId) {
        return invitationsByTarget.remove(targetId);
    }

    List<PendingGomokuInvitation> cancelInvitationsFrom(UUID inviterId) {
        List<PendingGomokuInvitation> cancelled = new ArrayList<>();
        invitationsByTarget.values().removeIf(invitation -> {
            if (invitation.inviterId().equals(inviterId)) {
                cancelled.add(invitation);
                return true;
            }
            return false;
        });
        return cancelled;
    }

    private void expireInvitation(PendingGomokuInvitation invitation) {
        if (!invitation.equals(invitationsByTarget.get(invitation.targetId()))) {
            return;
        }

        invitationsByTarget.remove(invitation.targetId());
        Player target = Bukkit.getPlayer(invitation.targetId());
        Player inviter = Bukkit.getPlayer(invitation.inviterId());
        if (target != null) {
            Messages.send(target, "来自 " + nameOf(inviter, "对方") + " 的五子棋邀请已过期。");
        }
        if (inviter != null) {
            Messages.send(inviter, "你发给 " + nameOf(target, "对方") + " 的五子棋邀请已过期。");
        }
    }

    GomokuGame createGame(Player blackPlayer, Player whitePlayer) {
        UUID blackId = blackPlayer.getUniqueId();
        UUID whiteId = whitePlayer.getUniqueId();
        if (isBusy(blackId) || isBusy(whiteId)) {
            return null;
        }

        GomokuGame game = new GomokuGame(this, blackId, whiteId);
        gamesByPlayer.put(blackId, game);
        gamesByPlayer.put(whiteId, game);
        return game;
    }

    void removeGame(GomokuGame game) {
        gamesByPlayer.remove(game.blackPlayerId(), game);
        gamesByPlayer.remove(game.whitePlayerId(), game);
    }

    void handleQuit(Player player) {
        UUID playerId = player.getUniqueId();

        PendingGomokuInvitation received = removeInvitationForTarget(playerId);
        if (received != null) {
            Player inviter = Bukkit.getPlayer(received.inviterId());
            if (inviter != null) {
                Messages.send(inviter, player.getName() + " 已离线，五子棋邀请已取消。");
            }
        }

        List<PendingGomokuInvitation> sent = cancelInvitationsFrom(playerId);
        for (PendingGomokuInvitation invitation : sent) {
            Player target = Bukkit.getPlayer(invitation.targetId());
            if (target != null) {
                Messages.send(target, player.getName() + " 已离线，五子棋邀请已取消。");
            }
        }

        GomokuGame game = gameFor(playerId);
        if (game != null) {
            game.forfeit(playerId, player.getName());
            return;
        }

        GomokuGame spectatorGame = spectatorGameFor(playerId);
        if (spectatorGame != null) {
            spectatorGame.onSpectatorClosed(playerId);
        }
    }

    void shutdown() {
        Set<GomokuGame> games = new HashSet<>(gamesByPlayer.values());
        gamesByPlayer.clear();
        invitationsByTarget.clear();
        for (GomokuGame game : games) {
            game.shutdown();
        }
    }

    void sendInvitation(Player inviter, Player target) {
        Component accept = Component.text("[接受]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/game gomuku accept " + inviter.getName()));
        Component deny = Component.text("[拒绝]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/game gomuku deny " + inviter.getName()));

        Component message = Messages.prefixed(Component.text(inviter.getName() + " 邀请你进行五子棋！", NamedTextColor.YELLOW)
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
