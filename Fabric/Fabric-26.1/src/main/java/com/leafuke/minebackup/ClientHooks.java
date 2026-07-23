package com.leafuke.minebackup;

import com.leafuke.minebackup.restore.RestoreSession;
import net.minecraft.network.chat.Component;

import java.util.Objects;

public final class ClientHooks {
    private static final Handler NO_OP = new Handler() {
        @Override
        public void requestRejoin(RestoreSession.RejoinInfo info) {
        }

        @Override
        public void restoreFailed(Component message) {
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

    public static void requestRejoin(RestoreSession.RejoinInfo info) {
        handler.requestRejoin(info);
    }

    public static void restoreFailed(Component message) {
        handler.restoreFailed(message);
    }

    public interface Handler {
        void requestRejoin(RestoreSession.RejoinInfo info);

        void restoreFailed(Component message);
    }
}
