package com.gamepaper.tictactoe;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

final class UndercoverGameManager {

    private final JavaPlugin plugin;
    private final GomokuManager boardGameManager;
    private final RandomGameManager randomGameManager;
    private final TntGameManager tntGameManager;
    private final UndercoverGameSettings settings;
    private final DeepSeekWordProvider wordProvider;
    private final LinkedHashSet<UUID> lobbyPlayers = new LinkedHashSet<>();
    private final Set<UUID> returnToSpawn = new LinkedHashSet<>();
    private final File stateFile;
    private PvpGameManager pvpGameManager;
    private UndercoverGameMatch currentMatch;
    private boolean debugGenerationRunning;

    UndercoverGameManager(JavaPlugin plugin, GomokuManager boardGameManager,
                          RandomGameManager randomGameManager, TntGameManager tntGameManager) {
        this.plugin = plugin;
        this.boardGameManager = boardGameManager;
        this.randomGameManager = randomGameManager;
        this.tntGameManager = tntGameManager;
        this.settings = new UndercoverGameSettings(plugin);
        this.wordProvider = new DeepSeekWordProvider();
        this.stateFile = new File(plugin.getDataFolder(), "undercover-game-state.yml");
        loadReturnState();
        logSpawnConfiguration();
    }

    JavaPlugin plugin() {
        return plugin;
    }

    UndercoverGameSettings settings() {
        return settings;
    }

    DeepSeekWordProvider wordProvider() {
        return wordProvider;
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
        logSpawnConfiguration();
    }

    boolean join(Player player) {
        UUID playerId = player.getUniqueId();
        if (currentMatch != null) {
            Messages.send(player, "谁是卧底游戏已经开始，当前不能加入。");
            return false;
        }
        if (lobbyPlayers.contains(playerId)) {
            Messages.send(player, "你已经在谁是卧底等待区了。");
            return false;
        }
        if (isBusyInOtherGame(playerId)) {
            Messages.send(player, "你正在参加另一局小游戏，不能加入谁是卧底。");
            return false;
        }
        int capacity = joinCapacity();
        if (capacity < 3) {
            Messages.send(player, "谁是卧底至少需要配置 3 个有效重生点。");
            return false;
        }
        if (lobbyPlayers.size() >= capacity) {
            Messages.send(player, "谁是卧底等待区已满（最多 " + capacity + " 人）。");
            return false;
        }
        if (!RandomGameManager.inventoryIsEmpty(player)) {
            Messages.send(player, "加入谁是卧底前，背包、盔甲栏和副手必须全部为空。");
            return false;
        }

        cancelPendingReturns(playerId);
        lobbyPlayers.add(playerId);
        sendLobbyMessage(player.getName() + " 加入了谁是卧底（"
                + lobbyPlayers.size() + "/" + capacity
                + "）。输入 /game undercover start 开始。", player);
        Messages.send(player, "你已加入谁是卧底等待区（"
                + lobbyPlayers.size() + "/" + capacity + "）。");
        if (lobbyPlayers.size() >= capacity) {
            startInternal();
        }
        return true;
    }

    boolean start(Player requester) {
        if (currentMatch != null) {
            Messages.send(requester, "谁是卧底游戏已经开始了。");
            return false;
        }
        if (!lobbyPlayers.contains(requester.getUniqueId())) {
            Messages.send(requester, "你需要先使用 /game undercover join 加入等待区。");
            return false;
        }
        return startInternal();
    }

    private boolean startInternal() {
        if (lobbyPlayers.size() < 3) {
            sendLobbyMessage("谁是卧底至少需要 3 名玩家才能开始。", null);
            return false;
        }
        if (settings.wordPairs().isEmpty() && !settings.aiSettings().usable()) {
            sendLobbyMessage("谁是卧底题库为空，请检查 undercover-game.words 配置。", null);
            return false;
        }
        List<Location> spawns = resolveSpawnLocations();
        if (spawns.size() < lobbyPlayers.size()) {
            sendLobbyMessage("谁是卧底的有效重生点数量不足，游戏没有开始。", null);
            return false;
        }
        for (UUID playerId : lobbyPlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()
                    || !RandomGameManager.inventoryIsEmpty(player)) {
                sendLobbyMessage("有玩家不在线或背包已经不为空，游戏没有开始。", null);
                return false;
            }
        }

        for (UUID playerId : lobbyPlayers) {
            cancelPendingReturns(playerId);
        }

