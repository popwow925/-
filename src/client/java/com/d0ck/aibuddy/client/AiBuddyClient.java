package com.d0ck.aibuddy.client;

import com.d0ck.aibuddy.AiBuddyMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class AiBuddyClient implements ClientModInitializer {
    private static KeyBinding settingsKey;

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(AiBuddyMod.BUDDY, AiBuddyRenderer::new);

        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aibuddy.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.aibuddy"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (settingsKey.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new BuddySettingsScreen(null));
            }
        });
    }
}
