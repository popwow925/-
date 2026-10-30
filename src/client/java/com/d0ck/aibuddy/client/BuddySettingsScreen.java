package com.d0ck.aibuddy.client;

import com.d0ck.aibuddy.AiBuddyConfig;
import com.d0ck.aibuddy.ai.LlmClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.List;

/** 按 K 打开：填写接口地址、API Key、模型 */
public class BuddySettingsScreen extends Screen {
    private record Preset(String name, String url, String model) {}

    private static final List<Preset> PRESETS = List.of(
            new Preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
            new Preset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
            new Preset("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
            new Preset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
            new Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
            new Preset("本地 Ollama", "http://localhost:11434/v1", "qwen2.5:7b"));

    private final Screen parent;
    private TextFieldWidget urlField, keyField, modelField, nameField, personaField;
    private boolean showKey = false;
    private int presetIndex = -1;
    private volatile String status = "";

    public BuddySettingsScreen(Screen parent) {
        super(Text.literal("AI 伙伴设置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        AiBuddyConfig c = AiBuddyConfig.get();
        int w = Math.min(320, this.width - 40);
        int x = (this.width - w) / 2;
        int y = 46;
        int gap = 34;

        urlField = field(x, y, w, c.baseUrl, "接口地址，例如 https://api.deepseek.com/v1");
        keyField = field(x, y + gap, w - 50, c.apiKey, "API Key（sk-...）");
        keyField.setRenderTextProvider((str, first) -> showKey
                ? OrderedText.styledForwardsVisitedString(str, Style.EMPTY)
                : OrderedText.styledForwardsVisitedString("*".repeat(str.length()), Style.EMPTY));
        addDrawableChild(ButtonWidget.builder(Text.literal("显示"), b -> {
            showKey = !showKey;
            b.setMessage(Text.literal(showKey ? "隐藏" : "显示"));
        }).dimensions(x + w - 46, y + gap, 46, 20).build());
        modelField = field(x, y + gap * 2, w, c.model, "模型名，例如 deepseek-chat");
        nameField = field(x, y + gap * 3, w, c.buddyName, "伙伴名字");
        personaField = field(x, y + gap * 4, w, c.persona, "性格/人设");

        int by = y + gap * 5;
        int bw = (w - 8) / 3;
        addDrawableChild(ButtonWidget.builder(Text.literal("预设: 选择服务商"), b -> {
            presetIndex = (presetIndex + 1) % PRESETS.size();
            Preset p = PRESETS.get(presetIndex);
            urlField.setText(p.url());
            modelField.setText(p.model());
            b.setMessage(Text.literal("预设: " + p.name()));
        }).dimensions(x, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("测试连接"), b -> test())
                .dimensions(x + bw + 4, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("保存"), b -> {
            save();
            close();
        }).dimensions(x + (bw + 4) * 2, by, bw, 20).build());
    }

    private TextFieldWidget field(int x, int y, int w, String value, String placeholder) {
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, x, y, w, 20, Text.literal(placeholder));
        f.setMaxLength(1024);
        f.setText(value == null ? "" : value);
        f.setPlaceholder(Text.literal(placeholder));
        addDrawableChild(f);
        return f;
    }

    private void save() {
        AiBuddyConfig c = AiBuddyConfig.get();
        c.baseUrl = urlField.getText().trim();
        c.apiKey = keyField.getText().trim();
        c.model = modelField.getText().trim();
        if (!nameField.getText().isBlank()) c.buddyName = nameField.getText().trim();
        c.persona = personaField.getText().trim();
        AiBuddyConfig.save();
    }

    private void test() {
        save();
        status = "§e测试中…";
        LlmClient.chat(List.of(new LlmClient.Msg("user", "只回复两个字：在呢")))
                .whenComplete((r, e) -> {
                    if (e != null) {
                        Throwable c = e.getCause() != null ? e.getCause() : e;
                        status = "§c失败：" + c.getMessage();
                    } else {
                        status = "§a成功！模型回复：" + r.trim();
                    }
                });
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int w = Math.min(320, this.width - 40);
        int x = (this.width - w) / 2;
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFFFFF);
        String[] labels = {"接口地址 (OpenAI 兼容)", "API Key", "模型", "伙伴名字", "性格"};
        for (int i = 0; i < labels.length; i++) {
            ctx.drawTextWithShadow(textRenderer, labels[i], x, 46 + i * 34 - 11, 0xA0A0A0);
        }
        String s = status;
        if (!s.isEmpty()) {
            String shown = textRenderer.trimToWidth(s, w);
            ctx.drawTextWithShadow(textRenderer, shown, x, 46 + 34 * 5 + 28, 0xFFFFFF);
        }
        ctx.drawTextWithShadow(textRenderer, "召唤：/aibuddy summon   右键伙伴打开背包", x, this.height - 20, 0x808080);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
