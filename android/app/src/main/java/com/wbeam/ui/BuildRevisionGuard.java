package com.wbeam.ui;

import java.util.Locale;

public final class BuildRevisionGuard {
    private BuildRevisionGuard() {
    }

    public static boolean isMismatch(
            boolean daemonReachable,
            boolean handshakeResolved,
            String apiImpl,
            String daemonBuildRevision,
            String appBuildRevision
    ) {
        if (!daemonReachable || !handshakeResolved) {
            return false;
        }
        if ("local".equalsIgnoreCase(apiImpl)) {
            return false;
        }
        String hostRev = normalizeRevision(daemonBuildRevision);
        String appRev = normalizeRevision(appBuildRevision);
        if (hostRev.isEmpty() || "-".equals(hostRev) || appRev.isEmpty()) {
            return false;
        }
        return !hostRev.equals(appRev);
    }

    public static String buildMismatchMessage(String appBuildRevision, String daemonBuildRevision) {
        return String.format(
                Locale.US,
                "Build mismatch: app=%s host=%s (redeploy APK or rebuild host)",
                appBuildRevision,
                daemonBuildRevision
        );
    }

    private static String normalizeRevision(String revision) {
        return revision == null ? "" : revision.trim();
    }
}
