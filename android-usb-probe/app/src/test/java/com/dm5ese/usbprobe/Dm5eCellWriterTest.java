package com.dm5ese.usbprobe;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Dm5eCellWriterTest {
    static class Wire implements Dm5eProtocol.Link {
        Deque<byte[]> replies=new ArrayDeque<>(); List<String> writes=new ArrayList<>();
        public void write(String value){writes.add(value);}
        public byte[] readLine() throws IOException { if(replies.isEmpty())throw new IOException("Timeout"); return replies.remove(); }
        public void readAck(){}
        void line(String s){replies.add(s.getBytes(StandardCharsets.ISO_8859_1));}
        void frame(String body){int sum=0; for(byte b:body.getBytes(StandardCharsets.ISO_8859_1))sum+=b&255; line(body+String.format(Locale.ROOT,"%4x",sum&65535));}
        void download(Dm5eProtocol.Capture c){
            line("DM5E");line("1");frame("[INSS] 000");
            c.metadata().forEach((k,v)-> {if(!List.of("VERS","RCFM").contains(k))frame(k+String.format(Locale.ROOT," %03d ",v.length())+v);});
            frame("[INSE] 000");frame("[HDRS] 000");frame("[HDRE] 000");frame("[STCS] 000");frame("[STCE] 000");frame("[D2TS]");
            frame("VERS 003 2.1");frame("RCFM 014 3 3 7 7 1 4 14");
            c.readings().forEach(r->frame(r.raw()));frame("[D2TE]");line("DM5E");
        }
        void directory(){line("DM5E");line("1");line("0001 TEST");}
    }
    static Dm5eProtocol.Capture fixture() {
        Map<String,String> m=new LinkedHashMap<>();
        String[][] pairs={{"SRNM","123456"},{"SFVR","01.24"},{"FLNM","TEST"},{"UNIT","MM"},{"TPNB","2"},{"NMBR","4"},{"L2NL","2"},{"L3NL","2"},{"L2SI","1"},{"L3SI","1"},{"ADDR","LR"},{"VERS","2.1"},{"RCFM","3 3 7 7 1 4 14"}};
        for(var pair:pairs)m.put(pair[0],pair[1]);
        List<Dm5eProtocol.Reading> r=new ArrayList<>();
        var records=new Dm5eProtocol.NewGrid("TEST",2,2).records();
        for(int n=0;n<4;n++)r.add(new Dm5eProtocol.Reading((n/2+1)+Dm5eProtocol.alpha(n%2+1),"","mm",records.get(7+n)));
        return new Dm5eProtocol.Capture(new Dm5eProtocol.FileEntry(1,"TEST"),m,r,List.of());
    }
    static Dm5eProtocol.Capture modified(Dm5eProtocol.Capture c,int index) throws Exception {
        var records=new ArrayList<>(c.readings());
        String raw=Dm5eCellWriter.record(records.get(index),new BigDecimal("12.897"),new BigDecimal("5996.8"));
        records.set(index,Dm5eProtocol.reading(raw,c.metadata(),index));
        return new Dm5eProtocol.Capture(c.file(),c.metadata(),records,List.of());
    }
    @Test public void correctOffsetAndEveryOtherCellVerified() throws Exception {
        var c=fixture();var after=modified(c,3); Wire w=new Wire(); w.directory();w.download(c);w.line("41 byte(s) is wrote to file.");w.download(after);
        var result=Dm5eCellWriter.write(w,c,"2B",new BigDecimal("12.897"),new BigDecimal("5996.8"), backup->assertFalse(w.writes.contains("\u001bFO TEST\r")));
        assertEquals(after.readings(),result.readings());assertTrue(w.writes.contains("\u001bFS 206\r"));
        assertEquals(1,Collections.frequency(w.writes,"\u001bFW\r"));
        assertTrue(w.writes.contains(after.readings().get(3).raw()+"\r\n\u001a"));
    }
    @Test public void changedRemoteFilePreventsWriting() throws Exception {
        var c=fixture();Wire w=new Wire();w.directory();w.download(modified(c,0));
        assertThrows(IOException.class,()->Dm5eCellWriter.write(w,c,"2B",BigDecimal.ONE,new BigDecimal("5996.8"),backup->{}));
        assertFalse(w.writes.contains("\u001bFO TEST\r"));
    }
    @Test public void backupFailurePreventsWriting() {
        var c=fixture();Wire w=new Wire();w.directory();w.download(c);
        assertThrows(IOException.class,()->Dm5eCellWriter.write(w,c,"2B",BigDecimal.ONE,new BigDecimal("5996.8"),backup->{throw new IOException("disk full");}));
        assertFalse(w.writes.contains("\u001bFO TEST\r"));
    }
    @Test public void unexpectedWriteReplyNeverRetriesOrSendsMoreCommands() {
        var c=fixture();Wire w=new Wire();w.directory();w.download(c);w.line("wrong");
        assertThrows(IOException.class,()->Dm5eCellWriter.write(w,c,"2B",BigDecimal.ONE,new BigDecimal("5996.8"),backup->{}));
        assertEquals(1,Collections.frequency(w.writes,"\u001bFW\r"));assertFalse(w.writes.contains("\u001bFC\r"));
    }
    @Test public void wrongCellInReadbackFails() throws Exception {
        var c=fixture();Wire w=new Wire();w.directory();w.download(c);w.line("41 byte(s) is wrote to file.");w.download(modified(c,0));
        assertThrows(IOException.class,()->Dm5eCellWriter.write(w,c,"2B",new BigDecimal("12.897"),new BigDecimal("5996.8"),backup->{}));
    }
    @Test public void unsupportedFirmwareRejectedBeforeIo() {
        var c=fixture();c.metadata().put("SFVR","01.29");Wire w=new Wire();
        assertThrows(IOException.class,()->Dm5eCellWriter.write(w,c,"2B",BigDecimal.ONE,new BigDecimal("5996.8"),backup->{}));assertTrue(w.writes.isEmpty());
    }
    @Test public void layoutAndUnknownAuxiliaryFields() throws Exception {
        assertEquals(83,Dm5eCellWriter.offset(0)); assertEquals(698,Dm5eCellWriter.offset(15));
        String raw=Dm5eCellWriter.record(fixture().readings().get(0),new BigDecimal("12.897"),new BigDecimal("5996.8"));
        assertEquals(39,raw.length()); assertEquals("\u00ff".repeat(18),raw.substring(21));
        assertThrows(IOException.class,()->Dm5eCellWriter.offset(100));
    }
}
