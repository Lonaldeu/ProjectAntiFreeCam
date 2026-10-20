package me.lonaldeu.antifreecam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaycastTest {

    /** A wall filling the plane x = 3. */
    private static final Raycast.Solid WALL = (x, y, z) -> x == 3;

    @Test
    void wallBlocksStraightAndDiagonalRays() {
        assertFalse(Raycast.clear(0.5, 1.6, 0.5, 6.5, 1.0, 0.5, WALL));
        assertFalse(Raycast.clear(0.5, 1.6, 0.5, 6.5, 0.2, 4.5, WALL));
        assertFalse(Raycast.clear(6.5, 1.0, 0.5, 0.5, 1.6, 0.5, WALL)); // other direction
    }

    @Test
    void rayThatNeverCrossesTheWallIsClear() {
        assertTrue(Raycast.clear(0.5, 1.6, 0.5, 2.5, 1.0, 5.5, WALL));
        assertTrue(Raycast.clear(0.5, 1.6, 0.5, 6.5, 1.0, 0.5, (x, y, z) -> false));
    }

    @Test
    void endBlocksAreNotChecked() {
        assertTrue(Raycast.clear(3.5, 1.0, 0.5, 3.2, 1.0, 9.5, (x, y, z) -> z == 0 || z == 9));
    }

    @Test
    void gapInTheWallLetsTheRayThrough() {
        Raycast.Solid wallWithHole = (x, y, z) -> x == 3 && !(y == 1 && z == 0);
        assertTrue(Raycast.clear(0.5, 1.5, 0.5, 6.5, 1.5, 0.5, wallWithHole));
        assertFalse(Raycast.clear(0.5, 1.5, 2.5, 6.5, 1.5, 2.5, wallWithHole));
    }
}
