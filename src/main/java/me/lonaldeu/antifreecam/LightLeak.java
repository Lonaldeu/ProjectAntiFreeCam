package me.lonaldeu.antifreecam;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Removes block light that reaches sent-real cells from hidden ones (below the cut, or in occluded sections) within
 * one chunk column: a lava lake just under the cut would otherwise light up the cave above it. Works like the game's
 * own light engine when a light source is removed: light that can have come from the hidden cells is taken away, and
 * what the remaining sources light is spread again. Light that entered from a neighbouring column is only known
 * through the {@link Side} cleaning left there, when that column was cleaned first.
 */
final class LightLeak {

    /** Block state ids of the column's sections, indexed like block light (y << 8 | z << 4 | x). */
    interface Blocks {
        int state(int section, int index);
    }

    /**
     * One side of a column: its light before and after cleaning, a byte per cell for rows {@code first} on (light rows,
     * the first one below the world; only those with light), at (g - first) * 16 + a for position a along the side (x
     * on the north and south sides, z on the west and east ones). Sides: 0 north (z 0), 1 south (z 15), 2 west (x 0),
     * 3 east (x 15); the side facing side s is s ^ 1.
     */
    record Side(int first, byte[] before, byte[] after) {
        static boolean same(Side a, Side b) {
            return a == b || a != null && b != null && a.first == b.first && Arrays.equals(a.before, b.before)
                    && Arrays.equals(a.after, b.after);
        }

        int rows() {
            return before.length / 16;
        }
    }

    // Kinds of re-lighting work: a cell's own light (set it if brighter), or light a cell already holds (spread it, if it
    // still does: a later removal may have taken it away).
    private static final long EMIT = 0, HAVE = 1;

    private LightLeak() {
    }

    /**
     * Cleans one column's block light. {@code light[i]} is light section i (0 is the one below the world), as nibble
     * arrays, null for all dark; arrays that change are replaced by copies, so the inputs can be shared. Cells at or
     * below {@code maxY}, or in a section whose bit is set in {@code occludedMask} (world sections), are hidden.
     * Returns whether anything changed.
     */
    static boolean clean(byte[][] light, int minY, int maxY, long occludedMask, Blocks blocks, byte[] emission, byte[] lightBlock) {
        return clean(light, minY, maxY, occludedMask, blocks, emission, lightBlock, null, null, null);
    }

