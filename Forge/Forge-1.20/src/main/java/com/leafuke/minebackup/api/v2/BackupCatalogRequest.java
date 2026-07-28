package com.leafuke.minebackup.api.v2;

public record BackupCatalogRequest(String callerId) {
    public BackupCatalogRequest {
        callerId = CallerId.normalize(callerId);
    }

    public static BackupCatalogRequest create(String callerId) {
        return new BackupCatalogRequest(callerId);
    }
}