        List<UUID> players = new ArrayList<>(lobbyPlayers);
        lobbyPlayers.clear();
        currentMatch = new UndercoverGameMatch(this, players, spawns);
        currentMatch.begin();
        return true;
    }

    boolean leave(Player player) {
        UUID playerId = player.getUniqueId();
        UndercoverGameMatch match = currentMatch;
        if (match != null && match.isParticipant(playerId)) {
            if (match.isActivePlayer(playerId)) {
                markReturnToSpawn(playerId);
                match.eliminateForDeparture(player, "主动退出游戏");
                match.restorePresentation(player);
                returnPlayerToSpawn(player);
            } else {
                markReturnToSpawn(playerId);
                match.restorePresentation(player);
                returnPlayerToSpawn(player);
                match.markLeft(playerId);
            }
            return true;
        }
        if (lobbyPlayers.remove(playerId)) {
            sendLobbyMessage(player.getName() + " 离开了谁是卧底等待区。", player);
            Messages.send(player, "你已离开谁是卧底等待区。");
            return true;
        }
        Messages.send(player, "你当前没有参加谁是卧底。");
        return false;
    }

    void sendStatus(Player player) {
        if (currentMatch != null) {
            Messages.send(player, currentMatch.statusText());
        } else if (lobbyPlayers.isEmpty()) {
            Messages.send(player, "谁是卧底当前没有等待中的玩家。");
        } else {
            Messages.send(player, "谁是卧底等待区：" + lobbyPlayers.size()
                    + "/" + joinCapacity() + " 人。");
        }
    }

    void debugGenerateWords(CommandSender sender, int amount) {
        if (!sender.hasPermission("gamepaper.undercover.debug")) {
            Messages.send(sender, "你没有权限使用谁是卧底 AI 调试指令。");
            return;
        }
        UndercoverGameSettings.AiSettings ai = settings.aiSettings();
        if (!ai.hasApiKey()) {
            Messages.send(sender, "DeepSeek API Key 为空，请先配置后执行 /game reload。");
            return;
        }
        if (debugGenerationRunning) {
            Messages.send(sender, "已有一组 DeepSeek 调试生成任务正在执行，请稍后再试。");
            return;
        }
        int count = Math.max(1, Math.min(amount, 20));
        debugGenerationRunning = true;
        Set<String> debugWords = new LinkedHashSet<>();
        Set<String> debugPairs = new LinkedHashSet<>();
        Messages.send(sender, "开始通过 DeepSeek 生成 " + count
                + " 组测试词；结果会逐组显示，批次内不会重复。");
        requestDebugWord(sender, ai, count, 1, 1, debugWords, debugPairs);
    }

    private void requestDebugWord(CommandSender sender,
                                  UndercoverGameSettings.AiSettings ai,
                                  int total, int index, int attempt,
                                  Set<String> debugWords, Set<String> debugPairs) {
        wordProvider.request(ai, Set.copyOf(debugWords), Set.copyOf(debugPairs), index)
                .whenComplete((pair, error) -> {
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null || pair == null) {
                            if (attempt < 3) {
                                requestDebugWord(sender, ai, total, index, attempt + 1,
                                        debugWords, debugPairs);
                                return;
                            }
                            debugGenerationRunning = false;
                            String reason = conciseAiError(error);
                            plugin.getLogger().warning(
                                    "DeepSeek 调试生成第 " + index + " 组失败：" + reason);
                            Messages.send(sender, "第 " + index + " 组生成失败：" + reason
                                    + "。调试任务已停止。");
                            return;
                        }
                        debugWords.add(DeepSeekWordProvider.normalize(pair.civilianWord()));
                        debugWords.add(DeepSeekWordProvider.normalize(pair.undercoverWord()));
                        debugPairs.add(DeepSeekWordProvider.pairKey(
                                pair.civilianWord(), pair.undercoverWord()));
                        Messages.send(sender, "[" + index + "/" + total + "] 平民词："
                                + pair.civilianWord() + " ｜ 卧底词：" + pair.undercoverWord());
                        if (index >= total) {
                            debugGenerationRunning = false;
                            Messages.send(sender, "DeepSeek 测试词生成完成，共 " + total + " 组。");
                            return;
                        }
                        requestDebugWord(sender, ai, total, index + 1, 1,
                                debugWords, debugPairs);
                    });
                });
    }

    private static String conciseAiError(Throwable error) {
        if (error == null) {
            return "未返回有效词组";
        }
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause.getCause() != null)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message;
    }

    UndercoverGameMatch matchFor(UUID playerId) {
        return currentMatch != null && currentMatch.isParticipant(playerId)
                ? currentMatch : null;
    }

    boolean isCommandAllowed(String rawMessage) {
        String command = rawMessage == null ? "" : rawMessage.trim().toLowerCase(java.util.Locale.ROOT);
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        String[] parts = command.split("\\s+");
        if (parts.length < 2 || !(parts[0].equals("game") || parts[0].equals("games"))) {
            return false;
        }
        if (!(parts[1].equals("undercover") || parts[1].equals("who"))) {
            return false;
        }
        return true;
    }

    void handleQuit(Player player) {
        UUID playerId = player.getUniqueId();
        lobbyPlayers.remove(playerId);
        UndercoverGameMatch match = currentMatch;
        if (match == null || !match.isParticipant(playerId)) {
            return;
        }
        markReturnToSpawn(playerId);
        match.handleQuit(player);
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
        UndercoverGameMatch match = matchFor(player.getUniqueId());
        if (match != null && match.isRunning() && match.isEliminated(player.getUniqueId())) {
            Location location = match.spawnFor(player.getUniqueId());
            if (location != null) {
                event.setRespawnLocation(location);
            }
            Bukkit.getScheduler().runTask(plugin, () -> match.becomeSpectator(player));
            return;
        }
        if (returnToSpawn.contains(player.getUniqueId())
                && !isBusyInOtherGame(player.getUniqueId())) {
            event.setRespawnLocation(player.getWorld().getSpawnLocation());
            Bukkit.getScheduler().runTask(plugin, () -> returnPlayerToSpawn(player));
        }
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
        tntGameManager.cancelReturnToSpawn(playerId);
        if (pvpGameManager != null) {
            pvpGameManager.cancelReturnToSpawn(playerId);
        }
    }

    void returnPlayerToSpawn(Player player) {
        if (!player.isOnline() || player.isDead()) {
            return;
        }
        player.closeInventory();
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setExtraContents(
                new ItemStack[player.getInventory().getExtraContents().length]);
        player.removePotionEffect(org.bukkit.potion.PotionEffectType.SATURATION);
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        player.setFallDistance(0.0F);
        player.teleport(player.getWorld().getSpawnLocation());
        if (returnToSpawn.remove(player.getUniqueId())) {
            saveReturnState();
        }
        Messages.send(player, "你已离开谁是卧底并回到世界出生点。");
    }

    void onMatchFinished(UndercoverGameMatch match) {
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
        return boardGameManager.isBusy(playerId)
                || boardGameManager.spectatorGameFor(playerId) != null
                || randomGameManager.isBusy(playerId)
                || tntGameManager.isBusy(playerId)
                || (pvpGameManager != null && pvpGameManager.isBusy(playerId));
    }

    private int joinCapacity() {
        return Math.min(settings.maxPlayers(), resolveSpawnLocations().size());
    }

    private List<Location> resolveSpawnLocations() {
        List<Location> locations = new ArrayList<>();
        for (UndercoverGameSettings.SpawnPoint point : settings.spawnPoints()) {
            Location location = point.resolve();
            if (location != null) {
                locations.add(location);
            }
        }
        return locations;
    }

    private void logSpawnConfiguration() {
        List<Location> locations = resolveSpawnLocations();
        plugin.getLogger().info("谁是卧底已读取 " + settings.spawnPoints().size()
                + " 个重生点，其中 " + locations.size() + " 个世界有效。");
        for (int index = 0; index < locations.size(); index++) {
            Location location = locations.get(index);
            plugin.getLogger().info("谁是卧底重生点 " + (index + 1) + "："
                    + location.getWorld().getName() + " "
                    + location.getX() + ", " + location.getY() + ", " + location.getZ());
        }
    }

    private void sendLobbyMessage(String message, Player except) {
        for (UUID playerId : lobbyPlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player != except) {
                Messages.send(player, message);
            }
        }
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
                plugin.getLogger().warning("undercover-game-state.yml 中存在无效 UUID：" + value);
            }
        }
    }

    private void saveReturnState() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录，无法保存谁是卧底状态。");
            return;
        }
        YamlConfiguration state = new YamlConfiguration();
        state.set("players", returnToSpawn.stream().map(UUID::toString).toList());
        try {
            state.save(stateFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("无法保存谁是卧底状态：" + exception.getMessage());
        }
    }
}
