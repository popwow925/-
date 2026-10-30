package com.d0ck.aibuddy.ai;

import com.d0ck.aibuddy.AiBuddyConfig;
import com.d0ck.aibuddy.AiBuddyMod;
import com.d0ck.aibuddy.entity.AiBuddyEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 伙伴的“大脑”：把游戏状态整理成文字发给大模型，再把返回的 JSON 指令交给实体执行。
 * 大模型只做决策（说什么、做什么），具体走路/挖掘/战斗由本地代码每 tick 执行，省 token 也不卡。
 */
public class BuddyBrain {
    private final AiBuddyEntity buddy;
    private final Deque<LlmClient.Msg> history = new ArrayDeque<>();
    private boolean busy = false;
    private String pending = null;
    private long lastEventTick = -100000;

    public BuddyBrain(AiBuddyEntity buddy) {
        this.buddy = buddy;
    }

    public void clearHistory() {
        history.clear();
    }

    /** 主人说话 */
    public void onOwnerChat(String text) {
        runOnServer(() -> request("主人说：" + text));
    }

    /** 游戏事件（受伤、天黑等），带冷却 */
    public void onEvent(String event) {
        runOnServer(() -> {
            if (!AiBuddyConfig.get().autoChat || !AiBuddyConfig.get().hasKey()) return;
            long now = buddy.getWorld().getTime();
            if (busy || now - lastEventTick < 20 * 45) return;
            lastEventTick = now;
            request("【事件】" + event);
        });
    }

    /** 任务完成/失败时的回报：只有空闲时才说，避免刷屏 */
    public void onTaskReport(String report) {
        runOnServer(() -> {
            if (!AiBuddyConfig.get().hasKey() || busy) return;
            long now = buddy.getWorld().getTime();
            if (now - lastEventTick < 20 * 8) return;
            lastEventTick = now;
            request("【任务回报】" + report + "（简短告诉主人结果，如无必要不要再安排新动作）");
        });
    }

    private void runOnServer(Runnable r) {
        MinecraftServer server = buddy.getServer();
        if (server == null) return;
        server.execute(r);
    }

    private void request(String userContent) {
        AiBuddyConfig cfg = AiBuddyConfig.get();
        if (!cfg.hasKey()) {
            buddy.say("（还没设置 API Key，按 K 或用 /aibuddy key 设置后我就能说话啦）");
            return;
        }
        if (busy) {
            pending = pending == null ? userContent : pending + "\n" + userContent;
            return;
        }
        busy = true;

        String observation = buddy.observe();
        List<LlmClient.Msg> msgs = new ArrayList<>();
        msgs.add(new LlmClient.Msg("system", Prompts.system(cfg)));
        msgs.addAll(history);
        msgs.add(new LlmClient.Msg("user", "【当前状态】\n" + observation + "\n\n" + userContent));

        LlmClient.chat(msgs).whenComplete((reply, err) -> runOnServer(() -> {
            busy = false;
            if (buddy.isRemoved()) return;
            if (err != null) {
                Throwable c = err.getCause() != null ? err.getCause() : err;
                AiBuddyMod.LOGGER.warn("AI 请求失败", c);
                buddy.sayError("AI 请求失败：" + c.getMessage());
            } else {
                remember("user", userContent);
                remember("assistant", reply);
                apply(reply);
            }
            if (pending != null) {
                String p = pending;
                pending = null;
                request(p);
            }
        }));
    }

    private void remember(String role, String content) {
        history.addLast(new LlmClient.Msg(role, content));
        int max = Math.max(2, AiBuddyConfig.get().maxHistory);
        while (history.size() > max) history.removeFirst();
    }

    private void apply(String reply) {
        JsonObject obj = extractJson(reply);
        if (obj == null) {
            buddy.say(reply.trim());
            return;
        }
        if (obj.has("say") && !obj.get("say").isJsonNull()) {
            String s = obj.get("say").getAsString().trim();
            if (!s.isEmpty()) buddy.say(s);
        }
        if (obj.has("actions") && obj.get("actions").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("actions");
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                try {
                    buddy.executeAction(e.getAsJsonObject());
                } catch (Exception ex) {
                    AiBuddyMod.LOGGER.warn("执行动作失败: {}", e, ex);
                }
            }
        }
    }

    /** 宽松解析：模型可能在 JSON 外包了 ```json 或多余文字 */
    static JsonObject extractJson(String s) {
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            JsonElement el = JsonParser.parseString(s.substring(a, b + 1));
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
