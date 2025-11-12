package com.leafuke.minebackup;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import net.minecraft.network.chat.Component;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStoppingEvent;
import com.leafuke.minebackup.knotlink.SignalSubscriber;

import java.util.HashMap;
import java.util.Map;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(MineBackup.MODID)
public class MineBackup
{
    /**
     * 解析 MineBackup 发送的键值对格式的 payload。
     * @param payload 例如 "event=backup_success;world=MyWorld"
     * @return 一个包含键值对的 Map
     */
    private Map<String, String> parsePayload(String payload) {
        Map<String, String> dataMap = new HashMap<>();
        if (payload == null || payload.isEmpty()) return dataMap;

        String[] pairs = payload.split(";");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                dataMap.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        return dataMap;
    }

    /**
     * 处理从 SignalSubscriber 收到的广播事件的核心方法。
     * @param payload 来自 MineBackup 的原始事件字符串。
     */
    private void handleBroadcastEvent(String payload) {
        if (serverInstance == null) return; // 确保服务器仍在运行

        // 关键修正：首先处理特殊的、非键值对格式的指令
        if ("minebackup save".equals(payload)) {
            serverInstance.execute(() -> {
                LOGGER.info("Received 'minebackup save' command, executing immediate world save.");
                serverInstance.getPlayerList().broadcastSystemMessage(
                        Component.translatable("minebackup.message.remote_save.start"), false);
                boolean allLevelsSaved = serverInstance.saveAllChunks(true, true, true);
                if(allLevelsSaved) {
                    serverInstance.getPlayerList().broadcastSystemMessage(
                            Component.translatable("minebackup.message.remote_save.success"), false);
                } else {
                    serverInstance.getPlayerList().broadcastSystemMessage(
                            Component.translatable("minebackup.message.remote_save.fail"), false);
                }
            });
            return; // 处理完毕，直接返回
        }

        // --- 对于标准的 key=value;... 格式的事件，继续使用原有逻辑 ---
        LOGGER.info("Received MineBackup broadcast event: {}", payload);
        Map<String, String> eventData = parsePayload(payload);

        String eventType = eventData.get("event");
        if (eventType == null) return;

        Component message = null;
        switch (eventType) {
            case "backup_started":
                message = Component.translatable(
                        "minebackup.broadcast.backup.started",
                        eventData.getOrDefault("world", "Unknown World")
                );
                break;
            case "restore_started":
                message = Component.translatable(
                        "minebackup.broadcast.restore.started",
                        eventData.getOrDefault("world", "Unknown World")
                );
                break;

            // (优化) 任务成功/失败的响应
            case "backup_success":
                message = Component.translatable(
                        "minebackup.broadcast.backup.success",
                        eventData.getOrDefault("world", "Unknown World"),
                        eventData.getOrDefault("file", "Unknown File")
                );
                break;
            case "backup_failed":
                message = Component.translatable(
                        "minebackup.broadcast.backup.failed",
                        eventData.getOrDefault("world", "Unknown World"),
                        eventData.getOrDefault("error", "Unknown Error")
                );
                break;
            case "pre_hot_backup":
                // 收到热备份信号，立即执行世界保存操作
                serverInstance.execute(() -> {
                    LOGGER.info("Executing immediate save for pre_hot_backup event.");
                    String worldName = eventData.getOrDefault("world", "Unknown World");

                    boolean allSaved = serverInstance.saveAllChunks(true, true, true);

                    // 广播一个保存中的提示
                    serverInstance.getPlayerList().broadcastSystemMessage(
                            Component.translatable("minebackup.broadcast.hot_backup.request", worldName), false);
                    if (!allSaved) {
                        LOGGER.warn("One or more levels failed to save during pre_hot_backup for world: {}", worldName);
                        serverInstance.getPlayerList().broadcastSystemMessage(
                                Component.translatable("minebackup.broadcast.hot_backup.warn", worldName), false);
                    }
                    for (ServerLevel level : serverInstance.getAllLevels()) {
                        level.save(null, true, false);
                    }

                    LOGGER.info("World saved successfully for hot backup.");
                    serverInstance.getPlayerList().broadcastSystemMessage(
                            Component.translatable("minebackup.broadcast.hot_backup.complete"), false);
                });
                break; // 保存逻辑已处理完毕，跳出 switch
            case "game_session_start":
                // 仅在服务器日志中记录，不打扰玩家
                LOGGER.info("MineBackup detected game session start for world: {}", eventData.getOrDefault("world", "未知"));
                // 可选：广播会话开始
                message = Component.translatable(
                        "minebackup.broadcast.session.start",
                        eventData.getOrDefault("world", "Unknown World")
                );
                break;
            case "game_session_end":
                LOGGER.info("MineBackup detected game session end for world: {}", eventData.getOrDefault("world", "未知"));
                // 可以选择在这里也通知玩家
                message = Component.translatable(
                        "minebackup.broadcast.session.end",
                        eventData.getOrDefault("world", "Unknown World")
                );
                break;
            case "auto_backup_started":
                message = Component.translatable(
                        "minebackup.broadcast.auto_backup.started",
                        eventData.getOrDefault("world", "Unknown World")
                );
                break;
        }

        // 如果有消息需要显示，安全地在 Minecraft 服务器主线程中执行
        if (message != null) {
            final Component finalMessage = message;
            // 使用 serverInstance 在服务器线程上执行，并广播给所有玩家
            serverInstance.execute(() ->
                    serverInstance.getPlayerList().broadcastSystemMessage(finalMessage, false)
            );
        }
    }

