package com.gamepaper.tictactoe;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

final class GomokuListener implements Listener {

    private final GomokuManager manager;

    GomokuListener(GomokuManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder(false) instanceof GomokuGame game)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != topInventory) {
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < topInventory.getSize()) {
            game.handleClick(player, rawSlot);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (topInventory.getHolder(false) instanceof GomokuGame) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder(false) instanceof GomokuGame game)) {
            return;
        }
        if (event.getPlayer() instanceof Player player) {
            if (game.isSpectator(player.getUniqueId())) {
                game.onSpectatorClosed(player.getUniqueId());
                Messages.send(player, "你已离开五子棋观战。");
                return;
            }
            if (game.isFinished()) {
                return;
            }
            if (!game.isParticipant(player.getUniqueId())) {
                return;
            }
            Messages.send(player, "你关闭了棋盘；对局仍在继续。使用 /game gomuku board 重新打开，或使用 /game gomuku leave 认输。");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        manager.handleQuit(event.getPlayer());
    }
}
