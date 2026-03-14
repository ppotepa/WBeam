package com.wbeam.ui.state;

/**
 * Plain data carrier for daemon state.
 */
public final class MainDaemonState {
    private boolean reachable = false;
    private String hostName = "-";
    private String service = "-";
    private String buildRevision = "-";
    private String state = "IDLE";
    private String lastError = "";
    private long runId = 0L;
    private long uptimeSec = 0L;

    public boolean isReachable() { return reachable; }
    public String getHostName() { return hostName; }
    public String getService() { return service; }
    public String getBuildRevision() { return buildRevision; }
    public String getState() { return state; }
    public String getLastError() { return lastError; }
    public long getRunId() { return runId; }
    public long getUptimeSec() { return uptimeSec; }

    @SuppressWarnings("java:S107")
    public void applySnapshot(
            boolean nextReachable,
            String nextHostName,
            String nextState,
            String nextLastError,
            long nextRunId,
            long nextUptimeSec,
            String nextService,
            String nextBuildRevision
    ) {
        reachable = nextReachable;
        hostName = nextHostName;
        state = nextState;
        lastError = nextLastError;
        runId = nextRunId;
        uptimeSec = nextUptimeSec;
        service = nextService;
        buildRevision = (nextBuildRevision == null || nextBuildRevision.trim().isEmpty())
                ? "-"
                : nextBuildRevision.trim();
    }

    public void markDisconnected() {
        reachable = false;
        state = "DISCONNECTED";
    }
}
