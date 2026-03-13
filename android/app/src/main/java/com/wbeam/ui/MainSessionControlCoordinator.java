package com.wbeam.ui;

import android.content.Context;
import android.widget.Toast;

import com.wbeam.BuildConfig;
import com.wbeam.api.HostApiClient;

public final class MainSessionControlCoordinator {
    public interface StatusSink {
        void onStatus(String state, String info, long bps);
    }

    public interface LineLogger {
        void onLine(String level, String line);
    }

    public interface StartRequester {
        void request(boolean userAction, boolean ensureViewer);
    }

    private MainSessionControlCoordinator() {
    }

    public static void handleApiFailure(
            Context context,
            String tag,
            String stateError,
            String prefix,
            boolean userAction,
            Exception error,
            StatusSink statusSink,
            LineLogger lineLogger
    ) {
        HostApiFailureNotifier.handle(
                context,
                tag,
                stateError,
                prefix,
                userAction,
                error,
                HostApiClient.API_BASE,
                statusSink::onStatus,
                line -> lineLogger.onLine("E", line)
        );
    }

    public static boolean isBuildMismatch(
            boolean daemonReachable,
            boolean handshakeResolved,
            String daemonBuildRevision
    ) {
        return BuildRevisionGuard.isMismatch(
                daemonReachable,
                handshakeResolved,
                BuildConfig.WBEAM_API_IMPL,
                daemonBuildRevision,
                BuildConfig.WBEAM_BUILD_REV
        );
    }

    public static void requestStartGuarded(
            Context context,
            boolean userAction,
            boolean ensureViewer,
            boolean daemonReachable,
            boolean handshakeResolved,
            String daemonBuildRevision,
            StatusSink statusSink,
            LineLogger lineLogger,
            StartRequester startRequester
    ) {
        if (isBuildMismatch(daemonReachable, handshakeResolved, daemonBuildRevision)) {
            String msg = BuildRevisionGuard.buildMismatchMessage(
                    BuildConfig.WBEAM_BUILD_REV,
                    daemonBuildRevision
            );
            statusSink.onStatus("error", msg, 0);
            lineLogger.onLine("E", msg);
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
            return;
        }
        startRequester.request(userAction, ensureViewer);
    }
}
