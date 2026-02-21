package com.leafuke.minebackup.compat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.leafuke.minebackup.MineBackup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

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
    private static final String PLAYER_CLASS = "net.minecraft.world.entity.player.Player";

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
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            for (ServerPlayer player : players) {
                if (!fakePlayerClazz.isInstance(player)) {
                    continue;
                }
                if (shouldSkipResident(player)) {
                    continue;
                }
                String name = player.getGameProfile().name();
                Object json = saveMethod.invoke(null, player);
                if (json instanceof JsonObject jsonObject) {
                    fakePlayerList.add(name, jsonObject);
                }
            }

            Gson gson = resolveGcaGson();
            Path file = server.getWorldPath(LevelResource.ROOT).resolve("fake_player.gca.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(fakePlayerList), StandardCharsets.UTF_8);
            MineBackup.LOGGER.info("[MineBackup] 已写入 GCA 假人文件: {}", file.toAbsolutePath());
        } catch (Exception e) {
            MineBackup.LOGGER.warn("[MineBackup] GCA 兼容保存失败: {}", e.getMessage());
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

    private static boolean shouldSkipResident(ServerPlayer player) {
        try {
            // Newer mappings changed Entity.saveWithoutId to accept a ValueOutput instead of CompoundTag.
            // Use reflection to create a TagValueOutput (which wraps a CompoundTag), call saveWithoutId with it,
            // then extract the CompoundTag reflectively and check the flag.
            Class<?> valueOutputClass = Class.forName("net.minecraft.world.level.storage.ValueOutput");
            Class<?> tagValueOutputClass = null;
            Object tagValueOutput = null;

            try {
                tagValueOutputClass = Class.forName("net.minecraft.world.level.storage.TagValueOutput");
                try {
                    // try constructor that accepts CompoundTag
                    tagValueOutput = tagValueOutputClass.getConstructor(CompoundTag.class).newInstance(new CompoundTag());
                } catch (NoSuchMethodException e) {
                    // try no-arg constructor
                    tagValueOutput = tagValueOutputClass.getConstructor().newInstance();
                }
            } catch (Throwable ignored) {
                // fallback: if TagValueOutput not present, try alternative common name
                try {
                    tagValueOutputClass = Class.forName("net.minecraft.world.level.storage.NbtTagValueOutput");
                    tagValueOutput = tagValueOutputClass.getConstructor(CompoundTag.class).newInstance(new CompoundTag());
                } catch (Throwable ignored2) {
                    // cannot create a ValueOutput wrapper -> give up
                    return false;
                }
            }

            // find the saveWithoutId method that accepts ValueOutput
            Method saveMethod = player.getClass().getMethod("saveWithoutId", valueOutputClass);
            saveMethod.invoke(player, tagValueOutput);

            // try to extract the CompoundTag from the wrapper instance
            CompoundTag tag = null;
            // look for a field of type CompoundTag
            for (Field f : tagValueOutput.getClass().getDeclaredFields()) {
                if (CompoundTag.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object val = f.get(tagValueOutput);
                    if (val instanceof CompoundTag) {
                        tag = (CompoundTag) val;
                        break;
                    }
                }
            }

            // if not found, try a no-arg method that returns CompoundTag
            if (tag == null) {
                for (Method m : tagValueOutput.getClass().getMethods()) {
                    if (m.getReturnType() == CompoundTag.class && m.getParameterCount() == 0) {
                        Object val = m.invoke(tagValueOutput);
                        if (val instanceof CompoundTag) {
                            tag = (CompoundTag) val;
                            break;
                        }
                    }
                }
            }

            if (tag == null) return false;
            return tag.contains("gca.NoResident");
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
