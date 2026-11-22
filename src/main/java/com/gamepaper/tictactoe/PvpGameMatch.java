package com.gamepaper.tictactoe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

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

final class PvpGameMatch {

    enum State {
        PREPARING,
        COUNTDOWN,
        PLAYING,
        ENDED
    }

    enum Team {
        RED("红队", NamedTextColor.RED, BarColor.RED),
        BLUE("蓝队", NamedTextColor.BLUE, BarColor.BLUE);

        private final String displayName;
        private final NamedTextColor textColor;
        private final BarColor barColor;

        Team(String displayName, NamedTextColor textColor, BarColor barColor) {
            this.displayName = displayName;
            this.textColor = textColor;
            this.barColor = barColor;
        }

        String displayName() {
            return displayName;
        }

        NamedTextColor textColor() {
            return textColor;
        }

        BarColor barColor() {
            return barColor;
        }
    }

    enum Job {
        ASSAULT("突击", "assault"),
        SUPPLY("补给", "supply"),
        SNIPER("狙击", "sniper");

        private final String displayName;
        private final String commandName;

        Job(String displayName, String commandName) {
            this.displayName = displayName;
            this.commandName = commandName;
        }

        String displayName() {
            return displayName;
        }

        String commandName() {
            return commandName;
        }

        static Job parse(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (normalized) {
                case "assault", "突击" -> ASSAULT;
                case "supply", "补给" -> SUPPLY;
                case "sniper", "狙击" -> SNIPER;
                default -> null;
            };
        }
    }

    private static final int ACTIONBAR_SEGMENTS = 10;
    private static final int ASSAULT_CLASS_SLOT = 11;
    private static final int SUPPLY_CLASS_SLOT = 13;
    private static final int SNIPER_CLASS_SLOT = 15;

    private final PvpGameManager manager;
    private final JavaPlugin plugin;
    private final List<UUID> participants;
    private final Set<UUID> activePlayers;
    private final Set<UUID> leftPlayers = new HashSet<>();
    private final Map<UUID, Team> teams = new LinkedHashMap<>();
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<UUID, Location> spawnLocations = new HashMap<>();
    private final Map<UUID, Long> skillReadyAt = new HashMap<>();
    private final Map<UUID, Long> respawnProtectedUntil = new HashMap<>();
    private final Map<UUID, Long> supplyAppleRemainingTicks = new HashMap<>();
    private final Location redSpawn;
    private final Location blueSpawn;
    private final Set<UUID> supportEffectPlayers = new HashSet<>();

    private State state = State.PREPARING;
    private int countdownRemaining;
    private long gameTicks;
    private int redScore;
    private int blueScore;
    private double redCaptureProgress;
    private double blueCaptureProgress;
    private int emptyZoneTicks;
    private BossBar redBossBar;
    private BossBar blueBossBar;
    private BukkitTask countdownTask;
    private BukkitTask gameTask;

    PvpGameMatch(PvpGameManager manager, List<UUID> players,
                 Location redSpawn, Location blueSpawn) {
        this.manager = manager;
        this.plugin = manager.plugin();
        this.participants = new ArrayList<>(players);
        this.activePlayers = new LinkedHashSet<>(players);
        this.redSpawn = redSpawn.clone();
        this.blueSpawn = blueSpawn.clone();
        Collections.shuffle(this.participants);
        assignTeamsAndSpawns();
    }