    /**
     * The same, also taking away light the neighbouring columns' cleaning took from their sides facing this one
     * ({@code neighbours[s]} faces side s, null where unknown or unchanged), and spreading in what they kept, where
     * {@code relight[s]} (null: everywhere): a neighbour cleaned before this column was known may still hold light that
     * came from here. Fills {@code sides} (when given) with this column's sides that cleaning changed, null for others.
     */
    static boolean clean(byte[][] light, int minY, int maxY, long occludedMask, Blocks blocks, byte[] emission, byte[] lightBlock,
                         Side[] neighbours, boolean[] relight, Side[] sides) {
        if (sides != null) Arrays.fill(sides, null);
        Column c = new Column(light, minY, maxY, occludedMask, blocks, emission, lightBlock);
        ArrayDeque<long[]> remove = new ArrayDeque<>(), spread = new ArrayDeque<>();
        Set<Long> zeroed = new HashSet<>();
        // Seeds: visible cells right above or below lit hidden ones. Hiding goes by whole rows, so only the rows on a
        // hidden/visible border are looked at.
        int rows = light.length * 16;
        for (int g = 0; g < rows; g++) {
            int i = g >> 4, y = g & 15;
            if (light[i] == null || !c.hiddenRow(g)) continue;
            boolean up = g + 1 < rows && !c.hiddenRow(g + 1), down = g > 0 && !c.hiddenRow(g - 1);
            for (int index = y << 8; (up || down) && index < (y + 1) << 8; index++) {
                int level = c.get(i, index);
                if (level == 0) continue;
                if (up) touch(c, c.neighbour(i, index, 1), level, remove, spread, zeroed);
                if (down) touch(c, c.neighbour(i, index, 0), level, remove, spread, zeroed);
            }
        }
        // Cells along a side next to light the neighbour's cleaning took away: lit through it, or not.
        if (neighbours != null) for (int s = 0; s < 4; s++) {
            Side n = neighbours[s];
            for (int r = 0; n != null && r < n.rows() && n.first() + r < rows; r++) {
                int g = n.first() + r;
                if (c.hiddenRow(g)) continue;
                for (int a = 0; a < 16; a++)
                    if (n.after()[r * 16 + a] < n.before()[r * 16 + a]) touch(c, sideCell(s, a, g), n.before()[r * 16 + a], remove, spread, zeroed);
            }
        }
        if (remove.isEmpty()) return false;
        while (!remove.isEmpty()) { // take away everything the removed light can have lit
            long[] e = remove.poll();
            for (int d = 0; d < 6; d++) touch(c, c.neighbour((int) (e[0] >> 12), (int) (e[0] & 4095), d), (int) e[1], remove, spread, zeroed);
        }
        // What the neighbours kept along their sides lights cleared cells here again.
        if (neighbours != null) for (int s = 0; s < 4; s++) {
            Side n = relight == null || relight[s] ? neighbours[s] : null;
            for (int r = 0; n != null && r < n.rows() && n.first() + r < rows; r++)
                for (int a = 0; a < 16; a++) {
                    long cell = sideCell(s, a, n.first() + r);
                    if (n.after()[r * 16 + a] <= 1 || !zeroed.contains(cell)) continue;
                    int i = (int) (cell >> 12), index = (int) (cell & 4095);
                    int level = Math.min(c.original(i, index), n.after()[r * 16 + a] - Math.max(1, c.lightBlock(i, index)));
                    if (level > 0) spread.add(new long[]{cell, level, EMIT});
                }
        }
        while (!spread.isEmpty()) { // and light it again from what is left: sources there, and brighter cells around it
            long[] e = spread.poll();
            int i = (int) (e[0] >> 12), index = (int) (e[0] & 4095), level = (int) e[1];
            if (e[2] == EMIT) {
                if (c.get(i, index) >= level) continue;
                c.set(i, index, level);
            } else if (c.get(i, index) != level) {
                continue;
            }
            for (int d = 0; d < 6; d++) {
                long n = c.neighbour(i, index, d);
                if (n < 0 || !zeroed.contains(n)) continue; // the rest still has the server's own light
                int ni = (int) (n >> 12), nIndex = (int) (n & 4095);
                // Never brighter than the server had it: taking sources away only darkens, and this way the shapes of
                // slabs and stairs, which aren't known here, can't let more light through than they really do.
                int next = Math.min(c.original(ni, nIndex), level - Math.max(1, c.lightBlock(ni, nIndex)));
                if (next <= c.get(ni, nIndex)) continue;
                c.set(ni, nIndex, next);
                spread.add(new long[]{n, next, HAVE});
            }
        }
        if (sides != null && c.changed) for (int s = 0; s < 4; s++) sides[s] = side(c, s, rows);
        return c.changed;
    }

    /** Cell a of side s at row g, as i << 12 | index. */
    private static long sideCell(int s, int a, int g) {
        int x = s == 2 ? 0 : s == 3 ? 15 : a, z = s == 0 ? 0 : s == 1 ? 15 : a;
        return (long) (g >> 4) << 12 | (g & 15) << 8 | z << 4 | x;
    }

    /** Side s of a cleaned column, or null when cleaning took nothing from it. */
    private static Side side(Column c, int s, int rows) {
        boolean reduced = false;
        for (int g = 0; g < rows && !reduced; g++)
            for (int a = 0; a < 16 && !reduced; a++) {
                long cell = sideCell(s, a, g);
                reduced = c.get((int) (cell >> 12), (int) (cell & 4095)) < c.original((int) (cell >> 12), (int) (cell & 4095));
            }
        if (!reduced) return null;
        int first = rows, last = -1; // only the rows with light: the rest is dark before and after
        for (int g = 0; g < rows; g++)
            for (int a = 0; a < 16; a++) {
                long cell = sideCell(s, a, g);
                if (c.original((int) (cell >> 12), (int) (cell & 4095)) == 0) continue;
                first = Math.min(first, g);
                last = g;
            }
        byte[] before = new byte[(last - first + 1) * 16], after = new byte[before.length];
        for (int g = first; g <= last; g++)
            for (int a = 0; a < 16; a++) {
                long cell = sideCell(s, a, g);
                before[(g - first) * 16 + a] = (byte) c.original((int) (cell >> 12), (int) (cell & 4095));
                after[(g - first) * 16 + a] = (byte) c.get((int) (cell >> 12), (int) (cell & 4095));
            }
        return new Side(first, before, after);
    }

