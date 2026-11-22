package com.gamepaper.tictactoe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class TicTacToeGame implements InventoryHolder {

    private static final int[][] WINNING_LINES = {
            {0, 1, 2},
            {3, 4, 5},
            {6, 7, 8},
            {0, 3, 6},
            {1, 4, 7},
            {2, 5, 8},
            {0, 4, 8},
            {2, 4, 6}
    };

    private final TicTacToeManager manager;
    private final UUID redPlayerId;
    private final UUID bluePlayerId;
    private final Inventory inventory;
    private final Material[] board = new Material[9];
    private UUID currentTurn;
    private boolean finished;

    TicTacToeGame(TicTacToeManager manager, UUID redPlayerId, UUID bluePlayerId) {
        this.manager = manager;
        this.redPlayerId = redPlayerId;
        this.bluePlayerId = bluePlayerId;
        this.currentTurn = redPlayerId;
        this.inventory = manager.plugin().getServer().createInventory(
                this,
                InventoryType.DISPENSER,
                Component.text("井字棋 | 红蓝对弈", NamedTextColor.DARK_AQUA)
        );
        refreshBoard();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    UUID redPlayerId() {
        return redPlayerId;
    }

    UUID bluePlayerId() {
        return bluePlayerId;
    }

    boolean isFinished() {
        return finished;
    }

    boolean isParticipant(UUID playerId) {
        return redPlayerId.equals(playerId) || bluePlayerId.equals(playerId);
    }

    void start() {
        Player red = player(redPlayerId);
        Player blue = player(bluePlayerId);
        if (red == null || blue == null) {
            manager.removeGame(this);
            shutdown();
            return;
        }

        red.sendMessage(Messages.prefixed(Component.text("你是", NamedTextColor.GRAY)
                .append(Component.text("红方", NamedTextColor.RED))
                .append(Component.text("，使用红色混凝土。", NamedTextColor.GRAY))));
        blue.sendMessage(Messages.prefixed(Component.text("你是", NamedTextColor.GRAY)
                .append(Component.text("蓝方", NamedTextColor.BLUE))
                .append(Component.text("，使用蓝色混凝土。", NamedTextColor.GRAY))));
        sendBoth("对局开始！点击投掷器中的空位落子，红方先手。");

        red.openInventory(inventory);
        blue.openInventory(inventory);
        announceTurn();
    }

    boolean openFor(Player player) {
        if (finished || !isParticipant(player.getUniqueId())) {
            return false;
        }
        player.openInventory(inventory);
        return true;
    }

    void play(Player player, int slot) {
        if (finished) {
            Messages.send(player, "这局井字棋已经结束。");
            return;
        }
        if (!isParticipant(player.getUniqueId())) {
            return;
        }
        if (!Objects.equals(currentTurn, player.getUniqueId())) {
            Messages.send(player, "现在轮到 " + nameOf(currentTurn) + " 落子。");
            return;
        }
        if (slot < 0 || slot >= board.length) {
            return;
        }
        if (board[slot] != null) {
            Messages.send(player, "这个位置已经有棋子了，请选择其他空位。");
            return;
        }

        Material piece = pieceFor(player.getUniqueId());
        board[slot] = piece;
        inventory.setItem(slot, pieceItem(piece));
        sendBoth(nameOf(player.getUniqueId()) + " 落子了。");

        if (hasWinner(piece)) {
            finishWithWinner(player);
            return;
        }
        if (isBoardFull()) {
            finishWithDraw();
            return;
        }

        currentTurn = otherPlayer(player.getUniqueId());
        announceTurn();
    }

    void forfeit(UUID quitterId) {
        if (finished || !isParticipant(quitterId)) {
            return;
        }

        finished = true;
        Player winner = player(otherPlayer(quitterId));
        manager.removeGame(this);
        if (winner != null) {
            Messages.send(winner, nameOf(quitterId) + " 已退出对局，你获胜了！");
        }
        closeLater();
    }

    void shutdown() {
        if (!finished) {
            finished = true;
        }
        closeInventories();
    }

    private void finishWithWinner(Player winner) {
        finished = true;
        manager.removeGame(this);
        Component message = Component.text("恭喜！", NamedTextColor.YELLOW)
                .append(Component.text(winner.getName(), colorFor(winner.getUniqueId())))
                .append(Component.text(" 获得胜利！", NamedTextColor.YELLOW));
        sendBoth(message);
        closeLater();
    }

    private void finishWithDraw() {
        finished = true;
        manager.removeGame(this);
        sendBoth(Component.text("棋盘已满，这局是平局！", NamedTextColor.YELLOW));
        closeLater();
    }

    private void announceTurn() {
        Player current = player(currentTurn);
        if (current == null) {
            return;
        }
        Player other = player(otherPlayer(currentTurn));
        Messages.send(current, Component.text("轮到你落子。", NamedTextColor.GREEN)
                .append(Component.text("请点击空位放置", NamedTextColor.GRAY))
                .append(Component.text(pieceName(pieceFor(currentTurn)), colorFor(currentTurn)))
                .append(Component.text("。", NamedTextColor.GRAY)));
        if (other != null) {
            Messages.send(other, Component.text("现在轮到 ", NamedTextColor.GRAY)
                    .append(Component.text(current.getName(), colorFor(currentTurn)))
                    .append(Component.text(" 落子。", NamedTextColor.GRAY)));
        }
    }

    private void sendBoth(String text) {
        sendBoth(Component.text(text, NamedTextColor.GRAY));
    }

    private void sendBoth(Component text) {
        Player red = player(redPlayerId);
        Player blue = player(bluePlayerId);
        if (red != null) {
            red.sendMessage(Messages.prefixed(text));
        }
        if (blue != null && blue != red) {
            blue.sendMessage(Messages.prefixed(text));
        }
    }

    private void closeLater() {
        long delay = Math.max(1L, manager.endDisplaySeconds() * 20L);
        Bukkit.getScheduler().runTaskLater(manager.plugin(), this::closeInventories, delay);
    }

    private void closeInventories() {
        Player red = player(redPlayerId);
        Player blue = player(bluePlayerId);
        closeIfViewing(red);
        if (blue != red) {
            closeIfViewing(blue);
        }
    }

    private void closeIfViewing(Player player) {
        if (player != null && player.getOpenInventory().getTopInventory() == inventory) {
            player.closeInventory();
        }
    }

    private void refreshBoard() {
        for (int slot = 0; slot < board.length; slot++) {
            inventory.setItem(slot, board[slot] == null ? emptySlotItem() : pieceItem(board[slot]));
        }
    }

    private static ItemStack emptySlotItem() {
        return namedItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                Component.text("空位 · 点击落子", NamedTextColor.GRAY),
                List.of(Component.text("这是一个共享的棋盘格。", NamedTextColor.DARK_GRAY)));
    }

    private static ItemStack pieceItem(Material piece) {
        boolean red = piece == Material.RED_CONCRETE;
        NamedTextColor color = red ? NamedTextColor.RED : NamedTextColor.BLUE;
        String name = red ? "红色混凝土" : "蓝色混凝土";
        return namedItem(piece, Component.text(name, color),
                List.of(Component.text("已占用", NamedTextColor.DARK_GRAY)));
    }

    private static ItemStack namedItem(Material material, Component displayName, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(displayName);
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private Material pieceFor(UUID playerId) {
        return redPlayerId.equals(playerId) ? Material.RED_CONCRETE : Material.BLUE_CONCRETE;
    }

    private UUID otherPlayer(UUID playerId) {
        return redPlayerId.equals(playerId) ? bluePlayerId : redPlayerId;
    }

    private boolean hasWinner(Material piece) {
        for (int[] line : WINNING_LINES) {
            if (board[line[0]] == piece && board[line[1]] == piece && board[line[2]] == piece) {
                return true;
            }
        }
        return false;
    }

    private boolean isBoardFull() {
        for (Material slot : board) {
            if (slot == null) {
                return false;
            }
        }
        return true;
    }

    private Player player(UUID playerId) {
        return Bukkit.getPlayer(playerId);
    }

    private String nameOf(UUID playerId) {
        Player player = player(playerId);
        return player == null ? "对方" : player.getName();
    }

    private static String pieceName(Material piece) {
        return piece == Material.RED_CONCRETE ? "红色混凝土" : "蓝色混凝土";
    }

    private NamedTextColor colorFor(UUID playerId) {
        return redPlayerId.equals(playerId) ? NamedTextColor.RED : NamedTextColor.BLUE;
    }
}