    void begin() {
        redBossBar = Bukkit.createBossBar(
                "红队积分：0 / " + manager.settings().winningScore(),
                Team.RED.barColor(), BarStyle.SEGMENTED_10);
        blueBossBar = Bukkit.createBossBar(
                "蓝队积分：0 / " + manager.settings().winningScore(),
                Team.BLUE.barColor(), BarStyle.SEGMENTED_10);
        redBossBar.setVisible(true);
        blueBossBar.setVisible(true);

        preparePlayers();
        state = State.COUNTDOWN;
        countdownRemaining = manager.settings().startCountdownSeconds();
        forceClassSelectionMenus();
        broadcast("占点游戏即将开始，倒计时 " + countdownRemaining + " 秒；倒计时期间不能移动或操作。", null);
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, this::countdownTick, 0L, 20L);
    }

    private void assignTeamsAndSpawns() {
        for (int index = 0; index < participants.size(); index++) {
            UUID playerId = participants.get(index);
            Team team = index % 2 == 0 ? Team.RED : Team.BLUE;
            teams.put(playerId, team);
            if (team == Team.RED) {
                spawnLocations.put(playerId, redSpawn.clone());
            } else {
                spawnLocations.put(playerId, blueSpawn.clone());
            }
        }
    }

    private void preparePlayers() {
        for (UUID playerId : participants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                continue;
            }
            player.setGameMode(GameMode.SURVIVAL);
            player.setAllowFlight(false);
            player.setFlying(false);
            clearInventory(player);
            clearPotionEffects(player);
            player.setHealth(maxHealth(player));
            player.setFoodLevel(20);
            player.setSaturation(20.0F);
            player.setFireTicks(0);
            player.setFallDistance(0.0F);
            player.teleport(spawnLocations.get(playerId));
            player.setAllowFlight(false);
            skillReadyAt.put(playerId, 0L);
            addBossBar(player, teams.get(playerId));
            Messages.send(player, "你是 " + teams.get(playerId).displayName()
                    + "，请在职业选择界面选择职业。");
        }
    }

    private void forceClassSelectionMenus() {
        if (state == State.ENDED) {
            return;
        }
        for (UUID playerId : activePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (!isLivingOnline(player) || jobs.containsKey(playerId)) {
                continue;
            }
            Inventory top = player.getOpenInventory().getTopInventory();
            if (!isClassSelectionInventory(top)) {
                openClassSelection(player);
            }
        }
    }

    private boolean allJobsChosen() {
        for (UUID playerId : activePlayers) {
            if (!jobs.containsKey(playerId)) {
                return false;
            }
        }
        return true;
    }

    private void openClassSelection(Player player) {
        if (!isLivingOnline(player) || jobs.containsKey(player.getUniqueId()) || state == State.ENDED) {
            return;
        }
        ClassSelectionHolder holder = new ClassSelectionHolder(this);
        Inventory inventory = Bukkit.createInventory(holder, 27, "选择职业");
        holder.inventory = inventory;
        ItemStack filler = menuItem(Material.GRAY_STAINED_GLASS_PANE,
                Component.text(" ", NamedTextColor.GRAY), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
        }
        inventory.setItem(ASSAULT_CLASS_SLOT, classButton(Job.ASSAULT,
                Material.STONE_SWORD, "双击空格向前突进，技能冷却可在动作栏查看。"));
        inventory.setItem(SUPPLY_CLASS_SLOT, classButton(Job.SUPPLY,
                Material.GOLDEN_APPLE, "持续支援附近队友，技能从游戏开始生效且无冷却。"));
        inventory.setItem(SNIPER_CLASS_SLOT, classButton(Job.SNIPER,
                Material.BOW, "右键标记敌人并加速队友；双击空格向后突进。"));
        player.openInventory(inventory);
    }

    private ItemStack classButton(Job job, Material material, String description) {
        return menuItem(material,
                Component.text(job.displayName(), job == Job.ASSAULT
                        ? NamedTextColor.RED : job == Job.SUPPLY
                        ? NamedTextColor.GREEN : NamedTextColor.AQUA),
                List.of(Component.text(description, NamedTextColor.GRAY),
                        Component.text("点击选择", NamedTextColor.YELLOW)));
    }

    private static ItemStack menuItem(Material material, Component displayName,
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

    boolean isClassSelectionInventory(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof ClassSelectionHolder holder
                && holder.match == this;
    }

    Job jobForClassSlot(int slot) {
        return switch (slot) {
            case ASSAULT_CLASS_SLOT -> Job.ASSAULT;
            case SUPPLY_CLASS_SLOT -> Job.SUPPLY;
            case SNIPER_CLASS_SLOT -> Job.SNIPER;
            default -> null;
        };
    }

    void selectJob(Player player, int slot) {
        Job job = jobForClassSlot(slot);
        UUID playerId = player.getUniqueId();
        if (job == null || state == State.ENDED || !isActivePlayer(playerId)
                || !isLivingOnline(player)) {
            return;
        }
        jobs.put(playerId, job);
        clearInventory(player);
        clearPotionEffects(player);
        player.setGameMode(GameMode.SURVIVAL);
        player.setHealth(maxHealth(player));
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        player.setFireTicks(0);
        player.setFallDistance(0.0F);
        equip(player, job);
        player.setAllowFlight(job == Job.ASSAULT || job == Job.SNIPER);
        player.setFlying(false);
        skillReadyAt.put(playerId, gameTicks);
        supplyAppleRemainingTicks.put(playerId, supplyAppleIntervalTicks());
        player.closeInventory();
        Messages.send(player, "你选择了 " + job.displayName() + " 职业。" );
    }

    void handleClassSelectionClosed(Player player) {
        UUID playerId = player.getUniqueId();
        if (state == State.ENDED || !isActivePlayer(playerId) || jobs.containsKey(playerId)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (state != State.ENDED && isActivePlayer(playerId) && !jobs.containsKey(playerId)) {
                openClassSelection(player);
            }
        }, 1L);
    }

    private void countdownTick() {
        if (state != State.COUNTDOWN) {
            cancelTask(countdownTask);
            return;
        }
        if (checkTeamElimination()) {
            return;
        }
        forceClassSelectionMenus();
        if (!allJobsChosen()) {
            countdownRemaining = Math.max(0, countdownRemaining);
            for (UUID playerId : activePlayers) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline() && jobs.get(playerId) == null) {
                    player.sendActionBar(Component.text("请打开职业选择界面并选择职业",
                            NamedTextColor.YELLOW));
                }
            }
            return;
        }
        if (countdownRemaining <= 0) {
            startPlaying();
            return;
        }
        broadcast("占点游戏将在 " + countdownRemaining + " 秒后开始。", null);
        for (UUID playerId : activePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendActionBar(Component.text("开始倒计时：" + countdownRemaining + " 秒",
                        NamedTextColor.YELLOW));
            }
        }
        countdownRemaining--;
    }

    private void startPlaying() {
        cancelTask(countdownTask);
        state = State.PLAYING;
        gameTicks = 0L;
        updateBossBars();
        broadcast("占点游戏开始！进入中间区域为队伍占领并获取积分。", null);
        gameTask = Bukkit.getScheduler().runTaskTimer(plugin, this::gameTick, 1L, 1L);
    }

    private void gameTick() {
        if (state != State.PLAYING) {
            cancelTask(gameTask);
            return;
        }
        gameTicks++;
        if (checkTeamElimination()) {
            return;
        }
        updateCaptureScore();
        if (state != State.PLAYING) {
            return;
        }
        updateSpawnRestoration();
        updateSupplySkills();
        updateActionBars();
        updateBossBars();
    }

    private boolean checkTeamElimination() {
        boolean redAlive = hasActiveTeamMember(Team.RED);
        boolean blueAlive = hasActiveTeamMember(Team.BLUE);
        if (redAlive && blueAlive) {
            return false;
        }
        if (!redAlive && !blueAlive) {
            finish(null);
        } else {
            finish(redAlive ? Team.RED : Team.BLUE);
        }
        return true;
    }

    private boolean hasActiveTeamMember(Team team) {
        for (UUID playerId : activePlayers) {
            if (teams.get(playerId) == team) {
                return true;
            }
        }
        return false;
    }

    private void updateCaptureScore() {
        int redInZone = 0;
        int blueInZone = 0;
        PvpGameSettings.CaptureZone zone = manager.settings().captureZone();
        for (UUID playerId : activePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || player.isDead()) {
                continue;
            }
            if (!zone.contains(player.getLocation())) {
                continue;
            }
            if (teams.get(playerId) == Team.RED) {
                redInZone++;
            } else if (teams.get(playerId) == Team.BLUE) {
                blueInZone++;
            }
        }

        double delta = 1.0D / 20.0D;
        if (redInZone == 0 && blueInZone == 0) {
            emptyZoneTicks++;
            if (emptyZoneTicks >= manager.settings().emptyZoneSeconds() * 20) {
                // 无人阶段必须使用同一份进度，避免上一队残留的小数进度先达到一分。
                double sharedProgress = Math.min(redCaptureProgress, blueCaptureProgress);
                redCaptureProgress = sharedProgress + delta;
                blueCaptureProgress = sharedProgress + delta;
            }
        } else {
            emptyZoneTicks = 0;
            if (redInZone > blueInZone) {
                redCaptureProgress += delta;
                blueCaptureProgress = 0.0D;
            } else if (blueInZone > redInZone) {
                blueCaptureProgress += delta;
                redCaptureProgress = 0.0D;
            }
        }

        int captureSeconds = manager.settings().captureSeconds();
        while (redCaptureProgress >= captureSeconds || blueCaptureProgress >= captureSeconds) {
            if (redCaptureProgress >= captureSeconds && blueCaptureProgress >= captureSeconds) {
                redCaptureProgress -= captureSeconds;
                blueCaptureProgress -= captureSeconds;
                redScore++;
                blueScore++;
                broadcast("占点区域无人，红蓝两队各获得 1 分（红队 " + redScore
                        + "，蓝队 " + blueScore + "）。", null);
            } else if (redCaptureProgress >= captureSeconds) {
                redCaptureProgress -= captureSeconds;
                redScore++;
                blueCaptureProgress = 0.0D;
                broadcast("红队占领区域，获得 1 分（" + redScore + "/"
                        + manager.settings().winningScore() + "）。", null);
            } else {
                blueCaptureProgress -= captureSeconds;
                blueScore++;
                redCaptureProgress = 0.0D;
                broadcast("蓝队占领区域，获得 1 分（" + blueScore + "/"
                        + manager.settings().winningScore() + "）。", null);
            }
        }
        boolean redReached = redScore >= manager.settings().winningScore();
        boolean blueReached = blueScore >= manager.settings().winningScore();
        if (redReached || blueReached) {
            finish(redReached && blueReached ? null : redReached ? Team.RED : Team.BLUE);
        }
    }

    private void updateSupplySkills() {
        Set<UUID> currentSupportTargets = new HashSet<>();
        Set<UUID> appleEligiblePlayers = new HashSet<>();
        double radiusSquared = manager.settings().supply().supportRadius()
                * manager.settings().supply().supportRadius();
        for (UUID supplyId : activePlayers) {
            if (jobs.get(supplyId) != Job.SUPPLY) {
                continue;
            }
            Player supply = Bukkit.getPlayer(supplyId);
            if (!isLivingOnline(supply)) {
                continue;
            }
            currentSupportTargets.add(supplyId);
            addRegeneration(supply);

            for (UUID teammateId : activePlayers) {
                if (teammateId.equals(supplyId) || teams.get(teammateId) != teams.get(supplyId)) {
                    continue;
                }
                Player teammate = Bukkit.getPlayer(teammateId);
                if (!isLivingOnline(teammate) || !sameWorld(supply, teammate)
                        || supply.getLocation().distanceSquared(teammate.getLocation()) > radiusSquared) {
                    continue;
                }
                currentSupportTargets.add(teammateId);
                appleEligiblePlayers.add(teammateId);
                addRegeneration(teammate);
                Job teammateJob = jobs.get(teammateId);
                if (teammateJob != null) {
                    ensureWeapon(teammate, teammateJob);
                }
                if (gameTicks % manager.settings().supply().repairIntervalTicks() == 0) {
                    repairEquipment(teammate);
                }
            }
        }
        long appleInterval = supplyAppleIntervalTicks();
        for (UUID playerId : activePlayers) {
            if (!appleEligiblePlayers.contains(playerId) || jobs.get(playerId) == null) {
                continue;
            }
            long remaining = supplyAppleRemainingTicks.getOrDefault(playerId, appleInterval);
            remaining--;
            if (remaining <= 0L) {
                Player player = Bukkit.getPlayer(playerId);
                if (isLivingOnline(player)) {
                    player.getInventory().addItem(new ItemStack(Material.GOLDEN_APPLE, 1));
                    Messages.send(player, "补给职业为你提供了一个金苹果。");
                }
                remaining = appleInterval;
            }
            supplyAppleRemainingTicks.put(playerId, remaining);
        }
        for (UUID previous : supportEffectPlayers) {
            if (!currentSupportTargets.contains(previous)) {
                Player player = Bukkit.getPlayer(previous);
                if (player != null && player.isOnline()) {
                    player.removePotionEffect(PotionEffectType.REGENERATION);
                }
            }
        }
        supportEffectPlayers.clear();
        supportEffectPlayers.addAll(currentSupportTargets);
    }

    private void updateSpawnRestoration() {
        double radiusSquared = manager.settings().supply().supportRadius()
                * manager.settings().supply().supportRadius();
        int repairInterval = manager.settings().supply().repairIntervalTicks();
        for (UUID playerId : activePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            Location spawn = spawnLocations.get(playerId);
            if (!isLivingOnline(player) || spawn == null || !sameWorld(player, spawn)
                    || player.getLocation().distanceSquared(spawn) > radiusSquared) {
                continue;
            }
            Job job = jobs.get(playerId);
            if (job == null) {
                continue;
            }
            ensureWeapon(player, job);
            if (gameTicks % repairInterval == 0) {
                repairEquipment(player);
            }
            restoreGoldenApples(player, job);
            restoreSpectralArrows(player, job);
        }
    }

    private void addRegeneration(Player player) {
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.REGENERATION, 40, 0, false, false, true));
    }

    private void repairEquipment(Player player) {
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> items = new ArrayList<>();
        Collections.addAll(items, inventory.getContents());
        items.add(inventory.getItemInOffHand());
        Collections.addAll(items, inventory.getArmorContents());
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            if (!(meta instanceof Damageable damageable) || meta.isUnbreakable()
                    || damageable.getDamage() <= 0) {
                continue;
            }
            damageable.setDamage(damageable.getDamage() - 1);
            item.setItemMeta(meta);
        }
    }

    private void ensureWeapon(Player player, Job job) {
        PvpGameSettings.GearConfig gear = gearFor(job);
        ItemStack weapon = findWeapon(player.getInventory(), gear.weaponMaterial());
        if (weapon == null) {
            player.getInventory().addItem(createItem(
                    gear.weaponMaterial(), 1, 1, gear.weaponUnbreakable(),
                    gear.weaponKnockback(), Component.text(job.displayName() + "武器", NamedTextColor.WHITE)));
            Messages.send(player, "你在补给点附近获得了一把耐久为 1 的" + job.displayName() + "武器。" );
            return;
        }
        ItemMeta meta = weapon.getItemMeta();
        if (meta instanceof Damageable damageable && !meta.isUnbreakable()
                && weapon.getType().getMaxDurability() > 0
                && damageable.getDamage() >= weapon.getType().getMaxDurability()) {
            damageable.setDamage(weapon.getType().getMaxDurability() - 1);
            weapon.setItemMeta(meta);
        }
    }

    private static ItemStack findWeapon(PlayerInventory inventory, Material material) {
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == material && item.getAmount() > 0) {
                return item;
            }
        }
        ItemStack offHand = inventory.getItemInOffHand();
        return offHand != null && offHand.getType() == material && offHand.getAmount() > 0
                ? offHand : null;
    }

    private void restoreGoldenApples(Player player, Job job) {
        int target = gearFor(job).goldenAppleCount();
        if (target <= 0) {
            return;
        }
        int current = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.GOLDEN_APPLE) {
                current += item.getAmount();
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && offHand.getType() == Material.GOLDEN_APPLE) {
            current += offHand.getAmount();
        }
        if (current < target) {
            player.getInventory().addItem(new ItemStack(Material.GOLDEN_APPLE, target - current));
        }
    }

    private void restoreSpectralArrows(Player player, Job job) {
        int target = gearFor(job).spectralArrowCount();
        if (target <= 0) {
            return;
        }
        int current = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.SPECTRAL_ARROW) {
                current += item.getAmount();
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && offHand.getType() == Material.SPECTRAL_ARROW) {
            current += offHand.getAmount();
        }
        if (current < target) {
            player.getInventory().addItem(new ItemStack(Material.SPECTRAL_ARROW, target - current));
        }
    }

    private void updateActionBars() {
        for (UUID playerId : activePlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (!isLivingOnline(player)) {
                continue;
            }
            Job job = jobs.get(playerId);
            if (job == null) {
                player.sendActionBar(Component.text("请打开职业选择界面并选择职业",
                        NamedTextColor.YELLOW));
                continue;
            }
            if (job == Job.SUPPLY) {
                player.sendActionBar(Component.text("技能：范围支援（被动）", NamedTextColor.GREEN));
                continue;
            }
            long readyAt = skillReadyAt.getOrDefault(playerId, 0L);
            int cooldown = cooldownTicks(job);
            long remaining = Math.max(0L, readyAt - gameTicks);
            double progress = cooldown <= 0 ? 1.0D : Math.min(1.0D,
                    Math.max(0.0D, 1.0D - (double) remaining / cooldown));
            int filled = (int) Math.round(progress * ACTIONBAR_SEGMENTS);
            StringBuilder bar = new StringBuilder();
            for (int index = 0; index < ACTIONBAR_SEGMENTS; index++) {
                bar.append(index < filled ? '■' : '□');
            }
            String suffix = remaining <= 0 ? "已充能" : ((remaining + 19) / 20) + "秒";
            player.sendActionBar(Component.text("技能：" + job.displayName() + " " + bar + " " + suffix,
                    remaining <= 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        }
    }

    private void updateBossBars() {
        double target = manager.settings().winningScore();
        double redProgress = Math.min(1.0D, (redScore + redCaptureProgress
                / manager.settings().captureSeconds()) / target);
        double blueProgress = Math.min(1.0D, (blueScore + blueCaptureProgress
                / manager.settings().captureSeconds()) / target);
        redBossBar.setTitle("红队积分：" + redScore + " / " + manager.settings().winningScore());
        blueBossBar.setTitle("蓝队积分：" + blueScore + " / " + manager.settings().winningScore());
        redBossBar.setProgress(redProgress);
        blueBossBar.setProgress(blueProgress);
    }

    boolean tryAssaultDash(Player player) {
        UUID playerId = player.getUniqueId();
        if (state != State.PLAYING || !isActivePlayer(playerId) || jobs.get(playerId) != Job.ASSAULT) {
            return false;
        }
        long readyAt = skillReadyAt.getOrDefault(playerId, 0L);
        if (gameTicks < readyAt) {
            return false;
        }
        skillReadyAt.put(playerId, gameTicks + cooldownTicks(Job.ASSAULT));
        Vector direction = player.getLocation().getDirection();
        direction.setY(0.0D);
        if (direction.lengthSquared() < 0.0001D) {
            direction = new Vector(0.0D, 0.0D, 1.0D);
        }
        double distance = manager.settings().assault().dashDistance();
        Vector velocity = direction.normalize().multiply(Math.max(0.2D, distance / 8.0D));
        velocity.setY(0.45D);
        player.setVelocity(velocity);
        player.setFallDistance(0.0F);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 18, 0.3, 0.2, 0.3, 0.05);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.7F, 1.6F);
        return true;
    }

    boolean trySniperDash(Player player) {
        UUID playerId = player.getUniqueId();
        if (state != State.PLAYING || !isActivePlayer(playerId) || jobs.get(playerId) != Job.SNIPER) {
            return false;
        }
        long readyAt = skillReadyAt.getOrDefault(playerId, 0L);
        if (gameTicks < readyAt) {
            return false;
        }
        skillReadyAt.put(playerId, gameTicks + cooldownTicks(Job.SNIPER));
        Vector direction = player.getLocation().getDirection();
        direction.setY(0.0D);
        if (direction.lengthSquared() < 0.0001D) {
            direction = new Vector(0.0D, 0.0D, 1.0D);
        }
        double distance = manager.settings().sniper().backDashDistance();
        Vector velocity = direction.normalize().multiply(-Math.max(0.2D, distance / 8.0D));
        velocity.setY(0.45D);
        player.setVelocity(velocity);
        player.setFallDistance(0.0F);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(),
                18, 0.3, 0.2, 0.3, 0.05);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.7F, 1.6F);
        return true;
    }

    boolean trySniperSkill(Player player, EquipmentSlot hand) {
        UUID playerId = player.getUniqueId();
        if (state != State.PLAYING || !isActivePlayer(playerId) || jobs.get(playerId) != Job.SNIPER) {
            return false;
        }
        ItemStack held = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        if (held == null || held.getType() != Material.SPECTRAL_ARROW || held.getAmount() <= 0) {
            return false;
        }
        long readyAt = skillReadyAt.getOrDefault(playerId, 0L);
        if (gameTicks < readyAt) {
            Messages.send(player, "狙击技能还在充能中。");
            return true;
        }
        held.setAmount(held.getAmount() - 1);
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(held);
        } else {
            player.getInventory().setItemInMainHand(held);
        }
        skillReadyAt.put(playerId, gameTicks + cooldownTicks(Job.SNIPER));
        Team ownTeam = teams.get(playerId);
        for (UUID targetId : activePlayers) {
            Player target = Bukkit.getPlayer(targetId);
            if (!isLivingOnline(target)) {
                continue;
            }
            if (teams.get(targetId) == ownTeam) {
                int speedSeconds = manager.settings().sniper().speedSeconds();
                if (speedSeconds > 0) {
                    target.addPotionEffect(new PotionEffect(
                            PotionEffectType.SPEED,
                            speedSeconds * 20,
                            manager.settings().sniper().speedAmplifier(),
                            false, true, true));
                }
            } else {
                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.GLOWING,
                        manager.settings().sniper().glowingSeconds() * 20,
                        0, false, true, true));
            }
        }
        broadcast(player.getName() + " 使用了狙击技能，敌军已被标记，队友获得速度。", null);
        player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1, 0),
                24, 0.4, 0.6, 0.4, 0.08);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 1.0F, 0.8F);
        return true;
    }

    void handleDeath(Player player) {
        if (state != State.PLAYING || !isActivePlayer(player.getUniqueId())) {
            return;
        }
        jobs.remove(player.getUniqueId());
        skillReadyAt.remove(player.getUniqueId());
        supplyAppleRemainingTicks.remove(player.getUniqueId());
        respawnProtectedUntil.remove(player.getUniqueId());
        player.setAllowFlight(false);
        broadcast(player.getName() + " 被击败，将在 "
                + manager.settings().respawnProtectionSeconds() + " 秒保护后重生。", null);
    }

    void handleRespawn(Player player, PlayerRespawnEvent event) {
        UUID playerId = player.getUniqueId();
        if (state != State.PLAYING || !isActivePlayer(playerId)) {
            return;
        }
        Location spawn = spawnLocations.get(playerId);
        if (spawn == null) {
            spawn = randomSpawn(teams.get(playerId));
        }
        if (spawn != null) {
            event.setRespawnLocation(spawn.clone());
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!isLivingOnline(player) || state != State.PLAYING || !isActivePlayer(playerId)) {
                return;
            }
            long protectionTicks = manager.settings().respawnProtectionSeconds() * 20L;
            respawnProtectedUntil.put(playerId, gameTicks + protectionTicks);
            player.setGameMode(GameMode.SURVIVAL);
            clearInventory(player);
            clearPotionEffects(player);
            player.setAllowFlight(false);
            player.setFlying(false);
            player.setNoDamageTicks((int) Math.min(Integer.MAX_VALUE, protectionTicks));
            player.setHealth(maxHealth(player));
            player.setFoodLevel(20);
            player.setFallDistance(0.0F);
            Messages.send(player, "你已在 " + teams.get(playerId).displayName()
                    + " 重生点复活，请重新选择职业；选择后 5 秒内无敌且不能移动。");
            openClassSelection(player);
        });
    }

    void handleLeave(Player player, boolean quit) {
        UUID playerId = player.getUniqueId();
        if (!isParticipant(playerId) || leftPlayers.contains(playerId)) {
            return;
        }
        leftPlayers.add(playerId);
        activePlayers.remove(playerId);
        manager.markReturnToSpawn(playerId);
        if (!quit && player.isOnline()) {
            clearPlayer(player);
            manager.returnToSpawnIfAlive(player);
        }
        broadcast(player.getName() + (quit ? " 退出服务器，视为离开占点游戏。" : " 离开了占点游戏。"), null);
        checkTeamElimination();
    }

    void serverShutdown() {
        if (state == State.ENDED) {
            return;
        }
        state = State.ENDED;
        cancelTask(countdownTask);
        cancelTask(gameTask);
        removeBossBars();
        for (UUID playerId : participants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline() && !player.isDead()) {
                clearPlayer(player);
                manager.returnToSpawnIfAlive(player);
            } else {
                manager.markReturnToSpawn(playerId);
            }
        }
        manager.onMatchFinished(this);
    }

    private void finish(Team winner) {
        if (state == State.ENDED) {
            return;
        }
        state = State.ENDED;
        cancelTask(countdownTask);
        cancelTask(gameTask);
        removeBossBars();
        supportEffectPlayers.clear();
        String title = winner == null ? "占点游戏平局" : winner.displayName() + "获胜";
        String subtitle = winner == null
                ? "双方同时达到目标积分"
                : "最终比分 红队 " + redScore + " - " + blueScore + " 蓝队";
        for (UUID playerId : participants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                manager.markReturnToSpawn(playerId);
                continue;
            }
            clearPlayer(player);
            player.showTitle(Title.title(
                    Component.text(title, winner == null ? NamedTextColor.GOLD : winner.textColor()),
                    Component.text(subtitle, NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(500))));
        }
        broadcast(title + "！" + subtitle + "。5 秒后所有参赛玩家返回世界出生点。", null);
        manager.onMatchFinished(this);
        manager.scheduleReturnToSpawn(participants);
    }

    private void addBossBar(Player player, Team team) {
        redBossBar.addPlayer(player);
        blueBossBar.addPlayer(player);
    }

    private void removeBossBars() {
        if (redBossBar != null) {
            redBossBar.removeAll();
            redBossBar.setVisible(false);
        }
        if (blueBossBar != null) {
            blueBossBar.removeAll();
            blueBossBar.setVisible(false);
        }
    }

    private PvpGameSettings.GearConfig gearFor(Job job) {
        return switch (job) {
            case ASSAULT -> manager.settings().assault().gear();
            case SUPPLY -> manager.settings().supply().gear();
            case SNIPER -> manager.settings().sniper().gear();
        };
    }

    private void equip(Player player, Job job) {
        PvpGameSettings.GearConfig gear = gearFor(job);
        ItemStack weapon = createItem(gear.weaponMaterial(), gear.weaponAmount(),
                gear.weaponRemainingDurability(), gear.weaponUnbreakable(),
                gear.weaponKnockback(), Component.text(job.displayName() + "武器", NamedTextColor.WHITE));
        player.getInventory().setItemInMainHand(weapon);
        player.getInventory().setArmorContents(new ItemStack[]{
                createItem(armorMaterial(gear.armorMaterial(), "_BOOTS"), 1, 0,
                        gear.armorUnbreakable(), 0,
                        Component.text(job.displayName() + "靴子", NamedTextColor.WHITE)),
                createItem(armorMaterial(gear.armorMaterial(), "_LEGGINGS"), 1, 0,
                        gear.armorUnbreakable(), 0,
                        Component.text(job.displayName() + "护腿", NamedTextColor.WHITE)),
                createItem(armorMaterial(gear.armorMaterial(), "_CHESTPLATE"), 1, 0,
                        gear.armorUnbreakable(), 0,
                        Component.text(job.displayName() + "胸甲", NamedTextColor.WHITE)),
                createItem(armorMaterial(gear.armorMaterial(), "_HELMET"), 1, 0,
                        gear.armorUnbreakable(), 0,
                        Component.text(job.displayName() + "头盔", NamedTextColor.WHITE))
        });
        if (gear.goldenAppleCount() > 0) {
            player.getInventory().addItem(new ItemStack(Material.GOLDEN_APPLE, gear.goldenAppleCount()));
        }
        if (gear.spectralArrowCount() > 0) {
            player.getInventory().addItem(new ItemStack(Material.SPECTRAL_ARROW, gear.spectralArrowCount()));
        }
    }

    private static Material armorMaterial(Material configured, String suffix) {
        if (configured == null) {
            Material parsed = Material.matchMaterial("LEATHER" + suffix);
            return parsed == null ? Material.LEATHER : parsed;
        }
        String name = configured.name();
        String base = name;
        for (String existingSuffix : List.of("_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS")) {
            if (base.endsWith(existingSuffix)) {
                base = base.substring(0, base.length() - existingSuffix.length());
                break;
            }
        }
        if (base.equals("LEATHER")) {
            Material parsed = Material.matchMaterial("LEATHER" + suffix);
            return parsed == null ? configured : parsed;
        }
        if (base.equals("GOLD")) {
            base = "GOLDEN";
        }
        Material parsed = Material.matchMaterial(base + suffix);
        return parsed == null ? configured : parsed;
    }

    private ItemStack createItem(Material material, int amount, int remainingDurability,
                                 boolean unbreakable, int knockback, Component displayName) {
        ItemStack item = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(displayName);
            meta.setUnbreakable(unbreakable);
            if (knockback > 0) {
                meta.addEnchant(Enchantment.KNOCKBACK, knockback, true);
            }
            if (meta instanceof Damageable damageable && material.getMaxDurability() > 0
                    && remainingDurability > 0) {
                int damage = Math.max(0, material.getMaxDurability() - remainingDurability);
                damageable.setDamage(damage);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private void clearPlayer(Player player) {
        clearInventory(player);
        clearPotionEffects(player);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setFallDistance(0.0F);
        if (!player.isDead()) {
            player.setGameMode(GameMode.SURVIVAL);
            player.setHealth(maxHealth(player));
            player.setFoodLevel(20);
            player.setSaturation(20.0F);
            player.setFireTicks(0);
        }
    }

    private static void clearInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setExtraContents(new ItemStack[player.getInventory().getExtraContents().length]);
    }

    private static void clearPotionEffects(Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
    }

    private int cooldownTicks(Job job) {
        if (job == Job.SUPPLY) {
            return 0;
        }
        int seconds = switch (job) {
            case ASSAULT -> manager.settings().assault().skillCooldownSeconds();
            case SNIPER -> manager.settings().sniper().skillCooldownSeconds();
            case SUPPLY -> 0;
        };
        return Math.max(1, seconds * 20);
    }

    private long supplyAppleIntervalTicks() {
        return manager.settings().supply().appleIntervalSeconds() * 20L;
    }

    private Location randomSpawn(Team team) {
        return (team == Team.RED ? redSpawn : blueSpawn).clone();
    }

    private boolean isLivingOnline(Player player) {
        return player != null && player.isOnline() && !player.isDead();
    }

    private static boolean sameWorld(Player first, Player second) {
        return first.getWorld().getUID().equals(second.getWorld().getUID());
    }

    private static boolean sameWorld(Player player, Location location) {
        return player != null && location != null && location.getWorld() != null
                && player.getWorld().getUID().equals(location.getWorld().getUID());
    }

    private static double maxHealth(Player player) {
        var attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0D : attribute.getValue();
    }

    private void broadcast(String message, Player except) {
        for (UUID playerId : participants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline() && player != except) {
                Messages.send(player, message);
            }
        }
    }

    private static void cancelTask(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    boolean isParticipant(UUID playerId) {
        return participants.contains(playerId);
    }

    boolean isActivePlayer(UUID playerId) {
        return activePlayers.contains(playerId);
    }

    boolean isRunning() {
        return state != State.ENDED;
    }

    boolean isCountdown() {
        return state == State.COUNTDOWN;
    }

    boolean isPlaying() {
        return state == State.PLAYING;
    }

    boolean isMovementLocked(UUID playerId) {
        return isCountdown() || respawnProtectedUntil.getOrDefault(playerId, 0L) > gameTicks;
    }

    boolean isInvulnerable(UUID playerId) {
        return isCountdown() || respawnProtectedUntil.getOrDefault(playerId, 0L) > gameTicks;
    }

    Team teamOf(UUID playerId) {
        return teams.get(playerId);
    }

    Job jobOf(UUID playerId) {
        return jobs.get(playerId);
    }

    Location spawnFor(UUID playerId) {
        Location location = spawnLocations.get(playerId);
        return location == null ? null : location.clone();
    }

    String statusText() {
        String phase = switch (state) {
            case PREPARING -> "准备中";
            case COUNTDOWN -> "倒计时 " + countdownRemaining + " 秒";
            case PLAYING -> "进行中";
            case ENDED -> "已结束";
        };
        return "占点游戏：" + phase + "，红队 " + redScore + "/"
                + manager.settings().winningScore() + "，蓝队 " + blueScore + "/"
                + manager.settings().winningScore() + "，参赛 " + activePlayers.size() + " 人。";
    }

    static final class ClassSelectionHolder implements InventoryHolder {

        private final PvpGameMatch match;
        private Inventory inventory;

        private ClassSelectionHolder(PvpGameMatch match) {
            this.match = match;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
