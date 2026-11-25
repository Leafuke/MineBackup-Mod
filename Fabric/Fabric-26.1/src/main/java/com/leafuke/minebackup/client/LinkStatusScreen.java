package com.leafuke.minebackup.client;

import com.leafuke.minebackup.MineBackup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class LinkStatusScreen extends Screen {
    private final Screen previous;
    private String reason;
    private Object observed;
    private Object routeStatus;

    public LinkStatusScreen(Screen previous, String reason) {
        super(Component.translatable("minebackup.interop.title"));
        this.previous = previous;
        this.reason = reason;
    }

    protected void init() {
        var link = MineBackup.knotLink().client();
        var status = link.status();
        String key = "minebackup.interop." + (reason == null ? status.code() : reason);
        Component message =
                Component.translatableWithFallback(
                        key,
                        Component.translatable("minebackup.interop.unverified").getString(),
                        status.detail());
        int w = Math.min(320, width - 24), x = (width - w) / 2;
        addRenderableWidget(new MultiLineTextWidget(x, 24, message, font).setMaxWidth(w));
        routeStatus=status;
        observed=link.latest();
        int y=72;
        if(link.latest()!=null){
            var summary=com.leafuke.minebackup.command.FailureMessages.observation(link.latest());
            addRenderableWidget(new MultiLineTextWidget(x,y,summary,font).setMaxWidth(w));y+=35;
        }
        for (var choice : status.choices()) {
            if (y + 50 > height) break;
            addRenderableWidget(
                    Button.builder(
                                    Component.literal(
                                            choice.endpoint().display() + " · " + choice.label()),
                                    b ->
                                            link.select(choice.key())
                                                    .whenComplete((s, e) -> updated()))
                            .bounds(x, y, w, 20)
                            .build());
            y += 22;
        }
        addRenderableWidget(
                Button.builder(
                                Component.translatable("minebackup.interop.refresh"),
                                b -> link.refresh().whenComplete((s, e) -> updated()))
                        .bounds(x, height - 27, w / 2 - 2, 20)
                        .build());
        addRenderableWidget(
                Button.builder(Component.translatable("gui.back"), b -> onClose())
                        .bounds(x + w / 2, height - 27, w / 2, 20)
                        .build());
    }

    @Override public void tick(){
        var link=MineBackup.knotLink().client();
        if(link.latest()!=observed || link.status()!=routeStatus)rebuildWidgets();
    }

    private void updated() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(
                () -> {
                    if (mc.screen == this) {
                        reason = null;
                        rebuildWidgets();
                    }
                });
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(previous);
    }
}
