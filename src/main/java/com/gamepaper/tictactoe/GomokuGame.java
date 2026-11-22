package com.gamepaper.tictactoe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class GomokuGame implements InventoryHolder {

    private static final int BOARD_SIZE = 9;
    private static final int VISIBLE_ROWS = 5;
    private static final int INVENTORY_SIZE = 54;
    private static final int BOARD_START_SLOT = 9;
    private static final int PREVIOUS_SLOT = 0;
    private static final int NEXT_SLOT = 8;
    private static final int STATUS_SLOT = 4;
    private static final int MAX_SCROLL_OFFSET = BOARD_SIZE - VISIBLE_ROWS;

    private static final int[][] DIRECTIONS = {
            {1, 0},
            {0, 1},
            {1, 1},
            {1, -1}
    };

    private final GomokuManager manager;
    private final UUID blackPlayerId;
    private final UUID whitePlayerId;
    private final Inventory inventory;
    private final Material[][] board = new Material[BOARD_SIZE][BOARD_SIZE];
    private final Set<UUID> spectators = new HashSet<>();
    private UUID currentTurn;
    private int scrollOffset;
    private boolean finished;
    private String resultText;

    GomokuGame(GomokuManager manager, UUID blackPlayerId, UUID whitePlayerId) {
        this.manager = manager;
        this.blackPlayerId = blackPlayerId;
        this.whitePlayerId = whitePlayerId;
        this.currentTurn = blackPlayerId;
        this.inventory = manager.plugin().getServer().createInventory(
                this,
                INVENTORY_SIZE,
                Component.text("五子棋 | 9×9 棋盘", NamedTextColor.DARK_AQUA)
        );
        refreshView();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    UUID blackPlayerId() {
        return blackPlayerId;
    }

    UUID whitePlayerId() {
        return whitePlayerId;
    }

    boolean isFinished() {
        return finished;
    }

    boolean isParticipant(UUID playerId) {
        return blackPlayerId.equals(playerId) || whitePlayerId.equals(playerId);
    }

    boolean isSpectator(UUID playerId) {
        return spectators.contains(playerId);
    }

    boolean addSpectator(Player player) {
        if (finished || isParticipant(player.getUniqueId())) {
            return false;
        }
        GomokuGame existingGame = manager.spectatorGameFor(player.getUniqueId());
        if (existingGame != null && existingGame != this) {
            return false;
        }

        spectators.add(player.getUniqueId());
        player.openInventory(inventory);
        Messages.send(player, "你正在观战 " + nameOf(blackPlayerId) + " 与 "
                + nameOf(whitePlayerId) + " 的五子棋对局。点击棋盘不会落子。");
        return true;
    }

    void stopSpectating(Player player) {
        if (!spectators.remove(player.getUniqueId())) {
            return;
        }
        if (player.getOpenInventory().getTopInventory() == inventory) {
            player.closeInventory();
        }
        Messages.send(player, "你已离开五子棋观战。");
    }

    void onSpectatorClosed(UUID playerId) {
        spectators.remove(playerId);
    }

    void start() {
        Player black = player(blackPlayerId);
        Player white = player(whitePlayerId);
        if (black == null || white == null) {
            manager.removeGame(this);
            shutdown();
            return;
        }

        black.sendMessage(Messages.prefixed(Component.text("你是", NamedTextColor.GRAY)
                .append(Component.text("黑方", NamedTextColor.DARK_GRAY))
                .append(Component.text("，使用黑色混凝土。", NamedTextColor.GRAY))));
        white.sendMessage(Messages.prefixed(Component.text("你是", NamedTextColor.GRAY)
                .append(Component.text("白方", NamedTextColor.WHITE))
                .append(Component.text("，使用白色混凝土。", NamedTextColor.GRAY))));
        sendBoth("对局开始！点击棋盘格落子，黑方先手。上方按钮可以滚动 9×9 棋盘。");

        black.openInventory(inventory);
        white.openInventory(inventory);
        announceTurn();
    }

    boolean openFor(Player player) {
        if (finished || !isParticipant(player.getUniqueId())) {
            return false;
        }
        player.openInventory(inventory);
        return true;
    }

    void handleClick(Player player, int rawSlot) {
        UUID playerId = player.getUniqueId();
        if (!isParticipant(playerId) && !isSpectator(playerId)) {
            return;
        }
        if (rawSlot == PREVIOUS_SLOT) {
            scrollBy(-1);
            return;
        }
        if (rawSlot == NEXT_SLOT) {
            scrollBy(1);
            return;
        }
        if (rawSlot < BOARD_START_SLOT || rawSlot >= INVENTORY_SIZE) {
            return;
        }

        int boardSlot = rawSlot - BOARD_START_SLOT;
        int visibleRow = boardSlot / BOARD_SIZE;
        int column = boardSlot % BOARD_SIZE;
        if (visibleRow < 0 || visibleRow >= VISIBLE_ROWS) {
            return;
        }
        if (!isParticipant(playerId)) {
            return;
        }
        play(player, scrollOffset + visibleRow, column);
    }

    void forfeit(UUID quitterId, String quitterName) {
        if (finished || !isParticipant(quitterId)) {
            return;
        }

        finished = true;
        UUID winnerId = otherPlayer(quitterId);
        String winnerName = nameOf(winnerId);
        resultText = quitterName + " 退出了对局";
        Player winner = player(winnerId);
        manager.removeGame(this);
        refreshView();
        broadcastWinner(winnerId, winnerName, quitterId, quitterName);
        if (winner != null) {
            Messages.send(winner, quitterName + " 已退出对局，你获胜了！");
        }
        closeLater();
    }

    void shutdown() {
        finished = true;
        closeInventories();
        spectators.clear();
    }

    private void play(Player player, int row, int column) {
        if (finished) {
            Messages.send(player, "这局五子棋已经结束。");
            return;
        }
        if (!Objects.equals(currentTurn, player.getUniqueId())) {
            Messages.send(player, "现在轮到 " + nameOf(currentTurn) + " 落子。");
            return;
        }
        if (row < 0 || row >= BOARD_SIZE || column < 0 || column >= BOARD_SIZE) {
            return;
        }
        if (board[row][column] != null) {
            Messages.send(player, "这个位置已经有棋子了，请选择其他位置。");
            return;
        }

        Material piece = pieceFor(player.getUniqueId());
        board[row][column] = piece;
        sendBoth(nameOf(player.getUniqueId()) + " 在第 " + (row + 1) + " 行、第 " + (column + 1) + " 列落子。");

        if (hasFive(row, column, piece)) {
            finishWithWinner(player);
            return;
        }
        if (isBoardFull()) {
            finishWithDraw();
            return;
        }

        currentTurn = otherPlayer(player.getUniqueId());
        refreshView();
        announceTurn();
    }

    private void scrollBy(int amount) {
        int nextOffset = Math.max(0, Math.min(MAX_SCROLL_OFFSET, scrollOffset + amount));
        if (nextOffset == scrollOffset) {
            return;
        }
        scrollOffset = nextOffset;
        refreshView();
        sendBoth("棋盘已滚动到第 " + (scrollOffset + 1) + " 至 "
                + Math.min(BOARD_SIZE, scrollOffset + VISIBLE_ROWS) + " 行。");
    }

    private void finishWithWinner(Player winner) {
        finished = true;
        resultText = winner.getName() + " 获胜";
        UUID loserId = otherPlayer(winner.getUniqueId());
        String loserName = nameOf(loserId);
        manager.removeGame(this);
        refreshView();
        broadcastWinner(winner.getUniqueId(), winner.getName(), loserId, loserName);
        sendBoth(Component.text("恭喜！", NamedTextColor.YELLOW)
                .append(Component.text(winner.getName(), colorFor(winner.getUniqueId())))
                .append(Component.text(" 获得胜利！", NamedTextColor.YELLOW)));
        closeLater();
    }

    private void finishWithDraw() {
        finished = true;
        resultText = "棋盘已满，平局";
        manager.removeGame(this);
        refreshView();
        Bukkit.broadcast(Messages.prefixed(Component.text("五子棋对局结束：", NamedTextColor.YELLOW)
                .append(Component.text(nameOf(blackPlayerId), NamedTextColor.DARK_GRAY))
                .append(Component.text(" 与 ", NamedTextColor.GRAY))
                .append(Component.text(nameOf(whitePlayerId), NamedTextColor.WHITE))
                .append(Component.text(" 战成平局。", NamedTextColor.YELLOW))));
        sendBoth(Component.text("棋盘已满，这局是平局！", NamedTextColor.YELLOW));
        closeLater();
    }

    private void broadcastWinner(UUID winnerId, String winnerName, UUID loserId, String loserName) {
        Bukkit.broadcast(Messages.prefixed(Component.text("五子棋对局结束：", NamedTextColor.YELLOW)
                .append(Component.text(winnerName, colorFor(winnerId)))
                .append(Component.text(" 击败了 ", NamedTextColor.GRAY))
                .append(Component.text(loserName, colorFor(loserId)))
                .append(Component.text("，获得胜利！", NamedTextColor.YELLOW))));
    }

    private void announceTurn() {
        Player current = player(currentTurn);
        if (current == null) {
            return;
        }
        Player other = player(otherPlayer(currentTurn));
        Messages.send(current, Component.text("轮到你落子。", NamedTextColor.GREEN)
                .append(Component.text("请点击棋盘格放置", NamedTextColor.GRAY))
                .append(Component.text(pieceName(pieceFor(currentTurn)), colorFor(currentTurn)))
                .append(Component.text("。", NamedTextColor.GRAY)));
        if (other != null) {
            Messages.send(other, Component.text("现在轮到 ", NamedTextColor.GRAY)
                    .append(Component.text(current.getName(), colorFor(currentTurn)))
                    .append(Component.text(" 落子。", NamedTextColor.GRAY)));
        }
    }

    private boolean hasFive(int row, int column, Material piece) {
        for (int[] direction : DIRECTIONS) {
            int count = 1
                    + countDirection(row, column, direction[0], direction[1], piece)
                    + countDirection(row, column, -direction[0], -direction[1], piece);
            if (count >= 5) {
                return true;
            }
        }
        return false;
    }

    private int countDirection(int row, int column, int rowStep, int columnStep, Material piece) {
        int count = 0;
        int nextRow = row + rowStep;
        int nextColumn = column + columnStep;
        while (nextRow >= 0 && nextRow < BOARD_SIZE
                && nextColumn >= 0 && nextColumn < BOARD_SIZE
                && board[nextRow][nextColumn] == piece) {
            count++;
            nextRow += rowStep;
            nextColumn += columnStep;
        }
        return count;
    }

    private boolean isBoardFull() {
        for (Material[] row : board) {
            for (Material cell : row) {
                if (cell == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private void refreshView() {
        for (int slot = 1; slot < 8; slot++) {
            inventory.setItem(slot, namedItem(Material.BLACK_STAINED_GLASS_PANE,
                    Component.text(" ", NamedTextColor.DARK_GRAY), List.of()));
        }
        inventory.setItem(PREVIOUS_SLOT, scrollOffset > 0
                ? namedItem(Material.ARROW, Component.text("上一行", NamedTextColor.GREEN),
                List.of(Component.text("点击向上滚动棋盘", NamedTextColor.GRAY)))
                : namedItem(Material.GRAY_DYE, Component.text("已经是最上方", NamedTextColor.DARK_GRAY), List.of()));
        inventory.setItem(NEXT_SLOT, scrollOffset < MAX_SCROLL_OFFSET
                ? namedItem(Material.ARROW, Component.text("下一行", NamedTextColor.GREEN),
                List.of(Component.text("点击向下滚动棋盘", NamedTextColor.GRAY)))
                : namedItem(Material.GRAY_DYE, Component.text("已经是最下方", NamedTextColor.DARK_GRAY), List.of()));
        inventory.setItem(STATUS_SLOT, statusItem());

        for (int visibleRow = 0; visibleRow < VISIBLE_ROWS; visibleRow++) {
            int boardRow = scrollOffset + visibleRow;
            for (int column = 0; column < BOARD_SIZE; column++) {
                int slot = BOARD_START_SLOT + visibleRow * BOARD_SIZE + column;
                Material piece = board[boardRow][column];
                inventory.setItem(slot, piece == null
                        ? emptyCellItem(boardRow, column)
                        : pieceItem(piece, boardRow, column));
            }
        }
    }

    private ItemStack statusItem() {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("黑方：" + nameOf(blackPlayerId), NamedTextColor.DARK_GRAY));
        lore.add(Component.text("白方：" + nameOf(whitePlayerId), NamedTextColor.GRAY));
        lore.add(Component.text("显示第 " + (scrollOffset + 1) + " 至 "
                + Math.min(BOARD_SIZE, scrollOffset + VISIBLE_ROWS) + " 行 / 共 9 行", NamedTextColor.AQUA));
        if (finished) {
            lore.add(Component.text("对局已结束", NamedTextColor.YELLOW));
            return namedItem(Material.GOLD_NUGGET,
                    Component.text(resultText == null ? "对局已结束" : resultText, NamedTextColor.YELLOW), lore);
        }
        lore.add(Component.text("当前回合：" + nameOf(currentTurn), colorFor(currentTurn)));
        return namedItem(Material.NETHER_STAR,
                Component.text("五子棋状态", NamedTextColor.AQUA), lore);
    }

    private static ItemStack emptyCellItem(int row, int column) {
        return namedItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                Component.text("空位 · 第 " + (row + 1) + " 行 / 第 " + (column + 1) + " 列", NamedTextColor.GRAY),
                List.of(Component.text("点击落子", NamedTextColor.DARK_GRAY)));
    }

    private static ItemStack pieceItem(Material piece, int row, int column) {
        boolean black = piece == Material.BLACK_CONCRETE;
        NamedTextColor color = black ? NamedTextColor.DARK_GRAY : NamedTextColor.WHITE;
        String name = black ? "黑色混凝土" : "白色混凝土";
        return namedItem(piece,
                Component.text(name + " · 第 " + (row + 1) + " 行 / 第 " + (column + 1) + " 列", color),
                List.of(Component.text("已占用", NamedTextColor.GRAY)));
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
        return blackPlayerId.equals(playerId) ? Material.BLACK_CONCRETE : Material.WHITE_CONCRETE;
    }

    private UUID otherPlayer(UUID playerId) {
        return blackPlayerId.equals(playerId) ? whitePlayerId : blackPlayerId;
    }

    private Player player(UUID playerId) {
        return Bukkit.getPlayer(playerId);
    }

    private String nameOf(UUID playerId) {
        Player player = player(playerId);
        return player == null ? "对方" : player.getName();
    }

    private static String pieceName(Material piece) {
        return piece == Material.BLACK_CONCRETE ? "黑色混凝土" : "白色混凝土";
    }

    private NamedTextColor colorFor(UUID playerId) {
        return blackPlayerId.equals(playerId) ? NamedTextColor.DARK_GRAY : NamedTextColor.WHITE;
    }

    private void sendBoth(String text) {
        sendBoth(Component.text(text, NamedTextColor.GRAY));
    }

    private void sendBoth(Component text) {
        Player black = player(blackPlayerId);
        Player white = player(whitePlayerId);
        if (black != null) {
            black.sendMessage(Messages.prefixed(text));
        }
        if (white != null && white != black) {
            white.sendMessage(Messages.prefixed(text));
        }
    }

    private void closeLater() {
        long delay = Math.max(1L, manager.endDisplaySeconds() * 20L);
        Bukkit.getScheduler().runTaskLater(manager.plugin(), this::closeInventories, delay);
    }

    private void closeInventories() {
        Set<UUID> viewerIds = new HashSet<>(spectators);
        viewerIds.add(blackPlayerId);
        viewerIds.add(whitePlayerId);
        for (UUID viewerId : viewerIds) {
            closeIfViewing(player(viewerId));
        }
        spectators.clear();
    }

    private void closeIfViewing(Player player) {
        if (player != null && player.getOpenInventory().getTopInventory() == inventory) {
            player.closeInventory();
        }
    }
}
