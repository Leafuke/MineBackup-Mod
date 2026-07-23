/**
 * Adapted KnotLink Java SDK 2.0 transport classes.
 *
 * <p>MineBackup uses KnotLink 3.0's magic-number framing:
 * {@code KK 00 02}, followed by a four-byte big-endian payload length.
 * The legacy length-only format remains available as
 * {@link com.leafuke.minebackup.knotlink.sdk.TcpClient.FrameFormat#LENGTH_PREFIXED};
 * active connections explicitly use
 * {@link com.leafuke.minebackup.knotlink.sdk.TcpClient.FrameFormat#MAGIC_V2}
 * so their behavior does not depend on a constructor default.</p>
 */
package com.leafuke.minebackup.knotlink.sdk;
