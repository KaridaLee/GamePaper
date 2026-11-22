package com.gamepaper.tictactoe;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class TntGameSettings {

    private int maxPlayers;
    private int startCountdownSeconds;
    private int roundSeconds;
    private int cooldownSeconds;
    private List<SpawnPoint> spawnPoints;
    private Boundary boundary;

    TntGameSettings(JavaPlugin plugin) {
        reload(plugin);
    }

    void reload(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        this.maxPlayers = Math.max(2,
                config.getInt("tnt-game.max-players", 5));
        this.startCountdownSeconds = Math.max(1,
                config.getInt("tnt-game.start-countdown-seconds", 10));
        this.roundSeconds = Math.max(1,
                config.getInt("tnt-game.round-seconds", 30));
        this.cooldownSeconds = Math.max(0,
                config.getInt("tnt-game.cooldown-seconds", 5));
        this.spawnPoints = readSpawnPoints(config);
        this.boundary = readBoundary(config);
    }

    int maxPlayers() {
        return maxPlayers;
    }

    int startCountdownSeconds() {
        return startCountdownSeconds;
    }

    int roundSeconds() {
        return roundSeconds;
    }

    int cooldownSeconds() {
        return cooldownSeconds;
    }

    List<SpawnPoint> spawnPoints() {
        return spawnPoints;
    }

    Boundary boundary() {
        return boundary;
    }

    private static List<SpawnPoint> readSpawnPoints(FileConfiguration config) {
        List<Map<?, ?>> values = config.getMapList("tnt-game.spawn-points");
        List<SpawnPoint> points = new ArrayList<>();
        for (Map<?, ?> value : values) {
            Object world = value.get("world");
            if (world == null) {
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
        ConfigurationSection section = config.getConfigurationSection("tnt-game.boundary");
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
