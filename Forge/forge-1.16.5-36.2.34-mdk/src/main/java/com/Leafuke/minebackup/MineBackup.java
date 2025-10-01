package com.Leafuke.minebackup;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
//import net.minecraftforge.event.server.ServerStartingEvent;
//import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(MineBackup.MODID)
public class MineBackup {
    public static final String MODID = "minebackup";
    public static final Logger LOGGER = LogManager.getLogger();

    // These constants are used when calling the external helper app. Adjust if your helper listens
    // on a different host/port or uses a different "app id"/"socket id" scheme.
    public static final String QUERIER_APP_ID = "0x00000001";
    public static final String QUERIER_SOCKET_ID = "0x00000010";

    public MineBackup() {
        // Register to Forge event bus to receive server / command events
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onCommonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("MineBackup: common setup");
    }

    @SubscribeEvent
    public void onClientSetup(FMLClientSetupEvent event) {
        // client-only setup (if you need it)
    }

//    @SubscribeEvent
//    public void onServerStarting(ServerStartingEvent event) {
//        LOGGER.info("MineBackup: Server starting");
//    }

//    @SubscribeEvent
//    public void onServerStopping(ServerStoppingEvent event) {
//        LOGGER.info("MineBackup: Server stopping");
//    }

    // Register commands through the RegisterCommandsEvent
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        Command.register(event.getDispatcher());
    }
}