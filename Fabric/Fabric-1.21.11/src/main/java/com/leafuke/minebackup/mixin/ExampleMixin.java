package com.leafuke.minebackup.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MinecraftServer Mixin（Fabric 1.21.11+，使用 Mojang 官方映射）
 * 用于在服务器启动时执行必要的初始化操作
 *
 * 注意：此 Mixin 不应影响玩家数据加载
 * MC 1.21.11 使用 Mojang 映射，方法名为 createLevels
 */
@Mixin(MinecraftServer.class)
public class ExampleMixin {

	/**
	 * 在服务器创建世界时注入代码
	 * Mojang 映射中方法名为 createLevels（MC 1.21.11+）
	 * 该方法在世界加载前调用，不会影响玩家数据
	 */
	@Inject(at = @At("RETURN"), method = "createLevels")
	private void onCreateLevels(CallbackInfo info) {
		// 在世界创建完成后执行初始化逻辑
		// 此处为扩展点，不影响玩家数据加载
	}
}