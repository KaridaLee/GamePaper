package com.gamepaper.tictactoe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class GameGui implements Listener {

    private static final int SIZE = 27;
    private static final int RANDOM_SLOT = 10;
    private static final int UNDERCOVER_SLOT = 13;
    private static final int TNT_SLOT = 16;
    private static final int PVP_SLOT = 22;
    private static final String TITLE = "休闲小游戏";

    private final RandomGameManager randomGameManager;
    private final TntGameManager tntGameManager;
    private final UndercoverGameManager undercoverGameManager;
    private final PvpGameManager pvpGameManager;
    private final JavaPlugin plugin;
    private final BukkitTask refreshTask;
    private final Map<UUID, BukkitTask> pendingOpenTasks = new HashMap<>();

    GameGui(JavaPlugin plugin, RandomGameManager randomGameManager, TntGameManager tntGameManager,
            UndercoverGameManager undercoverGameManager, PvpGameManager pvpGameManager) {
        this.randomGameManager = randomGameManager;
        this.tntGameManager = tntGameManager;
        this.undercoverGameManager = undercoverGameManager;
        this.pvpGameManager = pvpGameManager;
        this.plugin = plugin;
        this.refreshTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::refreshOpenMenus, 20L, 20L);
    }

    void shutdown() {
        refreshTask.cancel();
        for (BukkitTask task : pendingOpenTasks.values()) {
            task.cancel();
        }
        pendingOpenTasks.clear();
    }

    void open(Player player) {
        cancelPendingOpen(player.getUniqueId());
        GameGuiHolder holder = new GameGuiHolder();
        Inventory inventory = Bukkit.createInventory(holder, SIZE, TITLE);
        holder.inventory = inventory;
        refresh(inventory, player.getUniqueId());
        player.openInventory(inventory);
    }

    void openWhenReady(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        cancelPendingOpen(player.getUniqueId());
        player.closeInventory();
        BukkitTask[] taskHolder = new BukkitTask[1];
        int[] waitedTicks = new int[1];
        int maxWaitTicks = configuredExternalMenuWaitTicks();
        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                cancelPendingOpen(player.getUniqueId());
                return;
            }
            if (player.getOpenInventory().getTopInventory().getHolder()
                    instanceof GameGuiHolder) {
                cancelPendingOpen(player.getUniqueId());
                return;
            }

            boolean playerInventoryIsVisible = player.getOpenInventory().getType()
                    == InventoryType.CRAFTING;
            waitedTicks[0]++;
            if (playerInventoryIsVisible || waitedTicks[0] >= maxWaitTicks) {
                cancelPendingOpen(player.getUniqueId());
                open(player);
                return;
            }
            // DeluxeMenus may execute a close action after [player] returns.
            // Keep closing that external menu until its click/close sequence ends.
            player.closeInventory();
        }, 1L, 1L);
        pendingOpenTasks.put(player.getUniqueId(), taskHolder[0]);
    }

    private int configuredExternalMenuWaitTicks() {
        return Math.max(0, Math.min(40,
                plugin.getConfig().getInt("game-gui.external-menu-wait-ticks", 2)));
    }

    private void cancelPendingOpen(UUID playerId) {
        BukkitTask task = pendingOpenTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GameGuiHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getRawSlot() < 0
                || event.getRawSlot() >= top.getSize()) {
            return;
        }
        if (undercoverGameManager.matchFor(player.getUniqueId()) != null
                || pvpGameManager.matchFor(player.getUniqueId()) != null) {
            Messages.send(player, "谁是卧底进行中不能通过总菜单加入或退出其他游戏。");
            return;
        }

        if (event.getRawSlot() == RANDOM_SLOT) {
            if (randomGameManager.isQueued(player.getUniqueId())) {
                randomGameManager.leave(player);
            } else {
                randomGameManager.join(player);
            }
        } else if (event.getRawSlot() == TNT_SLOT) {
            if (tntGameManager.isQueued(player.getUniqueId())) {
                tntGameManager.leave(player);
            } else {
                tntGameManager.join(player);
            }
        } else if (event.getRawSlot() == UNDERCOVER_SLOT) {
            if (undercoverGameManager.isQueued(player.getUniqueId())) {
                undercoverGameManager.leave(player);
            } else {
                undercoverGameManager.join(player);
            }
        } else if (event.getRawSlot() == PVP_SLOT) {
            if (pvpGameManager.isQueued(player.getUniqueId())) {
                pvpGameManager.leave(player);
            } else {
                pvpGameManager.join(player);
            }
        }
        refresh(top, player.getUniqueId());
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GameGuiHolder) {
            event.setCancelled(true);
        }
    }

    private void refresh(Inventory inventory, UUID playerId) {
        ItemStack filler = namedItem(
                Material.GRAY_STAINED_GLASS_PANE,
                Component.text(" ", NamedTextColor.GRAY),
                List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
        }

        inventory.setItem(RANDOM_SLOT, gameButton(
                Material.GRASS_BLOCK,
                "随机物品柱子生存",
                randomGameManager.isQueued(playerId),
                "点击加入或退出随机物品游戏等待区",
                randomGameManager.queuedPlayerNames()));
        inventory.setItem(TNT_SLOT, gameButton(
                Material.TNT,
                "TNT 烫手山芋",
                tntGameManager.isQueued(playerId),
                "点击加入或退出 TNT 游戏等待区",
                tntGameManager.queuedPlayerNames()));
        inventory.setItem(UNDERCOVER_SLOT, gameButton(
                Material.WRITABLE_BOOK,
                "谁是卧底",
                undercoverGameManager.isQueued(playerId),
                "点击加入或退出谁是卧底等待区",
                undercoverGameManager.queuedPlayerNames()));
        inventory.setItem(PVP_SLOT, gameButton(
                Material.IRON_SWORD,
                "PVP 占点游戏",
                pvpGameManager.isQueued(playerId),
                "点击加入或退出 PVP 占点游戏等待区",
                pvpGameManager.queuedPlayerNames()));
    }

    boolean isGameMenu(InventoryHolder holder) {
        return holder instanceof GameGuiHolder;
    }

    private static ItemStack gameButton(Material material, String name,
                                         boolean queued, String description,
                                         List<String> queuedPlayers) {
        String action = queued ? "再次点击退出等待区" : "点击加入等待区";
        NamedTextColor color = queued ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(description, NamedTextColor.GRAY));
        lore.add(Component.text(action, color));
        if (queuedPlayers.isEmpty()) {
            lore.add(Component.text("当前队列：暂无玩家", NamedTextColor.DARK_GRAY));
        } else {
            lore.add(Component.text("当前队列（" + queuedPlayers.size() + "人）：",
                    NamedTextColor.AQUA));
            for (String queuedPlayer : queuedPlayers) {
                lore.add(Component.text("- " + queuedPlayer, NamedTextColor.WHITE));
            }
        }
        return namedItem(material,
                Component.text(name, color),
                lore);
    }

    private void refreshOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory inventory = player.getOpenInventory().getTopInventory();
            if (inventory.getHolder() instanceof GameGuiHolder) {
                refresh(inventory, player.getUniqueId());
            }
        }
    }

    private static ItemStack namedItem(Material material, Component displayName,
                                       List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(displayName);
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static final class GameGuiHolder implements InventoryHolder {

        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
