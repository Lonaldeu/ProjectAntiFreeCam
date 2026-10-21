package me.lonaldeu.antifreecam;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Edits the serialized chunk sections carried by a chunk packet. Each section is a short block count, a paletted
 * container of 4096 block states (y, z, x order) and a paletted container of 64 biomes.
 */
final class ChunkSections {

    private static final int BLOCKS = 4096, BIOMES = 64;
    private static final int MAX_INDIRECT_BLOCK_BITS = 8, MAX_INDIRECT_BIOME_BITS = 3;

    // Before 1.21.5 each container's long array was prefixed with its length. Both layouts are accepted; the one that
    // worked last is tried first, so a server pays for the wrong guess only once.
    private static volatile boolean prefixedFirst;

    private ChunkSections() {
    }

    private static boolean[] layouts() {
        return prefixedFirst ? new boolean[]{true, false} : new boolean[]{false, true};
    }

    /**
     * Replaces every block at or below {@code maxY} with {@code replacement}, and every section whose bit is set in
     * {@code occludedMask} (cave occlusion) with {@code occluded} above that. Fully replaced sections also get the
     * biomes of the first real section above the cut, so biomes like the deep dark don't give away what's there.
     * Returns {@code in} itself when nothing is replaced, or null when the data can't be parsed.
     */
    static byte[] hideBelow(byte[] in, int minY, int maxY, long occludedMask, int replacement, int occluded,
                            IntPredicate isAir, int globalBits) {
        if (minY > maxY && occludedMask == 0) return in;
        for (boolean prefixed : layouts()) {
            try {
                byte[] out = hideBelow(in, minY, maxY, occludedMask, replacement, occluded, isAir, globalBits, prefixed);
                prefixedFirst = prefixed;
                return out;
            } catch (RuntimeException wrongLayout) {
                // try the other layout
            }
        }
        return null;
    }

