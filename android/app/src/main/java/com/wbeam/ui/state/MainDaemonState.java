package com.wbeam.ui.state;

@SuppressWarnings("java:S1104")
public final class MainDaemonState {
    public boolean reachable = false;
    public String hostName = "-";
    public String service = "-";
    public String buildRevision = "-";
    public String state = "IDLE";
    public String lastError = "";
    public long runId = 0L;
    public long uptimeSec = 0L;

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
