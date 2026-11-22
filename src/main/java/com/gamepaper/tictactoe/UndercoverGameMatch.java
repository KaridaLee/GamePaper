package com.gamepaper.tictactoe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class UndercoverGameMatch {

    private static final int DESCRIPTION_PAGE_SIZE = 45;
    private static final int VIEWER_BOOK_SLOT = 8;
    private static final int WRITING_BOOK_SLOT = 0;
    private static final int VOTE_OPENER_SLOT = 7;
    private static final int REVIEW_SECONDS = 30;
    private static final int AI_ATTEMPTS = 3;
    private static final List<UndercoverGameSettings.WordPair> EMERGENCY_WORD_PAIRS = List.of(
            new UndercoverGameSettings.WordPair("香蕉", "狗粑粑"),
            new UndercoverGameSettings.WordPair("棉花糖", "云朵"),
            new UndercoverGameSettings.WordPair("耳机", "听诊器"),
            new UndercoverGameSettings.WordPair("甜甜圈", "救生圈"),
            new UndercoverGameSettings.WordPair("牙膏", "奶油"),
            new UndercoverGameSettings.WordPair("马桶刷", "仙女棒"),
            new UndercoverGameSettings.WordPair("路灯", "向日葵"),
            new UndercoverGameSettings.WordPair("章鱼", "拖把"),
            new UndercoverGameSettings.WordPair("方便面", "鸟巢"),
            new UndercoverGameSettings.WordPair("口红", "蜡笔"),
            new UndercoverGameSettings.WordPair("泡泡糖", "橡皮泥"),
            new UndercoverGameSettings.WordPair("月饼", "井盖"),
            new UndercoverGameSettings.WordPair("奶茶", "泥浆"),
            new UndercoverGameSettings.WordPair("鸡腿", "火炬"),
            new UndercoverGameSettings.WordPair("薯条", "铅笔"),
            new UndercoverGameSettings.WordPair("西兰花", "小树"),
            new UndercoverGameSettings.WordPair("饺子", "耳朵"),
            new UndercoverGameSettings.WordPair("棒棒糖", "交通标志"),
            new UndercoverGameSettings.WordPair("洗衣机", "时光机")
    );

    private final UndercoverGameManager manager;
    private final List<UUID> participants;
    private final LinkedHashSet<UUID> activePlayers;
    private final Set<UUID> eliminatedPlayers = new HashSet<>();
    private final Set<UUID> leftPlayers = new HashSet<>();
    private final Set<String> usedWords = new LinkedHashSet<>();
    private final Set<String> usedPairs = new LinkedHashSet<>();
    private final Map<UUID, String> playerNames = new LinkedHashMap<>();
    private final Map<UUID, Location> playerSpawns = new HashMap<>();
    private final Map<UUID, Scoreboard> previousScoreboards = new HashMap<>();
    private final Map<UUID, PotionEffect> previousSaturationEffects = new HashMap<>();
    private final List<ItemStack> descriptions = new ArrayList<>();
    private final Map<UUID, UUID> votes = new HashMap<>();
    private final NamespacedKey viewerBookKey;
    private final NamespacedKey writingBookKey;
    private final NamespacedKey voteOpenerKey;
    private final NamespacedKey voteTargetKey;

    private State state = State.PREPARING;
    private UUID undercoverId;
    private String civilianWord;
    private String undercoverWord;
    private int roundNumber;
    private List<UUID> descriptionOrder = List.of();
    private int descriptionIndex;
    private UUID currentDescriber;
    private int currentRoundDescriptionStart;
    private int remainingDescriptionSeconds;
    private int remainingReviewSeconds;
    private int remainingVotingSeconds;
    private boolean finishingVote;
    private BukkitTask effectTask;
    private BukkitTask descriptionTask;
    private BukkitTask reviewTask;
    private BukkitTask votingTask;

    UndercoverGameMatch(UndercoverGameManager manager, List<UUID> players,
                        List<Location> spawnLocations) {
        this.manager = manager;
        this.participants = List.copyOf(players);
        this.activePlayers = new LinkedHashSet<>(players);
        this.viewerBookKey = new NamespacedKey(manager.plugin(), "undercover_viewer_book");
        this.writingBookKey = new NamespacedKey(manager.plugin(), "undercover_writing_book");
        this.voteOpenerKey = new NamespacedKey(manager.plugin(), "undercover_vote_opener");
        this.voteTargetKey = new NamespacedKey(manager.plugin(), "undercover_vote_target");

        for (int index = 0; index < players.size(); index++) {
            UUID playerId = players.get(index);
            Player player = Bukkit.getPlayer(playerId);
            playerNames.put(playerId, player == null ? "未知玩家" : player.getName());
            playerSpawns.put(playerId, spawnLocations.get(index).clone());
        }
    }

    void begin() {
        undercoverId = participants.get(ThreadLocalRandom.current().nextInt(participants.size()));

        for (UUID playerId : participants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                leftPlayers.add(playerId);
                eliminatedPlayers.add(playerId);
                activePlayers.remove(playerId);
                manager.markReturnToSpawn(playerId);
                continue;
            }
            preparePlayer(player);
        }
        if (activePlayers.size() < 3) {
            finish(null, "上线玩家不足，游戏已取消。");
            return;
        }

        state = State.PREPARING;
        sendToParticipants("谁是卧底正在准备本轮词语，请稍候。" );
        Bukkit.getScheduler().runTask(manager.plugin(), this::reassertConfiguredSpawns);
        effectTask = Bukkit.getScheduler().runTaskTimer(
                manager.plugin(), this::refreshPlayerEffects, 0L, 20L);
        requestWordsAndStartRound();
    }

    boolean isParticipant(UUID playerId) {
        return participants.contains(playerId);
    }

    boolean isActivePlayer(UUID playerId) {
        return activePlayers.contains(playerId);
    }

    boolean isEliminated(UUID playerId) {
        return eliminatedPlayers.contains(playerId);
    }

    boolean isRunning() {
        return state != State.ENDED;
    }

    boolean isCurrentDescriber(UUID playerId) {
        return state == State.DESCRIBING && playerId.equals(currentDescriber);
    }

    Location spawnFor(UUID playerId) {
        Location location = playerSpawns.get(playerId);
        return location == null ? null : location.clone();
    }

    String statusText() {
        return "谁是卧底正在进行：第 " + Math.max(roundNumber, 1) + " 轮，存活 "
                + activePlayers.size() + "/" + participants.size() + " 人，阶段："
                + switch (state) {
                    case PREPARING -> "准备";
                    case DESCRIBING -> "说明（剩余 " + remainingDescriptionSeconds + " 秒）";
                    case REVIEWING -> "查看说明（剩余 " + remainingReviewSeconds + " 秒）";
                    case VOTING -> "投票（剩余 " + remainingVotingSeconds + " 秒）";
                    case ENDED -> "已结束";
                } + "。";
    }

    void submitDescription(Player player, BookMeta submittedMeta) {
        if (!isCurrentDescriber(player.getUniqueId())) {
            return;
        }
        cancelDescriptionTimer();
        BookMeta signedMeta = submittedMeta.clone();
        signedMeta.setAuthor(player.getName());
        signedMeta.setTitle("第" + roundNumber + "轮 - " + player.getName());
        if (signedMeta.getPages().isEmpty()) {
            signedMeta.addPage("（没有填写说明）");
        }
        signedMeta.getPersistentDataContainer().remove(writingBookKey);
        ItemStack signedBook = new ItemStack(Material.WRITTEN_BOOK);
        signedBook.setItemMeta(signedMeta);
        descriptions.add(signedBook);

        currentDescriber = null;
        Bukkit.getScheduler().runTask(manager.plugin(), () -> {
            if (!player.isOnline()) {
                advanceDescriptionTurn();
                return;
            }
            player.getInventory().setItem(WRITING_BOOK_SLOT, null);
            ensureViewerBook(player);
            player.playSound(player, Sound.UI_BUTTON_CLICK, 1.0F, 1.1F);
            sendToParticipants(player.getName() + " 已提交第 " + roundNumber + " 轮说明。" );
            Bukkit.getScheduler().runTaskLater(
                    manager.plugin(), this::advanceDescriptionTurn, 15L);
        });
    }

    private void submitTimedOutDescription(UUID playerId) {
        if (!isCurrentDescriber(playerId)) {
            return;
        }
        cancelDescriptionTimer();
        String playerName = nameOf(playerId);
        ItemStack signedBook = new ItemStack(Material.WRITTEN_BOOK);
        ItemMeta rawMeta = signedBook.getItemMeta();
        if (rawMeta instanceof BookMeta meta) {
            meta.setAuthor(playerName);
            meta.setTitle("第" + roundNumber + "轮 - " + playerName);
            meta.addPage("超时未填写");
            signedBook.setItemMeta(meta);
        }
        descriptions.add(signedBook);
        currentDescriber = null;
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            player.closeInventory();
            player.getInventory().setItem(WRITING_BOOK_SLOT, null);
            ensureViewerBook(player);
        }
        sendToParticipants(playerName + " 说明超时，已自动提交“超时未填写”。" );
        Bukkit.getScheduler().runTaskLater(manager.plugin(), this::advanceDescriptionTurn, 15L);
    }

    void reopenWritingBook(Player player) {
        ItemStack item = player.getInventory().getItem(WRITING_BOOK_SLOT);
        if (isWritingBook(item) && isCurrentDescriber(player.getUniqueId())) {
            player.getInventory().setHeldItemSlot(WRITING_BOOK_SLOT);
            player.updateInventory();
            if (!WritableBookOpener.openMainHand(player)) {
                Messages.send(player, "请右键手中的说明书，写完后点击“署名”提交。");
            }
        }
    }

    void openDescriptions(Player player, int requestedPage) {
        openDescriptions(player, requestedPage, false);
    }

    private void openDescriptions(Player player, int requestedPage, boolean currentRoundOnly) {
        int viewStart = currentRoundOnly ? currentRoundDescriptionStart : 0;
        int viewSize = Math.max(0, descriptions.size() - viewStart);
        int maxPage = viewSize == 0 ? 0 : (viewSize - 1) / DESCRIPTION_PAGE_SIZE;
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        DescriptionHolder holder = new DescriptionHolder(page, currentRoundOnly);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                (currentRoundOnly ? "本轮说明" : "所有说明") + " - 第 " + (page + 1) + " 页");
        holder.inventory = inventory;

        int start = viewStart + page * DESCRIPTION_PAGE_SIZE;
        int end = Math.min(start + DESCRIPTION_PAGE_SIZE, descriptions.size());
        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, descriptions.get(index).clone());
        }
        if (page > 0) {
            inventory.setItem(45, namedItem(Material.ARROW, "上一页", NamedTextColor.YELLOW,
                    List.of(Component.text("查看更早的说明", NamedTextColor.GRAY))));
        }
        String countText = currentRoundOnly
                ? "本轮共 " + viewSize + " 条说明"
                : "已提交 " + descriptions.size() + " 条说明";
        List<Component> infoLore = new ArrayList<>();
        infoLore.add(Component.text("按提交顺序排列", NamedTextColor.GRAY));
        if (currentRoundOnly && state == State.REVIEWING) {
            infoLore.add(Component.text("距离投票还有 " + remainingReviewSeconds + " 秒",
                    NamedTextColor.YELLOW));
        }
        inventory.setItem(49, namedItem(Material.BOOK, countText,
                NamedTextColor.AQUA, infoLore));
        if (page < maxPage) {
            inventory.setItem(53, namedItem(Material.ARROW, "下一页", NamedTextColor.YELLOW,
                    List.of(Component.text("查看后续说明", NamedTextColor.GRAY))));
        }
        player.openInventory(inventory);
    }

    void handleDescriptionInventoryClick(Player player, InventoryClickEvent event,
                                         DescriptionHolder holder) {
        event.setCancelled(true);
        if (event.getRawSlot() >= 0 && event.getRawSlot() < DESCRIPTION_PAGE_SIZE) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && clicked.getType() == Material.WRITTEN_BOOK) {
                ItemStack book = clicked.clone();
                Bukkit.getScheduler().runTask(manager.plugin(), () -> player.openBook(book));
            }
        } else if (event.getRawSlot() == 45) {
            openDescriptions(player, holder.page - 1, holder.currentRoundOnly);
        } else if (event.getRawSlot() == 53) {
            openDescriptions(player, holder.page + 1, holder.currentRoundOnly);
        }
    }

    void handleVoteClick(Player voter, InventoryClickEvent event, VoteHolder holder) {
        event.setCancelled(true);
        if (state != State.VOTING || !activePlayers.contains(voter.getUniqueId())
                || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()
                || !event.isLeftClick()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        String targetValue = clicked.getItemMeta().getPersistentDataContainer()
                .get(voteTargetKey, PersistentDataType.STRING);
        if (targetValue == null) {
            return;
        }
        UUID targetId;
        try {
            targetId = UUID.fromString(targetValue);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (!activePlayers.contains(targetId)) {
            Messages.send(voter, "这名玩家已经不在本轮投票中。");
            return;
        }
        votes.put(voter.getUniqueId(), targetId);
        Messages.send(voter, "你已投票给 " + nameOf(targetId) + "，投票结束前可修改。");
        voter.playSound(voter, Sound.UI_BUTTON_CLICK, 0.8F, 1.0F);
        refreshAllVoteInventories();
    }

    boolean isViewerBook(ItemStack item) {
        return hasByteTag(item, viewerBookKey);
    }

    boolean isWritingBook(ItemStack item) {
        return hasByteTag(item, writingBookKey);
    }

    boolean isVoteOpener(ItemStack item) {
        return hasByteTag(item, voteOpenerKey);
    }

    void reopenVoting(Player player) {
        if (state == State.VOTING && activePlayers.contains(player.getUniqueId())) {
            openVoting(player);
        }
    }

    boolean isWritingBookMeta(BookMeta meta) {
        Byte value = meta.getPersistentDataContainer().get(
                writingBookKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    void handleQuit(Player player) {
        UUID playerId = player.getUniqueId();
        if (leftPlayers.contains(playerId)) {
            return;
        }
        if (activePlayers.contains(playerId)) {
            eliminate(playerId, player.getName(), "退出服务器", false);
        }
        leftPlayers.add(playerId);
    }

    void eliminateForDeparture(Player player, String reason) {
        UUID playerId = player.getUniqueId();
        eliminate(playerId, player.getName(), reason, false);
        if (state != State.ENDED) {
            leftPlayers.add(playerId);
        }
    }

    void markLeft(UUID playerId) {
        leftPlayers.add(playerId);
    }

    void eliminateOnDeath(Player player) {
        eliminate(player.getUniqueId(), player.getName(), "死亡", true);
    }

    void becomeSpectator(Player player) {
        if (!isRunning() || !eliminatedPlayers.contains(player.getUniqueId())
                || leftPlayers.contains(player.getUniqueId())) {
            return;
        }
        Location location = spawnFor(player.getUniqueId());
        if (location != null && player.getGameMode() != GameMode.SPECTATOR) {
            player.teleport(location);
        }
        player.setGameMode(GameMode.SPECTATOR);
        ensureViewerBook(player);
        applySaturation(player);
        Messages.send(player, "你已被淘汰，现在可以旁观并查看所有说明。");
    }

    void restorePresentation(Player player) {
        restoreScoreboard(player);
        restoreSaturation(player);
    }

    boolean isMovementLocked() {
        return state != State.ENDED;
    }

    boolean isOwnCustomInventory(InventoryHolder holder) {
        return holder instanceof DescriptionHolder || holder instanceof VoteHolder;
    }

    void serverShutdown() {
        if (state == State.ENDED) {
            return;
        }
        finish(null, "服务器关闭，本局谁是卧底已结束。");
    }

    private void preparePlayer(Player player) {
        UUID playerId = player.getUniqueId();
        Location spawn = playerSpawns.get(playerId);
        previousScoreboards.put(playerId, player.getScoreboard());
        previousSaturationEffects.put(playerId,
                player.getPotionEffect(PotionEffectType.SATURATION));

        player.closeInventory();
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().clear();
        teleportToConfiguredSpawn(player, spawn);
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        applySaturation(player);
        ensureViewerBook(player);
    }

    private void reassertConfiguredSpawns() {
        if (state == State.ENDED) {
            return;
        }
        for (UUID playerId : activePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            Location spawn = playerSpawns.get(playerId);
            if (player != null && player.isOnline() && spawn != null) {
                teleportToConfiguredSpawn(player, spawn);
            }
        }
    }

    private void teleportToConfiguredSpawn(Player player, Location spawn) {
        if (!player.teleport(spawn.clone())) {
            manager.plugin().getLogger().warning("无法将玩家 " + player.getName()
                    + " 传送到谁是卧底重生点：" + spawn.getWorld().getName() + " "
                    + spawn.getX() + ", " + spawn.getY() + ", " + spawn.getZ());
        }
    }

    private void showPersonalWord(Player player) {
        String word = player.getUniqueId().equals(undercoverId)
                ? undercoverWord : civilianWord;
        player.showTitle(Title.title(
                Component.text("你的词语", NamedTextColor.GOLD),
                Component.text(word, NamedTextColor.AQUA),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4),
                        Duration.ofMillis(800))));
    }

    private void installScoreboard(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective(
                "undercover", Criteria.DUMMY,
                Component.text("谁是卧底", NamedTextColor.GOLD));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        String word = player.getUniqueId().equals(undercoverId)
                ? undercoverWord : civilianWord;
        objective.getScore("§7你的词语").setScore(3);
        objective.getScore("§b" + word).setScore(2);
        objective.getScore("§f右键书本查看说明").setScore(1);
        player.setScoreboard(board);
    }

    private void requestWordsAndStartRound() {
        if (state == State.ENDED) {
            return;
        }
        int nextRound = roundNumber + 1;
        UndercoverGameSettings.AiSettings aiSettings = manager.settings().aiSettings();
        if (!aiSettings.usable()) {
            applyWordsAndStart(selectLocalWordPair(), false);
            return;
        }

        state = State.PREPARING;
        sendToParticipants("正在向 DeepSeek 获取第 " + nextRound + " 轮全新词语……" );
        requestAiWords(aiSettings, nextRound, 1);
    }

    private void requestAiWords(UndercoverGameSettings.AiSettings aiSettings,
                                int nextRound, int attempt) {
        manager.wordProvider().request(aiSettings,
                        Set.copyOf(usedWords), Set.copyOf(usedPairs), nextRound)
                .whenComplete((pair, error) -> {
                    if (!manager.plugin().isEnabled()) {
                        return;
                    }
                    Bukkit.getScheduler().runTask(manager.plugin(), () -> {
                            if (state == State.ENDED) {
                                return;
                            }
                            if (error == null && pair != null && !isUsedPair(pair)) {
                                applyWordsAndStart(pair, true);
                                return;
                            }
                            if (attempt < AI_ATTEMPTS) {
                                requestAiWords(aiSettings, nextRound, attempt + 1);
                                return;
                            }
                            String reason = conciseError(error);
                            manager.plugin().getLogger().warning(
                                    "DeepSeek 词语生成失败，已使用本地题库回退：" + reason);
                            sendToParticipants("AI 获取词语失败，本轮已自动改用本地题库。" );
                            applyWordsAndStart(selectLocalWordPair(), false);
                        });
                });
    }

    private void applyWordsAndStart(UndercoverGameSettings.WordPair pair, boolean fromAi) {
        if (state == State.ENDED) {
            return;
        }
        civilianWord = pair.civilianWord();
        undercoverWord = pair.undercoverWord();
        rememberPair(pair);
        for (UUID playerId : participants) {
            if (leftPlayers.contains(playerId)) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                showPersonalWord(player);
                installScoreboard(player);
            }
        }
        sendToParticipants((fromAi ? "DeepSeek 已生成本轮新词。" : "本轮已从本地题库选择新词。")
                + " 每个人只能看到自己的词语，请不要直接说出原词。" );
        Bukkit.getScheduler().runTaskLater(manager.plugin(), this::startDescriptionRound, 80L);
    }

    private UndercoverGameSettings.WordPair selectLocalWordPair() {
        List<UndercoverGameSettings.WordPair> allPairs = manager.settings().wordPairs();
        List<UndercoverGameSettings.WordPair> candidatesPool = new ArrayList<>(allPairs);
        candidatesPool.addAll(EMERGENCY_WORD_PAIRS);
        List<UndercoverGameSettings.WordPair> unused = candidatesPool.stream()
                .filter(pair -> !isUsedPair(pair)).toList();
        if (!unused.isEmpty()) {
            return unused.get(ThreadLocalRandom.current().nextInt(unused.size()));
        }
        int uniqueNumber = Math.max(1, roundNumber + 1);
        UndercoverGameSettings.WordPair generated;
        do {
            generated = new UndercoverGameSettings.WordPair(
                    "香蕉雕像" + uniqueNumber, "粑粑化石" + uniqueNumber);
            uniqueNumber++;
        } while (isUsedPair(generated));
        return generated;
    }

    private boolean isUsedPair(UndercoverGameSettings.WordPair pair) {
        return usedWords.contains(DeepSeekWordProvider.normalize(pair.civilianWord()))
                || usedWords.contains(DeepSeekWordProvider.normalize(pair.undercoverWord()))
                || usedPairs.contains(DeepSeekWordProvider.pairKey(
                        pair.civilianWord(), pair.undercoverWord()));
    }

    private void rememberPair(UndercoverGameSettings.WordPair pair) {
        usedWords.add(DeepSeekWordProvider.normalize(pair.civilianWord()));
        usedWords.add(DeepSeekWordProvider.normalize(pair.undercoverWord()));
        usedPairs.add(DeepSeekWordProvider.pairKey(pair.civilianWord(), pair.undercoverWord()));
    }

    private static String conciseError(Throwable error) {
        if (error == null) {
            return "返回了重复或无效词语";
        }
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message;
    }

    private void startDescriptionRound() {
        if (state == State.ENDED) {
            return;
        }
        if (checkWinCondition()) {
            return;
        }
        state = State.DESCRIBING;
        roundNumber++;
        currentRoundDescriptionStart = descriptions.size();
        List<UUID> order = new ArrayList<>(activePlayers);
        Collections.shuffle(order);
        descriptionOrder = List.copyOf(order);
        descriptionIndex = 0;
        currentDescriber = null;
        sendToParticipants("第 " + roundNumber + " 轮说明开始，顺序已随机生成。" );
        advanceDescriptionTurn();
    }

    private void advanceDescriptionTurn() {
        if (state != State.DESCRIBING || currentDescriber != null) {
            return;
        }
        while (descriptionIndex < descriptionOrder.size()) {
            UUID nextId = descriptionOrder.get(descriptionIndex++);
            if (!activePlayers.contains(nextId)) {
                continue;
            }
            Player next = Bukkit.getPlayer(nextId);
            if (next == null) {
                manager.markReturnToSpawn(nextId);
                eliminate(nextId, nameOf(nextId), "退出服务器", false);
                leftPlayers.add(nextId);
                if (state == State.ENDED) {
                    return;
                }
                continue;
            }
            currentDescriber = nextId;
            giveWritingBook(next);
            remainingDescriptionSeconds = manager.settings().descriptionSeconds();
            sendToParticipants("现在轮到 " + next.getName() + " 说明，限时 "
                    + remainingDescriptionSeconds + " 秒。" );
            next.showTitle(Title.title(
                    Component.text("轮到你说明", NamedTextColor.YELLOW),
                    Component.text("写完后点击“署名”提交", NamedTextColor.WHITE)));
            Bukkit.getScheduler().runTaskLater(manager.plugin(), () -> {
                if (isCurrentDescriber(nextId) && next.isOnline()) {
                    reopenWritingBook(next);
                }
            }, 10L);
            startDescriptionTimer(nextId);
            return;
        }
        startReview();
    }

    private void giveWritingBook(Player player) {
        player.closeInventory();
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta rawMeta = book.getItemMeta();
        if (rawMeta instanceof BookMeta meta) {
            meta.displayName(Component.text("第 " + roundNumber + " 轮说明",
                    NamedTextColor.YELLOW));
            meta.lore(List.of(
                    Component.text("写下对自己词语的说明", NamedTextColor.GRAY),
                    Component.text("完成后点击“署名”提交", NamedTextColor.GREEN)));
            meta.getPersistentDataContainer().set(
                    writingBookKey, PersistentDataType.BYTE, (byte) 1);
            book.setItemMeta(meta);
        }
        player.getInventory().setHeldItemSlot(WRITING_BOOK_SLOT);
        player.getInventory().setItem(WRITING_BOOK_SLOT, book);
        player.updateInventory();
    }

    private void startDescriptionTimer(UUID describerId) {
        cancelDescriptionTimer();
        descriptionTask = Bukkit.getScheduler().runTaskTimer(manager.plugin(), () -> {
            if (!isCurrentDescriber(describerId)) {
                cancelDescriptionTimer();
                return;
            }
            remainingDescriptionSeconds--;
            if (remainingDescriptionSeconds == 30 || remainingDescriptionSeconds == 10
                    || remainingDescriptionSeconds <= 5) {
                Player player = Bukkit.getPlayer(describerId);
                if (player != null) {
                    Messages.send(player, "说明填写剩余 "
                            + Math.max(remainingDescriptionSeconds, 0) + " 秒。" );
                }
            }
            if (remainingDescriptionSeconds <= 0) {
                submitTimedOutDescription(describerId);
            }
        }, 20L, 20L);
    }

    private void cancelDescriptionTimer() {
        cancelTask(descriptionTask);
        descriptionTask = null;
    }

    private void startReview() {
        if (state == State.ENDED || checkWinCondition()) {
            return;
        }
        state = State.REVIEWING;
        currentDescriber = null;
        remainingReviewSeconds = REVIEW_SECONDS;
        sendToParticipants("本轮说明已全部提交，接下来有 30 秒查看本轮所有说明。" );
        for (UUID playerId : participants) {
            if (leftPlayers.contains(playerId)) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                openDescriptions(player, 0, true);
            }
        }
        reviewTask = Bukkit.getScheduler().runTaskTimer(
                manager.plugin(), this::tickReview, 20L, 20L);
    }

    private void tickReview() {
        if (state != State.REVIEWING) {
            cancelTask(reviewTask);
            reviewTask = null;
            return;
        }
        remainingReviewSeconds--;
        if (remainingReviewSeconds == 20 || remainingReviewSeconds == 10
                || remainingReviewSeconds <= 5) {
            sendToParticipants("查看说明剩余 " + Math.max(remainingReviewSeconds, 0) + " 秒。" );
        }
        if (remainingReviewSeconds <= 0) {
            cancelTask(reviewTask);
            reviewTask = null;
            startVoting();
        }
    }

    private void startVoting() {
        if (state == State.ENDED || checkWinCondition()) {
            return;
        }
        cancelTask(reviewTask);
        reviewTask = null;
        state = State.VOTING;
        currentDescriber = null;
        votes.clear();
        remainingVotingSeconds = manager.settings().votingSeconds();
        sendToParticipants("查看时间结束，开始投票：左键玩家头像即可投票，投票结束前可以改票。" );
        for (UUID playerId : activePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                giveVoteOpener(player);
                openVoting(player);
            }
        }
        votingTask = Bukkit.getScheduler().runTaskTimer(
                manager.plugin(), this::tickVoting, 20L, 20L);
    }

    private void tickVoting() {
        if (state != State.VOTING) {
            cancelTask(votingTask);
            votingTask = null;
            return;
        }
        remainingVotingSeconds--;
        refreshAllVoteInventories();
        if (remainingVotingSeconds == 10 || remainingVotingSeconds <= 5) {
            sendToActive("投票剩余 " + Math.max(remainingVotingSeconds, 0) + " 秒。" );
        }
        if (remainingVotingSeconds <= 0) {
            finishVoting();
        }
    }

    private void openVoting(Player player) {
        VoteHolder holder = new VoteHolder();
        Inventory inventory = Bukkit.createInventory(holder, 18,
                "谁是卧底投票 - " + remainingVotingSeconds + "秒");
        holder.inventory = inventory;
        fillVotingInventory(inventory, player.getUniqueId());
        player.openInventory(inventory);
    }

    private void giveVoteOpener(Player player) {
        ItemStack opener = new ItemStack(Material.COMPASS);
        ItemMeta meta = opener.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("重新打开投票页面", NamedTextColor.GREEN));
            meta.lore(List.of(Component.text("投票阶段右键使用", NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(
                    voteOpenerKey, PersistentDataType.BYTE, (byte) 1);
            opener.setItemMeta(meta);
        }
        player.getInventory().setItem(VOTE_OPENER_SLOT, opener);
    }

    private void removeVoteOpeners() {
        for (UUID playerId : participants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                continue;
            }
            for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
                if (isVoteOpener(player.getInventory().getItem(slot))) {
                    player.getInventory().setItem(slot, null);
                }
            }
        }
    }

    private void refreshVoteInventory(Player player, VoteHolder holder) {
        if (state != State.VOTING || holder.inventory == null) {
            return;
        }
        fillVotingInventory(holder.inventory, player.getUniqueId());
    }

    private void refreshAllVoteInventories() {
        for (UUID playerId : activePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                continue;
            }
            InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof VoteHolder voteHolder) {
                refreshVoteInventory(player, voteHolder);
            }
        }
    }

    private void fillVotingInventory(Inventory inventory, UUID voterId) {
        inventory.clear();
        int slot = 0;
        UUID ownVote = votes.get(voterId);
        for (UUID targetId : activePlayers) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta rawMeta = head.getItemMeta();
            if (rawMeta instanceof SkullMeta meta) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(targetId));
                meta.displayName(Component.text(nameOf(targetId), NamedTextColor.YELLOW));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("左键投票给该玩家", NamedTextColor.GREEN));
                List<String> voterNames = votes.entrySet().stream()
                        .filter(entry -> entry.getValue().equals(targetId))
                        .map(entry -> nameOf(entry.getKey()))
                        .toList();
                lore.add(Component.text("当前票数：" + voterNames.size(), NamedTextColor.AQUA));
                if (voterNames.isEmpty()) {
                    lore.add(Component.text("投票者：暂无", NamedTextColor.GRAY));
                } else {
                    lore.add(Component.text("投票者：", NamedTextColor.GRAY));
                    for (String voterName : voterNames) {
                        lore.add(Component.text("- " + voterName, NamedTextColor.WHITE));
                    }
                }
                if (targetId.equals(ownVote)) {
                    lore.add(Component.text("你当前投给了该玩家", NamedTextColor.YELLOW));
                }
                meta.lore(lore);
                meta.getPersistentDataContainer().set(
                        voteTargetKey, PersistentDataType.STRING, targetId.toString());
                head.setItemMeta(meta);
            }
            inventory.setItem(slot++, head);
        }
        inventory.setItem(17, namedItem(Material.CLOCK,
                "剩余 " + remainingVotingSeconds + " 秒", NamedTextColor.AQUA,
                List.of(Component.text("超时未投视为弃权", NamedTextColor.GRAY),
                        Component.text("票数须严格超过存活人数一半", NamedTextColor.RED))));
    }

    private void finishVoting() {
        if (state != State.VOTING) {
            return;
        }
        cancelTask(votingTask);
        votingTask = null;
        removeVoteOpeners();
        finishingVote = true;
        for (UUID playerId : participants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.getOpenInventory().getTopInventory().getHolder()
                    instanceof VoteHolder) {
                player.closeInventory();
            }
        }

        Map<UUID, Integer> voteCounts = new HashMap<>();
        for (UUID targetId : votes.values()) {
            if (!activePlayers.contains(targetId)) {
                continue;
            }
            voteCounts.merge(targetId, 1, Integer::sum);
        }
        UUID eliminated = null;
        int bestVoteCount = 0;
        int thresholdBase = activePlayers.size();
        for (UUID targetId : activePlayers) {
            int count = voteCounts.getOrDefault(targetId, 0);
            sendToParticipants(nameOf(targetId) + " 获得 " + count + " 票。" );
            if (count > thresholdBase / 2 && count > bestVoteCount) {
                eliminated = targetId;
                bestVoteCount = count;
            }
        }

        if (eliminated == null) {
            finishingVote = false;
            sendToParticipants("没有人的票数严格超过存活人数一半，本轮无人出局。" );
            Bukkit.getScheduler().runTaskLater(
                    manager.plugin(), this::requestWordsAndStartRound, 40L);
            return;
        }
        eliminate(eliminated, nameOf(eliminated), "被投票出局", false);
        finishingVote = false;
        if (state != State.ENDED) {
            Bukkit.getScheduler().runTaskLater(
                    manager.plugin(), this::requestWordsAndStartRound, 60L);
        }
    }

    private void eliminate(UUID playerId, String playerName, String reason, boolean dead) {
        if (state == State.ENDED || !activePlayers.remove(playerId)) {
            return;
        }
        playerNames.put(playerId, playerName);
        eliminatedPlayers.add(playerId);
        votes.remove(playerId);
        boolean wasCurrentDescriber = playerId.equals(currentDescriber);
        if (wasCurrentDescriber) {
            cancelDescriptionTimer();
            currentDescriber = null;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            player.closeInventory();
            player.getInventory().setItem(WRITING_BOOK_SLOT, null);
            ensureViewerBook(player);
            if (!dead && !leftPlayers.contains(playerId)) {
                becomeSpectator(player);
            }
        }
        sendToParticipants(playerName + " 因“" + reason + "”出局。" );

        if (playerId.equals(undercoverId)) {
            finishingVote = false;
            finish(false, "卧底 " + playerName + " 已出局，平民获胜！");
            return;
        }
        if (checkWinCondition()) {
            finishingVote = false;
            return;
        }
        if (state == State.VOTING && !finishingVote) {
            for (UUID activeId : activePlayers) {
                Player active = Bukkit.getPlayer(activeId);
                if (active != null && active.getOpenInventory().getTopInventory().getHolder()
                        instanceof VoteHolder) {
                    openVoting(active);
                }
            }
        }
        if (wasCurrentDescriber) {
            Bukkit.getScheduler().runTaskLater(
                    manager.plugin(), this::advanceDescriptionTurn, 10L);
        }
    }

    private boolean checkWinCondition() {
        if (state == State.ENDED) {
            return true;
        }
        if (!activePlayers.contains(undercoverId)) {
            finish(false, "卧底已出局，平民获胜！");
            return true;
        }
        long civilians = activePlayers.stream()
                .filter(playerId -> !playerId.equals(undercoverId)).count();
        if (civilians <= 1) {
            finish(true, "平民只剩一人，卧底 " + nameOf(undercoverId) + " 获胜！");
            return true;
        }
        return false;
    }

    private void finish(Boolean ignoredUndercoverWon, String result) {
        if (state == State.ENDED) {
            return;
        }
        state = State.ENDED;
        currentDescriber = null;
        cancelDescriptionTimer();
        cancelTask(reviewTask);
        cancelTask(votingTask);
        cancelTask(effectTask);
        reviewTask = null;
        votingTask = null;
        effectTask = null;
        removeVoteOpeners();
        manager.onMatchFinished(this);

        String reveal = undercoverId == null ? ""
                : " 卧底是 " + nameOf(undercoverId) + "，词语是“" + undercoverWord
                + "”；平民词语是“" + civilianWord + "”。";
        if (!result.startsWith("服务器关闭") && !result.startsWith("上线玩家不足")) {
            Bukkit.broadcast(Messages.prefixed("谁是卧底结束：" + result + reveal));
        } else {
            sendToParticipants("谁是卧底结束：" + result);
        }

        for (UUID playerId : participants) {
            manager.markReturnToSpawn(playerId);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline() && !leftPlayers.contains(playerId)) {
                restorePresentation(player);
                manager.returnPlayerToSpawn(player);
            }
        }
    }

    private void refreshPlayerEffects() {
        if (state == State.ENDED) {
            return;
        }
        for (UUID playerId : participants) {
            if (leftPlayers.contains(playerId)) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                applySaturation(player);
                player.setFoodLevel(20);
                player.setSaturation(20.0F);
            }
        }
    }

    private void applySaturation(Player player) {
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SATURATION, 40, 0, false, false, false));
    }

    private void restoreSaturation(Player player) {
        PotionEffect previous = previousSaturationEffects.remove(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.SATURATION);
        if (previous != null) {
            player.addPotionEffect(previous);
        }
    }

    private void restoreScoreboard(Player player) {
        Scoreboard previous = previousScoreboards.remove(player.getUniqueId());
        player.setScoreboard(previous == null
                ? Bukkit.getScoreboardManager().getMainScoreboard() : previous);
    }

    private void ensureViewerBook(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isViewerBook(item)) {
                return;
            }
        }
        ItemStack viewer = new ItemStack(Material.BOOK);
        ItemMeta meta = viewer.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("查看所有说明", NamedTextColor.AQUA));
            meta.lore(List.of(Component.text("右键打开公共说明箱子", NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(
                    viewerBookKey, PersistentDataType.BYTE, (byte) 1);
            viewer.setItemMeta(meta);
        }
        player.getInventory().setItem(VIEWER_BOOK_SLOT, viewer);
    }

    private boolean hasByteTag(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        Byte value = item.getItemMeta().getPersistentDataContainer()
                .get(key, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    private void sendToParticipants(String message) {
        for (UUID playerId : participants) {
            if (leftPlayers.contains(playerId)) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                Messages.send(player, message);
            }
        }
    }

    private void sendToActive(String message) {
        for (UUID playerId : activePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                Messages.send(player, message);
            }
        }
    }

    private String nameOf(UUID playerId) {
        return playerNames.getOrDefault(playerId, "未知玩家");
    }

    private static ItemStack namedItem(Material material, String name,
                                       NamedTextColor color, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, color));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static void cancelTask(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    enum State {
        PREPARING,
        DESCRIBING,
        REVIEWING,
        VOTING,
        ENDED
    }

    static final class DescriptionHolder implements InventoryHolder {

        private final int page;
        private final boolean currentRoundOnly;
        private Inventory inventory;

        private DescriptionHolder(int page, boolean currentRoundOnly) {
            this.page = page;
            this.currentRoundOnly = currentRoundOnly;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    static final class VoteHolder implements InventoryHolder {

        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

}
