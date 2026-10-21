package me.lonaldeu.antifreecam;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Cave occlusion for one player: which 16x16x16 sections of the chunks their client holds can be reached from the
 * player's own section through see-through blocks, like the client's own cave culling. Sections that can't be
 * reached are sent as solid replacement, so freecam and x-ray clients have nothing to show there.
 * <p>
 * A section's connectivity is a 36-bit value: bit 6a+b is set when faces a and b are joined by see-through cells,
 * so bit 7a means face a has any. Faces: 0 down, 1 up, 2 north (-z), 3 south (+z), 4 west (-x), 5 east (+x); the
 * opposite of face f is f ^ 1. Connectivity comes from each chunk packet's blocks; after a see-through block
 * update the server works it out again for that section. Reach only grows until the view changes or a section the
 * player reaches turns out more closed than before. Never re-sends to hide. Not thread-safe: one per connection,
 * used on its event loop only.
 */
final class Occlusion {

    static final long ALL = (1L << 36) - 1;
    private static final int START = 1 << 6; // the player's own section: every face is an exit
    private static final int[] DX = {0, 0, 0, 0, -1, 1}, DZ = {0, 0, -1, 1, 0, 0};

    interface Server {
        /** Re-send column (x, z) to this player; n counts re-sends in the current burst, for pacing. */
        void resend(int x, int z, int n);

        /** Work out the exact connectivity of section s of column (x, z); the answer comes back through {@link #exact}. */
        void recompute(int x, int z, int s);
    }

    private static final class Col {
        final int x, z;
        long[] conn;
        byte[] entered; // per section: faces it was entered through (bits 0-5), START (bit 6)
        long sentHidden; // sections the client currently holds as replacement
        long dirty; // sections changed while not entered: connectivity is worked out again once they are
        long pending; // sections whose connectivity is being worked out
        boolean resendQueued;

        Col(int x, int z, long[] conn) {
            this.x = x;
            this.z = z;
            this.conn = conn;
            this.entered = new byte[conn.length];
        }
    }

    private record Entry(Col col, int s) {
    }

    private final Server server;
    private final Map<Long, Col> cols = new HashMap<>();
    private final ArrayDeque<Entry> stack = new ArrayDeque<>();
    private final Set<Col> touched = new LinkedHashSet<>();
    private int minY = Integer.MIN_VALUE, maxHiddenY, heightHidden; // heightHidden: sections fully under the cut
    private boolean active;
    private boolean hasEye;
    private int ex, es, ez;
    private int burst;
    private int epoch; // bumped by clear(), so answers about the previous world can be told apart

    Occlusion(Server server) {
        this.server = server;
    }

    static long key(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }

    /** Every pair among the given faces (bit f = face f), as connectivity bits. */
    static long pairs(int faces) {
        long out = 0;
        for (int a = 0; a < 6; a++)
            if ((faces >> a & 1) != 0)
                for (int b = 0; b < 6; b++)
                    if ((faces >> b & 1) != 0) out |= 1L << (6 * a + b);
        return out;
    }

    /** The player's view. Rebuilds reach when it changed. */
    void view(int minY, int maxHiddenY, boolean active) {
        if (minY == this.minY && maxHiddenY == this.maxHiddenY && active == this.active) return;
        this.minY = minY;
        this.maxHiddenY = maxHiddenY;
        this.active = active;
        heightHidden = Math.max(0, Math.floorDiv(maxHiddenY - minY + 1, 16));
        burst = 0;
        rebuild();
        reveal(null);
    }

    /** A chunk packet for column (x, z) with these section connectivities is about to be sent: the sections to replace. */
    long add(int x, int z, long[] conn) {
        burst = 0;
        Col c = cols.get(key(x, z));
        boolean shrank = false;
        if (c == null || c.conn.length != conn.length) {
            c = new Col(x, z, conn);
            cols.put(key(x, z), c);
        } else {
            // Only a section the player reaches getting more closed can shrink their reach.
            for (int s = 0; s < conn.length; s++) shrank |= (c.conn[s] & ~conn[s]) != 0 && c.entered[s] != 0;
            c.conn = conn;
        }
        c.resendQueued = false;
        c.dirty = 0; // these connectivities are exact
        c.pending = 0;
        if (shrank) {
            rebuild();
        } else {
            for (int s = 0; s < c.entered.length; s++) if (c.entered[s] != 0) stack.push(new Entry(c, s));
            for (int g = 2; g < 6; g++) { // reach coming in from neighbours that arrived earlier
                Col n = cols.get(key(c.x + DX[g], c.z + DZ[g]));
                if (n == null) continue;
                for (int s = 0; s < Math.min(n.entered.length, c.entered.length); s++)
                    if (n.entered[s] != 0 && (exits(n, s) >> (g ^ 1) & 1) != 0) enter(c, s, 1 << g);
            }
            if (hasEye && c.x == ex && c.z == ez) enter(c, eyeSection(c), START);
            drain();
        }
        long hidden = hidden(c);
        c.sentHidden = hidden;
        reveal(c);
        return hidden;
    }

    /**
     * A block the client holds changed. Only a see-through block can open new paths; the server works out the
     * section's connectivity again (at once if the player reaches it, otherwise once they do). A placed block's
     * closing only counts from the column's next chunk packet.
     */
    void changed(int x, int y, int z, boolean opaque) {
        Col c = cols.get(key(x >> 4, z >> 4));
        if (c == null || opaque) return;
        int s = (y - minY) >> 4;
        if (s < 0 || s >= Math.min(c.conn.length, 64) || c.conn[s] == ALL) return;
        if (c.entered[s] != 0 && active) request(c, s);
        else c.dirty |= 1L << s; // worked out once reached, or once occlusion applies to this player again
    }

