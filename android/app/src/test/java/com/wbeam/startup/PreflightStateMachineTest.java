package com.wbeam.startup;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreflightStateMachineTest {
    @Test public void offlineKeepsRecoveryOverlayVisible() {
        PreflightStateMachine.Transition t = PreflightStateMachine.next(false, false, 800L);
        assertTrue(t.showOverlayNow);
        assertFalse(t.preflightComplete);
        assertFalse(t.scheduleHide);
    }

    @Test public void successfulPreflightSchedulesHideOnce() {
        PreflightStateMachine.Transition first = PreflightStateMachine.next(true, false, 800L);
        assertTrue(first.preflightComplete);
        assertTrue(first.scheduleHide);
        assertEquals(800L, first.hideDelayMs);
        PreflightStateMachine.Transition second = PreflightStateMachine.next(true, true, 800L);
        assertFalse(second.scheduleHide);
    }
}
