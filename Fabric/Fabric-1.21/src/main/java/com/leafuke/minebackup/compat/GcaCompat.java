package com.leafuke.minebackup.compat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.leafuke.minebackup.MineBackup;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Gugle Carpet Addition（GCA）兼容适配：在热备份前输出 fake_player.gca.json
 */
public final class GcaCompat {
    private static final String GCA_EXTENSION_CLASS = "dev.dubhe.gugle.carpet.GcaExtension";
    private static final String GCA_SETTING_CLASS = "dev.dubhe.gugle.carpet.GcaSetting";
    private static final String FAKE_PLAYER_RESIDENT_CLASS = "dev.dubhe.gugle.carpet.tools.player.FakePlayerResident";
    private static final String FAKE_PLAYER_CLASS = "carpet.patches.EntityPlayerMPFake";
    private static final String PLAYER_CLASS = "net.minecraft.entity.player.PlayerEntity";

    private static volatile Boolean gcaAvailable;

    private GcaCompat() {}

    public static void saveFakePlayersIfNeeded(MinecraftServer server) {
        if (server == null) return;
        if (!isGcaAvailable()) return;

        try {
            if (!isFakePlayerResidentEnabled()) {
                return;
            }

            Class<?> fakePlayerClazz = Class.forName(FAKE_PLAYER_CLASS);
            Class<?> playerClazz = Class.forName(PLAYER_CLASS);
            Class<?> residentClazz = Class.forName(FAKE_PLAYER_RESIDENT_CLASS);
            Method saveMethod = residentClazz.getMethod("save", playerClazz);

            JsonObject fakePlayerList = new JsonObject();
            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
            for (ServerPlayerEntity player : players) {
                if (!fakePlayerClazz.isInstance(player)) {
                    continue;
                }
                if (shouldSkipResident(player)) {
                    continue;
                }
                String name = player.getGameProfile().getName();
                Object json = saveMethod.invoke(null, player);
                if (json instanceof JsonObject jsonObject) {
                    fakePlayerList.add(name, jsonObject);
                }
            }

            Gson gson = resolveGcaGson();
            Path file = server.getSavePath(WorldSavePath.ROOT).resolve("fake_player.gca.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(fakePlayerList), StandardCharsets.UTF_8);
            MineBackup.LOGGER.info("已写入 GCA 假人文件: {}", file.toAbsolutePath());
        } catch (Exception e) {
            MineBackup.LOGGER.warn("GCA 兼容保存失败: {}", e.getMessage());
        }
    }

    private static boolean isGcaAvailable() {
        if (gcaAvailable != null) return gcaAvailable;
        try {
            Class.forName(GCA_EXTENSION_CLASS);
            gcaAvailable = true;
        } catch (Throwable ignored) {
            gcaAvailable = false;
        }
        return gcaAvailable;
    }

    private static boolean isFakePlayerResidentEnabled() {
        try {
            Class<?> settingClass = Class.forName(GCA_SETTING_CLASS);
            Field field = settingClass.getField("fakePlayerResident");
            return field.getBoolean(null);
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean shouldSkipResident(ServerPlayerEntity player) {
        try {
            NbtCompound tag = new NbtCompound();
            Method writeMethod = player.getClass().getMethod("writeNbt", NbtCompound.class);
            Object result = writeMethod.invoke(player, tag);
            NbtCompound realTag = result instanceof NbtCompound ? (NbtCompound) result : tag;
            return realTag.contains("gca.NoResident");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Gson resolveGcaGson() {
        try {
            Class<?> extClass = Class.forName(GCA_EXTENSION_CLASS);
            Field gsonField = extClass.getField("GSON");
            Object gson = gsonField.get(null);
            if (gson instanceof Gson realGson) {
                return realGson;
            }
        } catch (Throwable ignored) {
        }
        return new GsonBuilder().setPrettyPrinting().create();
    }
}
