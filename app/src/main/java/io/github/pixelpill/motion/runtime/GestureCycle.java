package io.github.pixelpill.motion.runtime;

/** Deterministic press/return gate shared by duplicate touch and native callbacks. */
public final class GestureCycle {
    public enum Phase { IDLE, PRESSING, PRESSED, RETURNING }

    private Phase phase = Phase.IDLE;
    private boolean active;
    private int generation;

    /** Returns a new generation, or -1 when this physical gesture already started. */
    public int beginPress() {
        return beginPress(true);
    }

    /** Native callbacks cannot reopen a return; a real new ACTION_DOWN still can. */
    public int beginPress(boolean allowDuringReturn) {
        if (active) return -1;
        if (!allowDuringReturn && phase == Phase.RETURNING) return -1;
        active = true;
        phase = Phase.PRESSING;
        return ++generation;
    }

    public boolean beginReturn() {
        if (!active) return false;
        active = false;
        phase = Phase.RETURNING;
        return true;
    }

    public void markPressed() {
        if (active) phase = Phase.PRESSED;
    }

    public void markIdle() {
        if (!active) phase = Phase.IDLE;
    }

    public boolean isActive() { return active; }
    public boolean isActive(int expectedGeneration) {
        return active && generation == expectedGeneration;
    }
    public Phase phase() { return phase; }
}
