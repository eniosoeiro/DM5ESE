package com.dm5ese.usbprobe;

import java.io.IOException;

/** Read-only 8Y response: 64 rows, 16 bytes per row, most significant pixel first. */
public final class Dm5eScreen {
    public static final int WIDTH = 128, HEIGHT = 64, BYTES = 1024;
    private Dm5eScreen() { }

    public static int[] pixels(byte[] frame) throws IOException {
        if (frame.length != BYTES) throw new IOException("Imagem incompleta do DM5E: " + frame.length + "/1024 bytes.");
        int[] pixels = new int[WIDTH * HEIGHT];
        for (int i = 0; i < pixels.length; i++)
            pixels[i] = (frame[i / 8] & (0x80 >>> (i % 8))) != 0 ? 0xff102c30 : 0xffe4efdb;
        return pixels;
    }
}
