package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.knotlink.protocol.KnotLinkRequest;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkResponse;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
interface KnotLinkGateway {
    CompletableFuture<KnotLinkResponse> query(KnotLinkRequest request);
}
