package com.dm5ese.usbprobe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class Dm5eLiveReadingTest {
    private Dm5eLiveReading parse(String value) throws IOException {
        return Dm5eLiveReading.parse(value.getBytes(StandardCharsets.US_ASCII));
    }
    @Test public void physicalResponseMatches1284mmDisplayButIsRetained() throws Exception {
        Dm5eLiveReading reading = parse("12837,0,U");
        assertEquals("12.837", reading.millimetres().toPlainString());
        assertFalse(reading.coupled());
    }
    @Test public void couplingIsIndependentOfNumericValue() throws Exception {
        assertTrue(parse("5000,16700,C").coupled());
        assertFalse(parse("5000,16700,U").coupled());
        assertEquals("5.000", parse("5000,16700,C").millimetres().toPlainString());
    }
    @Test public void rejectsMalformedOrUnsupportedResponses() {
        for (String raw : new String[]{"", "5000,1", "5000,1,X", "-1,0,C", "0,0,C",
            "1.5,0,C", "2147483648,0,C", "123,0,Cjunk", "BADCOMMAND", "123,0,C\n"})
            assertThrows(raw, IOException.class, () -> parse(raw));
    }
}
