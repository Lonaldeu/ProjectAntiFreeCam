package me.lonaldeu.antifreecam;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChunkSectionsTest {

    // Block ids: 0 air and 3 glass are see-through; 1 stone, 2 ore and 100+ block sight.
    private static final int AIR = 0, STONE = 1, ORE = 2, GLASS = 3, GLOBAL_BITS = 15;
    private static final IntPredicate IS_AIR = id -> id == AIR;
    private static final IntPredicate OPAQUE = id -> id == STONE || id == ORE || id >= 100;
    private static final int D = 1, U = 2, N = 4, S = 8, W = 16, E = 32; // face bits

    /** Three sections at Y -16, 0 and 16; everything at or below Y 4 must become air. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hidesBlocksAtOrBelowLimit(boolean prefixed) {
        int[] blocks = new int[4096];
        for (int i = 0; i < blocks.length; i++) blocks[i] = 1 + i % 20;
        byte[] section = section(blocks, prefixed);
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        for (int i = 0; i < 3; i++) chunk.writeBytes(section);

        byte[] out = ChunkSections.hideBelow(chunk.toByteArray(), -16, 4, 0, AIR, STONE, IS_AIR, GLOBAL_BITS);
        ByteBuffer r = ByteBuffer.wrap(out);

        assertEquals(0, r.getShort());
        assertArrayEquals(new int[4096], read(r, prefixed));

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
        assertEquals(data, ChunkSections.hideBelow(data, 32, 16, 0, AIR, STONE, IS_AIR, GLOBAL_BITS));
    }

    /** Fully hidden sections take the biome of the first section above the limit; the straddling one keeps its own. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void masksBiomesOfHiddenSections(boolean prefixed) {
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        for (int biome = 1; biome <= 3; biome++) chunk.writeBytes(section(new int[4096], prefixed, biome));

        ByteBuffer r = ByteBuffer.wrap(ChunkSections.hideBelow(chunk.toByteArray(), -16, 4, 0, AIR, STONE, IS_AIR, GLOBAL_BITS));
        assertArrayEquals(new int[]{3, 2, 3}, biomes(r, 3, prefixed));
    }

    /** An occluded section above the cut becomes solid stone with the biome of the first real section. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void occludedSectionBecomesStone(boolean prefixed) {
        int[] blocks = new int[4096];
        for (int i = 0; i < blocks.length; i++) blocks[i] = i % 2 == 0 ? AIR : ORE;
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        byte[][] sections = {section(blocks, prefixed, 1), section(blocks, prefixed, 2), section(blocks, prefixed, 3)};
        for (byte[] s : sections) chunk.writeBytes(s);

        byte[] out = ChunkSections.hideBelow(chunk.toByteArray(), -16, -17, 0b010, AIR, STONE, IS_AIR, GLOBAL_BITS);
        ByteBuffer r = ByteBuffer.wrap(out);
        byte[] first = new byte[sections[0].length];
        r.get(first);
        assertArrayEquals(sections[0], first);
        assertEquals(4096, r.getShort());
        int[] stone = new int[4096];
        Arrays.fill(stone, STONE);
        assertArrayEquals(stone, ChunkSections.readContainer(r, 4096, 8, prefixed));
        assertEquals(1, ChunkSections.readContainer(r, 64, 3, prefixed)[0]); // biome of the first real section
        byte[] last = new byte[r.remaining()];
        r.get(last);
        assertArrayEquals(sections[2], last);
    }

    /** An occluded section that straddles the cut: replacement under it, stone above. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void occludedStraddlingSection(boolean prefixed) {
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        for (int i = 0; i < 3; i++) chunk.writeBytes(section(new int[4096], prefixed));

        ByteBuffer r = ByteBuffer.wrap(ChunkSections.hideBelow(chunk.toByteArray(), -16, 4, 0b010, AIR, STONE, IS_AIR, GLOBAL_BITS));
        r.getShort();
        read(r, prefixed);
        assertEquals(4096 - 5 * 256, r.getShort());
        int[] expected = new int[4096];
        Arrays.fill(expected, 5 * 256, 4096, STONE);
        assertArrayEquals(expected, read(r, prefixed));
    }

    /** Light sections start one below the world: with minY 0 they cover -16, 0, 16, 32. Hide Y <= 20. */
    @Test
    void darkensLightAtOrBelowLimit() {
        List<byte[]> arrays = litArrays(4);
        var out = ChunkSections.hideLight(new ChunkSections.LightLayer(bits(0, 1, 2, 3), new BitSet(), arrays), 0, 20, 0);

        assertEquals(bits(2, 3), out.mask());
        assertEquals(bits(0, 1), out.empty());
        assertEquals(2, out.arrays().size());
        byte[] straddling = out.arrays().get(0);
        assertEquals(0, straddling[5 * 128 - 1]); // row 20 dark
        assertEquals((byte) 0xFF, straddling[5 * 128]); // row 21 lit
        assertEquals((byte) 0xFF, arrays.get(2)[0]); // input untouched
    }

    /** Section 1 is occluded: its light (light index 2) goes dark, the rest stays. */
    @Test
    void darkensLightOfOccludedSections() {
        var out = ChunkSections.hideLight(new ChunkSections.LightLayer(bits(0, 1, 2, 3), new BitSet(), litArrays(4)), 0, -17, 0b10);
        assertEquals(bits(0, 1, 3), out.mask());
        assertEquals(bits(2), out.empty());
        assertEquals(3, out.arrays().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void connectivityFastPaths(boolean prefixed) {
        int[] stoneAndOre = new int[4096];
        for (int i = 0; i < 4096; i++) stoneAndOre[i] = i % 7 == 0 ? ORE : STONE;
        int[] glass = new int[4096];
        Arrays.fill(glass, GLASS);
        int[] mostlyAir = new int[4096];
        Arrays.fill(mostlyAir, 0, 255, STONE); // fewer than 256 opaque cells counts as open, like the client

        assertArrayEquals(new long[]{Occlusion.ALL, 0, Occlusion.ALL, Occlusion.ALL},
                connectivity(prefixed, new int[4096], stoneAndOre, glass, mostlyAir));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void connectivityFollowsTunnels(boolean prefixed) {
        int[] straight = stone(), bent = stone(), pockets = stone(), throughGlass = stone();
        for (int x = 0; x < 16; x++) straight[cell(x, 8, 8)] = AIR;
        for (int x = 0; x <= 8; x++) bent[cell(x, 8, 8)] = AIR;
        for (int y = 8; y < 16; y++) bent[cell(8, y, 8)] = AIR;
        for (int z = 0; z < 4; z++) pockets[cell(8, 8, z)] = AIR; // touches north only
        for (int z = 12; z < 16; z++) pockets[cell(8, 8, z)] = AIR; // touches south only
        for (int x = 0; x < 16; x++) throughGlass[cell(x, 8, 8)] = x == 7 ? GLASS : AIR;

        assertArrayEquals(new long[]{Occlusion.pairs(W | E), Occlusion.pairs(W | U), Occlusion.pairs(N) | Occlusion.pairs(S),
                Occlusion.pairs(W | E)}, connectivity(prefixed, straight, bent, pockets, throughGlass));
    }

    /** Over 256 distinct block states: the section is written with the direct (global id) palette. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void connectivityWithDirectPalette(boolean prefixed) {
        int[] blocks = new int[4096];
        for (int i = 0; i < 4096; i++) blocks[i] = 100 + i % 300;
        for (int x = 0; x < 16; x++) blocks[cell(x, 3, 5)] = AIR;
        assertArrayEquals(new long[]{Occlusion.pairs(W | E)}, connectivity(prefixed, blocks));
    }

    @Test
    void connectivityOfGarbageIsNull() {
        assertNull(ChunkSections.connectivity(new byte[]{1, 2, 3}, OPAQUE));
    }

    private static long[] connectivity(boolean prefixed, int[]... sections) {
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        for (int[] blocks : sections) chunk.writeBytes(section(blocks, prefixed));
        return ChunkSections.connectivity(chunk.toByteArray(), OPAQUE);
    }

    private static int[] stone() {
        int[] blocks = new int[4096];
        Arrays.fill(blocks, STONE);
        return blocks;
    }

    private static int cell(int x, int y, int z) {
        return y << 8 | z << 4 | x;
    }

    private static BitSet bits(int... indices) {
        BitSet set = new BitSet();
        for (int i : indices) set.set(i);
        return set;
    }

    private static List<byte[]> litArrays(int n) {
        List<byte[]> arrays = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byte[] light = new byte[2048];
            Arrays.fill(light, (byte) 0xFF);
            arrays.add(light);
        }
        return arrays;
    }

    private static byte[] section(int[] blocks, boolean prefixed) {
        return section(blocks, prefixed, 7);
    }

    private static byte[] section(int[] blocks, boolean prefixed, int biome) {
        int count = (int) Arrays.stream(blocks).filter(id -> id != AIR).count();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(count >> 8);
        out.write(count);
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

    private static int[] biomes(ByteBuffer r, int sections, boolean prefixed) {
        int[] biomes = new int[sections];
        for (int i = 0; i < sections; i++) {
            r.getShort();
            ChunkSections.readContainer(r, 4096, 8, prefixed);
            biomes[i] = ChunkSections.readContainer(r, 64, 3, prefixed)[0];
        }
        return biomes;
    }
}
