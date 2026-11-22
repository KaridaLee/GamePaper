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

final class RandomGameSettings {

    private static final Set<String> DEFAULT_EXCLUDED_MATERIALS = Set.of(
            "BEDROCK",
            "END_PORTAL_FRAME",
            "OBSIDIAN",
            "CRYING_OBSIDIAN",
            "BARRIER",
            "COMMAND_BLOCK",
            "CHAIN_COMMAND_BLOCK",
            "REPEATING_COMMAND_BLOCK",
            "STRUCTURE_BLOCK",
            "STRUCTURE_VOID",
            "JIGSAW",
            "LIGHT",
            "SPAWNER",
            "DEBUG_STICK",
            "KNOWLEDGE_BOOK",
            "REINFORCED_DEEPSLATE"
    );

    private int maxPlayers;
    private int itemIntervalSeconds;
    private int countdownSeconds;
    private int randomEventStartDelaySeconds;
    private int randomEventIntervalSeconds;
    private List<SpawnPoint> spawnPoints;
    private Boundary boundary;
    private Set<String> commandWhitelist;
    private Set<String> excludedMaterials;

    RandomGameSettings(JavaPlugin plugin) {
        reload(plugin);
    }

    void reload(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        this.maxPlayers = Math.max(2, Math.min(6,
                config.getInt("random-game.max-players", 6)));
        this.itemIntervalSeconds = Math.max(1,
                config.getInt("random-game.item-interval-seconds", 10));
        this.countdownSeconds = Math.max(3,
                config.getInt("random-game.countdown-seconds", 10));
        this.randomEventStartDelaySeconds = Math.max(1,
                config.getInt("random-game.event-start-delay-seconds", 180));
        this.randomEventIntervalSeconds = Math.max(1,
                config.getInt("random-game.event-interval-seconds", 90));
        this.spawnPoints = readSpawnPoints(config);
        this.boundary = readBoundary(config);
        this.commandWhitelist = readCommandWhitelist(config);
        this.excludedMaterials = readExcludedMaterials(config);
    }

    int maxPlayers() {
        return maxPlayers;
    }

    int itemIntervalSeconds() {
        return itemIntervalSeconds;
    }

    int countdownSeconds() {
        return countdownSeconds;
    }

    int randomEventStartDelaySeconds() {
        return randomEventStartDelaySeconds;
    }

    int randomEventIntervalSeconds() {
        return randomEventIntervalSeconds;
    }

    List<SpawnPoint> spawnPoints() {
        return spawnPoints;
    }

    Boundary boundary() {
        return boundary;
    }

    Set<String> commandWhitelist() {
        return commandWhitelist;
    }

    boolean isExcluded(Material material) {
        String materialName = material.name();
        return excludedMaterials.contains(materialName)
                || materialName.endsWith("_COMMAND_BLOCK")
                || materialName.endsWith("_PORTAL_FRAME")
                || materialName.endsWith("_SPAWN_EGG");
    }

    private static List<SpawnPoint> readSpawnPoints(FileConfiguration config) {
        List<Map<?, ?>> values = config.getMapList("random-game.spawn-points");
        List<SpawnPoint> points = new java.util.ArrayList<>();
        for (Map<?, ?> value : values) {
            Object world = value.get("world");
            if (world == null || points.size() >= 6) {
                continue;
            }
            String worldName = String.valueOf(world).trim();
            if (worldName.isEmpty()) {
                continue;
            }
            points.add(new SpawnPoint(
                    worldName,
                    number(value.get("x"), 0.5D),
                    number(value.get("y"), 100.0D),
                    number(value.get("z"), 0.5D)
            ));
        }
        return List.copyOf(points);
    }

    private static Boundary readBoundary(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("random-game.boundary");
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

    private static Set<String> readCommandWhitelist(FileConfiguration config) {
        Set<String> whitelist = new HashSet<>();
        for (String command : config.getStringList("random-game.command-whitelist")) {
            String normalized = command.trim().toLowerCase(Locale.ROOT);
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            if (!normalized.isEmpty()) {
                whitelist.add(normalized.split("\\s+", 2)[0]);
            }
        }
        if (whitelist.isEmpty()) {
            whitelist.add("game");
        }
        return Set.copyOf(whitelist);
    }

    private static Set<String> readExcludedMaterials(FileConfiguration config) {
        Set<String> excluded = new HashSet<>(DEFAULT_EXCLUDED_MATERIALS);
        for (String material : config.getStringList("random-game.excluded-materials")) {
            if (material != null && !material.isBlank()) {
                excluded.add(material.trim().toUpperCase(Locale.ROOT));
            }
        }
        return Set.copyOf(excluded);
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    record SpawnPoint(String worldName, double x, double y, double z) {

        Location resolve() {
            World world = Bukkit.getWorld(worldName);
            return world == null ? null : new Location(world, x, y, z);
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
}
