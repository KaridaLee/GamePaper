package com.gamepaper.tictactoe;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class TntGameMatch {

    private enum State {
        PREPARING,
        ROUND,
        COOLDOWN,
        ENDED
    }

    private final TntGameManager manager;
    private final List<UUID> participants;
    private final Set<UUID> activePlayers = new HashSet<>();
    private final Map<UUID, String> playerNames = new HashMap<>();
    private final Map<UUID, Location> spawnByPlayer = new HashMap<>();
    private final Map<UUID, Location> deathLocations = new HashMap<>();
    private final Map<UUID, PotionEffect> previousGlowEffects = new HashMap<>();
    private final Map<UUID, PotionEffect> previousSaturationEffects = new HashMap<>();
    private final Map<UUID, PotionEffect> previousSpeedEffects = new HashMap<>();
    private final Set<UUID> leftPlayers = new HashSet<>();
    private final Set<UUID> pendingDetonations = new HashSet<>();
    private final List<Location> spawnLocations;
    private State state = State.PREPARING;
    private UUID holderId;
    private int remainingSeconds;
    private BukkitTask preparationTask;
    private BukkitTask roundTask;
    private BukkitTask cooldownTask;
    private BukkitTask visualTask;

    TntGameMatch(TntGameManager manager, List<UUID> participants, List<Location> spawnLocations) {
        this.manager = manager;
        this.participants = List.copyOf(participants);
        this.activePlayers.addAll(participants);
        this.spawnLocations = spawnLocations.stream().map(Location::clone).toList();
        for (int index = 0; index < participants.size(); index++) {
            UUID playerId = participants.get(index);
            Player player = Bukkit.getPlayer(playerId);
            playerNames.put(playerId, player == null ? playerId.toString() : player.getName());
            spawnByPlayer.put(playerId, this.spawnLocations.get(index).clone());
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
            player.getInventory().clear();
            player.getInventory().setArmorContents(new ItemStack[4]);
            player.getInventory().setExtraContents(new ItemStack[player.getInventory().getExtraContents().length]);
            player.setFallDistance(0.0F);
            player.teleport(spawnLocations.get(index));
            Messages.send(player, "你已进入 TNT 烫手山芋游戏，准备倒计时期间可以自由移动。" );
        }

        if (activePlayers.size() < 2) {
            finish(null);
            return;
        }

        state = State.PREPARING;
        visualTask = Bukkit.getScheduler().runTaskTimer(manager.plugin(), this::refreshVisuals, 0L, 20L);
        sendToParticipants("TNT 游戏将在 " + manager.settings().startCountdownSeconds()
                + " 秒后开始，准备期间不能破坏或放置方块。" );
        final int[] remaining = {manager.settings().startCountdownSeconds()};
        preparationTask = Bukkit.getScheduler().runTaskTimer(manager.plugin(), () -> {
            if (state != State.PREPARING) {
                return;
            }
            if (remaining[0] <= 0) {
                startRound();
                return;
            }
            if (remaining[0] <= 5 || remaining[0] % 5 == 0) {
                sendToActive("TNT 游戏将在 " + remaining[0] + " 秒后开始。" );
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

    boolean isRunning() {
        return state == State.PREPARING || state == State.ROUND || state == State.COOLDOWN;
    }

    boolean isRoundActive() {
        return state == State.ROUND;
    }

    boolean markBoundaryWarning(UUID playerId) {
        return boundaryWarnings.add(playerId);
    }

    void clearBoundaryWarning(UUID playerId) {
        boundaryWarnings.remove(playerId);
    }

    private final Set<UUID> boundaryWarnings = new HashSet<>();

    boolean transferTnt(Player attacker, Player target) {
        if (state != State.ROUND || holderId == null
                || !holderId.equals(attacker.getUniqueId())
                || !activePlayers.contains(target.getUniqueId())
                || !isHoldingTnt(attacker)) {
            return false;
        }

        attacker.getInventory().remove(Material.TNT);
        removeSpeed(attacker);
        target.getInventory().remove(Material.TNT);
        target.getInventory().setItemInMainHand(new ItemStack(Material.TNT, 1));
        holderId = target.getUniqueId();
        applySpeed(target);
        sendToParticipants(attacker.getName() + " 将 TNT 传给了 " + target.getName() + "！剩余 "
                + remainingSeconds + " 秒。" );
        return true;
    }

    void eliminateOnDeath(Player player) {
        UUID playerId = player.getUniqueId();
        String reason = pendingDetonations.remove(playerId) ? "TNT 爆炸" : "死亡";
        eliminateInternal(playerId, player.getName(), reason, true, player.getLocation(), false);
    }

    void eliminateForLeave(UUID playerId, String playerName, String reason) {
        eliminateInternal(playerId, playerName, reason, false, null, true);
    }

    void eliminateForQuit(UUID playerId, String playerName, String reason) {
        eliminateInternal(playerId, playerName, reason, false, null, false);
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
        removeGlow(player);
        removeSaturation(player);
        removeSpeed(player);
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        manager.returnToSpawnIfAlive(player);
        Messages.send(player, "你已离开旁观模式并退出本局 TNT 游戏。" );
    }

    void becomeSpectator(Player player) {
        if (!isEliminated(player.getUniqueId()) || state == State.ENDED) {
            return;
        }
        removeGlow(player);
        player.setGameMode(GameMode.SPECTATOR);
        Messages.send(player, "你已经失败，现在以旁观模式观战本局 TNT 游戏。" );
    }

    Location spectatorLocationFor(UUID playerId) {
        Location death = deathLocations.get(playerId);
        if (death != null) {
            return death.clone();
        }
        Location spawn = spawnByPlayer.get(playerId);
        return spawn == null ? null : spawn.clone();
    }

    String statusText() {
        String phase = switch (state) {
            case PREPARING -> "准备倒计时";
            case ROUND -> "追逐中";
            case COOLDOWN -> "冷却中";
            case ENDED -> "已结束";
        };
        String holder = holderId == null ? "无" : nameOf(holderId);
        return "TNT 烫手山芋：" + phase + "，存活 " + activePlayers.size() + "/" + participants.size()
                + "，当前持有者：" + holder + "。";
    }

    void serverShutdown() {
        if (state == State.ENDED) {
            return;
        }
        cancelTasks();
        state = State.ENDED;
        for (UUID playerId : participants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                removeGlow(player);
                removeSaturation(player);
                removeSpeed(player);
            }
        }
        for (UUID playerId : participants) {
            if (leftPlayers.contains(playerId)) {
                continue;
            }
            manager.markReturnToSpawn(playerId);
        }
        manager.onMatchFinished(this);
    }

    private void startRound() {
        if (state == State.ENDED) {
            return;
        }
        cancelTask(preparationTask);
        preparationTask = null;
        cancelTask(cooldownTask);
        cooldownTask = null;

        if (activePlayers.size() <= 1) {
            finish(activePlayers.isEmpty() ? null : activePlayers.iterator().next());
            return;
        }

        List<UUID> candidates = new ArrayList<>();
        for (UUID playerId : new HashSet<>(activePlayers)) {
            if (Bukkit.getPlayer(playerId) != null) {
                candidates.add(playerId);
            } else {
                leftPlayers.add(playerId);
                eliminateForQuit(playerId, nameOf(playerId), "退出服务器");
            }
        }
        if (state == State.ENDED || activePlayers.size() <= 1) {
            return;
        }
        if (candidates.isEmpty()) {
            finish(null);
            return;
        }

        holderId = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        state = State.ROUND;
        remainingSeconds = manager.settings().roundSeconds();
        Player holder = Bukkit.getPlayer(holderId);
        if (holder == null) {
            eliminateForQuit(holderId, nameOf(holderId), "退出服务器");
            return;
        }
        giveTnt(holder);
        sendToParticipants("TNT 已随机交给 " + holder.getName() + "！本轮倒计时 "
                + remainingSeconds + " 秒，攻击其他玩家即可传递 TNT。" );

        roundTask = Bukkit.getScheduler().runTaskTimer(manager.plugin(), this::tickRound, 20L, 20L);
    }

    private void tickRound() {
        if (state != State.ROUND || holderId == null) {
            return;
        }
        Player holder = Bukkit.getPlayer(holderId);
        if (holder == null) {
            leftPlayers.add(holderId);
            eliminateForQuit(holderId, nameOf(holderId), "退出服务器");
            return;
        }
        if (!isHoldingTnt(holder)) {
            giveTnt(holder);
        }

        remainingSeconds--;
        if (remainingSeconds <= 5 || remainingSeconds == 10) {
            sendToParticipants("TNT 爆炸倒计时：" + Math.max(remainingSeconds, 0) + " 秒。" );
        }
        if (remainingSeconds <= 0) {
            detonateHolder(holder);
        }
    }

    private void refreshVisuals() {
        if (!isRunning()) {
            return;
        }

        for (UUID playerId : participants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline() && isParticipant(playerId)) {
                applySaturation(player);
            }
        }

        for (UUID playerId : new HashSet<>(activePlayers)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline() && !player.isDead()) {
                applyGlow(player);
            }
        }

        if (state == State.ROUND && holderId != null) {
            Player holder = Bukkit.getPlayer(holderId);
            if (holder != null && holder.isOnline() && !holder.isDead()) {
                applySpeed(holder);
                spawnHolderFirework(holder);
            }
        }
    }

    private void applyGlow(Player player) {
        UUID playerId = player.getUniqueId();
        if (!previousGlowEffects.containsKey(playerId)) {
            previousGlowEffects.put(playerId, player.getPotionEffect(PotionEffectType.GLOWING));
        }
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.GLOWING, 40, 0, false, false, false));
    }

    private void removeGlow(Player player) {
        PotionEffect previous = previousGlowEffects.remove(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.GLOWING);
        if (previous != null) {
            player.addPotionEffect(previous);
        }
    }

    private void applySaturation(Player player) {
        UUID playerId = player.getUniqueId();
        if (!previousSaturationEffects.containsKey(playerId)) {
            previousSaturationEffects.put(playerId,
                    player.getPotionEffect(PotionEffectType.SATURATION));
        }
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SATURATION, 40, 0, false, false, false));
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
    }

    private void removeSaturation(Player player) {
        PotionEffect previous = previousSaturationEffects.remove(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.SATURATION);
        if (previous != null) {
            player.addPotionEffect(previous);
        }
    }

    private void applySpeed(Player player) {
        UUID playerId = player.getUniqueId();
        if (!previousSpeedEffects.containsKey(playerId)) {
            previousSpeedEffects.put(playerId, player.getPotionEffect(PotionEffectType.SPEED));
        }
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SPEED, 40, 0, false, false, false));
    }

    private void removeSpeed(Player player) {
        PotionEffect previous = previousSpeedEffects.remove(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.SPEED);
        if (previous != null) {
            player.addPotionEffect(previous);
        }
    }

    private void spawnHolderFirework(Player holder) {
        Location location = holder.getLocation().clone().add(0.0D, 0.2D, 0.0D);
        holder.getWorld().spawnParticle(
                Particle.EXPLOSION_EMITTER, location, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        holder.getWorld().spawnParticle(
                Particle.FIREWORK, location, 18, 0.45D, 0.45D, 0.45D, 0.08D);
        holder.getWorld().spawnParticle(
                Particle.FLAME, location, 18, 0.35D, 0.35D, 0.35D, 0.02D);
        holder.getWorld().spawnParticle(
                Particle.END_ROD, location, 12, 0.5D, 0.7D, 0.5D, 0.03D);
    }

    private void detonateHolder(Player holder) {
        if (state != State.ROUND || holderId == null) {
            return;
        }
        UUID playerId = holder.getUniqueId();
        pendingDetonations.add(playerId);
        holder.getInventory().remove(Material.TNT);
        World world = holder.getWorld();
        Location location = holder.getLocation();
        world.createExplosion(location, 0.0F, false, false);
        sendToParticipants(holder.getName() + " 手中的 TNT 爆炸了！" );
        if (!holder.isDead()) {
            holder.setHealth(0.0D);
        }
    }

    private void startCooldown() {
        if (state == State.ENDED || activePlayers.size() <= 1) {
            finish(activePlayers.isEmpty() ? null : activePlayers.iterator().next());
            return;
        }
        state = State.COOLDOWN;
        cancelTask(roundTask);
        roundTask = null;
        int seconds = manager.settings().cooldownSeconds();
        sendToParticipants("本轮结束，" + seconds + " 秒冷却后将从存活玩家中重新随机选择 TNT 持有者。" );
        long delay = Math.max(1L, seconds * 20L);
        cooldownTask = Bukkit.getScheduler().runTaskLater(manager.plugin(), this::startRound, delay);
    }

    private void eliminateInternal(UUID playerId, String playerName, String reason,
                                   boolean dead, Location deathLocation, boolean returnOnline) {
        if (state == State.ENDED || !activePlayers.remove(playerId)) {
            return;
        }

        playerNames.put(playerId, playerName);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            removeGlow(player);
            removeSpeed(player);
            if (!dead) {
                removeSaturation(player);
            }
        }
        boolean wasHolder = playerId.equals(holderId);
        if (wasHolder) {
            holderId = null;
            cancelTask(roundTask);
            roundTask = null;
        }
        if (dead) {
            if (deathLocation != null) {
                deathLocations.put(playerId, deathLocation.clone());
            }
        } else if (returnOnline) {
            manager.markReturnToSpawn(playerId);
        }

        sendToParticipants(playerName + " 已" + reason + "，被淘汰。" );
        if (activePlayers.size() <= 1) {
            finish(activePlayers.isEmpty() ? null : activePlayers.iterator().next());
            return;
        }
        if (wasHolder) {
            startCooldown();
        }
        if (!dead && returnOnline) {
            if (player != null && player.isOnline()) {
                manager.returnToSpawnIfAlive(player);
            }
        }
    }

    private void giveTnt(Player player) {
        for (UUID playerId : activePlayers) {
            Player activePlayer = Bukkit.getPlayer(playerId);
            if (activePlayer != null) {
                activePlayer.getInventory().remove(Material.TNT);
                if (!playerId.equals(player.getUniqueId())) {
                    removeSpeed(activePlayer);
                }
            }
        }
        player.getInventory().setItemInMainHand(new ItemStack(Material.TNT, 1));
        applySpeed(player);
    }

    private static boolean isHoldingTnt(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        return isTnt(mainHand) || isTnt(offHand);
    }

    private static boolean isTnt(ItemStack item) {
        return item != null && item.getType() == Material.TNT && item.getAmount() > 0;
    }

    private void finish(UUID winnerId) {
        if (state == State.ENDED) {
            return;
        }
        cancelTasks();
        state = State.ENDED;
        holderId = null;
        manager.onMatchFinished(this);

        if (winnerId == null) {
            sendToParticipants("TNT 烫手山芋游戏结束：没有存活者，游戏平局。" );
        } else {
            sendToParticipants("TNT 烫手山芋游戏结束！最后存活者是 " + nameOf(winnerId) + "。" );
        }

        for (UUID playerId : participants) {
            if (leftPlayers.contains(playerId)) {
                continue;
            }
            manager.markReturnToSpawn(playerId);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                removeGlow(player);
                removeSaturation(player);
                removeSpeed(player);
                manager.returnToSpawnIfAlive(player);
            }
        }
    }

    private void cancelTasks() {
        cancelTask(preparationTask);
        cancelTask(roundTask);
        cancelTask(cooldownTask);
        cancelTask(visualTask);
        preparationTask = null;
        roundTask = null;
        cooldownTask = null;
        visualTask = null;
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
}
