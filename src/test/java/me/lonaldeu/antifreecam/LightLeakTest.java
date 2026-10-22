package me.lonaldeu.antifreecam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightLeakTest {

    // States: 0 air, 1 lava (emits 15), 2 torch (emits 14), 3 stone (blocks light).
    private static final byte[] EMISSION = {0, 15, 14, 0}, LIGHT_BLOCK = {0, 0, 0, 15};
    private static final int MIN_Y = 0, SECTIONS = 4; // world Y 0..63; light sections 0..5 (-16..79)

    /** A column of air with a few placed blocks, lit along the single column x = z = 0 like the game would. */
    private static final class Column {
        final int[][] states = new int[SECTIONS][4096];
        final byte[][] light = new byte[SECTIONS + 2][];

        void block(int y, int state) {
            block(0, y, state);
        }

        void block(int x, int y, int state) {
            states[(y - MIN_Y) >> 4][(y - MIN_Y & 15) << 8 | x] = state;
        }

        void light(int y, int level) {
            light(0, y, level);
        }

        void light(int x, int y, int level) {
            int i = (y - MIN_Y + 16) >> 4, index = (y - MIN_Y + 16 & 15) << 8 | x;
            if (light[i] == null) light[i] = new byte[2048];
            light[i][index >> 1] |= (byte) (level << ((index & 1) << 2));
        }

        int lightAt(int y) {
            return lightAt(0, y);
        }

        int lightAt(int x, int y) {
            int i = (y - MIN_Y + 16) >> 4, index = (y - MIN_Y + 16 & 15) << 8 | x;
            return light[i] == null ? 0 : light[i][index >> 1] >> ((index & 1) << 2) & 15;
        }

        boolean clean(int maxY, long occluded) {
            return LightLeak.clean(light, MIN_Y, maxY, occluded, (s, index) -> states[s][index], EMISSION, LIGHT_BLOCK);
        }
    }

    /** Lava at Y 15 under a cut at 15 lights Y 16..29 straight up: all of that goes. */
    @Test
    void lightFromBelowTheCutIsRemoved() {
        Column c = new Column();
        c.block(15, 1);
        for (int y = 15; y <= 29; y++) c.light(y, 15 - (y - 15));
        assertTrue(c.clean(15, 0));
        for (int y = 16; y <= 29; y++) assertEquals(0, c.lightAt(y), "Y " + y);
    }

    /** A torch just above the cut keeps lighting its surroundings, as if the lava below weren't there. */
    @Test
    void lightFromAboveTheCutStays() {
        Column c = new Column();
        c.block(15, 1);
        c.block(17, 2);
        c.light(15, 15);
        c.light(16, 14); // from the lava (the torch alone would give 13)
        for (int y = 17; y <= 30; y++) c.light(y, 14 - (y - 17));
        assertTrue(c.clean(15, 0));
        assertEquals(13, c.lightAt(16));
        assertEquals(14, c.lightAt(17));
        assertEquals(13, c.lightAt(18));
        assertEquals(1, c.lightAt(30));
    }

    /** An opaque block above the cut stops nothing being re-lit through it, but its own light stays put. */
    @Test
    void stoneInBetweenStaysDark() {
        Column c = new Column();
        c.block(15, 1);
        c.block(16, 3);
        c.light(15, 15);
        assertFalse(c.clean(15, 0));
        assertEquals(0, c.lightAt(16));
    }

    /** Light from an occluded section above a visible one is removed too. */
    @Test
    void lightFromAnOccludedSectionAboveIsRemoved() {
        Column c = new Column();
        c.block(32, 1);
        for (int y = 32; y >= 20; y--) c.light(y, 15 - (32 - y));
        assertTrue(c.clean(MIN_Y - 1, 1L << 2)); // world section 2 (Y 32..47) occluded, nothing height-hidden
        for (int y = 20; y <= 31; y++) assertEquals(0, c.lightAt(y), "Y " + y);
    }

    /**
     * A hidden air cell lit from the visible cell above it must not make that cell a source of its own: the lava's
     * light reaches it sideways along the visible row, and all of that goes.
     */
    @Test
    void lightLitSidewaysFromTheLeakIsNotPutBack() {
        Column c = new Column();
        c.block(4, 16, 1); // lava, hidden (cut at 16); (5,16) is hidden stone, (6,16) hidden air lit from above
        c.block(5, 16, 3);
        c.light(4, 16, 15);
        c.light(6, 16, 11);
        c.light(4, 17, 14);
        c.light(5, 17, 13);
        c.light(6, 17, 12);
        assertTrue(c.clean(16, 0));
        assertEquals(0, c.lightAt(4, 17));
        assertEquals(0, c.lightAt(5, 17));
        assertEquals(0, c.lightAt(6, 17));
    }

    /** Light spread again never gets brighter than the server had it (a slab there may have dimmed it). */
    @Test
    void lightSpreadAgainIsNeverBrighterThanBefore() {
        Column c = new Column();
        c.block(15, 1);
        c.block(16, 2); // a torch right above the cut
        c.light(15, 15);
        c.light(16, 14);
        c.light(17, 10); // dimmer than the torch alone would make it: something not known here dims it
        assertTrue(c.clean(15, 0));
        assertEquals(14, c.lightAt(16));
        assertEquals(10, c.lightAt(17));
    }

    @Test
    void nothingHiddenIsLitSoNothingChanges() {
        Column c = new Column();
        c.block(20, 2);
        for (int y = 20; y <= 33; y++) c.light(y, 14 - (y - 20));
        byte[] before = c.light[2];
        assertFalse(c.clean(15, 0));
        assertSame(before, c.light[2]);
    }

    /** The arrays passed in may be shared with other players' packets: changed ones are copies. */
    @Test
    void inputArraysAreNotModified() {
        Column c = new Column();
        c.block(15, 1);
        for (int y = 15; y <= 29; y++) c.light(y, 15 - (y - 15));
        byte[] original = c.light[2], copy = original.clone();
        assertTrue(c.clean(15, 0));
        assertTrue(java.util.Arrays.equals(copy, original));
    }

    /** Light updates leave to the client only sections with visible cells that light from a hidden cell can reach. */
    @Test
    void nearHiddenMarksTheSectionsLightCanReach() {
        // Cut at Y 16: light section 2 (Y 16..31) straddles it, section 3 (Y 32..47) starts 16 blocks above it.
        assertFalse(LightLeak.nearHidden(1, MIN_Y, 16, 0)); // all hidden: dropped anyway
        assertTrue(LightLeak.nearHidden(2, MIN_Y, 16, 0));
        assertFalse(LightLeak.nearHidden(3, MIN_Y, 16, 0));
        assertTrue(LightLeak.nearHidden(3, MIN_Y, 16, 1L << 3)); // Y 48..63 occluded, right above it
    }

    /** Two columns side by side, x 0..15 and 16..31, of stone with some air, lit everywhere like the game would. */
    private static final class Pair {
        final int[][][] states = new int[2][SECTIONS][4096];
        final byte[][][] light = new byte[2][SECTIONS + 2][];

        Pair() {
            for (int[][] column : states) for (int[] section : column) java.util.Arrays.fill(section, 3);
        }

        void block(int x, int y, int z, int state) {
            states[x >> 4][(y - MIN_Y) >> 4][(y - MIN_Y & 15) << 8 | z << 4 | x & 15] = state;
        }

        int state(int x, int y, int z) {
            if (x < 0 || x > 31 || z < 0 || z > 15 || y < MIN_Y || y >= MIN_Y + SECTIONS * 16) return 3;
            return states[x >> 4][(y - MIN_Y) >> 4][(y - MIN_Y & 15) << 8 | z << 4 | x & 15];
        }

        /** Lights both columns from their sources, spreading across the boundary between them. */
        Pair lit() {
            int[][][] level = new int[32][SECTIONS * 16][16];
            java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
            for (int x = 0; x < 32; x++)
                for (int y = MIN_Y; y < MIN_Y + SECTIONS * 16; y++)
                    for (int z = 0; z < 16; z++)
                        if (EMISSION[state(x, y, z)] > 0) {
                            level[x][y - MIN_Y][z] = EMISSION[state(x, y, z)];
                            queue.add(new int[]{x, y, z});
                        }
            int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            while (!queue.isEmpty()) {
                int[] p = queue.poll();
                for (int[] d : steps) {
                    int x = p[0] + d[0], y = p[1] + d[1], z = p[2] + d[2];
                    if (x < 0 || x > 31 || z < 0 || z > 15 || y < MIN_Y || y >= MIN_Y + SECTIONS * 16) continue;
                    int next = level[p[0]][p[1] - MIN_Y][p[2]] - Math.max(1, LIGHT_BLOCK[state(x, y, z)]);
                    if (next <= level[x][y - MIN_Y][z]) continue;
                    level[x][y - MIN_Y][z] = next;
                    queue.add(new int[]{x, y, z});
                }
            }
            for (int x = 0; x < 32; x++)
                for (int y = MIN_Y; y < MIN_Y + SECTIONS * 16; y++)
                    for (int z = 0; z < 16; z++) {
                        if (level[x][y - MIN_Y][z] == 0) continue;
                        int i = (y - MIN_Y + 16) >> 4, index = (y - MIN_Y + 16 & 15) << 8 | z << 4 | x & 15;
                        byte[][] column = light[x >> 4];
                        if (column[i] == null) column[i] = new byte[2048];
                        column[i][index >> 1] |= (byte) (level[x][y - MIN_Y][z] << ((index & 1) << 2));
                    }
            return this;
        }

        int lightAt(int x, int y, int z) {
            int i = (y - MIN_Y + 16) >> 4, index = (y - MIN_Y + 16 & 15) << 8 | z << 4 | x & 15;
            byte[] a = light[x >> 4][i];
            return a == null ? 0 : a[index >> 1] >> ((index & 1) << 2) & 15;
        }

        boolean clean(int column, int maxY, LightLeak.Side[] neighbours, LightLeak.Side[] sides) {
            return clean(column, maxY, neighbours, null, sides);
        }

        boolean clean(int column, int maxY, LightLeak.Side[] neighbours, boolean[] relight, LightLeak.Side[] sides) {
            return LightLeak.clean(light[column], MIN_Y, maxY, 0, (s, index) -> states[column][s][index], EMISSION, LIGHT_BLOCK,
                    neighbours, relight, sides);
        }
    }

    /** Lava under the cut at the east edge of one column, a cave above it running into the next column. */
    private static Pair caveAcross(boolean lava, boolean torch) {
        Pair p = new Pair();
        if (lava) p.block(15, 16, 8, 1);
        for (int y = 17; y <= 20; y++) for (int x = 14; x <= 20; x++) p.block(x, y, 8, 0);
        if (torch) p.block(14, 18, 8, 2);
        return p.lit();
    }

    /** Light from below the cut that crosses into the next column above it is taken out there, given the first one's side. */
    @Test
    void lightCrossingIntoTheNextColumnIsRemovedThere() {
        Pair alone = caveAcross(true, false);
        alone.clean(1, 16, null, null);
        assertTrue(alone.lightAt(16, 17, 8) > 0); // cleaned on its own, the next column keeps it

        Pair p = caveAcross(true, false);
        LightLeak.Side[] west = new LightLeak.Side[4];
        assertTrue(p.clean(0, 16, null, west));
        assertTrue(west[3] != null); // the east side lost light
        LightLeak.Side[] neighbours = new LightLeak.Side[4];
        neighbours[2] = west[3]; // the next column's west side faces it
        p.clean(1, 16, neighbours, null);
        for (int x = 14; x <= 20; x++) for (int y = 17; y <= 20; y++) assertEquals(0, p.lightAt(x, y, 8), "x " + x + ", Y " + y);
    }

    /** A torch above the cut in the first column still lights the next one, as if the lava weren't there. */
    @Test
    void torchLightFromTheNextColumnStays() {
        Pair expected = caveAcross(false, true);
        Pair p = caveAcross(true, true);
        LightLeak.Side[] west = new LightLeak.Side[4];
        p.clean(0, 16, null, west);
        LightLeak.Side[] neighbours = new LightLeak.Side[4];
        neighbours[2] = west[3];
        p.clean(1, 16, neighbours, null);
        for (int x = 14; x <= 20; x++)
            for (int y = 17; y <= 20; y++) assertEquals(expected.lightAt(x, y, 8), p.lightAt(x, y, 8), "x " + x + ", Y " + y);
    }

    /**
     * The next column cleaned first, without this one known: what it kept along their side still holds this column's
     * leak, so it takes light away here but doesn't put any back.
     */
    @Test
    void aNeighbourThatDidNotKnowThisColumnLightsNothingBack() {
        Pair p = new Pair();
        p.block(15, 16, 8, 1); // the leak: lava under the cut at the first column's east edge, a cave running east
        for (int y = 17; y <= 20; y++) for (int x = 14; x <= 20; x++) p.block(x, y, 8, 0);
        p.block(16, 16, 2, 1); // and a light of the next column's own on its west side, so that side changes
        p.block(16, 17, 2, 0);
        p.lit();
        LightLeak.Side[] next = new LightLeak.Side[4];
        p.clean(1, 16, null, next); // cleaned first: the first column's leak at z 8 stays in it
        assertTrue(next[2] != null && p.lightAt(16, 17, 8) > 0);
        LightLeak.Side[] neighbours = new LightLeak.Side[4];
        neighbours[3] = next[2];
        boolean[] relight = new boolean[4]; // it didn't know this column: nothing put back from it
        p.clean(0, 16, neighbours, relight, null);
        for (int x = 14; x <= 15; x++) for (int y = 17; y <= 20; y++) assertEquals(0, p.lightAt(x, y, 8), "x " + x + ", Y " + y);
    }
}
