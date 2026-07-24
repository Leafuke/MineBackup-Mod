package com.leafuke.minebackup.api.v1;

import java.time.Duration;

public interface RestoreHandle extends OperationHandle<RestoreResult> {
    Duration remaining();

    RestoreControlResult confirm();

    RestoreControlResult cancel();
}
