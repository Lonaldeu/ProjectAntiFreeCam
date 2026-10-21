package me.lonaldeu.antifreecam;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcclusionTest {

    private static final int D = 1, U = 2, N = 4, S = 8, W = 16, E = 32;
    private static final long STONE = 0, SKY = Occlusion.ALL;
    private static final long SURFACE = Occlusion.pairs(U | N | S | W | E); // ground with air above: no way down
    private static final long OPEN_SURFACE = Occlusion.pairs(U | N | S | W | E | D); // a hole leads down
    private static final long TUNNEL_X = Occlusion.pairs(W | E);

    /** A tiny server: columns of section connectivity; answers re-sends and recomputes from them when asked. */
    private static final class World {
        final Map<Long, long[]> columns = new HashMap<>();
        final Map<Long, Long> clientHidden = new HashMap<>(); // what the client holds as replacement
        final List<int[]> resends = new ArrayList<>(); // {x, z}
        final List<int[]> recomputes = new ArrayList<>(); // {x, z, s, epoch}
        final ArrayDeque<int[]> pendingResends = new ArrayDeque<>(), pendingRecomputes = new ArrayDeque<>();
        final Occlusion occlusion = new Occlusion(new Occlusion.Server() {
            @Override
            public void resend(int x, int z, int n) {
                resends.add(new int[]{x, z});
                pendingResends.add(new int[]{x, z});
            }

            @Override
            public void recompute(int x, int z, int s) {
                int[] r = {x, z, s, occlusion.epoch()};
                recomputes.add(r);
                pendingRecomputes.add(r);
            }
        });

        void column(int x, int z, long... sections) {
            columns.put(Occlusion.key(x, z), sections);
        }

        void send(int x, int z) {
            clientHidden.put(Occlusion.key(x, z), occlusion.add(x, z, columns.get(Occlusion.key(x, z)).clone()));
        }

        void sendAll() {
            for (long key : columns.keySet()) send((int) key, (int) (key >> 32));
        }

        /** Answers recomputes and delivers re-sends until none are left. */
        void settle() {
            while (!pendingResends.isEmpty() || !pendingRecomputes.isEmpty()) {
                if (!pendingRecomputes.isEmpty()) {
                    int[] r = pendingRecomputes.poll();
                    occlusion.exact(r[0], r[1], r[2], columns.get(Occlusion.key(r[0], r[1]))[r[2]], r[3]);
                } else {
                    int[] c = pendingResends.poll();
                    send(c[0], c[1]);
                }
            }
        }

        long hidden(int x, int z) {
            return clientHidden.get(Occlusion.key(x, z));
        }
    }

    private static World flat(int radius) {
        World w = new World();
        w.occlusion.view(0, -1, true);
        for (int x = -radius; x <= radius; x++)
            for (int z = -radius; z <= radius; z++) w.column(x, z, STONE, SURFACE, SKY);
        return w;
    }

    @Test
    void surfacePlayerSeesSurfaceAndSkyButNotTheRockBelow() {
        World w = flat(3);
        w.occlusion.moveTo(0, 2, 0);
        w.sendAll();
        w.settle();
        assertEquals(0b001, w.hidden(3, 3));
        assertEquals(0b001, w.hidden(1, 0)); // the box around the eyes covers sections 1-3 only
    }

    @Test
    void sealedPlayerSeesOnlyTheBoxAroundThem() {
        World w = flat(3);
        w.occlusion.moveTo(0, 0, 0);
        w.sendAll();
        w.settle();
        assertEquals(0b000, w.hidden(0, 0) & 0b011);
        assertEquals(0b100, w.hidden(1, 1) & 0b100); // the sky is unreachable
        assertEquals(0b111, w.hidden(3, 0)); // far away: nothing reachable at all
    }

    @Test
    void eyeAboveTheWorldStillSeesOut() {
        World w = flat(3);
        w.occlusion.moveTo(0, 30, 0); // flying far above the build limit
        w.sendAll();
        w.settle();
        assertEquals(0b001, w.hidden(3, 3));
    }

    @Test
    void tunnelIsVisibleAndParallelSealedTunnelIsNot() {
        World w = new World();
        w.occlusion.view(0, -1, true);
        for (int x = -4; x <= 4; x++) {
            w.column(x, 0, TUNNEL_X, STONE);
            w.column(x, 1, STONE, STONE);
            w.column(x, 3, TUNNEL_X, STONE);
        }
        w.occlusion.moveTo(0, 0, 0);
        w.sendAll();
        w.settle();
        assertEquals(0, w.hidden(4, 0) & 1);
        assertEquals(1, w.hidden(4, 3) & 1);
    }

    @Test
    void arrivalOrderDoesNotChangeTheResult() {
        Map<Long, Long> expected = null;
        for (int seed = 0; seed < 20; seed++) {
            World w = flat(3);
            w.column(2, 2, Occlusion.pairs(U | S), SKY, SKY); // a hole in the surface, a shaft turning south
            w.column(2, 3, Occlusion.pairs(N | E), SURFACE, SKY); // with a cave branching off it
            w.column(3, 3, Occlusion.pairs(W), SURFACE, SKY);
            w.occlusion.moveTo(0, 2, 0);
            List<Long> keys = new ArrayList<>(w.columns.keySet());
            Collections.shuffle(keys, new Random(seed));
            for (long key : keys) w.send((int) key, (int) (key >> 32));
            w.settle();
            if (expected == null) expected = new HashMap<>(w.clientHidden);
            else assertEquals(expected, w.clientHidden, "seed " + seed);
        }
        assertEquals(0, expected.get(Occlusion.key(3, 3)) & 1); // reached through the shaft and the cave
    }

    @Test
    void laterColumnRevealsEarlierOneOnce() {
        World w = new World();
        w.occlusion.view(0, -1, true);
        for (int x = 0; x <= 3; x++) w.column(x, 0, TUNNEL_X);
        w.occlusion.moveTo(0, 0, 0);
        w.send(0, 0);
        w.send(3, 0); // not connected yet: hidden
        assertEquals(1, w.hidden(3, 0));
        w.send(1, 0);
        w.send(2, 0); // completes the tunnel: column 3 must be re-sent, column 2 needs nothing extra
        assertEquals(1, w.resends.size());
        assertEquals(3, w.resends.get(0)[0]);
        w.settle();
        assertEquals(0, w.hidden(3, 0));
        assertEquals(1, w.resends.size());
    }

    @Test
    void miningIntoAPocketRevealsIt() {
        World w = flat(2);
        w.occlusion.moveTo(0, 2, 0);
        w.sendAll();
        w.settle();
        w.resends.clear(); // those were columns arriving out of order
        assertEquals(1, w.hidden(2, 0) & 1);
        w.occlusion.changed(2 * 16 + 5, 16, 5, true); // placing stone opens nothing
        assertTrue(w.recomputes.isEmpty() && w.resends.isEmpty());

        w.column(2, 0, STONE, OPEN_SURFACE, SKY); // the server now has a hole at the bottom of the surface section
        w.occlusion.changed(2 * 16 + 5, 16, 5, false);
        assertEquals(1, w.recomputes.size());
        w.settle();
        assertEquals(1, w.resends.size());
        assertEquals(0, w.hidden(2, 0) & 1);
    }

    /** A see-through change that joins nothing (a crop growing) is checked against the real blocks and reveals nothing. */
    @Test
    void cropGrowingRevealsNothing() {
        World w = flat(2);
        w.occlusion.moveTo(0, 2, 0);
        w.sendAll();
        w.settle();
        w.resends.clear();
        w.occlusion.changed(2 * 16 + 5, 16, 5, false);
        w.settle();
        assertTrue(w.resends.isEmpty());
        assertEquals(1, w.hidden(2, 0) & 1);
    }

    /** A change out of reach is only worked out once the section is reached. */
    @Test
    void changeOutOfReachIsCheckedWhenReached() {
        World w = flat(2);
        w.occlusion.moveTo(0, 2, 0);
        w.sendAll();
        w.settle();
        w.occlusion.changed(2 * 16 + 5, 3, 5, false); // inside the unreached rock layer
        assertTrue(w.recomputes.isEmpty());
        w.column(2, 0, STONE, OPEN_SURFACE, SKY);
        w.occlusion.changed(2 * 16 + 5, 16, 5, false); // now a hole reaches it
        w.settle();
        assertEquals(2, w.recomputes.size()); // the surface section, then the rock section once reached
    }

    @Test
    void reSentColumnThatClosedHidesAgain() {
        World w = flat(2);
        w.column(2, 0, STONE, OPEN_SURFACE, SKY);
        w.occlusion.moveTo(0, 2, 0);
        w.sendAll();
        w.settle();
        assertEquals(0, w.hidden(2, 0) & 1);
        w.column(2, 0, STONE, SURFACE, SKY); // sealed again
        w.send(2, 0);
        assertEquals(1, w.hidden(2, 0) & 1);
    }

    /** A new world drops the client's chunks, but the player's position stays known. */
    @Test
    void worldChangeKeepsTheEye() {
        World w = flat(2);
        w.occlusion.moveTo(0, 2, 0);
        w.sendAll();
        w.settle();
        w.occlusion.clear();
        w.sendAll();
        w.settle();
        assertEquals(0b001, w.hidden(2, 2));
        assertEquals(0b001, w.hidden(0, 0)); // the player's own column is real, not all stone
    }

    @Test
    void inactiveOcclusionHidesNothing() {
        World w = flat(2);
        w.occlusion.view(0, -1, false);
        w.occlusion.moveTo(0, 2, 0);
        w.sendAll();
        assertEquals(0, w.hidden(2, 2));
        assertTrue(w.resends.isEmpty());
    }

    /** A cave under the cut must not count as a way into what lies above it. */
    @Test
    void sectionsUnderTheHeightCutAreNotOccludedOrEntered() {
        World w = new World();
        w.occlusion.view(0, 15, true); // section 0 is height-hidden
        w.column(0, 0, SKY, SKY, STONE);
        w.column(1, 0, SKY, STONE, STONE);
        w.column(2, 0, SKY, STONE, STONE);
        w.column(3, 0, SKY, Occlusion.pairs(D), STONE); // a pocket only reachable from below
        w.occlusion.moveTo(0, 1, 0);
        w.sendAll();
        w.settle();
        assertEquals(0, w.hidden(0, 0) & 1); // under the cut: not occlusion's business
        assertEquals(0b010, w.hidden(3, 0) & 0b010);
    }

    /** A new world while a section is being worked out: the answer is about the old world and must open nothing. */
    @Test
    void answerFromBeforeAWorldChangeIsIgnored() {
        World w = flat(2);
        w.occlusion.moveTo(0, 2, 0);
        w.sendAll();
        w.settle();
        w.occlusion.changed(2 * 16 + 5, 16, 5, false);
        int[] stale = w.pendingRecomputes.poll();
        w.occlusion.clear(); // the new world is just as sealed
        w.sendAll();
        w.settle();
        w.resends.clear();
        w.occlusion.exact(stale[0], stale[1], stale[2], OPEN_SURFACE, stale[3]); // "a hole leads down", about the old world
        assertTrue(w.resends.isEmpty());
    }
}
