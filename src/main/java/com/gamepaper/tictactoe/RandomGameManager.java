package com.gamepaper.tictactoe;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class RandomGameManager {

    private static final long MAX_CLEAR_VOLUME = 2_000_000L;

    private final JavaPlugin plugin;
    private final GomokuManager otherGameManager;
    private final RandomGameSettings settings;
    private List<Material> itemPool;
    private final LinkedHashSet<UUID> lobbyPlayers = new LinkedHashSet<>();
    private final Set<UUID> returnToSpawn = new LinkedHashSet<>();
    private final Set<SpawnKey> permittedNamedCreatureSpawns = new HashSet<>();
    private final File stateFile;
    private TntGameManager tntGameManager;
    private UndercoverGameManager undercoverGameManager;
    private PvpGameManager pvpGameManager;
    private RandomGameMatch currentMatch;

    RandomGameManager(JavaPlugin plugin, GomokuManager otherGameManager) {
        this.plugin = plugin;
        this.otherGameManager = otherGameManager;
        this.settings = new RandomGameSettings(plugin);
        this.itemPool = buildItemPool();
        this.stateFile = new File(plugin.getDataFolder(), "random-game-state.yml");
        loadReturnState();
    }

    RandomGameSettings settings() {
        return settings;
    }

    JavaPlugin plugin() {
        return plugin;
    }

    List<Material> itemPool() {
        return itemPool;
    }

    void setTntGameManager(TntGameManager tntGameManager) {
        this.tntGameManager = tntGameManager;
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
        itemPool = buildItemPool();
    }

    boolean join(Player player) {
        if (currentMatch != null) {
            Messages.send(player, "随机物品游戏已经开始，当前不能加入。");
            return false;
        }
        if (lobbyPlayers.contains(player.getUniqueId())) {
            Messages.send(player, "你已经在随机物品游戏等待区了。");
            return false;
        }
        if (isBusyInOtherGame(player.getUniqueId())) {
            Messages.send(player, "你正在进行另一局小游戏，不能加入随机物品游戏。");
            return false;
        }
        int capacity = joinCapacity();
        if (capacity < 2) {
            Messages.send(player, "随机物品游戏至少需要配置 2 个有效重生点。");
            return false;
        }
        if (lobbyPlayers.size() >= capacity) {
            Messages.send(player, "随机物品游戏等待区已满（最多 " + capacity + " 人）。");
            return false;
        }
        if (!inventoryIsEmpty(player)) {
            Messages.send(player, "加入随机物品游戏前，背包、盔甲栏和副手必须全部为空。");
            return false;
        }

        cancelPendingReturns(player.getUniqueId());
        lobbyPlayers.add(player.getUniqueId());
        sendLobbyMessage(player.getName() + " 加入了随机物品游戏（"
                + lobbyPlayers.size() + "/" + capacity + "）。输入 /game random start 开始。", player);
        if (lobbyPlayers.size() >= capacity) {
            startInternal();
        }
        return true;
    }

    boolean start(Player requester) {
        if (currentMatch != null) {
            Messages.send(requester, "随机物品游戏已经开始了。");
            return false;
        }
        if (requester != null && !lobbyPlayers.contains(requester.getUniqueId())) {
            Messages.send(requester, "你需要先使用 /game random join 加入等待区。");
            return false;
        }
        return startInternal();
    }

    private boolean startInternal() {
        if (lobbyPlayers.size() < 2) {
            sendLobbyMessage("随机物品游戏至少需要 2 名玩家才能开始。", null);
            return false;
        }
        if (itemPool.isEmpty()) {
            sendLobbyMessage("随机物品池为空，请检查 excluded-materials 配置。", null);
            return false;
        }

        List<Location> spawnLocations = resolveSpawnLocations();
        if (spawnLocations.size() < lobbyPlayers.size()) {
            sendLobbyMessage("有效重生点数量不足，无法开始随机物品游戏。", null);
            return false;
        }
        if (settings.boundary().resolveWorld() == null) {
            sendLobbyMessage("游戏范围配置的世界不存在：" + settings.boundary().worldName(), null);
            return false;
        }
        for (UUID playerId : lobbyPlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !inventoryIsEmpty(player)) {
                sendLobbyMessage("有玩家不在线或背包已经不为空，游戏没有开始。", null);
                return false;
            }
        }
        if (!clearConfiguredArea()) {
            sendLobbyMessage("清理游戏范围失败，游戏没有开始。请检查 boundary 配置。", null);
            return false;
        }

        List<UUID> players = new ArrayList<>(lobbyPlayers);
        lobbyPlayers.clear();
        currentMatch = new RandomGameMatch(this, players, spawnLocations);
        currentMatch.begin();
        return true;
    }

    boolean leave(Player player) {
        UUID playerId = player.getUniqueId();
        if (currentMatch != null && currentMatch.isParticipant(playerId)) {
            if (currentMatch.isActivePlayer(playerId)) {
                currentMatch.markLeft(playerId);
                currentMatch.eliminate(playerId, player.getName(), "退出游戏");
            } else if (currentMatch.isEliminated(playerId)) {
                currentMatch.leaveAsSpectator(player);
            } else {
                Messages.send(player, "你已经在本局随机物品游戏中失败了。");
            }
            return true;
        }
        if (lobbyPlayers.remove(playerId)) {
            sendLobbyMessage(player.getName() + " 离开了随机物品游戏等待区。", player);
            Messages.send(player, "你已离开随机物品游戏等待区。");
            return true;
        }
        Messages.send(player, "你当前没有参加随机物品游戏。");
        return false;
    }

    void sendStatus(Player player) {
        if (currentMatch != null) {
            Messages.send(player, currentMatch.statusText());
            return;
        }
        if (lobbyPlayers.isEmpty()) {
            Messages.send(player, "随机物品游戏当前没有等待中的玩家。");
            return;
        }
        Messages.send(player, "随机物品游戏等待区：" + lobbyPlayers.size() + "/" + joinCapacity() + " 人。");
    }

    RandomGameMatch matchFor(UUID playerId) {
        return currentMatch != null && currentMatch.isParticipant(playerId) ? currentMatch : null;
    }

    boolean isRunningAt(Location location) {
        return currentMatch != null && currentMatch.isRunning()
                && settings.boundary().contains(location);
    }

    boolean isNamedCreatureSpawnAllowed(Location location) {
        SpawnKey key = SpawnKey.from(location);
        return key != null && permittedNamedCreatureSpawns.contains(key);
    }

    boolean spawnNamedZombie(Location location, String name) {
        SpawnKey key = SpawnKey.from(location);
        if (key == null || location.getWorld() == null) {
            return false;
        }

        permittedNamedCreatureSpawns.add(key);
        try {
            Entity entity = location.getWorld().spawnEntity(location, EntityType.ZOMBIE);
            if (entity instanceof Zombie zombie) {
                zombie.setCustomName(name);
                zombie.setCustomNameVisible(true);
                zombie.setRemoveWhenFarAway(false);
                return true;
            }
            return false;
        } finally {
            permittedNamedCreatureSpawns.remove(key);
        }
    }

    boolean isCommandAllowed(String rawMessage) {
        String command = rawMessage == null ? "" : rawMessage.trim();
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isEmpty()) {
            return false;
        }
        String root = command.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (!settings.commandWhitelist().contains(root)) {
            return false;
        }
        if (root.equals("ttt") || root.equals("gomoku") || root.equals("gomuku")) {
            return false;
        }
        if (!root.equals("game")) {
            return true;
        }

        String[] parts = command.toLowerCase(java.util.Locale.ROOT).split("\\s+");
        if (parts.length < 2 || !(parts[1].equals("random") || parts[1].equals("pillar"))) {
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
        RandomGameMatch match = currentMatch;
        if (match != null && match.isParticipant(playerId)
                && match.isActivePlayer(playerId)) {
            cancelReturnToSpawn(playerId);
            match.markLeft(playerId);
            match.eliminate(playerId, player.getName(), "退出服务器", true);
            return;
        }
        if (match != null && match.isParticipant(playerId)
                && match.isEliminated(playerId)) {
            cancelReturnToSpawn(playerId);
            match.markLeft(playerId);
            return;
        }
        lobbyPlayers.remove(playerId);
    }

    void handleJoin(Player player) {
        if (!returnToSpawn.contains(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            UUID playerId = player.getUniqueId();
            if (returnToSpawn.contains(playerId)
                    && !isBusy(playerId) && !isBusyInOtherGame(playerId)) {
                returnPlayerToSpawn(player);
            }
        });
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
        if (tntGameManager != null) {
            tntGameManager.cancelReturnToSpawn(playerId);
        }
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

    void onMatchFinished(RandomGameMatch match) {
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
                || (tntGameManager != null && tntGameManager.isBusy(playerId))
                || (undercoverGameManager != null && undercoverGameManager.isBusy(playerId))
                || (pvpGameManager != null && pvpGameManager.isBusy(playerId));
    }

    private int joinCapacity() {
        return Math.min(settings.maxPlayers(), settings.spawnPoints().size());
    }

    private List<Location> resolveSpawnLocations() {
        List<Location> locations = new ArrayList<>();
        for (RandomGameSettings.SpawnPoint point : settings.spawnPoints()) {
            Location location = point.resolve();
            if (location == null || !settings.boundary().contains(location)) {
                continue;
            }
            locations.add(location);
        }
        return locations;
    }

    private boolean clearConfiguredArea() {
        RandomGameSettings.Boundary boundary = settings.boundary();
        World world = boundary.resolveWorld();
        if (world == null) {
            return false;
        }
        int minY = Math.max(world.getMinHeight(), boundary.minY());
        int maxY = Math.min(world.getMaxHeight() - 1, boundary.maxY());
        if (minY > maxY) {
            return false;
        }

        long volume = (long) (boundary.maxX() - boundary.minX() + 1)
                * (maxY - minY + 1)
                * (boundary.maxZ() - boundary.minZ() + 1);
        if (volume <= 0 || volume > MAX_CLEAR_VOLUME) {
            plugin.getLogger().warning("random-game boundary 清理体积过大：" + volume
                    + "，上限为 " + MAX_CLEAR_VOLUME + "。");
            return false;
        }

        for (int x = boundary.minX(); x <= boundary.maxX(); x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = boundary.minZ(); z <= boundary.maxZ(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() != Material.OBSIDIAN && !block.getType().isAir()) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
        for (Entity entity : new ArrayList<>(world.getEntities())) {
            if (!(entity instanceof Player)
                    && boundary.contains(entity.getLocation())
                    && !hasCustomName(entity)) {
                entity.remove();
            }
        }
        return true;
    }

    private static boolean hasCustomName(Entity entity) {
        String name = entity.getCustomName();
        return name != null && !name.isBlank();
    }

    private List<Material> buildItemPool() {
        List<Material> materials = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.isItem() && !settings.isExcluded(material)) {
                materials.add(material);
            }
        }
        return Collections.unmodifiableList(materials);
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
                plugin.getLogger().warning("random-game-state.yml 中存在无效 UUID：" + value);
            }
        }
    }

    private void saveReturnState() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录，无法保存随机物品游戏状态。");
            return;
        }
        YamlConfiguration state = new YamlConfiguration();
        state.set("players", returnToSpawn.stream().map(UUID::toString).toList());
        try {
            state.save(stateFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("无法保存随机物品游戏状态：" + exception.getMessage());
        }
    }

    static boolean inventoryIsEmpty(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (!isEmpty(item)) {
                return false;
            }
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (!isEmpty(item)) {
                return false;
            }
        }
        for (ItemStack item : player.getInventory().getExtraContents()) {
            if (!isEmpty(item)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private record SpawnKey(UUID worldId, int x, int y, int z) {

        private static SpawnKey from(Location location) {
            if (location == null || location.getWorld() == null) {
                return null;
            }
            return new SpawnKey(
                    location.getWorld().getUID(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
        }
    }
}
