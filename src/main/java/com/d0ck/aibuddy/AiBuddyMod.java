package com.d0ck.aibuddy;

import com.d0ck.aibuddy.entity.AiBuddyEntity;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AiBuddyMod implements ModInitializer {
    public static final String MOD_ID = "aibuddy";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final EntityType<AiBuddyEntity> BUDDY = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "buddy"),
            EntityType.Builder.create(AiBuddyEntity::new, SpawnGroup.MISC)
                    .dimensions(0.6f, 1.8f)
                    .eyeHeight(1.62f)
                    .maxTrackingRange(10)
                    .build("buddy"));

    /** 主人 UUID -> 当前加载中的伙伴 */
    public static final Map<UUID, AiBuddyEntity> ACTIVE = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        AiBuddyConfig.load();
        FabricDefaultAttributeRegistry.register(BUDDY, AiBuddyEntity.createBuddyAttributes());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("aibuddy")
                        .then(CommandManager.literal("summon").executes(AiBuddyMod::summon))
                        .then(CommandManager.literal("dismiss").executes(AiBuddyMod::dismiss))
                        .then(CommandManager.literal("status").executes(AiBuddyMod::status))
                        .then(CommandManager.literal("reset").executes(ctx -> {
                            AiBuddyEntity b = buddyOf(ctx.getSource());
                            if (b != null) b.brain().clearHistory();
                            ctx.getSource().sendFeedback(() -> Text.literal("已清空对话记忆"), false);
                            return 1;
                        }))
                        .then(CommandManager.literal("key").requires(AiBuddyMod::canConfigure)
                                .then(CommandManager.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> setConfig(ctx, "key"))))
                        .then(CommandManager.literal("url").requires(AiBuddyMod::canConfigure)
                                .then(CommandManager.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> setConfig(ctx, "url"))))
                        .then(CommandManager.literal("model").requires(AiBuddyMod::canConfigure)
                                .then(CommandManager.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> setConfig(ctx, "model"))))
                        .then(CommandManager.literal("name").requires(AiBuddyMod::canConfigure)
                                .then(CommandManager.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> setConfig(ctx, "name"))))
        ));

        // 主人的聊天 -> 伙伴
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            AiBuddyEntity buddy = getActive(sender.getUuid());
            if (buddy == null) return;
            String text = message.getSignedContent();
            String prefix = AiBuddyConfig.get().chatPrefix;
            if (prefix != null && !prefix.isEmpty()) {
                if (!text.startsWith(prefix)) return;
                text = text.substring(prefix.length()).trim();
            }
            if (!text.isEmpty()) buddy.brain().onOwnerChat(text);
        });

        // 进服提示
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            server.execute(() -> {
                if (!AiBuddyConfig.get().hasKey()) {
                    p.sendMessage(Text.literal("[AI伙伴] ").formatted(Formatting.GOLD)
                            .append(Text.literal("还没有设置 API。按 K 打开设置，或输入 /aibuddy key <你的Key>").formatted(Formatting.YELLOW)));
                }
                p.sendMessage(Text.literal("[AI伙伴] ").formatted(Formatting.GOLD)
                        .append(Text.literal("[点我召唤伙伴]").styled(s -> s.withColor(Formatting.GREEN).withUnderline(true)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/aibuddy summon")))));
            });
        });

        LOGGER.info("AI Buddy loaded");
    }

    private static boolean canConfigure(ServerCommandSource src) {
        return src.hasPermissionLevel(2) || src.getServer().isSingleplayer();
    }

    public static AiBuddyEntity getActive(UUID owner) {
        AiBuddyEntity b = ACTIVE.get(owner);
        if (b != null && (b.isRemoved() || !b.isAlive())) {
            ACTIVE.remove(owner, b);
            return null;
        }
        return b;
    }

    private static AiBuddyEntity buddyOf(ServerCommandSource src) {
        ServerPlayerEntity p = src.getPlayer();
        return p == null ? null : getActive(p.getUuid());
    }

    private static int summon(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        AiBuddyEntity existing = getActive(player.getUuid());
        if (existing != null && existing.getWorld() == player.getWorld()) {
            existing.teleportNear(player);
            ctx.getSource().sendFeedback(() -> Text.literal("伙伴已传送到你身边"), false);
            return 1;
        }
        ServerWorld world = player.getServerWorld();
        AiBuddyEntity buddy = BUDDY.create(world);
        if (buddy == null) return 0;
        buddy.setOwner(player);
        buddy.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), player.getYaw(), 0);
        if (existing != null) { // 在别的维度：把背包和装备带过来
            existing.mergeInto(buddy);
            existing.discard();
        } else {
            buddy.giveStarterKit();
        }
        buddy.refreshName();
        world.spawnEntity(buddy);
        ACTIVE.put(player.getUuid(), buddy);
        if (!AiBuddyConfig.get().hasKey()) {
            player.sendMessage(Text.literal("伙伴来了，但还没设置 API Key，暂时只会跟随和护卫。按 K 设置。").formatted(Formatting.YELLOW));
        } else {
            buddy.brain().onEvent("你刚被主人召唤到身边，跟主人打个招呼。");
        }
        return 1;
    }

    private static int dismiss(CommandContext<ServerCommandSource> ctx) {
        AiBuddyEntity b = buddyOf(ctx.getSource());
        if (b == null) {
            ctx.getSource().sendFeedback(() -> Text.literal("附近没有你的伙伴"), false);
            return 0;
        }
        b.dropEverything();
        b.discard();
        ctx.getSource().sendFeedback(() -> Text.literal("伙伴已离开，物品掉落在原地"), false);
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> ctx) {
        AiBuddyEntity b = buddyOf(ctx.getSource());
        AiBuddyConfig c = AiBuddyConfig.get();
        String keyMask = c.hasKey() ? c.apiKey.substring(0, Math.min(5, c.apiKey.length())) + "****" : "(未设置)";
        ctx.getSource().sendFeedback(() -> Text.literal("接口: " + c.baseUrl + "\n模型: " + c.model + "\nKey: " + keyMask
                + "\n伙伴: " + (b == null ? "未召唤/未加载" : b.describeState())), false);
        return 1;
    }

    private static int setConfig(CommandContext<ServerCommandSource> ctx, String what) {
        String v = StringArgumentType.getString(ctx, "value").trim();
        AiBuddyConfig c = AiBuddyConfig.get();
        switch (what) {
            case "key" -> c.apiKey = v;
            case "url" -> c.baseUrl = v;
            case "model" -> c.model = v;
            case "name" -> {
                c.buddyName = v;
                AiBuddyEntity b = buddyOf(ctx.getSource());
                if (b != null) b.refreshName();
            }
        }
        AiBuddyConfig.save();
        ctx.getSource().sendFeedback(() -> Text.literal("已保存 " + what), false);
        return 1;
    }
}
