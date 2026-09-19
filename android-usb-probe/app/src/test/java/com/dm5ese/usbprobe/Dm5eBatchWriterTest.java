package com.dm5ese.usbprobe;
import org.junit.Test;
import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.io.IOException;
import java.util.*;

public class Dm5eBatchWriterTest {
    final BigDecimal value=new BigDecimal("12.897"), speed=new BigDecimal("5996.8");
    @Test public void twoCellsSentAndOtherCellsPreserved() throws Exception {
        var base=Dm5eCellWriterTest.fixture(); var one=Dm5eCellWriterTest.modified(base,0); var two=Dm5eCellWriterTest.modified(one,3);
        var w=new Dm5eCellWriterTest.Wire(); w.directory();w.download(base);
        w.directory();w.download(base);w.line("41 byte(s) is wrote to file.");w.download(one);
        w.directory();w.download(one);w.line("41 byte(s) is wrote to file.");w.download(two);
        var result=Dm5eBatchWriter.send(w,base,Map.of("1A",value,"2B",value),speed,c->{},s->{});
        assertEquals(two.readings(),result.readings());
        assertEquals(2,Collections.frequency(w.writes,"\u001bFW\r"));
    }
    @Test public void resumedBatchSkipsAlreadyAppliedCell() throws Exception {
        var base=Dm5eCellWriterTest.fixture();var one=Dm5eCellWriterTest.modified(base,0);var two=Dm5eCellWriterTest.modified(one,3);
        var w=new Dm5eCellWriterTest.Wire();w.directory();w.download(one);
        w.directory();w.download(one);w.line("41 byte(s) is wrote to file.");w.download(two);
        Dm5eBatchWriter.send(w,base,Map.of("1A",value,"2B",value),speed,c->{},s->{});
        assertEquals(1,Collections.frequency(w.writes,"\u001bFW\r"));
        assertFalse(w.writes.contains("\u001bFS 83\r"));
    }
    @Test public void allAppliedRequiresNoWrite() throws Exception {
        var base=Dm5eCellWriterTest.fixture();var done=Dm5eCellWriterTest.modified(base,0);
        var w=new Dm5eCellWriterTest.Wire();w.directory();w.download(done);
        var result=Dm5eBatchWriter.send(w,base,Map.of("1A",value),speed,c->{},s->{});
        assertEquals(done.readings(),result.readings());assertFalse(w.writes.contains("\u001bFW\r"));
    }
    @Test public void externalChangeStopsBeforeAnyWrite() throws Exception {
        var base=Dm5eCellWriterTest.fixture();var w=new Dm5eCellWriterTest.Wire();w.directory();w.download(Dm5eCellWriterTest.modified(base,1));
        assertThrows(IOException.class,()->Dm5eBatchWriter.send(w,base,Map.of("1A",value),speed,c->{},s->{}));
        assertFalse(w.writes.contains("\u001bFO TEST\r"));
    }
    @Test public void interruptedFirstCellNeverSendsSecondOrRepeats() {
        var base=Dm5eCellWriterTest.fixture();var w=new Dm5eCellWriterTest.Wire();w.directory();w.download(base);
        w.directory();w.download(base); // Timeout awaiting FW report, possibly already written.
        assertThrows(IOException.class,()->Dm5eBatchWriter.send(w,base,Map.of("1A",value,"2B",value),speed,c->{},s->{}));
        assertEquals(1,Collections.frequency(w.writes,"\u001bFW\r"));assertFalse(w.writes.contains("\u001bFS 206\r"));
    }
    @Test public void backupFailureStopsBeforeWriting() {
        var base=Dm5eCellWriterTest.fixture();var w=new Dm5eCellWriterTest.Wire();w.directory();w.download(base);
        assertThrows(IOException.class,()->Dm5eBatchWriter.send(w,base,Map.of("1A",value),speed,c->{throw new IOException("disk full");},s->{}));
        assertFalse(w.writes.contains("\u001bFO TEST\r"));
    }
}