    /**
     * Visible cell n next to one whose light {@code from} is taken away: lit through it, so its light goes too (and
     * its own emission comes back later), or lit by something at least as bright, which then lights the cleared cells
     * again.
     */
    private static void touch(Column c, long n, int from, ArrayDeque<long[]> remove, ArrayDeque<long[]> spread, Set<Long> zeroed) {
        if (n < 0) return;
        int i = (int) (n >> 12), index = (int) (n & 4095);
        if (c.hiddenRow(i * 16 + (index >> 8))) return;
        int level = c.get(i, index);
        if (level != 0 && level < from) {
            c.set(i, index, 0);
            zeroed.add(n);
            remove.add(new long[]{n, level});
            int own = c.emission(i, index);
            if (own > 0) spread.add(new long[]{n, own, EMIT});
        } else if (level >= from && level > 0) {
            spread.add(new long[]{n, level, HAVE});
        }
    }

    /** Whether row g of a column (light section g / 16, row g % 16) is hidden. */
    static boolean hiddenRow(int g, int minY, int maxY, long occludedMask) {
        int section = (g >> 4) - 1; // light sections start one below the world
        return minY - 16 + g <= maxY || section >= 0 && section < 64 && (occludedMask >> section & 1) != 0;
    }

    /** Whether light section i has visible cells within 15 blocks of a hidden one, which can light them. */
    static boolean nearHidden(int i, int minY, int maxY, long occludedMask) {
        boolean visible = false;
        for (int g = i * 16; g < i * 16 + 16 && !visible; g++) visible = !hiddenRow(g, minY, maxY, occludedMask);
        if (!visible) return false; // all hidden: dropped anyway
        for (int g = Math.max(0, i * 16 - 15); g < i * 16 + 31; g++) if (hiddenRow(g, minY, maxY, occludedMask)) return true;
        return false;
    }

    /** The column's light, copied on first write, and what is hidden. */
    private static final class Column {
        final byte[][] light, original;
        final boolean[] copied;
        final int minY, maxY;
        final long occludedMask;
        final Blocks blocks;
        final byte[] emission, lightBlock;
        boolean changed;

        Column(byte[][] light, int minY, int maxY, long occludedMask, Blocks blocks, byte[] emission, byte[] lightBlock) {
            this.light = light;
            this.original = light.clone();
            this.copied = new boolean[light.length];
            this.minY = minY;
            this.maxY = maxY;
            this.occludedMask = occludedMask;
            this.blocks = blocks;
            this.emission = emission;
            this.lightBlock = lightBlock;
        }

        boolean hiddenRow(int g) {
            return LightLeak.hiddenRow(g, minY, maxY, occludedMask);
        }

        int get(int i, int index) {
            return nibble(light[i], index);
        }

        int original(int i, int index) {
            return nibble(original[i], index);
        }

        private static int nibble(byte[] a, int index) {
            return a == null ? 0 : a[index >> 1] >> ((index & 1) << 2) & 15;
        }

        void set(int i, int index, int level) {
            if (get(i, index) == level) return;
            if (!copied[i]) {
                light[i] = light[i] == null ? new byte[2048] : light[i].clone();
                copied[i] = true;
            }
            int shift = (index & 1) << 2;
            light[i][index >> 1] = (byte) (light[i][index >> 1] & ~(15 << shift) | level << shift);
            changed = true;
        }

        private int state(int i, int index) {
            int section = i - 1; // light sections start one below the world
            return section < 0 || section >= light.length - 2 ? -1 : blocks.state(section, index);
        }

        int emission(int i, int index) {
            int s = state(i, index);
            return s < 0 || s >= emission.length ? 0 : emission[s];
        }

        int lightBlock(int i, int index) {
            int s = state(i, index);
            return s < 0 || s >= lightBlock.length ? 0 : lightBlock[s];
        }

        /** Neighbour d of a cell (0 down, 1 up, 2 north, 3 south, 4 west, 5 east) as i << 12 | index, or -1 outside the column. */
        long neighbour(int i, int index, int d) {
            int x = index & 15, z = index >> 4 & 15, y = index >> 8;
            switch (d) {
                case 0 -> {
                    if (y > 0) return (long) i << 12 | index - 256;
                    return i > 0 ? (long) (i - 1) << 12 | index + 15 * 256 : -1;
                }
                case 1 -> {
                    if (y < 15) return (long) i << 12 | index + 256;
                    return i < light.length - 1 ? (long) (i + 1) << 12 | index - 15 * 256 : -1;
                }
                case 2 -> {
                    return z > 0 ? (long) i << 12 | index - 16 : -1;
                }
                case 3 -> {
                    return z < 15 ? (long) i << 12 | index + 16 : -1;
                }
                case 4 -> {
                    return x > 0 ? (long) i << 12 | index - 1 : -1;
                }
                default -> {
                    return x < 15 ? (long) i << 12 | index + 1 : -1;
                }
            }
        }
    }
}