    /** The exact connectivity of section s of column (x, z), worked out by the server after a change. */
    void exact(int x, int z, int s, long conn) {
        Col c = cols.get(key(x, z));
        if (c == null || s >= Math.min(c.conn.length, 64)) return;
        c.pending &= ~(1L << s);
        long old = c.conn[s];
        if (conn == old) return;
        c.conn[s] = conn;
        if (c.entered[s] == 0) return;
        burst = 0;
        if ((old & ~conn) != 0) {
            rebuild(); // it closed: reach may shrink (shown as hidden again from the column's next send)
        } else {
            stack.push(new Entry(c, s));
            touched.add(c);
            drain();
        }
        reveal(null);
    }

    private void request(Col c, int s) {
        long bit = 1L << s;
        c.dirty &= ~bit;
        if ((c.pending & bit) != 0) return;
        c.pending |= bit;
        server.recompute(c.x, c.z, s);
    }

    /** The eye's section, kept inside the column so players above or below the world still see out. */
    private int eyeSection(Col c) {
        return Math.max(0, Math.min(es, Math.min(c.conn.length, 64) - 1));
    }

    /** The player's eyes are now in section s of column (x, z). */
    void moveTo(int x, int s, int z) {
        if (hasEye && x == ex && s == es && z == ez) return;
        hasEye = true;
        ex = x;
        es = s;
        ez = z;
        burst = 0;
        Col c = cols.get(key(x, z));
        if (c != null) {
            enter(c, eyeSection(c), START);
            drain();
        }
        for (int dx = -1; dx <= 1; dx++) // the box around the eyes moved
            for (int dz = -1; dz <= 1; dz++) {
                Col n = cols.get(key(x + dx, z + dz));
                if (n != null) touched.add(n);
            }
        reveal(null);
    }

    void forget(long key) {
        cols.remove(key);
    }

    /** The client dropped all chunks (a new world). The eye stays: it is the player's position, not chunk state. */
    void clear() {
        cols.clear();
        epoch++;
    }

    /** Changes on every {@link #clear()}: an {@link #exact} answer requested under another epoch is about other chunks. */
    int epoch() {
        return epoch;
    }

    /** Whether the client holds block (x, y, z) as replacement. */
    boolean replaced(int x, int y, int z) {
        Col c = cols.get(key(x >> 4, z >> 4));
        int s = (y - minY) >> 4;
        return c != null && s >= 0 && s < 64 && (c.sentHidden >> s & 1) != 0;
    }

    /** The sections of column (x, z) the client holds as replacement. */
    long replacedMask(int x, int z) {
        Col c = cols.get(key(x, z));
        return c == null ? 0 : c.sentHidden;
    }

    private void rebuild() {
        for (Col c : cols.values()) {
            Arrays.fill(c.entered, (byte) 0);
            touched.add(c);
        }
        if (hasEye) {
            Col c = cols.get(key(ex, ez));
            if (c != null) enter(c, eyeSection(c), START);
        }
        drain();
    }

    private void enter(Col c, int s, int bits) {
        // shortcut: one long per column mask, so sections 64+ are never occluded; use long[] for worlds over 1024 high
        if (s < heightHidden || s >= Math.min(c.conn.length, 64)) return;
        int fresh = bits & ~c.entered[s];
        if (fresh == 0) return;
        if (c.entered[s] == 0 && active && (c.dirty >> s & 1) != 0) request(c, s); // changed while out of reach
        c.entered[s] |= (byte) fresh;
        stack.push(new Entry(c, s));
        touched.add(c);
    }

    private static int exits(Col c, int s) {
        int entered = c.entered[s];
        if ((entered & START) != 0) return 63;
        int out = 0;
        for (int f = 0; f < 6; f++) if ((entered >> f & 1) != 0) out |= (int) (c.conn[s] >>> (6 * f)) & 63;
        return out;
    }

    private void drain() {
        while (!stack.isEmpty()) {
            Entry e = stack.pop();
            Col c = e.col();
            int s = e.s(), out = exits(c, s);
            if ((out & 1) != 0) enter(c, s - 1, 1 << 1);
            if ((out & 2) != 0) enter(c, s + 1, 1);
            for (int g = 2; g < 6; g++) {
                if ((out >> g & 1) == 0) continue;
                Col n = cols.get(key(c.x + DX[g], c.z + DZ[g]));
                if (n != null) enter(n, s, 1 << (g ^ 1));
            }
        }
    }

    private long visible(Col c) {
        long visible = 0;
        int limit = Math.min(c.entered.length, 64);
        for (int s = 0; s < limit; s++) if (c.entered[s] != 0) visible |= 1L << s;
        if (hasEye && Math.abs(c.x - ex) <= 1 && Math.abs(c.z - ez) <= 1) { // the box around the eyes is always real
            int eye = eyeSection(c);
            for (int s = eye - 1; s <= eye + 1; s++) if (s >= 0 && s < limit) visible |= 1L << s;
        }
        return visible;
    }

    private long hidden(Col c) {
        if (!active) return 0;
        int limit = Math.min(c.conn.length, 64);
        long eligible = (limit == 64 ? -1L : (1L << limit) - 1) & (heightHidden >= 64 ? 0 : -1L << heightHidden);
        return ~visible(c) & eligible;
    }

    /** Re-sends touched columns (other than {@code except}) that now show a section the client holds as replacement. */
    private void reveal(Col except) {
        for (Col c : touched) {
            if (c == except || !active || c.resendQueued || (visible(c) & c.sentHidden) == 0) continue;
            c.resendQueued = true;
            server.resend(c.x, c.z, burst++);
        }
        touched.clear();
    }
}
