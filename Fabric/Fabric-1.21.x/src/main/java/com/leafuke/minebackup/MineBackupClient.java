// java
package com.leafuke.minebackup;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;
import java.util.Optional;

public class MineBackupClient implements ClientModInitializer {

    // 用于在客户端会话中暂存需要自动重连的世界ID（对应存档文件夹名）
    public static String worldToRejoin = null;
    public static boolean readyToRejoin = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            if (readyToRejoin) {
                Minecraft.getInstance().setScreen(new TitleScreen());
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
        });
    }

    /**
     * 尝试自动进入指定的单人世界（levelId 为世界存档文件夹名）。
     * 这个方法必须在客户端主线程上运行（上层用 client.execute 保证）。
     */
    private void attemptAutoRejoin(Minecraft client, String levelId) {
        try {
            // 1) 给玩家一个提示（若 player 存在）
            if (client.player != null) {
                client.player.sendSystemMessage(Component.translatable("minebackup.message.restore.rejoining"));
            } else {
                MineBackup.LOGGER.info("Client player is null; will still try to start integrated server for '{}'.", levelId);
            }

            // 2) 如果当前正在运行集成服务器，尝试断开/关闭它
            boolean integratedRunning = invokeBooleanPossibleMethods(client, "isIntegratedServerRunning", "isLocalServerRunning");
            if (!integratedRunning) {
                // 备用判断：如果 client.level 非空，则认为可能在世界中
                integratedRunning = client.level != null;
            }

            if (integratedRunning) {
                try {
                    // 尝试先断开当前 world（若能找到 disconnect 方法）
                    safeInvokeDisconnect(client, Component.translatable("minebackup.message.restore.rejoining"));
                } catch (Exception e) {
                    MineBackup.LOGGER.warn("Error while disconnecting existing integrated server: {}", e.getMessage());
                }
            }

            // 3) 尝试释放目标世界的 session lock（反射调用，兼容多个映射）
            try {
                deleteSessionLockForLevel(client, levelId);
            } catch (Exception e) {
                MineBackup.LOGGER.warn("Could not delete session lock for '{}': {}", levelId, e.getMessage());
            }

            // 4) 给用户显示正在进入的提示，再启动 integrated server（反射尝试多个方法名）
            client.setScreen(new SimpleMessageScreen(Component.translatable("minebackup.message.restore.entering")));

            boolean started = tryStartIntegratedServer(client, levelId);
            if (!started) {
                MineBackup.LOGGER.warn("Could not start integrated server via reflection; falling back to SelectWorldScreen for manual join.");
//                client.setScreen(new SelectWorldScreen(new TitleScreen()));
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

    // ----- 辅助反射方法与回退实现 -----

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
        // 尝试多种 disconnect 签名： disconnect(Screen, boolean) 或 disconnect(Screen)
        try {
            Method m = client.getClass().getMethod("disconnect", Screen.class, boolean.class);
            m.invoke(client, new SimpleMessageScreen(notice), Boolean.FALSE);
            return;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            MineBackup.LOGGER.debug("disconnect(Screen, boolean) failed: {}", e.getMessage());
        }
        try {
            Method m2 = client.getClass().getMethod("disconnect", Screen.class);
            m2.invoke(client, new SimpleMessageScreen(notice));
            return;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            MineBackup.LOGGER.debug("disconnect(Screen) failed: {}", e.getMessage());
        }
        // 回退：尝试 setScreen(null) 或显示消息
        try {
            client.setScreen(new SimpleMessageScreen(notice));
        } catch (Exception e) {
            MineBackup.LOGGER.debug("Fallback setScreen message failed: {}", e.getMessage());
        }
    }

    private static void deleteSessionLockForLevel(Minecraft client, String levelId) {
        // 尝试调用 client.getLevelStorage() / client.getSaveLoader()
        Object levelStorage = invokeFirstNoArgMethod(client, "getLevelStorage", "getSaveLoader", "getLevelStorageService", "getSaveLoaderManager");
        if (levelStorage == null) {
            MineBackup.LOGGER.debug("No level storage object found via reflection.");
            return;
        }

        // 寻找 createSession 或 createSessionAccessor 等方法
        Optional<Method> createSession = findMethodByNameAndParamCount(levelStorage.getClass(), new String[]{"createSession", "createSessionAccessor"}, 1);
        if (createSession.isPresent()) {
            try {
                Object session = createSession.get().invoke(levelStorage, levelId);
                if (session != null) {
                    // 尝试 deleteSessionLock 或 close
                    tryInvokeNoArgOnObject(session, "deleteSessionLock", "deleteLock", "close", "release");
                }
            } catch (Exception e) {
                MineBackup.LOGGER.debug("createSession invocation failed: {}", e.getMessage());
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
        // 常见方法名尝试： createIntegratedServerLoader -> loader.start(levelId, null)
        try {
//            client.doWorldLoad(true, false, false, false);
            Method createLoader = client.getClass().getMethod("createIntegratedServerLoader");
            Object loader = createLoader.invoke(client);
            if (loader != null) {
                // 尝试 start(String, Object) 或 start(String)

                for (Method m : loader.getClass().getMethods()) {
                    if (m.getName().equals("start")) {
                        try {
                            if (m.getParameterCount() == 2) {
                                m.invoke(loader, levelId, null);
                                return true;
                            } else if (m.getParameterCount() == 1) {
                                m.invoke(loader, levelId);
                                return true;
                            }
                        } catch (Exception e) {
                            MineBackup.LOGGER.debug("loader.start invocation failed: {}", e.getMessage());
                        }
                    }
                }
            }
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            MineBackup.LOGGER.debug("createIntegratedServerLoader failed: {}", e.getMessage());
        }

        // 再尝试 createIntegratedServer(String) 或 startIntegratedServer(String)
        String[] fallbacks = new String[]{"createIntegratedServer", "startIntegratedServer", "openLevel"};
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

    // ----- 简单的消息界面，替代不存在的 MessageScreen -----
    private static class SimpleMessageScreen extends Screen {
        private final Component message;

        protected SimpleMessageScreen(Component message) {
            super(Component.empty());
            this.message = message;
        }

        @Override
        protected void init() {
            // 添加一个返回按钮，防止卡在界面
            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> {
                Minecraft.getInstance().setScreen(null);
            }).bounds(this.width / 2 - 100, this.height / 2 + 20, 200, 20).build());
        }

//        @Override
//        public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
//            this.renderBackground(matrices);
//            int x = this.width / 2;
//            int y = this.height / 2 - 10;
//            String text = message.getString();
//            Minecraft.getInstance().font.drawShadow(matrices, text, (float) (x - Minecraft.getInstance().font.width(text) / 2), y, 0xFFFFFF);
//            super.render(matrices, mouseX, mouseY, delta);
//        }


        @Override
        public boolean shouldCloseOnEsc() {
            return true;
        }
    }
}
