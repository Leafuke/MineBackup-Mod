package com.leafuke.minebackup.client.mixin;

import com.leafuke.minebackup.client.LinkStatusScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
abstract class LinkStatusEntryMixin {
    @Shadow
    protected abstract <T extends GuiEventListener & Renderable & NarratableEntry>
            T addRenderableWidget(T element);

    @Inject(method = "init(II)V", at = @At("RETURN"))
    private void minebackup$status(int width, int height, CallbackInfo ci) {
        Screen screen = (Screen) (Object) this;
        if (screen instanceof TitleScreen || screen instanceof SelectWorldScreen)
            addRenderableWidget(
                    Button.builder(
                                    Component.translatable("minebackup.interop.title"),
                                    button ->
                                            Minecraft.getInstance()
                                                    .setScreen(new LinkStatusScreen(screen, null)))
                            .bounds(width - 92, 4, 88, 20)
                            .build());
    }
}
