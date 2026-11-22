package com.gamepaper.tictactoe;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

final class TntGameListener implements Listener {

    private final TntGameManager manager;

    TntGameListener(TntGameManager manager) {
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
        TntGameMatch match = manager.matchFor(player.getUniqueId());
        if (match != null && match.isEliminated(player.getUniqueId()) && match.isRunning()) {
            Location spectatorLocation = match.spectatorLocationFor(player.getUniqueId());
            if (spectatorLocation != null) {
                event.setRespawnLocation(spectatorLocation);
            }
            Bukkit.getScheduler().runTask(manager.plugin(), () -> match.becomeSpectator(player));
            return;
        }
        manager.handleRespawn(player, event);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        TntGameMatch match = manager.matchFor(player.getUniqueId());
        if (match == null || !match.isRunning()) {
            return;
        }

        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        player.getInventory().clear();
        if (match.isActivePlayer(player.getUniqueId())) {
            match.eliminateOnDeath(player);
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        TntGameMatch match = manager.matchFor(player.getUniqueId());
        if (match != null && match.isRunning() && !manager.isCommandAllowed(event.getMessage())) {
            event.setCancelled(true);
            Messages.send(player, "TNT 游戏进行中只能使用 /game tnt 的指令。" );
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof Player target)) {
            return;
        }
        TntGameMatch match = manager.matchFor(attacker.getUniqueId());
        if (match != null) {
            // 保留正常攻击伤害和击退，TNT 传递只是附加效果。
            match.transferTnt(attacker, target);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (isRunningPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isRunningPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (isRunningPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isRunningPlayer(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isRunningPlayer(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && isRunningPlayer(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        TntGameMatch match = manager.matchFor(player.getUniqueId());
        if (match == null || !match.isRunning() || event.getTo() == null) {
            return;
        }
        if (!manager.settings().boundary().contains(event.getTo())) {
            event.setCancelled(true);
            if (match.markBoundaryWarning(player.getUniqueId())) {
                Messages.send(player, "你不能离开 TNT 游戏范围。" );
            }
        } else {
            match.clearBoundaryWarning(player.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        TntGameMatch match = manager.matchFor(player.getUniqueId());
        if (match != null && match.isRunning()
                && (event.getTo() == null || !manager.settings().boundary().contains(event.getTo()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (manager.isRunningAt(event.getLocation())) {
            event.setCancelled(true);
        }
    }

    private boolean isRunningPlayer(Player player) {
        TntGameMatch match = manager.matchFor(player.getUniqueId());
        return match != null && match.isRunning();
    }
}
