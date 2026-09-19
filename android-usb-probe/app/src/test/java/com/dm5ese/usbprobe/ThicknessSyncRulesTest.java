package com.dm5ese.usbprobe;
import org.junit.Test;
import static org.junit.Assert.*;

public class ThicknessSyncRulesTest {
    @Test public void boundedRetry() {
        assertTrue(ThicknessSyncRules.due("PENDING", 0, 0, 1));
        assertFalse(ThicknessSyncRules.due("PENDING", 5, 0, 1));
        assertFalse(ThicknessSyncRules.due("PENDING", 1, 100, 99));
        assertTrue(ThicknessSyncRules.due("SENDING", 1, 100, 100));
        for(String state:new String[]{"FAILED","RECEIVED","LOCAL"}) assertFalse(ThicknessSyncRules.due(state,0,0,1));
    }
    @Test public void progressiveBackoff() {
        assertEquals(2000,ThicknessSyncRules.delay(1)); assertEquals(4000,ThicknessSyncRules.delay(2));
        assertTrue(ThicknessSyncRules.delay(100)<=300000);
    }
    @Test public void authenticationNeverRetriesSilently() {
        for(int code:new int[]{400,401,403,409,413}) assertFalse(ThicknessSyncRules.httpFailure(code).retryable);
    }
    @Test public void transientFailureRemainsPending() {
        for(int code:new int[]{408,429,500,503}) assertTrue(ThicknessSyncRules.httpFailure(code).retryable);
    }
    @Test public void arbitraryExceptionTextCannotLeak() {
        assertFalse(ThicknessSyncRules.safeMessage(new java.io.IOException("sensitive token")).contains("sensitive"));
        assertFalse(ThicknessSyncRules.safeMessage(new IllegalArgumentException("sensitive token")).contains("sensitive"));
    }
}
