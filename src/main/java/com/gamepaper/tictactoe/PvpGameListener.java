package com.gamepaper.tictactoe;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.UUID;

final class PvpGameListener implements Listener {

    private final PvpGameManager manager;

    PvpGameListener(PvpGameManager manager) {
        this.manager = manager;
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
        Player player = event.getPlayer();
        PvpGameMatch match = manager.matchFor(player.getUniqueId());
        if (match != null && match.isRunning() && match.isActivePlayer(player.getUniqueId())) {
            match.handleRespawn(player, event);
            return;
        }
        manager.handleRespawn(player, event);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        PvpGameMatch match = manager.matchFor(player.getUniqueId());
        if (match == null || !match.isRunning() || !match.isActivePlayer(player.getUniqueId())) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        match.handleDeath(player);
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        PvpGameMatch match = manager.matchFor(player.getUniqueId());
        if (match != null && match.isRunning() && match.isActivePlayer(player.getUniqueId())
                && !manager.isCommandAllowed(event.getMessage())) {
            event.setCancelled(true);
            Messages.send(player, "游戏进行中不能使用这个指令；只能使用 /game pvp leave、status 或 help。" );
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PvpGameMatch match = manager.matchFor(player.getUniqueId());
        if (match == null || !match.isRunning() || !match.isActivePlayer(player.getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (match.isMovementLocked(player.getUniqueId())) {
            if (changedPosition(from, to)) {
                event.setCancelled(true);
            }
            return;
        }
        if (match.isPlaying() && !manager.settings().boundary().contains(to)) {
            event.setCancelled(true);
            Messages.send(player, "你不能离开占点游戏范围。" );
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        PvpGameMatch match = manager.matchFor(player.getUniqueId());
        if (match == null || !match.isRunning() || !match.isActivePlayer(player.getUniqueId())) {
            return;
        }
        if (match.isMovementLocked(player.getUniqueId())
                || (match.isPlaying() && event.getTo() != null
                && !manager.settings().boundary().contains(event.getTo()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        PvpGameMatch match = manager.matchFor(player.getUniqueId());
        if (match != null && match.isRunning() && match.isActivePlayer(player.getUniqueId())
                && match.isInvulnerable(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player target)) {
            return;
        }
        PvpGameMatch targetMatch = manager.matchFor(target.getUniqueId());
        if (targetMatch == null || !targetMatch.isRunning()
                || !targetMatch.isActivePlayer(target.getUniqueId())) {
            return;
        }
        if (targetMatch.isInvulnerable(target.getUniqueId()) || !targetMatch.isPlaying()) {
            event.setCancelled(true);
            return;
        }
        UUID attackerId = attackerId(event.getDamager());
        if (attackerId == null) {
            return;
        }
        PvpGameMatch attackerMatch = manager.matchFor(attackerId);
        if (attackerMatch != targetMatch || !targetMatch.isActivePlayer(attackerId)) {
            return;
        }
        if (targetMatch.teamOf(attackerId) == targetMatch.teamOf(target.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        cancelForPlayingPlayer(event.getPlayer(), event);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        cancelForPlayingPlayer(event.getPlayer(), event);
    }

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent event) {
        cancelForPlayingPlayer(event.getPlayer(), event);
    }

    @EventHandler
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            cancelForPlayingPlayer(player, event);
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            PvpGameMatch match = manager.matchFor(player.getUniqueId());
            if (match != null && match.isRunning() && match.isActivePlayer(player.getUniqueId())
                    && match.isClassSelectionInventory(event.getInventory())) {
                return;
            }
            if (match != null && match.isRunning() && match.isActivePlayer(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder()
                instanceof PvpGameMatch.ClassSelectionHolder) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                PvpGameMatch match = manager.matchFor(player.getUniqueId());
                if (match != null && match.isClassSelectionInventory(event.getView().getTopInventory())
                        && event.getRawSlot() >= 0
                        && event.getRawSlot() < event.getView().getTopInventory().getSize()) {
                    match.selectJob(player, event.getRawSlot());
                }
            }
            return;
        }
        if (event.getWhoClicked() instanceof Player player) {
            cancelForPlayingPlayer(player, event);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !(event.getView().getTopInventory().getHolder()
                instanceof PvpGameMatch.ClassSelectionHolder)) {
            return;
        }
        PvpGameMatch match = manager.matchFor(player.getUniqueId());
        if (match != null && match.isClassSelectionInventory(event.getView().getTopInventory())) {
            match.handleClassSelectionClosed(player);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            cancelForPlayingPlayer(player, event);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        PvpGameMatch match = manager.matchFor(player.getUniqueId());
        if (match == null || !match.isRunning() || !match.isActivePlayer(player.getUniqueId())) {
            return;
        }
        if (match.jobOf(player.getUniqueId()) == null) {
            event.setCancelled(true);
            return;
        }
        if (!match.isPlaying() || match.isMovementLocked(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (match.trySniperSkill(player, event.getHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        PvpGameMatch match = manager.matchFor(player.getUniqueId());
        if (match != null && match.isRunning() && match.isActivePlayer(player.getUniqueId())
                && (match.jobOf(player.getUniqueId()) == null
                || !match.isPlaying() || match.isMovementLocked(player.getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        PvpGameMatch match = manager.matchFor(player.getUniqueId());
        if (match != null && match.isRunning() && match.isActivePlayer(player.getUniqueId())
                && (match.jobOf(player.getUniqueId()) == null
                || !match.isPlaying() || match.isMovementLocked(player.getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        cancelForPlayingPlayer(event.getPlayer(), event);
    }

    @EventHandler
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        PvpGameMatch match = manager.matchFor(player.getUniqueId());
        if (match == null || !match.isRunning() || !match.isActivePlayer(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        if (match.isPlaying() && !match.isMovementLocked(player.getUniqueId())) {
            if (match.jobOf(player.getUniqueId()) == PvpGameMatch.Job.ASSAULT) {
                match.tryAssaultDash(player);
            } else if (match.jobOf(player.getUniqueId()) == PvpGameMatch.Job.SNIPER) {
                match.trySniperDash(player);
            }
        }
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (manager.isRunningAt(event.getLocation())) {
            event.setCancelled(true);
        }
    }

    private void cancelForPlayingPlayer(Player player, org.bukkit.event.Cancellable event) {
        PvpGameMatch match = manager.matchFor(player.getUniqueId());
        if (match != null && match.isRunning() && match.isActivePlayer(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private UUID attackerId(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player player) {
            return player.getUniqueId();
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) {
                return player.getUniqueId();
            }
        }
        return null;
    }

    private static boolean changedPosition(Location from, Location to) {
        return from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ();
    }
}
