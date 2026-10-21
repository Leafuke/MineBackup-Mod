package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.CurrentWorldAutomationMode;
import com.leafuke.minebackup.api.v2.CurrentWorldAutomationState;
import com.leafuke.minebackup.config.Config.JoinStatusNotificationMode;
import com.leafuke.minebackup.compat.TextEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

final class AutomationWelcomeMessage {
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private AutomationWelcomeMessage() {
    }

    static Optional<MutableText> build(
            CurrentWorldAutomationState state,
            JoinStatusNotificationMode notificationMode,
            boolean canManage) {
        if (!state.currentWorldAvailable()
                || notificationMode == JoinStatusNotificationMode.NEVER
                || (notificationMode == JoinStatusNotificationMode.ENABLED_ONLY
                        && state.mode() == CurrentWorldAutomationMode.OFF)) {
            return Optional.empty();
        }

        MutableText message;
        if (state.mode() == CurrentWorldAutomationMode.OFF) {
            message = Text.translatable("minebackup.message.auto.welcome.disabled");
            if (canManage) {
                message.append(Text.literal(" ")).append(button(
                        "enable", 0x55FF55,
                        TextEvents.suggestCommand("/mb auto start ")));
            }
        } else {
            boolean backup = state.mode() == CurrentWorldAutomationMode.BACKUP;
            String nextRun = state.nextRun().map(TIME_FORMAT::format)
                    .orElseGet(() -> Text.translatable(
                            "minebackup.message.auto.status.next_pending").getString());
            message = Text.translatable(
                    backup ? "minebackup.message.auto.welcome.enabled"
                            : "minebackup.message.auto.welcome.remind",
                    Text.translatable(backup ? "minebackup.message.auto.mode.backup"
                            : "minebackup.message.auto.mode.remind"),
                    state.interval().orElseThrow().toMinutes(),
                    nextRun);
            if (canManage) {
                message.append(Text.literal(" ")).append(button(
                        "disable", 0xFF5555,
                        TextEvents.runCommand("/mb auto stop")));
                message.append(Text.literal(" ")).append(button(
                        "config", 0xFFFF55,
                        TextEvents.suggestCommand("/mb auto start ")));
            }
        }
        return Optional.of(message);
    }

    private static MutableText button(String name, int color, ClickEvent action) {
        String key = "minebackup.message.auto.welcome.button." + name;
        HoverEvent hover = TextEvents.showText(Text.translatable(key + ".hover"));
        return Text.translatable(key).styled(style -> {
            style = style.withColor(color);
            if (action != null) {
                style = style.withClickEvent(action);
            }
            if (hover != null) {
                style = style.withHoverEvent(hover);
            }
            return style;
        });
    }
}
