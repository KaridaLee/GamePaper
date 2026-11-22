package com.gamepaper.tictactoe;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DeepSeekWordProvider {

    private static final Pattern CONTENT_PATTERN = Pattern.compile(
            "\\\"content\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
    private static final Pattern CIVILIAN_PATTERN = Pattern.compile(
            "\\\"civilian\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
    private static final Pattern UNDERCOVER_PATTERN = Pattern.compile(
            "\\\"undercover\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");

    private final HttpClient client;

    DeepSeekWordProvider() {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    CompletableFuture<UndercoverGameSettings.WordPair> request(
            UndercoverGameSettings.AiSettings settings,
            Set<String> usedWords, Set<String> usedPairs, int roundNumber) {
        if (!settings.hasApiKey()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("DeepSeek API Key 为空"));
        }
        try {
            String userPrompt = buildUserPrompt(
                    settings.roundPrompt(), usedWords, usedPairs, roundNumber);
            String requestBody = "{" +
                    "\"model\":\"" + jsonEscape(settings.model()) + "\"," +
                    "\"messages\":[" +
                    "{\"role\":\"system\",\"content\":\""
                    + jsonEscape(settings.systemPrompt()) + "\"}," +
                    "{\"role\":\"user\",\"content\":\"" + jsonEscape(userPrompt) + "\"}" +
                    "]," +
                    "\"response_format\":{\"type\":\"json_object\"}," +
                    "\"thinking\":{\"type\":\"disabled\"}," +
                    "\"temperature\":1.35," +
                    "\"max_tokens\":120" +
                    "}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(settings.baseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                    .header("Authorization", "Bearer " + settings.apiKey())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            return client.sendAsync(request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> parseResponse(response, usedWords, usedPairs));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private UndercoverGameSettings.WordPair parseResponse(
            HttpResponse<String> response, Set<String> usedWords, Set<String> usedPairs) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("DeepSeek HTTP " + response.statusCode());
        }
        Matcher contentMatcher = CONTENT_PATTERN.matcher(response.body());
        if (!contentMatcher.find()) {
            throw new IllegalStateException("DeepSeek 响应缺少 choices.message.content");
        }
        String content = unescapeJson(contentMatcher.group(1)).trim();
        Matcher civilianMatcher = CIVILIAN_PATTERN.matcher(content);
        Matcher undercoverMatcher = UNDERCOVER_PATTERN.matcher(content);
        if (!civilianMatcher.find() || !undercoverMatcher.find()) {
            throw new IllegalStateException("DeepSeek 内容不符合固定 JSON 格式");
        }
        String civilian = sanitizeWord(unescapeJson(civilianMatcher.group(1)));
        String undercover = sanitizeWord(unescapeJson(undercoverMatcher.group(1)));
        validatePair(civilian, undercover, usedWords, usedPairs);
        return new UndercoverGameSettings.WordPair(civilian, undercover);
    }

    private static String buildUserPrompt(String template, Set<String> usedWords,
                                          Set<String> usedPairs, int roundNumber) {
        List<String> history = new ArrayList<>(usedPairs);
        String historyText = history.isEmpty() ? "无"
                : String.join("；", history.subList(Math.max(0, history.size() - 30), history.size()));
        List<String> words = new ArrayList<>(usedWords);
        String wordText = words.isEmpty() ? "无"
                : String.join("、", words.subList(Math.max(0, words.size() - 60), words.size()));
        return template
                .replace("{round}", Integer.toString(roundNumber))
                .replace("{used_words}", wordText)
                .replace("{used_pairs}", historyText);
    }

    private static void validatePair(String civilian, String undercover,
                                     Set<String> usedWords, Set<String> usedPairs) {
        if (civilian.isBlank() || undercover.isBlank()
                || civilian.equalsIgnoreCase(undercover)) {
            throw new IllegalStateException("DeepSeek 返回了空词或相同词");
        }
        if (civilian.codePointCount(0, civilian.length()) > 8
                || undercover.codePointCount(0, undercover.length()) > 8) {
            throw new IllegalStateException("DeepSeek 返回的词语超过 8 个字符");
        }
        String first = normalize(civilian);
        String second = normalize(undercover);
        if (usedWords.contains(first) || usedWords.contains(second)
                || usedPairs.contains(pairKey(first, second))) {
            throw new IllegalStateException("DeepSeek 返回了本局已经使用过的词");
        }
    }

    static String pairKey(String first, String second) {
        String a = normalize(first);
        String b = normalize(second);
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String sanitizeWord(String value) {
        return value.trim().replaceAll("^[\\s\\\"'“”‘’]+|[\\s\\\"'“”‘’]+$", "");
    }

    private static String jsonEscape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 32);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.toString();
    }

    private static String unescapeJson(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\\' || index + 1 >= value.length()) {
                result.append(character);
                continue;
            }
            char escaped = value.charAt(++index);
            switch (escaped) {
                case '"' -> result.append('"');
                case '\\' -> result.append('\\');
                case '/' -> result.append('/');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (index + 4 >= value.length()) {
                        throw new IllegalStateException("DeepSeek JSON 中存在不完整 Unicode 转义");
                    }
                    try {
                        result.append((char) Integer.parseInt(
                                value.substring(index + 1, index + 5), 16));
                    } catch (NumberFormatException exception) {
                        throw new IllegalStateException("DeepSeek JSON 中存在无效 Unicode 转义");
                    }
                    index += 4;
                }
                default -> result.append(escaped);
            }
        }
        return result.toString();
    }
}
