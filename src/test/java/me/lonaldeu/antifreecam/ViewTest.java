package me.lonaldeu.antifreecam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewTest {

    private static final int DEPTH = 48;
    private static final double HYSTERESIS = 2;

    private static int cutAt(double y, int held) {
        return View.depthCut(y - DEPTH, held, HYSTERESIS);
    }

    @Test
    void movingUpHidesTheNextSectionOnlyPastTheHysteresis() {
        assertEquals(-49, cutAt(15, -49));
        assertEquals(-49, cutAt(16.25, -49)); // jumping across Y 16 keeps the view
        assertEquals(-49, cutAt(17.9, -49));
        assertEquals(-33, cutAt(18, -49)); // two blocks past the boundary
    }

    @Test
    void movingDownShowsMoreAtOnce() {
        assertEquals(-49, cutAt(15.9, -33));
    }

    @Test
    void theBoundaryAtYZeroHoldsToo() {
        assertEquals(-65, cutAt(0.5, -65)); // -65: nothing height-hidden in a world starting at -64
        assertEquals(-49, cutAt(2, -65));
    }

    @Test
    void aLongWayUpAtOnceIsNotHeldBack() {
        assertEquals(-17, cutAt(40, -65));
    }
}
