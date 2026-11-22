package com.gamepaper.tictactoe;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class TntGameManager {

    private final JavaPlugin plugin;
    private final GomokuManager otherGameManager;
    private final RandomGameManager randomGameManager;
    private final TntGameSettings settings;
    private final LinkedHashSet<UUID> lobbyPlayers = new LinkedHashSet<>();
    private final Set<UUID> returnToSpawn = new LinkedHashSet<>();
    private final File stateFile;
    private UndercoverGameManager undercoverGameManager;
    private PvpGameManager pvpGameManager;
    private TntGameMatch currentMatch;

    TntGameManager(JavaPlugin plugin, GomokuManager otherGameManager,
                   RandomGameManager randomGameManager) {
        this.plugin = plugin;
        this.otherGameManager = otherGameManager;
        this.randomGameManager = randomGameManager;
        this.settings = new TntGameSettings(plugin);
        this.stateFile = new File(plugin.getDataFolder(), "tnt-game-state.yml");
        loadReturnState();
    }

    JavaPlugin plugin() {
        return plugin;
    }

    TntGameSettings settings() {
        return settings;
    }

    void setUndercoverGameManager(UndercoverGameManager undercoverGameManager) {
        this.undercoverGameManager = undercoverGameManager;
    }

    void setPvpGameManager(PvpGameManager pvpGameManager) {
        this.pvpGameManager = pvpGameManager;
    }

    boolean isBusy(UUID playerId) {
        return lobbyPlayers.contains(playerId)
                || (currentMatch != null && currentMatch.isParticipant(playerId));
    }

    boolean isQueued(UUID playerId) {
        return lobbyPlayers.contains(playerId);
    }

    List<String> queuedPlayerNames() {
        List<String> names = new ArrayList<>();
        for (UUID playerId : lobbyPlayers) {
            Player player = Bukkit.getPlayer(playerId);
            String name = player == null ? Bukkit.getOfflinePlayer(playerId).getName() : player.getName();
            names.add(name == null || name.isBlank() ? playerId.toString() : name);
        }
        return List.copyOf(names);
    }

    boolean canReloadConfig() {
        return currentMatch == null;
    }

    void reloadConfig() {
        settings.reload(plugin);
    }

    boolean join(Player player) {
        if (currentMatch != null) {
            Messages.send(player, "TNT烫手山芋游戏已经开始，当前不能加入。");
            return false;
        }
        UUID playerId = player.getUniqueId();
        if (lobbyPlayers.contains(playerId)) {
            Messages.send(player, "你已经在 TNT 烫手山芋游戏等待区了。");
            return false;
        }
        if (isBusyInOtherGame(playerId)) {
            Messages.send(player, "你正在进行另一局小游戏，不能加入 TNT 烫手山芋游戏。");
            return false;
        }
        int capacity = joinCapacity();
        if (capacity < 2) {
            Messages.send(player, "TNT 游戏至少需要配置 2 个有效进入位置。");
            return false;
        }
        if (lobbyPlayers.size() >= capacity) {
            Messages.send(player, "TNT 游戏等待区已满（最多 " + capacity + " 人）。");
            return false;
        }
        if (!RandomGameManager.inventoryIsEmpty(player)) {
            Messages.send(player, "加入 TNT 游戏前，背包、盔甲栏和副手必须全部为空。");
            return false;
        }

        cancelPendingReturns(playerId);
        lobbyPlayers.add(playerId);
        sendLobbyMessage(player.getName() + " 加入了 TNT 烫手山芋游戏（"
                + lobbyPlayers.size() + "/" + capacity + "）。输入 /game tnt start 开始。", player);
        return true;
    }

    boolean start(Player requester) {
        if (currentMatch != null) {
            Messages.send(requester, "TNT 烫手山芋游戏已经开始了。");
            return false;
        }
        if (requester != null && !lobbyPlayers.contains(requester.getUniqueId())) {
            Messages.send(requester, "你需要先使用 /game tnt join 加入等待区。");
            return false;
        }
        return startInternal();
    }

    private boolean startInternal() {
        if (lobbyPlayers.size() < 2) {
            sendLobbyMessage("TNT 烫手山芋游戏至少需要 2 名玩家才能开始。", null);
            return false;
        }

        List<Location> spawnLocations = resolveSpawnLocations();
        if (spawnLocations.size() < lobbyPlayers.size()) {
            sendLobbyMessage("有效进入位置数量不足，无法开始 TNT 游戏。", null);
            return false;
        }
        if (settings.boundary().resolveWorld() == null) {
            sendLobbyMessage("TNT 游戏范围配置的世界不存在：" + settings.boundary().worldName(), null);
            return false;
        }
        for (UUID playerId : lobbyPlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !RandomGameManager.inventoryIsEmpty(player)) {
                sendLobbyMessage("有玩家不在线或背包已经不为空，TNT 游戏没有开始。", null);
                return false;
            }
        }

        List<UUID> players = new ArrayList<>(lobbyPlayers);
        lobbyPlayers.clear();
        currentMatch = new TntGameMatch(this, players, spawnLocations);
        currentMatch.begin();
        return true;
    }

    boolean leave(Player player) {
        UUID playerId = player.getUniqueId();
        if (currentMatch != null && currentMatch.isParticipant(playerId)) {
            if (currentMatch.isActivePlayer(playerId)) {
                markReturnToSpawn(playerId);
                currentMatch.markLeft(playerId);
                currentMatch.eliminateForLeave(playerId, player.getName(), "退出游戏");
            } else if (currentMatch.isEliminated(playerId)) {
                currentMatch.leaveAsSpectator(player);
            } else {
                Messages.send(player, "你已经在本局 TNT 游戏中失败了。");
            }
            return true;
        }
        if (lobbyPlayers.remove(playerId)) {
            sendLobbyMessage(player.getName() + " 离开了 TNT 烫手山芋游戏等待区。", player);
            Messages.send(player, "你已离开 TNT 烫手山芋游戏等待区。");
            return true;
        }
        Messages.send(player, "你当前没有参加 TNT 烫手山芋游戏。");
        return false;
    }

    void sendStatus(Player player) {
        if (currentMatch != null) {
            Messages.send(player, currentMatch.statusText());
            return;
        }
        if (lobbyPlayers.isEmpty()) {
            Messages.send(player, "TNT 烫手山芋游戏当前没有等待中的玩家。");
            return;
        }
        Messages.send(player, "TNT 游戏等待区：" + lobbyPlayers.size() + "/" + joinCapacity() + " 人。");
    }

    TntGameMatch matchFor(UUID playerId) {
        return currentMatch != null && currentMatch.isParticipant(playerId) ? currentMatch : null;
    }

    boolean isRunningAt(Location location) {
        return currentMatch != null && currentMatch.isRunning()
                && settings.boundary().contains(location);
    }

    boolean isCommandAllowed(String rawMessage) {
        String command = rawMessage == null ? "" : rawMessage.trim();
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isEmpty()) {
            return false;
        }

        String[] parts = command.toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length < 2 || !(parts[0].equals("game") || parts[0].equals("games"))) {
            return false;
        }
        if (!(parts[1].equals("tnt") || parts[1].equals("hotpotato") || parts[1].equals("hot-potato"))) {
            return false;
        }
        return parts.length < 3
                || parts[2].equals("leave")
                || parts[2].equals("退出")
                || parts[2].equals("status")
                || parts[2].equals("状态")
                || parts[2].equals("help")
                || parts[2].equals("帮助");
    }

    void handleQuit(Player player) {
        UUID playerId = player.getUniqueId();
        TntGameMatch match = currentMatch;
        if (match != null && match.isParticipant(playerId)) {
            cancelReturnToSpawn(playerId);
            if (match.isActivePlayer(playerId)) {
                match.markLeft(playerId);
                match.eliminateForQuit(playerId, player.getName(), "退出服务器");
            } else if (match.isEliminated(playerId)) {
                match.markLeft(playerId);
            }
            return;
        }
        lobbyPlayers.remove(playerId);
    }

    void handleJoin(Player player) {
        if (returnToSpawn.contains(player.getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                UUID playerId = player.getUniqueId();
                if (returnToSpawn.contains(playerId)
                        && !isBusy(playerId) && !isBusyInOtherGame(playerId)) {
                    returnPlayerToSpawn(player);
                }
            });
        }
    }

    void handleRespawn(Player player, org.bukkit.event.player.PlayerRespawnEvent event) {
        if (!returnToSpawn.contains(player.getUniqueId())
                || isBusyInOtherGame(player.getUniqueId())) {
            return;
        }
        event.setRespawnLocation(player.getWorld().getSpawnLocation());
        Bukkit.getScheduler().runTask(plugin, () -> returnPlayerToSpawn(player));
    }

    void markReturnToSpawn(UUID playerId) {
        if (returnToSpawn.add(playerId)) {
            saveReturnState();
        }
    }

    void cancelReturnToSpawn(UUID playerId) {
        if (returnToSpawn.remove(playerId)) {
            saveReturnState();
        }
    }

    private void cancelPendingReturns(UUID playerId) {
        cancelReturnToSpawn(playerId);
        randomGameManager.cancelReturnToSpawn(playerId);
        if (undercoverGameManager != null) {
            undercoverGameManager.cancelReturnToSpawn(playerId);
        }
        if (pvpGameManager != null) {
            pvpGameManager.cancelReturnToSpawn(playerId);
        }
    }

    void returnToSpawnIfAlive(Player player) {
        returnPlayerToSpawn(player);
    }

    void onMatchFinished(TntGameMatch match) {
        if (currentMatch == match) {
            currentMatch = null;
        }
    }

    void shutdown() {
        if (currentMatch != null) {
            currentMatch.serverShutdown();
            currentMatch = null;
        }
        lobbyPlayers.clear();
        saveReturnState();
    }

    private boolean isBusyInOtherGame(UUID playerId) {
        return otherGameManager.isBusy(playerId)
                || otherGameManager.spectatorGameFor(playerId) != null
                || randomGameManager.isBusy(playerId)
                || (undercoverGameManager != null && undercoverGameManager.isBusy(playerId))
                || (pvpGameManager != null && pvpGameManager.isBusy(playerId));
    }

    private int joinCapacity() {
        return Math.min(settings.maxPlayers(), settings.spawnPoints().size());
    }

    private List<Location> resolveSpawnLocations() {
        List<Location> locations = new ArrayList<>();
        for (TntGameSettings.SpawnPoint point : settings.spawnPoints()) {
            Location location = point.resolve();
            if (location != null && settings.boundary().contains(location)) {
                locations.add(location);
            }
        }
        return locations;
    }

    private void sendLobbyMessage(String message, Player except) {
        for (UUID playerId : lobbyPlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player != except) {
                Messages.send(player, message);
            }
        }
    }

    private void returnPlayerToSpawn(Player player) {
        if (!player.isOnline() || player.isDead()) {
            return;
        }
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setExtraContents(new ItemStack[player.getInventory().getExtraContents().length]);
        player.setFallDistance(0.0F);
        player.teleport(player.getWorld().getSpawnLocation());
        if (returnToSpawn.remove(player.getUniqueId())) {
            saveReturnState();
        }
        Messages.send(player, "你已回到世界出生点。");
    }

    private void loadReturnState() {
        if (!stateFile.exists()) {
            return;
        }
        YamlConfiguration state = YamlConfiguration.loadConfiguration(stateFile);
        for (String value : state.getStringList("players")) {
            try {
                returnToSpawn.add(UUID.fromString(value));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("tnt-game-state.yml 中存在无效 UUID：" + value);
            }
        }
    }

    private void saveReturnState() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录，无法保存 TNT 游戏状态。");
            return;
        }
        YamlConfiguration state = new YamlConfiguration();
        state.set("players", returnToSpawn.stream().map(UUID::toString).toList());
        try {
            state.save(stateFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("无法保存 TNT 游戏状态：" + exception.getMessage());
        }
    }
}
