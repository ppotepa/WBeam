package com.wbeam.startup;

import java.util.Locale;

/**
 * Builds preflight/startup overlay model from current runtime snapshot.
 */
public final class StartupOverlayModelBuilder {
    private static final String SERVICE_PREFIX = "service=";
    private static final String BULLET = " \u00b7 ";

    private StartupOverlayModelBuilder() {}

    /**
     * Plain data carrier for startup overlay input.
     */
    @SuppressWarnings("java:S1104")
    public static final class Input {
        public boolean daemonReachable;
        public String daemonHostName;
        public String daemonService;
        public String daemonBuildRevision;
        public String daemonState;
        public String daemonLastError;
        public boolean handshakeResolved;
        public boolean buildMismatch;
        public boolean requiresTransportProbe;
        public boolean probeOk;
        public boolean probeInFlight;
        public String probeInfo;
        public String apiImpl;
        public String apiBase;
        public String apiHost;
        public String streamHost;
        public int streamPort;
        public String appBuildRevision;
        public String lastUiInfo;
        public String effectiveDaemonState;
        public double latestPresentFps;
        public long startupBeganAtMs;
        public int controlRetryCount;
        public long nowMs;
        public String lastStatsLine;
        public String daemonErrCompact;
    }

    public static final class Model {
        public static final int SS_PENDING = 0;
        public static final int SS_ACTIVE = 1;
        public static final int SS_OK = 2;
        public static final int SS_ERROR = 3;

        /**
         * Plain data carrier for startup overlay output model.
         */
        @SuppressWarnings("java:S1104")
        public int step1State;
        public int step2State;
        public int step3State;
        public String step1Detail;
        public String step2Detail;
        public String step3Detail;
        public String subtitle;
        public String infoLog;
        public boolean allOk;
        public long updatedStartupBeganAtMs;
        public int updatedControlRetryCount;
        public long elapsedMs;
    }

