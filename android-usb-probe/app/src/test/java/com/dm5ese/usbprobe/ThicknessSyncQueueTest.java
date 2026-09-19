package com.dm5ese.usbprobe;

import org.junit.Test;
import static org.junit.Assert.*;

public class ThicknessSyncQueueTest {
    @Test public void contentHashIsStableAndDoesNotRoundDecimals() throws Exception {
        String snapshot="{\"valueDecimal\":\"5.123456\",\"state\":\"OK\"}";
        assertEquals(64, ThicknessSyncQueue.sha256(snapshot).length());
        assertEquals(ThicknessSyncQueue.sha256(snapshot), ThicknessSyncQueue.sha256(snapshot));
        assertNotEquals(ThicknessSyncQueue.sha256(snapshot), ThicknessSyncQueue.sha256(snapshot.replace("5.123456","5.123457")));
    }
}