    private static final Logger LOGGER = LogUtils.getLogger();


    public static final String MODID = "minebackup";

    // --- 新增内容 ---
    private static SignalSubscriber knotLinkSubscriber;
    private static MinecraftServer serverInstance;

    // MineBackup.cpp 中用于广播的ID
    private static final String BROADCAST_APP_ID = "0x00000020";
    private static final String BROADCAST_SIGNAL_ID = "0x00000020";





    // Define mod id in a common place for everything to reference
//    public static final String MODID = "examplemod";
    // Directly reference a slf4j logger
//    private static final Logger LOGGER = LogUtils.getLogger();

    public MineBackup() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        Command.register(event.getDispatcher());
    }


    // Create a Deferred Register to hold Blocks which will all be registered under the "minebackup" namespace
//    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "minebackup" namespace
//    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "minebackup" namespace
//    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // Creates a new Block with the id "minebackup:example_block", combining the namespace and path
//    public static final RegistryObject<Block> EXAMPLE_BLOCK = BLOCKS.register("example_block", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.STONE)));
//    // Creates a new BlockItem with the id "minebackup:example_block", combining the namespace and path
//    public static final RegistryObject<Item> EXAMPLE_BLOCK_ITEM = ITEMS.register("example_block", () -> new BlockItem(EXAMPLE_BLOCK.get(), new Item.Properties()));
//
//    // Creates a new food item with the id "minebackup:example_id", nutrition 1 and saturation 2
//    public static final RegistryObject<Item> EXAMPLE_ITEM = ITEMS.register("example_item", () -> new Item(new Item.Properties().food(new FoodProperties.Builder()
//            .alwaysEat().nutrition(1).saturationMod(2f).build())));
//
//    // Creates a creative tab with the id "minebackup:example_tab" for the example item, that is placed after the combat tab
//    public static final RegistryObject<CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
//            .withTabsBefore(CreativeModeTabs.COMBAT)
//            .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
//            .displayItems((parameters, output) -> {
//                output.accept(EXAMPLE_ITEM.get()); // Add the example item to the tab. For your own tabs, this method is preferred over the event
//            }).build());

    public MineBackup(FMLJavaModLoadingContext context)
    {
        IEventBus modEventBus = context.getModEventBus();


        // Register the Deferred Register to the mod event bus so blocks get registered
//        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
//        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
//        CREATIVE_MODE_TABS.register(modEventBus);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);

    }



    // Add the example block item to the building blocks tab
//    private void addCreative(BuildCreativeModeTabContentsEvent event)
//    {
//        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS)
////            event.accept(EXAMPLE_BLOCK_ITEM);
//    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call


    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            // Some client setup code
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        LOGGER.info("MineBackup Mod: Server is starting, initializing KnotLink Subscriber...");
        serverInstance = event.getServer();

        // 初始化并启动 SignalSubscriber
        knotLinkSubscriber = new SignalSubscriber(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
        // 设置回调方法
        knotLinkSubscriber.setSignalListener(this::handleBroadcastEvent);

        // 在新线程中启动以避免阻塞服务器
        new Thread(knotLinkSubscriber::start).start();
    }
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("MineBackup Mod: Server is stopping, shutting down KnotLink Subscriber...");
        if (knotLinkSubscriber != null) {
            knotLinkSubscriber.stop();
        }
        serverInstance = null;
    }




}
