/**
 * Adapted KnotLink Java SDK 2.0 transport classes.
 *
 * <p>MineBackup uses the SDK's plain four-byte big-endian length framing.
 * KnotLink's optional magic-number framing remains implemented by
 * {@link com.leafuke.minebackup.knotlink.sdk.TcpClient.FrameFormat#MAGIC_V2}
 * but is deliberately not enabled until the server does so.</p>
 */
package com.leafuke.minebackup.knotlink.sdk;
