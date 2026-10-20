package me.lonaldeu.antifreecam;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
     * Replaces every block at or below {@code maxY} with {@code replacement}.
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
        ByteBuffer r = ByteBuffer.wrap(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int baseY = minY; r.hasRemaining(); baseY += 16) {
            int start = r.position();
            r.getShort();
            if (baseY > maxY) {
                skipContainer(r, BLOCKS, MAX_INDIRECT_BLOCK_BITS, prefixed);
                skipContainer(r, BIOMES, MAX_INDIRECT_BIOME_BITS, prefixed);
                out.write(in, start, r.position() - start);
                continue;
            }
            int[] blocks = readContainer(r, BLOCKS, MAX_INDIRECT_BLOCK_BITS, prefixed);
            int biomesStart = r.position();
            skipContainer(r, BIOMES, MAX_INDIRECT_BIOME_BITS, prefixed);

            int rows = Math.min(16, maxY - baseY + 1);
            Arrays.fill(blocks, 0, rows * 256, replacement);
            int nonAir = 0;
            for (int id : blocks) if (!isAir.test(id)) nonAir++;
            out.write(nonAir >> 8);
            out.write(nonAir);
            writeBlocks(out, blocks, globalBits, prefixed);
            out.write(in, biomesStart, r.position() - biomesStart);
        }
        return out.toByteArray();
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
