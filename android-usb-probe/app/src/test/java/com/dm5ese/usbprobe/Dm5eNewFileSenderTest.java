package com.dm5ese.usbprobe;
import org.junit.Test;
import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.io.IOException;
import java.util.*;

public class Dm5eNewFileSenderTest {
    @Test public void emptyGridNeedsNoVelocity() throws Exception {
        var empty=new Dm5eNewFileSender.Draft("EMPTY",2,2,null,Map.of());
        assertEquals(new Dm5eProtocol.NewGrid("EMPTY",2,2).records(),empty.records());
    }
    @Test public void filledGridWithoutVelocityFailsBeforeUsb() {
        var w=new Dm5eCellWriterTest.Wire();
        var filled=new Dm5eNewFileSender.Draft("FILLED",2,2,null,Map.of("1A",BigDecimal.ONE));
        assertThrows(IOException.class,()->Dm5eNewFileSender.send(w,filled,s->{}));
        assertTrue(w.writes.isEmpty());
    }
    Dm5eNewFileSender.Draft draft(Map<String,BigDecimal> values){return new Dm5eNewFileSender.Draft("TEST",2,2,new BigDecimal("5996.8"),values);}
    @Test public void createsFilledGridAndVerifiesAllRecords() throws Exception {
        var d=draft(Map.of("1A",new BigDecimal("12.897")));var c=Dm5eCellWriterTest.modified(Dm5eCellWriterTest.fixture(),0);
        var w=new Dm5eCellWriterTest.Wire();w.line("DM5E");w.line("0");w.directory();w.download(c);
        var result=Dm5eNewFileSender.send(w,d,s->{});
        assertEquals(c.readings(),result.readings());assertEquals(1,Collections.frequency(w.writes,"\u001bFU TEST\r"));
        for(String record:d.records())assertTrue(w.writes.contains(record+"\r\n"));
    }
    @Test public void matchingExistingFileIsOnlyRead() throws Exception {
        var w=new Dm5eCellWriterTest.Wire();w.directory();w.download(Dm5eCellWriterTest.fixture());
        Dm5eNewFileSender.send(w,draft(Map.of()),s->{});
        assertFalse(w.writes.stream().anyMatch(s->s.contains("FU")||s.contains("DF")||s.contains("FW")));
    }
    @Test public void differentExistingContentIsNeverOverwritten() throws Exception {
        var w=new Dm5eCellWriterTest.Wire();w.directory();w.download(Dm5eCellWriterTest.modified(Dm5eCellWriterTest.fixture(),0));
        assertThrows(IOException.class,()->Dm5eNewFileSender.send(w,draft(Map.of()),s->{}));
        assertFalse(w.writes.contains("\u001bFU TEST\r"));
    }
    @Test public void interruptedUploadIsNotRetried() {
        var w=new Dm5eCellWriterTest.Wire(){@Override public void readAck(){throw new IllegalStateException("disconnect");}};
        w.line("DM5E");w.line("0");
        assertThrows(IOException.class,()->Dm5eNewFileSender.send(w,draft(Map.of()),s->{}));
        assertEquals(1,Collections.frequency(w.writes,"\u001bFU TEST\r"));
    }
    @Test public void invalidCoordinatesAndNamesFailBeforeUsb() {
        var w=new Dm5eCellWriterTest.Wire();
        assertThrows(IOException.class,()->Dm5eNewFileSender.send(w,draft(Map.of("9Z",BigDecimal.ONE)),s->{}));
        assertThrows(IOException.class,()->Dm5eNewFileSender.send(w,new Dm5eNewFileSender.Draft("TEST\r",2,2,new BigDecimal("5996.8"),Map.of()),s->{}));
        assertTrue(w.writes.isEmpty());
    }
}
