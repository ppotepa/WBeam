package com.wbeam.startup;

public final class StartupOverlayProbeHooksFactory {
    @FunctionalInterface
    public interface ProbeRequirement {
        boolean get();
    }

    @FunctionalInterface
    public interface ProbeStarter {
        void run();
    }

    private StartupOverlayProbeHooksFactory() {
    }

    public static StartupOverlayHooksFactory.ProbeHooks create(
            ProbeRequirement probeRequirement,
            ProbeStarter probeStarter
    ) {
        return new StartupOverlayHooksFactory.ProbeHooks() {
            @Override
            public boolean requiresTransportProbe() {
                return probeRequirement.get();
            }

            @Override
            public void maybeStartTransportProbe() {
                probeStarter.run();
            }
        };
    }
}
