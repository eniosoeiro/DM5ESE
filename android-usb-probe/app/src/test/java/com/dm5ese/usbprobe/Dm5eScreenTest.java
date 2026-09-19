package com.dm5ese.usbprobe;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class Dm5eScreenTest {
    @Test public void decodesRowsAndMostSignificantPixelFirst() throws Exception {
        byte[] frame = new byte[1024];
        frame[0] = (byte) 0x80; frame[15] = 1; frame[16] = (byte) 0x80; frame[1023] = 1;
        int[] pixels = Dm5eScreen.pixels(frame);
        assertEquals(8192, pixels.length);
        assertEquals(0xff102c30, pixels[0]);
        assertEquals(0xffe4efdb, pixels[1]);
        assertEquals(0xff102c30, pixels[127]);
        assertEquals(0xff102c30, pixels[128]);
        assertEquals(0xffe4efdb, pixels[129]);
        assertEquals(0xff102c30, pixels[8191]);
    }
    @Test public void rejectsTruncationAndExtraBytes() {
        for (int length : new int[]{0, 928, 976, 1023, 1025, 2048})
            assertThrows(IOException.class, () -> Dm5eScreen.pixels(new byte[length]));
    }
    @Test public void preservesBinaryControlCharactersAsPixels() throws Exception {
        byte[] frame = new byte[1024]; frame[0] = 13; frame[1] = 10;
        int[] pixels = Dm5eScreen.pixels(frame);
        assertEquals(0xffe4efdb, pixels[0]);
        assertEquals(0xff102c30, pixels[4]);
        assertEquals(0xff102c30, pixels[5]);
        assertEquals(0xff102c30, pixels[7]);
        assertEquals(0xff102c30, pixels[12]);
        assertEquals(0xff102c30, pixels[14]);
    }
}
