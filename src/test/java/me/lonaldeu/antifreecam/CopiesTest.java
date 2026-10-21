package me.lonaldeu.antifreecam;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CopiesTest {

    /** Like the 26.3 chunk packet: a record, whose fields can't be written. */
    record Packet(int x, int z, String data, List<byte[]> light) {
    }

    /** Like the chunk data before and after 26.3: a class with final fields. */
    static final class Data {
        private final byte[] buffer;
        private final String label;

        Data(byte[] buffer, String label) {
            this.buffer = buffer;
            this.label = label;
        }
    }

    @Test
    void recordIsRebuiltWithTheChangedComponents() {
        Packet original = new Packet(1, 2, "real", List.of());
        Packet copy = (Packet) Copies.with(original, "data", "hidden", "z", 9);
        assertEquals(new Packet(1, 9, "hidden", List.of()), copy);
        assertEquals("real", original.data());
    }

    @Test
    void classIsCopiedFieldByField() {
        Data original = new Data(new byte[]{1}, "a");
        Data copy = (Data) Copies.with(original, "buffer", new byte[]{2});
        assertNotSame(original, copy);
        assertEquals(2, copy.buffer[0]);
        assertEquals("a", copy.label);
        assertEquals(1, original.buffer[0]);
    }

    @Test
    void unknownFieldIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> Copies.with(new Packet(1, 2, "", List.of()), "nope", 1));
    }
}
