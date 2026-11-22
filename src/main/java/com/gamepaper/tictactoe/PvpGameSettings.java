package com.gamepaper.tictactoe;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class PvpGameSettings {

    private int maxPlayers;
    private int winningScore;
    private int captureSeconds;
    private int startCountdownSeconds;
    private int respawnProtectionSeconds;
    private int emptyZoneSeconds;
    private SpawnPoint redSpawnPoint;
    private SpawnPoint blueSpawnPoint;
    private Boundary boundary;
    private CaptureZone captureZone;
    private Set<String> commandWhitelist;
    private AssaultConfig assault;
    private SupplyConfig supply;
    private SniperConfig sniper;

    PvpGameSettings(JavaPlugin plugin) {
        reload(plugin);
    }

    void reload(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        this.maxPlayers = Math.max(2, Math.min(32,
                config.getInt("pvp-game.max-players", 12)));
        this.winningScore = Math.max(1,
                config.getInt("pvp-game.winning-score", 10));
        this.captureSeconds = Math.max(1,
                config.getInt("pvp-game.capture-seconds", 10));
        this.startCountdownSeconds = Math.max(1,
                config.getInt("pvp-game.start-countdown-seconds", 10));
        this.respawnProtectionSeconds = Math.max(0,
                config.getInt("pvp-game.respawn-protection-seconds", 5));
        this.emptyZoneSeconds = Math.max(0,
                config.getInt("pvp-game.empty-zone-seconds", 20));
        this.redSpawnPoint = readSpawnPoint(config, "pvp-game.team-spawns.red");
        this.blueSpawnPoint = readSpawnPoint(config, "pvp-game.team-spawns.blue");
        this.boundary = readBoundary(config);
        this.captureZone = readCaptureZone(config);
        this.commandWhitelist = readCommandWhitelist(config);
        this.assault = readAssault(config);
        this.supply = readSupply(config);
        this.sniper = readSniper(config);
    }

    int maxPlayers() {
        return maxPlayers;
    }

    int winningScore() {
        return winningScore;
    }

    int captureSeconds() {
        return captureSeconds;
    }

    int startCountdownSeconds() {
        return startCountdownSeconds;
    }

    int respawnProtectionSeconds() {
        return respawnProtectionSeconds;
    }

    int emptyZoneSeconds() {
        return emptyZoneSeconds;
    }

    SpawnPoint redSpawnPoint() {
        return redSpawnPoint;
    }

    SpawnPoint blueSpawnPoint() {
        return blueSpawnPoint;
    }

    Boundary boundary() {
        return boundary;
    }

    CaptureZone captureZone() {
        return captureZone;
    }

    Set<String> commandWhitelist() {
        return commandWhitelist;
    }

    AssaultConfig assault() {
        return assault;
    }

    SupplyConfig supply() {
        return supply;
    }

    SniperConfig sniper() {
        return sniper;
    }

    private static SpawnPoint readSpawnPoint(FileConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section != null && section.getString("world") != null) {
            return spawnPointFromValues(
                    section.getString("world"), section.getDouble("x", 0.5D),
                    section.getDouble("y", 100.0D), section.getDouble("z", 0.5D),
                    (float) section.getDouble("yaw", 0.0D),
                    (float) section.getDouble("pitch", 0.0D));
        }

        // 兼容 0.7.0 的列表写法：旧配置只取每队列表中的第一个重生点。
        List<Map<?, ?>> legacyValues = config.getMapList(path);
        if (!legacyValues.isEmpty()) {
            Map<?, ?> value = legacyValues.get(0);
            return spawnPointFromValues(
                    value.get("world"), number(value.get("x"), 0.5D),
                    number(value.get("y"), 100.0D), number(value.get("z"), 0.5D),
                    (float) number(value.get("yaw"), 0.0D),
                    (float) number(value.get("pitch"), 0.0D));
        }
        return null;
    }

    private static SpawnPoint spawnPointFromValues(Object worldValue, double x, double y,
                                                   double z, float yaw, float pitch) {
        if (worldValue == null) {
            return null;
        }
        String worldName = String.valueOf(worldValue).trim();
        return worldName.isEmpty() ? null : new SpawnPoint(worldName, x, y, z, yaw, pitch);
    }

    private static Boundary readBoundary(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("pvp-game.boundary");
        String worldName = section == null ? "world" : section.getString("world", "world");
        int minX = section == null ? -32 : section.getInt("min-x", -32);
        int maxX = section == null ? 32 : section.getInt("max-x", 32);
        int minY = section == null ? 0 : section.getInt("min-y", 0);
        int maxY = section == null ? 128 : section.getInt("max-y", 128);
        int minZ = section == null ? -32 : section.getInt("min-z", -32);
        int maxZ = section == null ? 32 : section.getInt("max-z", 32);
        return new Boundary(
                worldName == null || worldName.isBlank() ? "world" : worldName,
                Math.min(minX, maxX), Math.max(minX, maxX),
                Math.min(minY, maxY), Math.max(minY, maxY),
                Math.min(minZ, maxZ), Math.max(minZ, maxZ)
        );
    }

    private static CaptureZone readCaptureZone(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("pvp-game.capture-zone");
        String defaultWorld = config.getString("pvp-game.boundary.world", "world");
        String worldName = section == null
                ? (defaultWorld == null || defaultWorld.isBlank() ? "world" : defaultWorld)
                : section.getString("world", defaultWorld);
        return new CaptureZone(
                worldName == null || worldName.isBlank() ? "world" : worldName,
                section == null ? 0.5D : section.getDouble("x", 0.5D),
                section == null ? 100.0D : section.getDouble("y", 100.0D),
                section == null ? 0.5D : section.getDouble("z", 0.5D),
                Math.max(0.5D, section == null ? 5.0D : section.getDouble("radius", 5.0D)),
                section == null ? 0.0D : section.getDouble("min-y", 0.0D),
                section == null ? 128.0D : section.getDouble("max-y", 128.0D)
        );
    }

    private static Set<String> readCommandWhitelist(FileConfiguration config) {
        Set<String> whitelist = new HashSet<>();
        for (String command : config.getStringList("pvp-game.command-whitelist")) {
            String normalized = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            if (!normalized.isEmpty()) {
                String[] parts = normalized.split("\\s+");
                whitelist.add(parts.length >= 2
                        ? parts[0] + " " + parts[1] : parts[0]);
            }
        }
        if (whitelist.isEmpty()) {
            whitelist.add("game pvp");
        }
        return Set.copyOf(whitelist);
    }

    private static AssaultConfig readAssault(FileConfiguration config) {
        String path = "pvp-game.classes.assault";
        return new AssaultConfig(
                readGear(config, path, Material.STONE_SWORD, 32, false, 2,
                        Material.IRON_HELMET, true, 0, 0),
                Math.max(1.0D, config.getDouble(path + ".dash-distance", 10.0D)),
                Math.max(1, config.getInt(path + ".skill-cooldown-seconds", 30))
        );
    }

    private static SupplyConfig readSupply(FileConfiguration config) {
        String path = "pvp-game.classes.supply";
        return new SupplyConfig(
                readGear(config, path, Material.WOODEN_SWORD, 0, true, 0,
                        Material.LEATHER, true, 0, 1),
                Math.max(1.0D, config.getDouble(path + ".support-radius", 3.0D)),
                Math.max(1, config.getInt(path + ".apple-interval-seconds", 3)),
                Math.max(1, config.getInt(path + ".repair-interval-ticks", 3))
        );
    }

    private static SniperConfig readSniper(FileConfiguration config) {
        String path = "pvp-game.classes.sniper";
        return new SniperConfig(
                readGear(config, path, Material.BOW, 32, false, 0,
                        Material.LEATHER, true, 32, 0),
                Math.max(1, config.getInt(path + ".glowing-seconds", 5)),
                Math.max(0, config.getInt(path + ".speed-seconds", 10)),
                Math.max(0, config.getInt(path + ".speed-amplifier", 0)),
                Math.max(1.0D, config.getDouble(path + ".back-dash-distance", 5.0D)),
                Math.max(1, config.getInt(path + ".skill-cooldown-seconds", 30))
        );
    }

    private static GearConfig readGear(FileConfiguration config, String path,
                                        Material defaultWeapon, int defaultRemainingDurability,
                                        boolean defaultWeaponUnbreakable, int defaultGoldenApples,
                                        Material defaultArmor, boolean defaultArmorUnbreakable,
                                        int defaultSpectralArrows, int defaultKnockback) {
        Material weapon = material(config.getString(path + ".weapon.material"), defaultWeapon);
        Material armor = material(config.getString(path + ".armor.material"), defaultArmor);
        return new GearConfig(
                weapon,
                Math.max(0, config.getInt(path + ".weapon.remaining-durability",
                        defaultRemainingDurability)),
                config.getBoolean(path + ".weapon.unbreakable", defaultWeaponUnbreakable),
                Math.max(1, config.getInt(path + ".weapon.amount", 1)),
                Math.max(0, config.getInt(path + ".golden-apples", defaultGoldenApples)),
                armor,
                config.getBoolean(path + ".armor.unbreakable", defaultArmorUnbreakable),
                Math.max(0, config.getInt(path + ".spectral-arrows", defaultSpectralArrows)),
                Math.max(0, config.getInt(path + ".weapon.knockback", defaultKnockback))
        );
    }

    private static Material material(String value, Material fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Material parsed = Material.matchMaterial(value.trim());
        return parsed == null ? fallback : parsed;
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    record SpawnPoint(String worldName, double x, double y, double z, float yaw, float pitch) {

        Location resolve() {
            World world = Bukkit.getWorld(worldName);
            return world == null ? null : new Location(world, x, y, z, yaw, pitch);
        }
    }

    record Boundary(String worldName, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {

        World resolveWorld() {
            return Bukkit.getWorld(worldName);
        }

        boolean contains(Location location) {
            if (location == null || location.getWorld() == null
                    || !location.getWorld().getName().equalsIgnoreCase(worldName)) {
                return false;
            }
            return location.getX() >= minX && location.getX() < maxX + 1.0D
                    && location.getY() >= minY && location.getY() < maxY + 1.0D
                    && location.getZ() >= minZ && location.getZ() < maxZ + 1.0D;
        }
    }

    record CaptureZone(String worldName, double x, double y, double z,
                       double radius, double minY, double maxY) {

        boolean contains(Location location) {
            if (location == null || location.getWorld() == null
                    || !location.getWorld().getName().equalsIgnoreCase(worldName)
                    || location.getY() < minY || location.getY() > maxY) {
                return false;
            }
            double dx = location.getX() - x;
            double dz = location.getZ() - z;
            return dx * dx + dz * dz <= radius * radius;
        }
    }

    record GearConfig(Material weaponMaterial, int weaponRemainingDurability,
                      boolean weaponUnbreakable, int weaponAmount, int goldenAppleCount,
                      Material armorMaterial, boolean armorUnbreakable,
                      int spectralArrowCount, int weaponKnockback) {
    }

    record AssaultConfig(GearConfig gear, double dashDistance, int skillCooldownSeconds) {
    }

    record SupplyConfig(GearConfig gear, double supportRadius, int appleIntervalSeconds,
                        int repairIntervalTicks) {
    }

    record SniperConfig(GearConfig gear, int glowingSeconds, int speedSeconds,
                        int speedAmplifier, double backDashDistance,
                        int skillCooldownSeconds) {
    }
}
