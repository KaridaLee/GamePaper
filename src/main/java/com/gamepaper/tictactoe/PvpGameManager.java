package com.gamepaper.tictactoe;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class PvpGameManager {

    private final JavaPlugin plugin;
    private final GomokuManager otherGameManager;
    private final RandomGameManager randomGameManager;
    private final TntGameManager tntGameManager;
    private final UndercoverGameManager undercoverGameManager;
    private final PvpGameSettings settings;
    private final LinkedHashSet<UUID> lobbyPlayers = new LinkedHashSet<>();
    private final Set<UUID> returnToSpawn = new LinkedHashSet<>();
    private final File stateFile;
    private PvpGameMatch currentMatch;

    PvpGameManager(JavaPlugin plugin, GomokuManager otherGameManager,
                   RandomGameManager randomGameManager, TntGameManager tntGameManager,
                   UndercoverGameManager undercoverGameManager) {
        this.plugin = plugin;
        this.otherGameManager = otherGameManager;
        this.randomGameManager = randomGameManager;
        this.tntGameManager = tntGameManager;
        this.undercoverGameManager = undercoverGameManager;
        this.settings = new PvpGameSettings(plugin);
        this.stateFile = new File(plugin.getDataFolder(), "pvp-game-state.yml");
        loadReturnState();
    }

    JavaPlugin plugin() {
        return plugin;
    }

    PvpGameSettings settings() {
        return settings;
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
        UUID playerId = player.getUniqueId();
        if (currentMatch != null) {
            Messages.send(player, "占点游戏已经开始，当前不能加入。");
            return false;
        }
        if (lobbyPlayers.contains(playerId)) {
            Messages.send(player, "你已经在占点游戏等待区了。");
            return false;
        }
        if (isBusyInOtherGame(playerId)) {
            Messages.send(player, "你正在进行另一局小游戏，不能加入占点游戏。");
            return false;
        }
        int capacity = joinCapacity();
        if (capacity < 2) {
            Messages.send(player, "占点游戏至少需要红、蓝两队各有一个有效重生点。");
            return false;
        }
        if (lobbyPlayers.size() >= capacity) {
            Messages.send(player, "占点游戏等待区已满（最多 " + capacity + " 人）。");
            return false;
        }
        if (!RandomGameManager.inventoryIsEmpty(player)) {
            Messages.send(player, "加入占点游戏前，背包、盔甲栏和副手必须全部为空。");
            return false;
        }

        cancelPendingReturns(playerId);
        lobbyPlayers.add(playerId);
        sendLobbyMessage(player.getName() + " 加入了占点游戏（"
                + lobbyPlayers.size() + "/" + capacity + "）。输入 /game pvp start 开始。", player);
        Messages.send(player, "你已加入占点游戏，职业将在游戏开始时通过职业选择界面选择。");
        if (lobbyPlayers.size() >= capacity) {
            startInternal();
        }
        return true;
    }

    boolean start(Player requester) {
        if (currentMatch != null) {
            Messages.send(requester, "占点游戏已经开始了。");
            return false;
        }
        if (requester != null && !lobbyPlayers.contains(requester.getUniqueId())) {
            Messages.send(requester, "你需要先使用 /game pvp join 加入等待区。");
            return false;
        }
        return startInternal();
    }

    private boolean startInternal() {
        if (lobbyPlayers.size() < 2) {
            sendLobbyMessage("占点游戏至少需要 2 名玩家才能开始。", null);
            return false;
        }

        Location redSpawn = resolveSpawnLocation(settings.redSpawnPoint());
        Location blueSpawn = resolveSpawnLocation(settings.blueSpawnPoint());
        if (redSpawn == null || blueSpawn == null) {
            sendLobbyMessage("红、蓝队的共享重生点无效或不在游戏范围内，无法开局。", null);
            return false;
        }
        if (settings.boundary().resolveWorld() == null) {
            sendLobbyMessage("占点游戏范围配置的世界不存在：" + settings.boundary().worldName(), null);
            return false;
        }
        org.bukkit.World zoneWorld = Bukkit.getWorld(settings.captureZone().worldName());
        if (zoneWorld == null) {
            sendLobbyMessage("占点区域配置的世界不存在：" + settings.captureZone().worldName(), null);
            return false;
        }
        if (!settings.boundary().contains(new Location(zoneWorld,
                settings.captureZone().x(), settings.captureZone().y(), settings.captureZone().z()))) {
            sendLobbyMessage("占点区域中心不在 pvp-game.boundary 范围内，游戏无法开始。", null);
            return false;
        }
        for (UUID playerId : lobbyPlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || !RandomGameManager.inventoryIsEmpty(player)) {
                sendLobbyMessage("有玩家不在线或背包已经不为空，游戏没有开始。", null);
                return false;
            }
        }

        List<UUID> players = new ArrayList<>(lobbyPlayers);
        lobbyPlayers.clear();
        currentMatch = new PvpGameMatch(this, players, redSpawn, blueSpawn);
        currentMatch.begin();
        return true;
    }

    boolean leave(Player player) {
        UUID playerId = player.getUniqueId();
        if (currentMatch != null && currentMatch.isParticipant(playerId)) {
            currentMatch.handleLeave(player, false);
            return true;
        }
        if (lobbyPlayers.remove(playerId)) {
            sendLobbyMessage(player.getName() + " 离开了占点游戏等待区。", player);
            Messages.send(player, "你已离开占点游戏等待区。");
            return true;
        }
        Messages.send(player, "你当前没有参加占点游戏。");
        return false;
    }

    void sendStatus(Player player) {
        if (currentMatch != null) {
            if (currentMatch.isParticipant(player.getUniqueId())) {
                Messages.send(player, currentMatch.statusText());
            } else {
                Messages.send(player, "占点游戏正在进行：" + currentMatch.statusText());
            }
            return;
        }
        if (lobbyPlayers.isEmpty()) {
            Messages.send(player, "占点游戏当前没有等待中的玩家。");
            return;
        }
        Messages.send(player, "占点游戏等待区：" + lobbyPlayers.size() + "/" + joinCapacity() + " 人。职业将在开局时选择。");
    }

    PvpGameMatch matchFor(UUID playerId) {
        return currentMatch != null && currentMatch.isParticipant(playerId) ? currentMatch : null;
    }

    boolean isRunningAt(Location location) {
        return currentMatch != null && currentMatch.isRunning()
                && settings.boundary().contains(location);
    }

    void handleJoin(Player player) {
        if (!returnToSpawn.contains(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            UUID playerId = player.getUniqueId();
            if (returnToSpawn.contains(playerId) && !isBusy(playerId) && !isBusyInOtherGame(playerId)) {
                returnPlayerToSpawn(player);
            }
        });
    }

    void handleRespawn(Player player, org.bukkit.event.player.PlayerRespawnEvent event) {
        if (!returnToSpawn.contains(player.getUniqueId()) || isBusyInOtherGame(player.getUniqueId())) {
            return;
        }
        event.setRespawnLocation(player.getWorld().getSpawnLocation());
        Bukkit.getScheduler().runTask(plugin, () -> returnPlayerToSpawn(player));
    }

    void handleQuit(Player player) {
        UUID playerId = player.getUniqueId();
        if (currentMatch != null && currentMatch.isParticipant(playerId)) {
            cancelReturnToSpawn(playerId);
            currentMatch.handleLeave(player, true);
            return;
        }
        lobbyPlayers.remove(playerId);
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

    void scheduleReturnToSpawn(List<UUID> playerIds) {
        for (UUID playerId : playerIds) {
            markReturnToSpawn(playerId);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID playerId : playerIds) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline() && !player.isDead()) {
                    returnPlayerToSpawn(player);
                }
            }
        }, 100L);
    }

    void returnToSpawnIfAlive(Player player) {
        returnPlayerToSpawn(player);
    }

    void onMatchFinished(PvpGameMatch match) {
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

    boolean isCommandAllowed(String rawMessage) {
        String command = rawMessage == null ? "" : rawMessage.trim();
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isEmpty()) {
            return false;
        }
        String normalized = command.toLowerCase(Locale.ROOT);
        String[] parts = normalized.split("\\s+");
        String root = parts[0];
        String commandPrefix = parts.length >= 2 ? parts[0] + " " + parts[1] : root;
        if (!settings.commandWhitelist().contains(root)
                && !settings.commandWhitelist().contains(commandPrefix)) {
            return false;
        }
        if (!(root.equals("game") || root.equals("games"))) {
            return true;
        }
        if (parts.length < 2 || !(parts[1].equals("pvp") || parts[1].equals("capture"))) {
            return false;
        }
        return parts.length < 3
                || parts[2].equals("leave")
                || parts[2].equals("status")
                || parts[2].equals("help");
    }

    private boolean isBusyInOtherGame(UUID playerId) {
        return otherGameManager.isBusy(playerId)
                || otherGameManager.spectatorGameFor(playerId) != null
                || randomGameManager.isBusy(playerId)
                || tntGameManager.isBusy(playerId)
                || undercoverGameManager.isBusy(playerId);
    }

    private int joinCapacity() {
        return resolveSpawnLocation(settings.redSpawnPoint()) != null
                && resolveSpawnLocation(settings.blueSpawnPoint()) != null
                ? settings.maxPlayers() : 0;
    }

    private Location resolveSpawnLocation(PvpGameSettings.SpawnPoint point) {
        if (point == null) {
            return null;
        }
        Location location = point.resolve();
        return location != null && settings.boundary().contains(location) ? location : null;
    }

    private void sendLobbyMessage(String message, Player except) {
        for (UUID playerId : lobbyPlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player != except) {
                Messages.send(player, message);
            }
        }
    }

    private void cancelPendingReturns(UUID playerId) {
        cancelReturnToSpawn(playerId);
        randomGameManager.cancelReturnToSpawn(playerId);
        tntGameManager.cancelReturnToSpawn(playerId);
        undercoverGameManager.cancelReturnToSpawn(playerId);
    }

    private void returnPlayerToSpawn(Player player) {
        if (!player.isOnline() || player.isDead()) {
            return;
        }
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setExtraContents(new ItemStack[player.getInventory().getExtraContents().length]);
        for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.setFallDistance(0.0F);
        player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
                ? 20.0D : player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        player.setFireTicks(0);
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
                plugin.getLogger().warning("pvp-game-state.yml 中存在无效 UUID：" + value);
            }
        }
    }

    private void saveReturnState() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录，无法保存占点游戏状态。");
            return;
        }
        YamlConfiguration state = new YamlConfiguration();
        state.set("players", returnToSpawn.stream().map(UUID::toString).toList());
        try {
            state.save(stateFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("无法保存占点游戏状态：" + exception.getMessage());
        }
    }
}