    private static byte[] hideBelow(byte[] in, int minY, int maxY, long occludedMask, int replacement, int occluded,
                                    IntPredicate isAir, int globalBits, boolean prefixed) {
        List<int[]> sections = sections(in, prefixed);
        // Biomes for replaced sections: those of the first real section above the cut, else the top one.
        int[] donor = sections.isEmpty() ? null : sections.get(sections.size() - 1);
        for (int i = 0; i < sections.size(); i++)
            if (minY + i * 16 > maxY && !masked(occludedMask, i)) {
                donor = sections.get(i);
                break;
            }

        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < sections.size(); i++) {
            int[] s = sections.get(i);
            int baseY = minY + i * 16;
            boolean masked = masked(occludedMask, i);
            if (baseY > maxY && !masked) {
                out.write(in, s[0], s[2] - s[0]);
                continue;
            }
            if (baseY + 15 <= maxY || baseY > maxY) { // fully under the cut, or occluded above it: one block throughout
                int block = baseY > maxY ? occluded : replacement;
                int count = isAir.test(block) ? 0 : BLOCKS;
                out.write(count >> 8);
                out.write(count);
                writeBlocks(out, new int[]{block}, globalBits, prefixed);
                out.write(in, donor[1], donor[2] - donor[1]);
                continue;
            }
            // The one section that straddles the cut: its lower rows become the replacement.
            int[] blocks = new int[BLOCKS];
            if (masked) Arrays.fill(blocks, occluded);
            else blocks = readContainer(ByteBuffer.wrap(in, s[0] + 2, s[1] - s[0] - 2), BLOCKS, MAX_INDIRECT_BLOCK_BITS, prefixed);
            Arrays.fill(blocks, 0, (maxY - baseY + 1) * 256, replacement);
            int nonAir = 0;
            for (int id : blocks) if (!isAir.test(id)) nonAir++;
            out.write(nonAir >> 8);
            out.write(nonAir);
            writeBlocks(out, blocks, globalBits, prefixed);
            int[] biomes = masked ? donor : s;
            out.write(in, biomes[1], biomes[2] - biomes[1]);
        }
        return out.toByteArray();
    }

    private static boolean masked(long mask, int section) {
        return section < 64 && (mask >> section & 1) != 0;
    }

    /** Where each section's block and biome containers start ({start, biomesStart, end}); also validates the layout. */
    private static List<int[]> sections(byte[] in, boolean prefixed) {
        ByteBuffer r = ByteBuffer.wrap(in);
        List<int[]> sections = new ArrayList<>();
        while (r.hasRemaining()) {
            int start = r.position();
            r.getShort();
            skipContainer(r, BLOCKS, MAX_INDIRECT_BLOCK_BITS, prefixed);
            int biomesStart = r.position();
            skipContainer(r, BIOMES, MAX_INDIRECT_BIOME_BITS, prefixed);
            sections.add(new int[]{start, biomesStart, r.position()});
        }
        return sections;
    }

    private static final ThreadLocal<boolean[]> OPEN = ThreadLocal.withInitial(() -> new boolean[BLOCKS]);
    private static final ThreadLocal<boolean[]> SEEN = ThreadLocal.withInitial(() -> new boolean[BLOCKS]);
    private static final ThreadLocal<int[]> STACK = ThreadLocal.withInitial(() -> new int[BLOCKS]);

    /**
     * Each section's face connectivity (see {@link Occlusion}) from the chunk packet's section data, or null when it
     * can't be parsed. Cheap for uniform sections and for palettes that are all see-through or all opaque.
     */
    static long[] connectivity(byte[] in, IntPredicate opaque) {
        for (boolean prefixed : layouts()) {
            try {
                List<int[]> sections = sections(in, prefixed);
                long[] conn = new long[sections.size()];
                for (int i = 0; i < conn.length; i++) conn[i] = connectivity(in, sections.get(i), opaque, prefixed);
                prefixedFirst = prefixed;
                return conn;
            } catch (RuntimeException wrongLayout) {
                // try the other layout
            }
        }
        return null;
    }

    private static long connectivity(byte[] in, int[] section, IntPredicate opaque, boolean prefixed) {
        ByteBuffer r = ByteBuffer.wrap(in, section[0], section[1] - section[0]);
        if (r.getShort() == 0) return Occlusion.ALL; // nothing but air
        return blockConnectivity(r, opaque, prefixed);
    }

    /**
     * Connectivity of one serialized block container (as the server writes a single section's blocks), or -1 when
     * it can't be parsed.
     */
    static long containerConnectivity(byte[] container, IntPredicate opaque) {
        for (boolean prefixed : layouts()) {
            try {
                ByteBuffer r = ByteBuffer.wrap(container);
                long conn = blockConnectivity(r, opaque, prefixed);
                if (!r.hasRemaining()) {
                    prefixedFirst = prefixed;
                    return conn;
                }
            } catch (RuntimeException wrongLayout) {
                // try the other layout
            }
        }
        return -1;
    }

    /** Reads one block container from {@code r} (all of it) and returns its connectivity. */
    private static long blockConnectivity(ByteBuffer r, IntPredicate opaque, boolean prefixed) {
        r.mark();
        int bits = r.get() & 0xFF;
        if (bits == 0) {
            boolean open = !opaque.test(readVarInt(r));
            if (prefixed) expect(readVarInt(r), 0);
            return open ? Occlusion.ALL : 0;
        }
        if (bits <= MAX_INDIRECT_BLOCK_BITS) {
            boolean anyOpaque = false, anyOpen = false;
            for (int n = readVarInt(r); n > 0; n--) {
                if (opaque.test(readVarInt(r))) anyOpaque = true;
                else anyOpen = true;
            }
            if (!anyOpaque || !anyOpen) {
                int perLong = 64 / bits, longs = (BLOCKS + perLong - 1) / perLong;
                if (prefixed) expect(readVarInt(r), longs);
                r.position(r.position() + longs * 8);
                return anyOpaque ? 0 : Occlusion.ALL;
            }
        }
        r.reset();
        int[] ids = readContainer(r, BLOCKS, MAX_INDIRECT_BLOCK_BITS, prefixed);
        boolean[] open = OPEN.get();
        int opaqueCells = 0;
        for (int i = 0; i < BLOCKS; i++) {
            open[i] = !opaque.test(ids[i]);
            if (!open[i]) opaqueCells++;
        }
        return opaqueCells < 256 ? Occlusion.ALL : faceMask(open); // the client's own shortcut for mostly open sections
    }

    /** Which faces each group of connected see-through cells touches (cells indexed y << 8 | z << 4 | x). */
    static long faceMask(boolean[] open) {
        boolean[] seen = SEEN.get();
        int[] stack = STACK.get();
        Arrays.fill(seen, false);
        long conn = 0;
        for (int start = 0; start < BLOCKS; start++) {
            if (!open[start] || seen[start]) continue;
            int faces = 0, top = 0;
            stack[top++] = start;
            seen[start] = true;
            while (top > 0) {
                int c = stack[--top], x = c & 15, z = c >> 4 & 15, y = c >> 8;
                if (y == 0) faces |= 1;
                if (y == 15) faces |= 2;
                if (z == 0) faces |= 4;
                if (z == 15) faces |= 8;
                if (x == 0) faces |= 16;
                if (x == 15) faces |= 32;
                if (x > 0) top = push(c - 1, open, seen, stack, top);
                if (x < 15) top = push(c + 1, open, seen, stack, top);
                if (z > 0) top = push(c - 16, open, seen, stack, top);
                if (z < 15) top = push(c + 16, open, seen, stack, top);
                if (y > 0) top = push(c - 256, open, seen, stack, top);
                if (y < 15) top = push(c + 256, open, seen, stack, top);
            }
            conn |= Occlusion.pairs(faces);
        }
        return conn;
    }

    private static int push(int cell, boolean[] open, boolean[] seen, int[] stack, int top) {
        if (open[cell] && !seen[cell]) {
            seen[cell] = true;
            stack[top++] = cell;
        }
        return top;
    }

    /** One light layer (sky or block) of a light packet: which sections have arrays, which are all-dark, and the arrays. */
    record LightLayer(BitSet mask, BitSet empty, List<byte[]> arrays) {
    }

    /**
     * Darkens light at or below {@code maxY} and in occluded sections, so torches, lava and cave openings there can't
     * be located. Light sections start one section below the world. Sections entirely hidden become "all dark"; the
     * one that straddles the cut gets its lower rows zeroed. The input is left untouched.
     */
    static LightLayer hideLight(LightLayer in, int minY, int maxY, long occludedMask) {
        BitSet mask = (BitSet) in.mask().clone(), empty = (BitSet) in.empty().clone();
        List<byte[]> arrays = new ArrayList<>(in.arrays().size());
        int next = 0;
        for (int i = in.mask().nextSetBit(0); i >= 0; i = in.mask().nextSetBit(i + 1)) {
            byte[] light = in.arrays().get(next++);
            int baseY = minY - 16 + i * 16;
            if (baseY + 15 <= maxY || i >= 1 && masked(occludedMask, i - 1)) {
                mask.clear(i);
            } else if (baseY > maxY) {
                arrays.add(light);
            } else {
                byte[] copy = light.clone();
                Arrays.fill(copy, 0, (maxY - baseY + 1) * 128, (byte) 0); // two 4-bit values per byte, 256 per row
                arrays.add(copy);
            }
        }
        for (int i = 0; minY - 16 + i * 16 + 15 <= maxY; i++) empty.set(i);
        for (long m = occludedMask; m != 0; m &= m - 1) empty.set(Long.numberOfTrailingZeros(m) + 1);
        return new LightLayer(mask, empty, arrays);
    }

    static int[] readContainer(ByteBuffer r, int entries, int maxIndirectBits, boolean prefixed) {
        int bits = r.get() & 0xFF;
        int[] values = new int[entries];
        if (bits == 0) {
            Arrays.fill(values, readVarInt(r));
            if (prefixed) expect(readVarInt(r), 0);
            return values;
        }
        int[] palette = null;
        if (bits <= maxIndirectBits) {
            palette = new int[readVarInt(r)];
            for (int i = 0; i < palette.length; i++) palette[i] = readVarInt(r);
        }
        int perLong = 64 / bits;
        int longs = (entries + perLong - 1) / perLong;
        if (prefixed) expect(readVarInt(r), longs);
        long mask = (1L << bits) - 1;
        for (int i = 0, k = 0; i < longs; i++) {
            long word = r.getLong();
            for (int j = 0; j < perLong && k < entries; j++, k++) {
                int v = (int) (word >>> (j * bits) & mask);
                values[k] = palette == null ? v : palette[v];
            }
        }
        return values;
    }

    private static void skipContainer(ByteBuffer r, int entries, int maxIndirectBits, boolean prefixed) {
        int bits = r.get() & 0xFF;
        if (bits == 0) {
            readVarInt(r);
        } else if (bits <= maxIndirectBits) {
            for (int n = readVarInt(r); n > 0; n--) readVarInt(r);
        }
        int longs = 0;
        if (bits > 0) {
            int perLong = 64 / bits;
            longs = (entries + perLong - 1) / perLong;
        }
        if (prefixed) expect(readVarInt(r), longs);
        r.position(r.position() + longs * 8);
    }

    /** Writes a block container. A single value stands for a section filled with that block. */
    static void writeBlocks(ByteArrayOutputStream out, int[] values, int globalBits, boolean prefixed) {
        Map<Integer, Integer> palette = new LinkedHashMap<>();
        for (int v : values) palette.putIfAbsent(v, palette.size());
        if (palette.size() == 1) {
            out.write(0);
            writeVarInt(out, values[0]);
            if (prefixed) writeVarInt(out, 0);
            return;
        }
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        boolean direct = bits > MAX_INDIRECT_BLOCK_BITS;
        if (direct) bits = globalBits;
        out.write(bits);
        if (!direct) {
            writeVarInt(out, palette.size());
            for (int v : palette.keySet()) writeVarInt(out, v);
        }
        int perLong = 64 / bits;
        int longs = (values.length + perLong - 1) / perLong;
        if (prefixed) writeVarInt(out, longs);
        for (int i = 0, k = 0; i < longs; i++) {
            long word = 0;
            for (int j = 0; j < perLong && k < values.length; j++, k++) {
                long v = direct ? values[k] : palette.get(values[k]);
                word |= v << (j * bits);
            }
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (word >>> shift));
        }
    }

    private static void expect(int actual, int expected) {
        if (actual != expected) throw new IllegalStateException("expected " + expected + " but read " + actual);
    }

    static int readVarInt(ByteBuffer r) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte b = r.get();
            value |= (b & 0x7F) << shift;
            if (b >= 0) return value;
        }
        throw new IllegalStateException("VarInt too long");
    }

    static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
