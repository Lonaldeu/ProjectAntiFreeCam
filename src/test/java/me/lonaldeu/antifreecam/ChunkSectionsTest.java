package me.lonaldeu.antifreecam;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkSectionsTest {

    private static final int AIR = 0, GLOBAL_BITS = 15;

    /** Three sections at Y -16, 0 and 16; everything at or below Y 4 must become air. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hidesBlocksAtOrBelowLimit(boolean prefixed) {
        int[] blocks = new int[4096];
        for (int i = 0; i < blocks.length; i++) blocks[i] = 1 + i % 20;
        byte[] section = section(blocks, prefixed);
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        for (int i = 0; i < 3; i++) chunk.writeBytes(section);

        byte[] out = ChunkSections.hideBelow(chunk.toByteArray(), -16, 4, AIR, id -> id == AIR, GLOBAL_BITS);
        ByteBuffer r = ByteBuffer.wrap(out);

        assertEquals(0, r.getShort());
        int[] all = new int[4096];
        assertArrayEquals(all, read(r, prefixed));

        int[] partial = blocks.clone();
        Arrays.fill(partial, 0, 5 * 256, AIR);
        assertEquals(4096 - 5 * 256, r.getShort());
        assertArrayEquals(partial, read(r, prefixed));

        byte[] rest = new byte[r.remaining()];
        r.get(rest);
        assertArrayEquals(section, rest);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void leavesHighChunksAlone(boolean prefixed) {
        byte[] data = section(new int[4096], prefixed);
        assertEquals(data, ChunkSections.hideBelow(data, 32, 16, AIR, id -> id == AIR, GLOBAL_BITS));
    }

    /** Fully hidden sections take the biome of the first section above the limit; the straddling one keeps its own. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void masksBiomesOfHiddenSections(boolean prefixed) {
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        for (int biome = 1; biome <= 3; biome++) chunk.writeBytes(section(new int[4096], prefixed, biome));

        ByteBuffer r = ByteBuffer.wrap(ChunkSections.hideBelow(chunk.toByteArray(), -16, 4, AIR, id -> id == AIR, GLOBAL_BITS));
        int[] biomes = new int[3];
        for (int i = 0; i < 3; i++) {
            r.getShort();
            ChunkSections.readContainer(r, 4096, 8, prefixed);
            biomes[i] = ChunkSections.readContainer(r, 64, 3, prefixed)[0];
        }
        assertArrayEquals(new int[]{3, 2, 3}, biomes);
    }

    /** Light sections start one below the world: with minY 0 they cover -16, 0, 16, 32. Hide Y <= 20. */
    @org.junit.jupiter.api.Test
    void darkensLightAtOrBelowLimit() {
        java.util.BitSet mask = new java.util.BitSet();
        mask.set(0, 4);
        java.util.List<byte[]> arrays = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            byte[] light = new byte[2048];
            Arrays.fill(light, (byte) 0xFF);
            arrays.add(light);
        }
        var out = ChunkSections.hideLight(new ChunkSections.LightLayer(mask, new java.util.BitSet(), arrays), 0, 20);

        assertEquals(java.util.BitSet.valueOf(new long[]{0b1100}), out.mask());
        assertEquals(java.util.BitSet.valueOf(new long[]{0b0011}), out.empty());
        assertEquals(2, out.arrays().size());
        byte[] straddling = out.arrays().get(0);
        assertEquals(0, straddling[5 * 128 - 1]); // row 20 dark
        assertEquals((byte) 0xFF, straddling[5 * 128]); // row 21 lit
        assertEquals((byte) 0xFF, arrays.get(2)[0]); // input untouched
    }

    private static byte[] section(int[] blocks, boolean prefixed) {
        return section(blocks, prefixed, 7);
    }

    private static byte[] section(int[] blocks, boolean prefixed, int biome) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x10);
        out.write(0x00);
        ChunkSections.writeBlocks(out, blocks, GLOBAL_BITS, prefixed);
        out.write(0); // single-valued biome container
        ChunkSections.writeVarInt(out, biome);
        if (prefixed) ChunkSections.writeVarInt(out, 0);
        return out.toByteArray();
    }

    private static int[] read(ByteBuffer r, boolean prefixed) {
        int[] blocks = ChunkSections.readContainer(r, 4096, 8, prefixed);
        ChunkSections.readContainer(r, 64, 3, prefixed);
        return blocks;
    }
}
