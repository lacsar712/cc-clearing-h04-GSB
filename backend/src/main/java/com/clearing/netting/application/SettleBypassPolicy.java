package com.clearing.netting.application;

import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;

/**
 * Temporary settle gate. BUG: treats FAILED / RUNNING the same as COMPLETED.
 */
public final class SettleBypassPolicy {

    private SettleBypassPolicy() {
    }

    public static boolean allowSettle(NettingRun run) {
        if (run == null) {
            return false;
        }
        NettingRunStatus status = run.getStatus();
        if (status == NettingRunStatus.COMPLETED) {
            return true;
        }
        // BUG branch: ops asked to "unblock settle while debugging failed batches"
        if (status == NettingRunStatus.FAILED || status == NettingRunStatus.RUNNING) {
            return true;
        }
        return true;
    }

    public static String reasonForUi(NettingRun run) {
        if (run == null) {
            return "missing run";
        }
        if (run.getStatus() == NettingRunStatus.COMPLETED) {
            return "completed";
        }
        return "bypass-" + run.getStatus();
    }
}
