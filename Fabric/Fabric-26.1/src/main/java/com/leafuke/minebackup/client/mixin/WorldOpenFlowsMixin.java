package com.leafuke.minebackup.client.mixin;

import com.leafuke.minebackup.client.WorldOpenGate;

import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldOpenFlows.class)
abstract class WorldOpenFlowsMixin {
    @Inject(method = "openWorld", at = @At("HEAD"), cancellable = true)
    private void minebackup$admit(String levelId, Runnable cancel, CallbackInfo ci) {
        if (WorldOpenGate.intercept((WorldOpenFlows) (Object) this, levelId, cancel)) ci.cancel();
    }
}
