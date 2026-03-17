package com.wbeam.ui;

import android.content.Context;
import android.widget.Toast;

import com.wbeam.BuildConfig;
import com.wbeam.api.HostApiClient;

@SuppressWarnings("java:S107")
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

    public static final class ApiFailureInput {
        private Context context;
        private String tag;
        private String stateError;
        private String prefix;
        private boolean userAction;
        private Exception error;
        private StatusSink statusSink;
        private LineLogger lineLogger;

        public ApiFailureInput setContext(Context value) { context = value; return this; }
        public ApiFailureInput setTag(String value) { tag = value; return this; }
        public ApiFailureInput setStateError(String value) { stateError = value; return this; }
        public ApiFailureInput setPrefix(String value) { prefix = value; return this; }
        public ApiFailureInput setUserAction(boolean value) { userAction = value; return this; }
        public ApiFailureInput setError(Exception value) { error = value; return this; }
        public ApiFailureInput setStatusSink(StatusSink value) { statusSink = value; return this; }
        public ApiFailureInput setLineLogger(LineLogger value) { lineLogger = value; return this; }
    }

    public static final class RequestStartInput {
        private Context context;
        private boolean userAction;
        private boolean ensureViewer;
        private boolean daemonReachable;
        private boolean handshakeResolved;
        private String daemonBuildRevision;
        private StatusSink statusSink;
        private LineLogger lineLogger;
        private StartRequester startRequester;

        public RequestStartInput setContext(Context value) { context = value; return this; }
        public RequestStartInput setUserAction(boolean value) { userAction = value; return this; }
        public RequestStartInput setEnsureViewer(boolean value) { ensureViewer = value; return this; }
        public RequestStartInput setDaemonReachable(boolean value) { daemonReachable = value; return this; }
        public RequestStartInput setHandshakeResolved(boolean value) { handshakeResolved = value; return this; }
        public RequestStartInput setDaemonBuildRevision(String value) { daemonBuildRevision = value; return this; }
        public RequestStartInput setStatusSink(StatusSink value) { statusSink = value; return this; }
        public RequestStartInput setLineLogger(LineLogger value) { lineLogger = value; return this; }
        public RequestStartInput setStartRequester(StartRequester value) { startRequester = value; return this; }
    }

    public static void handleApiFailure(ApiFailureInput input) {
        HostApiFailureNotifier.handle(
                input.context,
                input.tag,
                input.stateError,
                input.prefix,
                input.userAction,
                input.error,
                HostApiClient.API_BASE,
                input.statusSink::onStatus,
                line -> input.lineLogger.onLine("E", line)
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

    public static void requestStartGuarded(RequestStartInput input) {
        if (isBuildMismatch(input.daemonReachable, input.handshakeResolved, input.daemonBuildRevision)) {
            String msg = BuildRevisionGuard.buildMismatchMessage(
                    BuildConfig.WBEAM_BUILD_REV,
                    input.daemonBuildRevision
            );
            input.statusSink.onStatus("error", msg, 0);
            input.lineLogger.onLine("E", msg);
            Toast.makeText(input.context, msg, Toast.LENGTH_LONG).show();
            return;
        }
        input.startRequester.request(input.userAction, input.ensureViewer);
    }
}
