package com.gamepaper.tictactoe;

import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;

final class RandomGameListener implements Listener {

    private final RandomGameManager manager;

    RandomGameListener(RandomGameManager manager) {
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
        RandomGameMatch match = manager.matchFor(player.getUniqueId());
        if (match != null && match.isEliminated(player.getUniqueId()) && match.isRunning()) {
            Location gameSpawn = match.gameSpawnFor(player.getUniqueId());
            if (gameSpawn != null) {
                event.setRespawnLocation(gameSpawn);
                Bukkit.getScheduler().runTask(manager.plugin(), () -> match.becomeSpectator(player));
                return;
            }
        }
        manager.handleRespawn(event.getPlayer(), event);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        RandomGameMatch match = manager.matchFor(player.getUniqueId());
        if (match == null || !match.isRunning() || !match.isActivePlayer(player.getUniqueId())) {
            return;
        }

        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        player.getInventory().clear();
        match.eliminate(player.getUniqueId(), player.getName(), "死亡", true);
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        RandomGameMatch match = manager.matchFor(player.getUniqueId());
        if (match != null && match.isRunning() && !manager.isCommandAllowed(event.getMessage())) {
            event.setCancelled(true);
            Messages.send(player, "游戏进行中不能使用这个指令；可用指令请查看 random-game.command-whitelist 配置。");
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        RandomGameMatch match = manager.matchFor(player.getUniqueId());
        if (match == null || !match.isActivePlayer(player.getUniqueId())) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (match.isCountdown()) {
            if (changedPosition(from, to)) {
                event.setCancelled(true);
            }
            return;
        }
        if (match.isActive() && to.getY() <= 0.0D) {
            if (!player.isDead()) {
                player.setHealth(0.0D);
            }
            return;
        }
        if (match.isActive() && !manager.settings().boundary().contains(to)) {
            event.setCancelled(true);
            if (match.markBoundaryWarning(player.getUniqueId())) {
                Messages.send(player, "你不能离开随机物品游戏范围。");
            }
        } else {
            match.clearBoundaryWarning(player.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        RandomGameMatch match = manager.matchFor(player.getUniqueId());
        if (match == null || !match.isActivePlayer(player.getUniqueId()) || !match.isRunning()) {
            return;
        }
        if (match.isCountdown()
                || (event.getTo() != null && !manager.settings().boundary().contains(event.getTo()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && isCountdownPlayer(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isCountdownPlayer(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && isCountdownPlayer(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (isCountdownPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (isCountdownPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        RandomGameMatch match = manager.matchFor(player.getUniqueId());
        if (match == null || !match.isActivePlayer(player.getUniqueId())) {
            return;
        }
        if (match.isCountdown() || (match.isActive()
                && !manager.settings().boundary().contains(event.getBlock().getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        RandomGameMatch match = manager.matchFor(player.getUniqueId());
        if (match == null || !match.isActivePlayer(player.getUniqueId())) {
            return;
        }
        if (match.isCountdown() || (match.isActive()
                && !manager.settings().boundary().contains(event.getBlock().getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (isCountdownPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isCountdownPlayer(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (isCountdownPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        if (isCountdownPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        if (isCountdownPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        if (isCountdownPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isCountdownPlayer(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        String customName = event.getEntity().getCustomName();
        boolean named = customName != null && !customName.isBlank();
        if (manager.isRunningAt(event.getLocation())
                && !named
                && !manager.isNamedCreatureSpawnAllowed(event.getLocation())) {
            event.setCancelled(true);
        }
    }

    private boolean isCountdownPlayer(Player player) {
        RandomGameMatch match = manager.matchFor(player.getUniqueId());
        return match != null && match.isCountdown() && match.isActivePlayer(player.getUniqueId());
    }

    private static boolean changedPosition(Location from, Location to) {
        return from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ();
    }
}
