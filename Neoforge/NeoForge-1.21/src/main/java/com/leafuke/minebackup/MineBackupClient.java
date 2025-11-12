package com.leafuke.minebackup;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.lang.reflect.Method;
import java.util.Optional;

public class MineBackupClient {

    public static String worldToRejoin = null;
    public static boolean readyToRejoin = false;

    // 由主类调用的静态初始化方法
    public static void initialize() {
        // 将这个类的实例注册到事件总线，以便 @SubscribeEvent 能工作
        NeoForge.EVENT_BUS.register(new MineBackupClient());
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        if (readyToRejoin) {
            Minecraft client = Minecraft.getInstance();
            client.setScreen(new TitleScreen());
            readyToRejoin = false;
            String levelId = worldToRejoin;
            worldToRejoin = null;

            if (levelId != null) {
                MineBackup.LOGGER.info("Attempting to automatically rejoin world: {}", levelId);
                try {
                    client.execute(() -> attemptAutoRejoin(client, levelId));
                } catch (Exception e) {
                    MineBackup.LOGGER.error("Failed while attempting auto-rejoin handling.", e);
                }
            }
        }
    }

    private void attemptAutoRejoin(Minecraft client, String levelId) {
        try {
            if (client.player != null) {
                client.player.sendSystemMessage(Component.translatable("minebackup.message.restore.rejoining"));
            } else {
                MineBackup.LOGGER.info("Client player is null; will still try to start integrated server for '{}'.", levelId);
            }

            // [映射变更] isIntegratedServerRunning -> isSingleplayer
            boolean integratedRunning = invokeBooleanPossibleMethods(client, "isSingleplayer", "isIntegratedServerRunning", "isLocalServerRunning");
            if (!integratedRunning) {
                integratedRunning = client.level != null;
            }

            if (integratedRunning) {
                try {
                    safeInvokeDisconnect(client, Component.translatable("minebackup.message.restore.rejoining"));
                } catch (Exception e) {
                    MineBackup.LOGGER.warn("Error while disconnecting existing integrated server: {}", e.getMessage());
                }
            }

            try {
                // [映射变更] 更新删除会话锁的逻辑
                deleteSessionLockForLevel(client, levelId);
            } catch (Exception e) {
                MineBackup.LOGGER.warn("Could not delete session lock for '{}': {}", levelId, e.getMessage());
            }

            client.setScreen(new SimpleMessageScreen(Component.translatable("minebackup.message.restore.rejoining")));

            boolean started = tryStartIntegratedServer(client, levelId);
            if (!started) {
                MineBackup.LOGGER.warn("Could not start integrated server; falling back to SelectWorldScreen for manual join.");
                client.setScreen(new SelectWorldScreen(new TitleScreen()));
            }
        } catch (Exception e) {
            MineBackup.LOGGER.error("Auto rejoin failed for world '{}': {}", levelId, e);
            try {
                client.setScreen(new SelectWorldScreen(new TitleScreen()));
            } catch (Exception ex) {
                MineBackup.LOGGER.warn("Failed to open SelectWorldScreen after auto-rejoin failure: {}", ex.getMessage());
            }
        }
    }


