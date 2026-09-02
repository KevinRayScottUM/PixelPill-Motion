package io.github.pixelpill.motion.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GestureCycleTest {
    @Test public void duplicatePressAndReleaseCallbacksAreIgnored() {
        GestureCycle cycle = new GestureCycle();
        int generation = cycle.beginPress();
        assertTrue(generation > 0);
        assertEquals(-1, cycle.beginPress());
        cycle.markPressed();
        assertEquals(GestureCycle.Phase.PRESSED, cycle.phase());
        assertTrue(cycle.beginReturn());
        assertFalse(cycle.beginReturn());
        cycle.markIdle();
        assertEquals(GestureCycle.Phase.IDLE, cycle.phase());
    }

    @Test public void newPressDuringReturnGetsANewGeneration() {
        GestureCycle cycle = new GestureCycle();
        int first = cycle.beginPress();
        assertTrue(cycle.beginReturn());
        int second = cycle.beginPress();
        assertTrue(second > first);
        assertFalse(cycle.isActive(first));
        assertTrue(cycle.isActive(second));
        assertEquals(GestureCycle.Phase.PRESSING, cycle.phase());
    }

    @Test public void delayedNativePressCannotReopenReturn() {
        GestureCycle cycle = new GestureCycle();
        assertTrue(cycle.beginPress() > 0);
        assertTrue(cycle.beginReturn());
        assertEquals(-1, cycle.beginPress(false));
        assertEquals(GestureCycle.Phase.RETURNING, cycle.phase());
        assertTrue(cycle.beginPress(true) > 0);
    }
}
