package com.leafuke.minebackup.knotlink.sdk;

import java.io.IOException;

/** Invalid framing or encoding, distinct from a lost transport connection. */
public final class KnotLinkFrameException extends IOException {
    public KnotLinkFrameException(String message) { super(message); }
    public KnotLinkFrameException(String message, Throwable cause) { super(message, cause); }
}
