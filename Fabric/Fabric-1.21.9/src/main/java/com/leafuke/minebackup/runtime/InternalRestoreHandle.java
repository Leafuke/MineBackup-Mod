package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.OperationHandle;
import com.leafuke.minebackup.api.v2.RestoreResult;

import java.time.Duration;

interface InternalRestoreHandle extends OperationHandle<RestoreResult> {
    Duration remaining();

    RestoreControlResult confirm();

    RestoreControlResult cancel();
}
