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

    private ChunkSections() {
    }

    /**
     * Replaces every block at or below {@code maxY} with {@code replacement}. Sections entirely in that range also get
     * the biomes of the first section above it, so biomes like the deep dark don't give away what's underneath.
     * Returns {@code in} itself when no section reaches that low, or null when the data can't be parsed.
     */
    static byte[] hideBelow(byte[] in, int minY, int maxY, int replacement, IntPredicate isAir, int globalBits) {
        if (minY > maxY) return in;
        // Before 1.21.5 each container's long array was prefixed with its length; accept both layouts.
        for (boolean prefixed : new boolean[]{false, true}) {
            try {
                return hideBelow(in, minY, maxY, replacement, isAir, globalBits, prefixed);
            } catch (RuntimeException wrongLayout) {
                // try the other layout
            }
        }
        return null;
    }

    private static byte[] hideBelow(byte[] in, int minY, int maxY, int replacement, IntPredicate isAir, int globalBits, boolean prefixed) {
        // First pass: where each section's block and biome containers start, which also validates the layout.
        ByteBuffer r = ByteBuffer.wrap(in);
        List<int[]> sections = new ArrayList<>(); // {start, biomesStart, end}
        while (r.hasRemaining()) {
            int start = r.position();
            r.getShort();
            skipContainer(r, BLOCKS, MAX_INDIRECT_BLOCK_BITS, prefixed);
            int biomesStart = r.position();
            skipContainer(r, BIOMES, MAX_INDIRECT_BIOME_BITS, prefixed);
            sections.add(new int[]{start, biomesStart, r.position()});
        }
        int[] donor = null; // biomes for fully hidden sections: those of the first section above the range
        for (int i = 0; i < sections.size() && donor == null; i++)
            if (minY + i * 16 > maxY) donor = sections.get(i);

        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < sections.size(); i++) {
            int[] s = sections.get(i);
            int baseY = minY + i * 16;
            if (baseY > maxY) {
                out.write(in, s[0], s[2] - s[0]);
                continue;
            }
            if (baseY + 15 <= maxY) {
                int count = isAir.test(replacement) ? 0 : BLOCKS;
                out.write(count >> 8);
                out.write(count);
                writeBlocks(out, new int[]{replacement}, globalBits, prefixed);
                int[] biomes = donor != null ? donor : s;
                out.write(in, biomes[1], biomes[2] - biomes[1]);
                continue;
            }
            // The one section that straddles the limit: rewrite only its lower rows.
            int[] blocks = readContainer(ByteBuffer.wrap(in, s[0] + 2, s[1] - s[0] - 2), BLOCKS, MAX_INDIRECT_BLOCK_BITS, prefixed);
            Arrays.fill(blocks, 0, (maxY - baseY + 1) * 256, replacement);
            int nonAir = 0;
            for (int id : blocks) if (!isAir.test(id)) nonAir++;
            out.write(nonAir >> 8);
            out.write(nonAir);
            writeBlocks(out, blocks, globalBits, prefixed);
            out.write(in, s[1], s[2] - s[1]);
        }
        return out.toByteArray();
    }

    /** One light layer (sky or block) of a light packet: which sections have arrays, which are all-dark, and the arrays. */
    record LightLayer(BitSet mask, BitSet empty, List<byte[]> arrays) {
    }

    /**
     * Darkens light at or below {@code maxY}, so torches, lava and cave openings down there can't be located.
     * Light sections start one section below the world. Sections entirely in range become "all dark"; the one that
     * straddles the limit gets its lower rows zeroed. The input is left untouched.
     */
    static LightLayer hideLight(LightLayer in, int minY, int maxY) {
        BitSet mask = (BitSet) in.mask().clone(), empty = (BitSet) in.empty().clone();
        List<byte[]> arrays = new ArrayList<>(in.arrays().size());
        int next = 0;
        for (int i = in.mask().nextSetBit(0); i >= 0; i = in.mask().nextSetBit(i + 1)) {
            byte[] light = in.arrays().get(next++);
            int baseY = minY - 16 + i * 16;
            if (baseY > maxY) {
                arrays.add(light);
            } else if (baseY + 15 > maxY) {
                byte[] copy = light.clone();
                Arrays.fill(copy, 0, (maxY - baseY + 1) * 128, (byte) 0); // two 4-bit values per byte, 256 per row
                arrays.add(copy);
            } else {
                mask.clear(i);
            }
        }
        for (int i = 0; minY - 16 + i * 16 + 15 <= maxY; i++) empty.set(i);
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
