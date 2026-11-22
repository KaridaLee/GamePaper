package com.gamepaper.tictactoe;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

final class UndercoverGameListener implements Listener {

    private final UndercoverGameManager manager;
    private final GameGui gameGui;

    UndercoverGameListener(UndercoverGameManager manager, GameGui gameGui) {
        this.manager = manager;
        this.gameGui = gameGui;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        manager.handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        manager.handleQuit(event.getPlayer());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        manager.handleRespawn(event.getPlayer(), event);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UndercoverGameMatch match = manager.matchFor(player.getUniqueId());
        if (match == null || !match.isRunning() || !match.isActivePlayer(player.getUniqueId())) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        player.getInventory().clear();
        match.eliminateOnDeath(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UndercoverGameMatch match = manager.matchFor(player.getUniqueId());
        if (match == null || !match.isRunning() || !match.isActivePlayer(player.getUniqueId())
                || event.getTo() == null || samePosition(event.getFrom(), event.getTo())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        UndercoverGameMatch match = manager.matchFor(event.getPlayer().getUniqueId());
        if (match != null && match.isRunning() && match.isMovementLocked()
                && match.isActivePlayer(event.getPlayer().getUniqueId())
                && event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (isRunningParticipant(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isRunningParticipant(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isRunningParticipant(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isRunningParticipant(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isRunningParticipant(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (isRunningParticipant(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        UndercoverGameMatch match = manager.matchFor(event.getPlayer().getUniqueId());
        if (match != null && match.isRunning() && !manager.isCommandAllowed(event.getMessage())) {
            event.setCancelled(true);
            Messages.send(event.getPlayer(),
                    "谁是卧底进行中只能使用 /game undercover 的当前游戏指令。");
        }
    }

    @EventHandler
    public void onBookInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UndercoverGameMatch match = manager.matchFor(player.getUniqueId());
        if (match == null || !match.isRunning() || !event.getAction().isRightClick()) {
            return;
        }
        ItemStack item = event.getItem();
        if (match.isViewerBook(item)) {
            event.setCancelled(true);
            match.openDescriptions(player, 0);
        } else if (match.isVoteOpener(item)) {
            event.setCancelled(true);
            match.reopenVoting(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBookEdit(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        UndercoverGameMatch match = manager.matchFor(player.getUniqueId());
        if (match == null || !match.isRunning() || !event.isSigning()) {
            return;
        }
        if (!match.isWritingBookMeta(event.getPreviousBookMeta())
                || !match.isCurrentDescriber(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        match.submitDescription(player, event.getNewBookMeta());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        UndercoverGameMatch match = manager.matchFor(player.getUniqueId());
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (match != null && match.isRunning()
                && !match.isOwnCustomInventory(holder)
                && gameGui.isGameMenu(holder)) {
            event.setCancelled(true);
            return;
        }
        if (match != null && holder instanceof UndercoverGameMatch.DescriptionHolder descriptionHolder) {
            match.handleDescriptionInventoryClick(player, event, descriptionHolder);
            return;
        }
        if (match != null && holder instanceof UndercoverGameMatch.VoteHolder voteHolder) {
            match.handleVoteClick(player, event, voteHolder);
            return;
        }
        if (match != null && match.isRunning()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && isRunningParticipant(player)) {
            event.setCancelled(true);
        }
    }

    private boolean isRunningParticipant(Player player) {
        UndercoverGameMatch match = manager.matchFor(player.getUniqueId());
        return match != null && match.isRunning();
    }

    private static boolean samePosition(Location from, Location to) {
        return from.getWorld() == to.getWorld()
                && from.getX() == to.getX()
                && from.getY() == to.getY()
                && from.getZ() == to.getZ();
    }
}
