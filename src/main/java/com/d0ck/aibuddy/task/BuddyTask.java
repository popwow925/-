package com.d0ck.aibuddy.task;

import com.d0ck.aibuddy.entity.AiBuddyEntity;

import java.util.Locale;

/** 伙伴的一个具体任务，由本地代码每 tick 推进。 */
public interface BuddyTask {
    /** @return true 表示任务结束 */
    boolean tick(AiBuddyEntity b);

    /** 给 AI 看的任务描述 */
    String describe();

    /** 结束时的结果（可为 null，不回报） */
    default String result() { return null; }

    /** 战斗中是否暂停 */
    default boolean pausesInCombat() { return true; }

    /**
     * 英文 id 匹配：按 "_" 分词包含即可。
     * log -> oak_log / stripped_birch_log；iron_ore -> deepslate_iron_ore；coal -> coal_ore
     */
    static boolean matches(String path, String query) {
        if (query == null || query.isBlank()) return false;
        String q = query.toLowerCase(Locale.ROOT).trim().replace("minecraft:", "").replace(' ', '_');
        if (q.equals("wood") || q.equals("tree")) q = "log";
        if (path.equals(q)) return true;
        return ("_" + path + "_").contains("_" + q + "_");
    }
}
