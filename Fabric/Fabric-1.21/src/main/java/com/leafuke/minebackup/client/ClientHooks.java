package com.leafuke.minebackup.client;

import com.leafuke.minebackup.restore.RestoreSession;
import net.minecraft.text.Text;

import java.util.Objects;

public final class ClientHooks {
    private static final Handler NO_OP = new Handler() {
        @Override
        public void requestRejoin(RestoreSession.RejoinInfo info, RestoreUiMessages messages) {
        }

        @Override
        public void restoreFailed(Text message) {
        }
    };
    private static volatile Handler handler = NO_OP;

    private ClientHooks() {
    }

    public static void register(Handler value) {
        handler = Objects.requireNonNull(value, "value");
    }

    public static void clear() {
        handler = NO_OP;
    }

    public static void requestRejoin(RestoreSession.RejoinInfo info, RestoreUiMessages messages) {
        handler.requestRejoin(info, messages);
    }

    public static void restoreFailed(Text message) {
        handler.restoreFailed(message);
    }

    public static void requestRejoin(RestoreSession.RejoinInfo info, RestoreUiMessages messages,
            java.util.function.BooleanSupplier current, java.util.function.BiConsumer<Boolean, String> completion) {
        handler.requestRejoin(info, messages, current, completion);
    }

    public static void restoreFailed(Text message, java.util.function.BooleanSupplier current) {
        handler.restoreFailed(message, current);
    }

    public interface Handler {
        void requestRejoin(RestoreSession.RejoinInfo info, RestoreUiMessages messages);

        void restoreFailed(Text message);

        default void requestRejoin(RestoreSession.RejoinInfo info, RestoreUiMessages messages,
                java.util.function.BooleanSupplier current, java.util.function.BiConsumer<Boolean, String> completion) {
            if (current.getAsBoolean()) requestRejoin(info, messages);
        }

        default void restoreFailed(Text message, java.util.function.BooleanSupplier current) {
            if (current.getAsBoolean()) restoreFailed(message);
        }
    }
}