    private static boolean invokeBooleanPossibleMethods(Object target, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                Object res = m.invoke(target);
                if (res instanceof Boolean) return (Boolean) res;
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                MineBackup.LOGGER.debug("invokeBooleanPossibleMethods '{}' failed: {}", name, e.getMessage());
            }
        }
        return false;
    }

    private static void safeInvokeDisconnect(Minecraft client, Component notice) {
        try {
            // 这个方法签名在 Parchment 中仍然存在
            Method m = client.getClass().getMethod("disconnect", Screen.class);
            m.invoke(client, new SimpleMessageScreen(notice));
            return;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            MineBackup.LOGGER.debug("disconnect(Screen) failed: {}", e.getMessage());
        }

        // 备用方案
        try {
            client.setScreen(new SimpleMessageScreen(notice));
        } catch (Exception e) {
            MineBackup.LOGGER.debug("Fallback setScreen message failed: {}", e.getMessage());
        }
    }

    private static void deleteSessionLockForLevel(Minecraft client, String levelId) {
        // [映射变更] getLevelStorage/getSaveLoader -> getLevelSource
        Object levelSource = invokeFirstNoArgMethod(client, "getLevelSource", "getLevelStorage", "getSaveLoader");
        if (levelSource == null) {
            MineBackup.LOGGER.debug("No level storage/source object found via reflection.");
            return;
        }

        // [映射变更] createSession -> createAccess
        Optional<Method> createAccess = findMethodByNameAndParamCount(levelSource.getClass(), new String[]{"createAccess"}, 1);
        if (createAccess.isPresent()) {
            try {
                Object access = createAccess.get().invoke(levelSource, levelId);
                if (access != null) {
                    // [映射变更] deleteSessionLock -> deleteLock
                    tryInvokeNoArgOnObject(access, "deleteLock", "close", "deleteSessionLock");
                }
            } catch (Exception e) {
                MineBackup.LOGGER.debug("createAccess invocation failed: {}", e.getMessage());
            }
        }
    }

    private static Object invokeFirstNoArgMethod(Object target, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                MineBackup.LOGGER.debug("invokeFirstNoArgMethod '{}' failed: {}", name, e.getMessage());
            }
        }
        return null;
    }

    private static Optional<Method> findMethodByNameAndParamCount(Class<?> cls, String[] names, int paramCount) {
        for (String name : names) {
            for (Method m : cls.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    private static void tryInvokeNoArgOnObject(Object target, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                m.invoke(target);
                MineBackup.LOGGER.debug("Successfully invoked '{}' on {}", name, target.getClass().getName());
                return;
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                MineBackup.LOGGER.debug("tryInvokeNoArgOnObject '{}' failed: {}", name, e.getMessage());
            }
        }
    }

    private static boolean tryStartIntegratedServer(Minecraft client, String levelId) {
        client.createWorldOpenFlows();
        try {
            Object worldOpenFlows = invokeFirstNoArgMethod(client, "createWorldOpenFlows");
            if (worldOpenFlows != null) {
                Method loadLevel = worldOpenFlows.getClass().getMethod("loadLevel", Screen.class, String.class);
                loadLevel.invoke(worldOpenFlows, new TitleScreen(), levelId);
                return true;
            }
        } catch (Exception e) {
            MineBackup.LOGGER.debug("createWorldOpenFlows().loadLevel() reflection failed: {}", e.getMessage());
        }

        // 备用方案：尝试直接的 startIntegratedServer 方法
        String[] fallbacks = new String[]{"startIntegratedServer", "loadLevel"};
        for (String name : fallbacks) {
            try {
                for (Method m : client.getClass().getMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                        m.invoke(client, levelId);
                        return true;
                    }
                }
            } catch (Exception e) {
                MineBackup.LOGGER.debug("{} invocation failed: {}", name, e.getMessage());
            }
        }
        return false;
    }


    private static class SimpleMessageScreen extends Screen {
        private final Component message;

        protected SimpleMessageScreen(Component message) {
            super(Component.empty());
            this.message = message;
        }

        @Override
        protected void init() {
            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> {
                Minecraft.getInstance().setScreen(null);
            }).bounds(this.width / 2 - 100, this.height / 2 + 20, 200, 20).build());
        }

        // render 方法在较新版本中 PoseStack 是第一个参数
//        @Override
//        public void render(PoseStack poseStack, int mouseX, int mouseY, float delta) {
////            super.render(poseStack, mouseX, mouseY, delta);
////            drawCenteredString(poseStack, this.font, this.message, this.width / 2, this.height / 2 - 10, 0xFFFFFF);
//        }

        @Override
        public boolean shouldCloseOnEsc() {
            return true;
        }
    }
}