    public static Model build(Input in) {
        Model out = new Model();
        long elapsedMs = in.startupBeganAtMs > 0L
                ? Math.max(0L, in.nowMs - in.startupBeganAtMs)
                : 0L;
        long updatedStartupBeganAtMs = in.startupBeganAtMs;
        int updatedControlRetryCount = in.controlRetryCount;

        if (!in.daemonReachable && elapsedMs > 20_000L) {
            updatedControlRetryCount++;
            updatedStartupBeganAtMs = in.nowMs;
            elapsedMs = 0L;
        }
        if (in.daemonReachable && updatedControlRetryCount > 0) {
            updatedControlRetryCount = 0;
        }
        out.updatedStartupBeganAtMs = updatedStartupBeganAtMs;
        out.updatedControlRetryCount = updatedControlRetryCount;
        out.elapsedMs = elapsedMs;

        int step1 = in.daemonReachable ? Model.SS_OK : Model.SS_ACTIVE;
        String step1Detail;
        if (step1 == Model.SS_OK) {
            boolean isLocalImpl = "local".equalsIgnoreCase(in.apiImpl);
            if (isLocalImpl) {
                step1Detail = "on-device (local api) \u00b7 no host connection needed";
            } else {
                step1Detail = "reachable" + BULLET + "api_impl=" + safe(in.apiImpl)
                        + BULLET + safe(in.daemonHostName);
            }
        } else if (updatedControlRetryCount == 0) {
            step1Detail = "polling " + safe(in.apiBase)
                    + " \u2026 (" + (elapsedMs / 1000L) + "s)"
                    + BULLET + "install/start desktop service if this does not recover";
        } else {
            step1Detail = "no response" + BULLET + "retry #" + updatedControlRetryCount
                    + BULLET + "polling " + safe(in.apiBase)
                    + " (" + (elapsedMs / 1000L) + "s)"
                    + BULLET + "check desktop service status";
        }

        int step2;
        String step2Detail;
        if (step1 != Model.SS_OK) {
            step2 = Model.SS_PENDING;
            step2Detail = "waiting for control link";
        } else if (!in.handshakeResolved) {
            step2 = Model.SS_ACTIVE;
            step2Detail = "resolving service / api version\u2026";
        } else if (in.buildMismatch) {
            step2 = Model.SS_ERROR;
            step2Detail = "build mismatch · app=" + safe(in.appBuildRevision)
                    + " · host=" + safe(in.daemonBuildRevision);
        } else {
            step2 = Model.SS_OK;
            if (in.requiresTransportProbe) {
                if (in.probeOk) {
                    step2Detail = SERVICE_PREFIX + safe(in.daemonService) + BULLET + "transport test OK";
                } else if (in.probeInFlight) {
                    step2Detail = SERVICE_PREFIX + safe(in.daemonService) + BULLET + "transport test in progress\u2026";
                } else {
                    step2Detail = SERVICE_PREFIX + safe(in.daemonService) + BULLET + "transport test pending";
                }
            } else {
                step2Detail = SERVICE_PREFIX + safe(in.daemonService) + BULLET + safe(in.apiImpl);
            }
        }

        boolean streamFlowing = "STREAMING".equalsIgnoreCase(safe(in.effectiveDaemonState));
        int streamReconnects = parseReconnectCount(in.lastStatsLine);
        String streamAddr = streamAddress(in.streamHost);
        boolean daemonStartFailure = safe(in.daemonErrCompact)
                .toLowerCase(Locale.US)
                .contains("stream start aborted");
        boolean streamIsLoopback = isLoopbackHost(in.streamHost);
        String streamFixHint = streamIsLoopback
                ? "check ADB reverse for stream/control ports" + BULLET + "ensure desktop service is running"
                : "check USB tethering / host IP / LAN" + BULLET + "ensure desktop service is running";

        int step3;
        String step3Detail;
        if (step2 != Model.SS_OK) {
            step3 = Model.SS_PENDING;
            step3Detail = (step2 == Model.SS_ERROR && in.buildMismatch)
                    ? "blocked by build mismatch"
                    : "waiting for handshake";
        } else if (in.requiresTransportProbe && !in.probeOk) {
            step3 = Model.SS_ACTIVE;
            if (in.probeInFlight) {
                step3Detail = "testing transport I/O\u2026 " + safe(in.probeInfo);
            } else {
                step3Detail = "transport test retrying\u2026 " + safe(in.probeInfo);
            }
        } else if (streamFlowing) {
            step3 = Model.SS_OK;
            step3Detail = "live" + BULLET + "fps=" + String.format(Locale.US, "%.0f", in.latestPresentFps)
                    + BULLET + safe(in.effectiveDaemonState).toLowerCase(Locale.US);
        } else {
            boolean hasWaited = elapsedMs > 5_000L;
            if (daemonStartFailure && hasWaited) {
                step3 = Model.SS_ERROR;
                step3Detail = "host stream start failed" + BULLET + safe(in.daemonErrCompact);
            } else {
                step3 = Model.SS_ACTIVE;
                if (streamReconnects > 0 && hasWaited) {
                    step3Detail = "retry #" + streamReconnects
                            + BULLET + streamAddr + ":" + in.streamPort
                            + " unreachable" + BULLET + streamFixHint
                            + (safe(in.daemonErrCompact).isEmpty() ? "" : BULLET + "host error: " + safe(in.daemonErrCompact));
                } else if (streamReconnects > 0) {
                    step3Detail = "reconnecting" + BULLET + "attempt #" + streamReconnects + BULLET + "awaiting frames\u2026";
                } else if (hasWaited) {
                    step3Detail = "connecting to " + streamAddr + ":" + in.streamPort
                            + BULLET + streamFixHint
                            + (safe(in.daemonErrCompact).isEmpty() ? "" : BULLET + "host error: " + safe(in.daemonErrCompact));
                } else {
                    step3Detail = "decoder started" + BULLET + "awaiting frames\u2026";
                }
            }
        }

        String subtitle;
        if (step1 != Model.SS_OK) {
            if (elapsedMs < 2000L && updatedControlRetryCount == 0) {
                subtitle = "starting up\u2026";
            } else if (updatedControlRetryCount == 0) {
                subtitle = "awaiting control link" + BULLET + "start desktop service if needed\u2026";
            } else {
                subtitle = "retrying control link" + BULLET + "attempt #" + updatedControlRetryCount
                        + BULLET + "check desktop service\u2026";
            }
        } else if (step2 != Model.SS_OK) {
            subtitle = (step2 == Model.SS_ERROR && in.buildMismatch)
                    ? "build mismatch" + BULLET + "redeploy APK or rebuild host"
                    : "handshake in progress\u2026";
        } else if (step3 != Model.SS_OK) {
            if (step3 == Model.SS_ERROR && daemonStartFailure) {
                subtitle = "host stream start failed" + BULLET + "check host logs";
            } else {
                subtitle = streamReconnects > 0
                        ? "stream reconnecting" + BULLET + "attempt #" + streamReconnects + "\u2026"
                        : elapsedMs > 5_000L
                                ? "stream unreachable" + BULLET + "retrying\u2026"
                                : "waiting for video frames\u2026";
            }
        } else {
            subtitle = "all systems ready";
        }

        StringBuilder info = new StringBuilder();
        info.append("api=").append(safe(in.apiBase))
                .append("  impl=").append(safe(in.apiImpl)).append('\n')
                .append("stream=").append(safe(in.streamHost)).append(':')
                .append(in.streamPort).append('\n')
                .append("app=").append(safe(in.appBuildRevision))
                .append("  host=").append(safe(in.daemonBuildRevision)).append('\n')
                .append("state=").append(safe(in.daemonState));
        if (!safe(in.daemonLastError).isEmpty()) {
            info.append('\n').append("error=").append(safe(in.daemonLastError));
        } else if (!safe(in.lastUiInfo).isEmpty()) {
            info.append('\n').append("hint=").append(safe(in.lastUiInfo));
        }

        out.step1State = step1;
        out.step1Detail = step1Detail;
        out.step2State = step2;
        out.step2Detail = step2Detail;
        out.step3State = step3;
        out.step3Detail = step3Detail;
        out.subtitle = subtitle;
        out.infoLog = info.toString();
        out.allOk = step1 == Model.SS_OK && step2 == Model.SS_OK && step3 == Model.SS_OK;
        return out;
    }

    private static int parseReconnectCount(String statsLine) {
        if (statsLine == null) {
            return 0;
        }
        try {
            int rIdx = statsLine.indexOf("reconnects: ");
            if (rIdx < 0) {
                return 0;
            }
            String rPart = statsLine.substring(rIdx + "reconnects: ".length()).trim();
            int end = 0;
            while (end < rPart.length() && Character.isDigit(rPart.charAt(end))) {
                end++;
            }
            if (end <= 0) {
                return 0;
            }
            return Integer.parseInt(rPart.substring(0, end));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return true;
        }
        String trimmed = host.trim();
        return trimmed.isEmpty()
                || "127.0.0.1".equals(trimmed)
                || "localhost".equalsIgnoreCase(trimmed);
    }

    private static String streamAddress(String host) {
        if (host != null && !host.trim().isEmpty()) {
            return host.trim();
        }
        return "127.0.0.1";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
