package com.d0ck.aibuddy.client;

import com.d0ck.aibuddy.entity.AiBuddyEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.model.ArmorEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

/** 用玩家模型渲染伙伴，支持盔甲、手持物品 */
public class AiBuddyRenderer extends BipedEntityRenderer<AiBuddyEntity, PlayerEntityModel<AiBuddyEntity>> {
    private static final Identifier TEXTURE = Identifier.of("aibuddy", "textures/entity/buddy.png");

    public AiBuddyRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
        this.addFeature(new ArmorFeatureRenderer<>(this,
                new ArmorEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
                new ArmorEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
                ctx.getModelManager()));
    }

    @Override
    public Identifier getTexture(AiBuddyEntity entity) {
        return TEXTURE;
    }
}
