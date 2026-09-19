package com.dm5ese.usbprobe;
import org.junit.Test;
import static org.junit.Assert.*;

public class CellMeasurementTest {
    private final Dm5eLiveReading coupled = new Dm5eLiveReading(5270, 0, true, "5270,0,C");
    @Test public void requiresSelectionAndNewReadingAfterSwitch() {
        CellMeasurement c = new CellMeasurement(); c.accept(coupled, 100);
        assertFalse(c.canSave(100)); c.select("1A"); assertFalse(c.canSave(100));
        c.accept(coupled, 101); assertTrue(c.canSave(101)); c.select("1B"); assertFalse(c.canSave(102));
    }
    @Test public void expiresAt1500msAndRejectsClockReversal() {
        CellMeasurement c = new CellMeasurement(); c.select("1A"); c.accept(coupled, 100);
        assertTrue(c.canSave(1599)); assertFalse(c.canSave(1600)); assertFalse(c.canSave(99));
        assertThrows(IllegalStateException.class, () -> c.snapshot(1600));
    }
    @Test public void uncoupledOrInvalidatedCannotOverwrite() {
        CellMeasurement c = new CellMeasurement(); c.select("1A"); c.accept(coupled, 100);
        c.accept(new Dm5eLiveReading(5270, 0, false, "5270,0,U"), 110);
        assertFalse(c.canSave(110)); c.accept(coupled, 120); c.clear(); assertFalse(c.canSave(120));
    }
    @Test public void frozenSnapshotDoesNotChangeWithNextResponse() {
        CellMeasurement c = new CellMeasurement(); c.select("1A"); c.accept(coupled, 100);
        Dm5eLiveReading frozen = c.snapshot(100);
        c.accept(new Dm5eLiveReading(8000, 0, true, "8000,0,C"), 200);
        assertEquals(5270, frozen.micrometres()); assertEquals(8000, c.snapshot(200).micrometres());
    }
}
