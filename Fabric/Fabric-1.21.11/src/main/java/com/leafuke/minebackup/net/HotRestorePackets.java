package com.leafuke.minebackup.net;

import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.restore.HotRestoreState;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/**
 * 热还原网络包定义类
 * 用于客户端和服务器之间的热还原通信
 *
 * 使用 Fabric Networking API 实现自定义网络包
 * MC 1.21.11+ 使用 Mojang 官方映射
 */
public final class HotRestorePackets {
    private HotRestorePackets() {}

    // 热还原请求包的标识符
//    public static final Identifier REQUEST_HOT_RESTORE_ID =
//            new Identifier(MineBackup.MOD_ID, "request_hot_restore");

    /**
     * 热还原请求包
     * 客户端发送给服务器，请求执行热还原
     * @param levelId 要还原的世界存档文件夹名
     */
//    public record RequestHotRestorePayload(String levelId) implements CustomPacketPayload {
//        public static final Type<RequestHotRestorePayload> TYPE = new Type<>(REQUEST_HOT_RESTORE_ID);
//
//        // 使用 Mojang 映射的 StreamCodec
//        public static final StreamCodec<FriendlyByteBuf, RequestHotRestorePayload> CODEC = StreamCodec.of(
//            (buf, value) -> buf.writeUtf(value.levelId == null ? "" : value.levelId),
//            buf -> {
//                String v = buf.readUtf(32767);
//                return new RequestHotRestorePayload(v.isEmpty() ? null : v);
//            }
//        );
//
//        @Override
//        public Type<? extends CustomPacketPayload> type() {
//            return TYPE;
//        }
//    }

    /**
     * 注册所有网络包
     * 在模组初始化时调用
     */
//    public static void registerCommon() {
//        // 注册客户端到服务器的包类型
//        PayloadTypeRegistry.playC2S().register(RequestHotRestorePayload.TYPE, RequestHotRestorePayload.CODEC);
//
//        // 注册服务器端接收处理器
//        ServerPlayNetworking.registerGlobalReceiver(RequestHotRestorePayload.TYPE, (payload, context) -> {
////            MinecraftServer server = context.player().server;
////            server.execute(() -> {
////                // 设置热还原状态
////                HotRestoreState.levelIdToRejoin = payload.levelId();
////                HotRestoreState.waitingForServerStopAck = true;
////
////                MineBackup.LOGGER.info("[MineBackup] 收到热还原请求，目标世界: {}", payload.levelId());
////            });
//        });
//    }
}
