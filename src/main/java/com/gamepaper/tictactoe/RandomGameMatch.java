package com.gamepaper.tictactoe;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class RandomGameMatch {

    private enum State {
        PREPARING,
        COUNTDOWN,
        ACTIVE,
        ENDED
    }

    private final RandomGameManager manager;
    private final List<UUID> participants;
    private final Set<UUID> activePlayers;
    private final Map<UUID, String> playerNames = new HashMap<>();
    private final Map<UUID, Location> spawnByPlayer = new HashMap<>();
    private final List<Location> spawnLocations;
    private final Set<UUID> boundaryWarnings = new HashSet<>();
    private final Set<UUID> leftPlayers = new HashSet<>();
    private State state = State.PREPARING;
    private BukkitTask countdownTask;
    private BukkitTask itemTask;
    private BukkitTask randomEventTask;

    RandomGameMatch(RandomGameManager manager, List<UUID> participants, List<Location> spawnLocations) {
        this.manager = manager;
        this.participants = List.copyOf(participants);
        this.activePlayers = new HashSet<>(participants);
        this.spawnLocations = spawnLocations.stream().map(Location::clone).toList();
        for (UUID playerId : participants) {
            Player player = Bukkit.getPlayer(playerId);
            playerNames.put(playerId, player == null ? playerId.toString() : player.getName());
        }
        for (int index = 0; index < participants.size(); index++) {
            spawnByPlayer.put(participants.get(index), this.spawnLocations.get(index).clone());
        }
    }

    void begin() {
        for (int index = 0; index < participants.size(); index++) {
            UUID playerId = participants.get(index);
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                activePlayers.remove(playerId);
                leftPlayers.add(playerId);
                continue;
            }

            player.setGameMode(GameMode.SURVIVAL);
            player.setFlying(false);
            player.setAllowFlight(false);
            player.setFallDistance(0.0F);
            player.teleport(spawnLocations.get(index));
            Messages.send(player, "你已被分散传送到柱子上，游戏范围已经清理。");
        }

        if (activePlayers.size() < 2) {
            finish(null);
            return;
        }

        state = State.COUNTDOWN;
        sendToParticipants("游戏即将开始，倒计时 " + manager.settings().countdownSeconds()
                + " 秒。倒计时期间不能移动、操作或使用未列入白名单的指令。");
        final int[] remaining = {manager.settings().countdownSeconds()};
        countdownTask = Bukkit.getScheduler().runTaskTimer(manager.plugin(), () -> {
            if (state != State.COUNTDOWN) {
                return;
            }
            if (remaining[0] <= 0) {
                startActive();
                return;
            }
            if (remaining[0] <= 5 || remaining[0] % 5 == 0) {
                sendToActive("游戏将在 " + remaining[0] + " 秒后开始。");
            }
            remaining[0]--;
        }, 0L, 20L);
    }

    boolean isParticipant(UUID playerId) {
        return participants.contains(playerId) && !leftPlayers.contains(playerId);
    }

    boolean isActivePlayer(UUID playerId) {
        return activePlayers.contains(playerId);
    }

    boolean isEliminated(UUID playerId) {
        return participants.contains(playerId)
                && !activePlayers.contains(playerId)
                && !leftPlayers.contains(playerId);
    }

    Location gameSpawnFor(UUID playerId) {
        Location spawn = spawnByPlayer.get(playerId);
        return spawn == null ? null : spawn.clone();
    }

    void becomeSpectator(Player player) {
        if (!isEliminated(player.getUniqueId()) || state != State.ACTIVE) {
            return;
        }
        player.setGameMode(GameMode.SPECTATOR);
        Messages.send(player, "你已经失败，现在以旁观模式观战本局游戏。");
    }

    void markLeft(UUID playerId) {
        leftPlayers.add(playerId);
    }

    void leaveAsSpectator(Player player) {
        UUID playerId = player.getUniqueId();
        if (!isEliminated(playerId)) {
            return;
        }
        leftPlayers.add(playerId);
        manager.markReturnToSpawn(playerId);
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        manager.returnToSpawnIfAlive(player);
        Messages.send(player, "你已离开旁观模式并退出本局游戏。");
    }

    boolean isCountdown() {
        return state == State.COUNTDOWN;
    }

    boolean isActive() {
        return state == State.ACTIVE;
    }

    boolean isRunning() {
        return state == State.COUNTDOWN || state == State.ACTIVE;
    }

    boolean markBoundaryWarning(UUID playerId) {
        return boundaryWarnings.add(playerId);
    }

    void clearBoundaryWarning(UUID playerId) {
        boundaryWarnings.remove(playerId);
    }

    void eliminate(UUID playerId, String playerName, String reason) {
        eliminate(playerId, playerName, reason, false);
    }

    void eliminate(UUID playerId, String playerName, String reason, boolean playerIsDead) {
        if (state == State.ENDED || !activePlayers.remove(playerId)) {
            return;
        }

        playerNames.put(playerId, playerName);
        if (!playerIsDead) {
            manager.markReturnToSpawn(playerId);
        }
        if (!playerIsDead) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                manager.returnToSpawnIfAlive(player);
            }
        }
        sendToActive(playerName + " 已" + reason + "，被淘汰。");

        if (activePlayers.size() == 1) {
            finish(activePlayers.iterator().next());
        } else if (activePlayers.isEmpty()) {
            finish(null);
        }
    }

    void serverShutdown() {
        if (state == State.ENDED) {
            return;
        }
        cancelTasks();
        state = State.ENDED;
        for (UUID playerId : participants) {
            if (!leftPlayers.contains(playerId)) {
                manager.markReturnToSpawn(playerId);
            }
        }
        manager.onMatchFinished(this);
    }

    String statusText() {
        String phase = switch (state) {
            case PREPARING -> "准备中";
            case COUNTDOWN -> "倒计时";
            case ACTIVE -> "进行中";
            case ENDED -> "已结束";
        };
        return "随机物品游戏：" + phase + "，存活 " + activePlayers.size() + "/" + participants.size()
                + "，每 " + manager.settings().itemIntervalSeconds() + " 秒随机发放一个物品。";
    }

    private void startActive() {
        if (state != State.COUNTDOWN) {
            return;
        }
        cancelTask(countdownTask);
        countdownTask = null;
        state = State.ACTIVE;
        sendToActive("游戏开始！每 " + manager.settings().itemIntervalSeconds()
                + " 秒会随机获得一个生存可用物品。");
        long interval = Math.max(1L, manager.settings().itemIntervalSeconds() * 20L);
        itemTask = Bukkit.getScheduler().runTaskTimer(manager.plugin(), this::grantRandomItems, interval, interval);

        long eventDelay = Math.max(1L, manager.settings().randomEventStartDelaySeconds() * 20L);
        long eventInterval = Math.max(1L, manager.settings().randomEventIntervalSeconds() * 20L);
        randomEventTask = Bukkit.getScheduler().runTaskTimer(
                manager.plugin(), this::triggerRandomEvent, eventDelay, eventInterval);
    }

    private void grantRandomItems() {
        if (state != State.ACTIVE) {
            return;
        }
        for (UUID playerId : new HashSet<>(activePlayers)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                leftPlayers.add(playerId);
                eliminate(playerId, nameOf(playerId), "退出服务器", true);
                continue;
            }
            Material material = manager.itemPool().get(
                    ThreadLocalRandom.current().nextInt(manager.itemPool().size()));
            ItemStack item = new ItemStack(material, 1);
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            Messages.send(player, "你获得了随机物品：" + readableMaterialName(material) + "。");
        }
    }

    private void triggerRandomEvent() {
        if (state != State.ACTIVE || activePlayers.isEmpty()) {
            return;
        }

        switch (ThreadLocalRandom.current().nextInt(3)) {
            case 0 -> triggerAnvilRain();
            case 1 -> triggerRandomSupply();
            case 2 -> triggerDisaster();
            default -> throw new IllegalStateException("未知随机事件");
        }
    }

    private void triggerAnvilRain() {
        int spawned = 0;
        for (UUID playerId : new HashSet<>(activePlayers)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || player.getWorld() == null) {
                continue;
            }

            World world = player.getWorld();
            double y = Math.min(
                    player.getEyeLocation().getY() + 50.0D,
                    world.getMaxHeight() - 2.0D
            );
            if (y <= world.getMinHeight()) {
                continue;
            }

            Location location = new Location(world, player.getX(), y, player.getZ());
            FallingBlock anvil = world.spawnFallingBlock(location, Material.ANVIL.createBlockData());
            anvil.setCustomName("铁砧雨");
            anvil.setCustomNameVisible(true);
            anvil.setDropItem(true);
            anvil.setHurtEntities(true);
            spawned++;
        }
        sendToParticipants("随机事件【铁砧雨】触发！已存活玩家头顶 50 格处落下铁砧。");
        if (spawned == 0) {
            sendToParticipants("本次铁砧雨没有找到可用的存活玩家位置。");
        }
    }

    private void triggerRandomSupply() {
        int playersGiven = 0;
        for (UUID playerId : new HashSet<>(activePlayers)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                continue;
            }

            for (int index = 0; index < 5; index++) {
                Material material = manager.itemPool().get(
                        ThreadLocalRandom.current().nextInt(manager.itemPool().size()));
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack(material, 1));
                for (ItemStack leftover : leftovers.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
            playersGiven++;
        }
        sendToParticipants("随机事件【物资补给】触发！每名存活玩家获得 5 个随机物品。");
        if (playersGiven == 0) {
            sendToParticipants("本次物资补给没有找到可用的存活玩家。");
        }
    }

    private void triggerDisaster() {
        int spawned = 0;
        for (UUID playerId : new HashSet<>(activePlayers)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                continue;
            }
            Location location = findNearbyBlockForZombie(player);
            if (location != null && manager.spawnNamedZombie(location, "灾厄")) {
                spawned++;
            }
        }
        sendToParticipants("随机事件【灾厄】触发！在存活玩家周围生成了命名为“灾厄”的僵尸。");
        if (spawned == 0) {
            sendToParticipants("本次灾厄没有找到可用的方块位置。");
        }
    }

    private Location findNearbyBlockForZombie(Player player) {
        Location playerLocation = player.getLocation();
        World world = player.getWorld();
        int baseX = playerLocation.getBlockX();
        int baseY = playerLocation.getBlockY();
        int baseZ = playerLocation.getBlockZ();
        List<Location> candidates = new ArrayList<>();

        int minY = Math.max(world.getMinHeight(), baseY - 3);
        int maxY = Math.min(world.getMaxHeight() - 2, baseY + 3);
        for (int x = baseX - 2; x <= baseX + 2; x++) {
            for (int z = baseZ - 2; z <= baseZ + 2; z++) {
                int offsetX = x - baseX;
                int offsetZ = z - baseZ;
                if (offsetX * offsetX + offsetZ * offsetZ > 4) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    Block feet = world.getBlockAt(x, y + 1, z);
                    Block head = world.getBlockAt(x, y + 2, z);
                    if (!block.getType().isSolid()
                            || !feet.getType().isAir()
                            || !head.getType().isAir()) {
                        continue;
                    }
                    Location spawn = new Location(world, x + 0.5D, y + 1.0D, z + 0.5D);
                    if (manager.settings().boundary().contains(spawn)) {
                        candidates.add(spawn);
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private void finish(UUID winnerId) {
        if (state == State.ENDED) {
            return;
        }
        cancelTasks();
        state = State.ENDED;
        manager.onMatchFinished(this);

        for (UUID playerId : participants) {
            if (leftPlayers.contains(playerId)) {
                continue;
            }
            manager.markReturnToSpawn(playerId);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                manager.returnToSpawnIfAlive(player);
            }
        }

        if (winnerId == null) {
            sendToParticipants("随机物品游戏结束：没有存活者，游戏平局。");
            return;
        }

        String winnerName = nameOf(winnerId);
        sendToParticipants("随机物品游戏结束！最后存活者是 " + winnerName + "。");
    }

    private void cancelTasks() {
        cancelTask(countdownTask);
        cancelTask(itemTask);
        cancelTask(randomEventTask);
        countdownTask = null;
        itemTask = null;
        randomEventTask = null;
    }

    private static void cancelTask(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private void sendToParticipants(String message) {
        for (UUID playerId : participants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                Messages.send(player, message);
            }
        }
    }

    private void sendToActive(String message) {
        for (UUID playerId : new HashSet<>(activePlayers)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                Messages.send(player, message);
            }
        }
    }

    private String nameOf(UUID playerId) {
        return playerNames.getOrDefault(playerId, "未知玩家");
    }

    private static String readableMaterialName(Material material) {
        String name = material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return name.isEmpty() ? material.name() : name;
    }
}
