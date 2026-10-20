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

    private static byte[] section(int[] blocks, boolean prefixed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x10);
        out.write(0x00);
        ChunkSections.writeBlocks(out, blocks, GLOBAL_BITS, prefixed);
        out.write(0); // single-valued biome container
        ChunkSections.writeVarInt(out, 7);
        if (prefixed) ChunkSections.writeVarInt(out, 0);
        return out.toByteArray();
    }

    private static int[] read(ByteBuffer r, boolean prefixed) {
        int[] blocks = ChunkSections.readContainer(r, 4096, 8, prefixed);
        ChunkSections.readContainer(r, 64, 3, prefixed);
        return blocks;
    }
}
