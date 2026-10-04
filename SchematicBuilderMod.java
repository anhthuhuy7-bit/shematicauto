package com.nova.autobuild;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;

public class SchematicBuilderMod implements ClientModInitializer {
    private static KeyBinding openKey;

    public static Path dir() { return FabricLoader.getInstance().getGameDir().resolve("schematics"); }

    @Override
    public void onInitializeClient() {
        try { Files.createDirectories(dir()); } catch (Exception ignored) {}

        // 1.21.9+: KeyBinding dùng Category object thay vì String. Nếu lỗi compile ở dòng này, xem README.
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autobuild.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B,
                KeyBinding.Category.create(Identifier.of("autobuild", "main"))));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            AutoBuilder.INSTANCE.tick(mc);
            while (openKey.wasPressed()) {
                if (mc.currentScreen == null && mc.player != null) mc.setScreen(new AutoBuildScreen());
            }
        });
    }
}
