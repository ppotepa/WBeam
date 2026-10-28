package com.wbeam.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class BuildRevisionGuardTest {
    @Test public void matchingRevisionsAreCompatible() {
        assertFalse(BuildRevisionGuard.isMismatch(true, true, "host",
                "0.1.3-rc.1", "0.1.3-rc.1"));
    }

    @Test public void mismatchedHostReleaseIsRejected() {
        assertTrue(BuildRevisionGuard.isMismatch(true, true, "host",
                "0.1.3-rc.2", "0.1.3-rc.1"));
    }

    @Test public void offlineAndLocalModesDoNotCreateFalseMismatch() {
        assertFalse(BuildRevisionGuard.isMismatch(false, true, "host", "0.1.3", "0.1.2"));
        assertFalse(BuildRevisionGuard.isMismatch(true, false, "host", "0.1.3", "0.1.2"));
        assertFalse(BuildRevisionGuard.isMismatch(true, true, "local", "0.1.3", "0.1.2"));
    }
}
