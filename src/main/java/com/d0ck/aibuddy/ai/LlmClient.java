package com.d0ck.aibuddy.ai;

import com.d0ck.aibuddy.AiBuddyConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** OpenAI 兼容 /chat/completions 客户端（DeepSeek、通义、Kimi、智谱、OpenAI、本地 Ollama 等都可用） */
public final class LlmClient {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private LlmClient() {}

    public record Msg(String role, String content) {}

    public static CompletableFuture<String> chat(List<Msg> messages) {
        AiBuddyConfig cfg = AiBuddyConfig.get();
        String base = cfg.baseUrl.trim().replaceAll("/+$", "");
        String url = base.endsWith("/chat/completions") ? base : base + "/chat/completions";

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.addProperty("temperature", cfg.temperature);
        body.addProperty("stream", false);
        JsonArray arr = new JsonArray();
        for (Msg m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content());
            arr.add(o);
        }
        body.add("messages", arr);

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.apiKey.trim())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new RuntimeException("API 地址格式不对: " + url));
        }

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(resp -> {
            if (resp.statusCode() / 100 != 2) {
                String b = resp.body();
                if (b.length() > 200) b = b.substring(0, 200);
                throw new RuntimeException("HTTP " + resp.statusCode() + ": " + b);
            }
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            return json.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
        });
    }
}
