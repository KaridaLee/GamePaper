package com.gamepaper.tictactoe;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class UndercoverGameSettings {

    private static final String DEFAULT_AI_SYSTEM_PROMPT = """
            你是“谁是卧底”小游戏的创意词语设计师。你必须只输出一个 JSON 对象，不能输出 Markdown、解释或额外字段。
            固定 JSON 格式：{"civilian":"平民词","undercover":"卧底词"}

            设计要求：
            1. 两个词必须是简体中文常见名词或短语，每个 1 至 8 个汉字，不能完全相同。
            2. 两者要有一个可描述的共同点，但真实类别、用途、气味、形状、场景或联想应存在有趣反差。
            3. 重点是让玩家仅看自己的词时难以反推出另一个词；避免苹果/梨、可乐/雪碧、猫/狗这类同类别近义配对。
            4. 鼓励荒诞但可理解的组合，例如“香蕉”和“狗粑粑”：外形或颜色有联系，但类别差异很大，描述时容易产生误导和笑点。
            5. 不得包含人身攻击、歧视、色情、政治敏感、违法或危险内容；轻微厕所幽默可以。
            6. 不要重复用户给出的历史词语或历史词组。
            7. 输出必须可由 JSON 解析器直接解析，示例：{"civilian":"香蕉","undercover":"狗粑粑"}
            """.strip();

    private static final String DEFAULT_AI_ROUND_PROMPT = """
            请为第 {round} 轮生成一组全新的、有反差且有趣的词。
            不得使用以下历史词：{used_words}
            不得重复以下历史词组：{used_pairs}
            只输出固定 JSON：{"civilian":"平民词","undercover":"卧底词"}
            """.strip();

    private int maxPlayers;
    private int descriptionSeconds;
    private int votingSeconds;
    private AiSettings aiSettings;
    private List<SpawnPoint> spawnPoints;
    private List<WordPair> wordPairs;

    UndercoverGameSettings(JavaPlugin plugin) {
        reload(plugin);
    }

    void reload(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        this.maxPlayers = Math.min(6, Math.max(3,
                config.getInt("undercover-game.max-players", 6)));
        this.descriptionSeconds = Math.max(5,
                config.getInt("undercover-game.description-seconds", 60));
        this.votingSeconds = Math.max(5,
                config.getInt("undercover-game.voting-seconds", 20));
        this.aiSettings = readAiSettings(config);
        this.spawnPoints = readSpawnPoints(config);
        this.wordPairs = readWordPairs(config);
    }

    int maxPlayers() {
        return maxPlayers;
    }

    int votingSeconds() {
        return votingSeconds;
    }

    int descriptionSeconds() {
        return descriptionSeconds;
    }

    AiSettings aiSettings() {
        return aiSettings;
    }

    List<SpawnPoint> spawnPoints() {
        return spawnPoints;
    }

    List<WordPair> wordPairs() {
        return wordPairs;
    }

    private static List<SpawnPoint> readSpawnPoints(FileConfiguration config) {
        List<SpawnPoint> points = new ArrayList<>();
        for (Map<?, ?> value : config.getMapList("undercover-game.spawn-points")) {
            if (points.size() >= 6) {
                break;
            }
            Object worldValue = value.get("world");
            if (worldValue == null || String.valueOf(worldValue).isBlank()) {
                continue;
            }
            points.add(new SpawnPoint(
                    String.valueOf(worldValue).trim(),
                    number(value.get("x"), 0.5D),
                    number(value.get("y"), 100.0D),
                    number(value.get("z"), 0.5D),
                    (float) number(value.get("yaw"), 0.0D),
                    (float) number(value.get("pitch"), 0.0D)
            ));
        }
        return List.copyOf(points);
    }

    private static List<WordPair> readWordPairs(FileConfiguration config) {
        List<WordPair> pairs = new ArrayList<>();
        for (Map<?, ?> value : config.getMapList("undercover-game.words")) {
            String civilian = text(value.get("civilian"));
            String undercover = text(value.get("undercover"));
            if (civilian.isEmpty() || undercover.isEmpty()
                    || civilian.equalsIgnoreCase(undercover)) {
                continue;
            }
            pairs.add(new WordPair(civilian, undercover));
        }
        return List.copyOf(pairs);
    }

    private static AiSettings readAiSettings(FileConfiguration config) {
        String baseUrl = config.getString(
                "undercover-game.ai.base-url", "https://api.deepseek.com");
        String model = config.getString(
                "undercover-game.ai.model", "deepseek-v4-flash");
        String apiKey = config.getString("undercover-game.ai.api-key", "");
        String systemPrompt = nonBlank(config.getString(
                "undercover-game.ai.system-prompt"), DEFAULT_AI_SYSTEM_PROMPT);
        String roundPrompt = nonBlank(config.getString(
                "undercover-game.ai.round-prompt"), DEFAULT_AI_ROUND_PROMPT);
        return new AiSettings(
                config.getBoolean("undercover-game.ai.enabled", false),
                normalizeBaseUrl(baseUrl),
                model == null || model.isBlank() ? "deepseek-v4-flash" : model.trim(),
                apiKey == null ? "" : apiKey.trim(),
                Math.max(3, config.getInt("undercover-game.ai.timeout-seconds", 15)),
                systemPrompt,
                roundPrompt
        );
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String normalizeBaseUrl(String value) {
        String url = value == null || value.isBlank()
                ? "https://api.deepseek.com" : value.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
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

    record WordPair(String civilianWord, String undercoverWord) {
    }

    record AiSettings(boolean enabled, String baseUrl, String model,
                      String apiKey, int timeoutSeconds,
                      String systemPrompt, String roundPrompt) {

        boolean usable() {
            return enabled && !apiKey.isBlank();
        }

        boolean hasApiKey() {
            return !apiKey.isBlank();
        }
    }
}
