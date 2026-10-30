package com.d0ck.aibuddy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * config/aibuddy.json —— 单人游戏下客户端设置界面和服务端指令共用同一个文件。
 */
public class AiBuddyConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("aibuddy.json");
    private static AiBuddyConfig INSTANCE;

    /** OpenAI 兼容接口地址（到 /v1 为止），会自动拼接 /chat/completions */
    public String baseUrl = "https://api.deepseek.com/v1";
    public String apiKey = "";
    public String model = "deepseek-chat";
    /** 伙伴名字 */
    public String buddyName = "小克";
    /** 额外人设，会附加到系统提示词 */
    public String persona = "开朗、靠谱、有点话痨的游戏搭子，说话简短口语化。";
    /** 遇到危险/天黑等事件时主动说话（会额外消耗少量 token） */
    public boolean autoChat = true;
    /** 聊天前缀：为空时主人说的每句话都发给伙伴；设为 "@" 则只有 @ 开头的话才发给伙伴 */
    public String chatPrefix = "";
    public double temperature = 0.7;
    public int maxHistory = 16;

    public static AiBuddyConfig get() {
        if (INSTANCE == null) load();
        return INSTANCE;
    }

    public static synchronized void load() {
        try {
            if (Files.exists(PATH)) {
                INSTANCE = GSON.fromJson(Files.readString(PATH, StandardCharsets.UTF_8), AiBuddyConfig.class);
            }
        } catch (Exception e) {
            AiBuddyMod.LOGGER.warn("读取 aibuddy.json 失败，使用默认配置", e);
        }
        if (INSTANCE == null) INSTANCE = new AiBuddyConfig();
        save();
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(INSTANCE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AiBuddyMod.LOGGER.warn("保存 aibuddy.json 失败", e);
        }
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
