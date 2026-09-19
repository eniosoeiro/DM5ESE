package com.dm5ese.usbprobe;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Dm5eProtocolTest {
    private static byte[] frame(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.ISO_8859_1);
        int sum = 0; for (byte b : bytes) sum += b & 255;
        return (body + String.format(Locale.ROOT, "%4x", sum & 65535)).getBytes(StandardCharsets.ISO_8859_1);
    }
    private static String field(String key, String data) { return key + String.format(Locale.ROOT, " %03d ", data.length()) + data; }
    private static class Fake implements Dm5eProtocol.Link {
        public void awaitDeletion() { }
        int acknowledgements;
        int failAck = -1;
        public void readAck() throws IOException {
            if (++acknowledgements == failAck) throw new IOException("Disconnected");
        }
        final Deque<byte[]> lines = new ArrayDeque<>();
        final List<String> writes = new ArrayList<>();
        void line(String line) { lines.add(line.getBytes(StandardCharsets.ISO_8859_1)); }
        void block(String block) { lines.add(frame(block)); }
        public void write(String command) { writes.add(command); }
        public byte[] readLine() throws IOException {
            if (lines.isEmpty()) throw new IOException("Truncated transport"); return lines.remove();
        }
    }
    private Fake fixture() {
        Fake f = new Fake(); f.line("DM5E"); f.line("1");
        f.block("[INSS] 000"); f.block(field("SRNM", "123456")); f.block("[INSE] 000");
        f.block("[HDRS] 000"); f.block(field("FLNM", "TEST")); f.block(field("UNIT", "MM")); f.block("[HDRE] 000");
        f.block("[STCS] 000");
        String[][] values = {{"TPNB","2"}, {"NMBR","2"}, {"L2NL","1"}, {"L3NL","2"}, {"L2SI","1"}, {"L3SI","1"}, {"ADDR","LR"}};
        for (String[] v : values) f.block(field(v[0], v[1]));
        f.block("[STCE] 000"); f.block("[D2TS]"); f.block(field("VERS","2.1")); f.block(field("RCFM","3 3 7 7 1 4 14"));
        f.block("\u00ff\u00ff1\u00ff\u00ffA\u00ff  5.12\u00ff\u00ff 5996M\u00ff\u00ff\u00ff\u00ff2084 0 2 511 4");
        f.block("\u00ff\u00ff1\u00ff\u00ffB\u00ff  4.86\u00ff\u00ff 5996M\u00ff\u00ff\u00ff\u00ff2084 0 2 511 7");
        f.block("[D2TE]"); f.line("DM5E"); return f;
    }
    @Test public void fullDownloadPreservesDecimalAndPositions() throws Exception {
        Fake f = fixture(); Dm5eProtocol.Capture c = new Dm5eProtocol(f).download(new Dm5eProtocol.FileEntry(1, "TEST"));
        assertEquals("1A", c.readings().get(0).position()); assertEquals("5.12", c.readings().get(0).value());
        assertEquals("1B", c.readings().get(1).position()); assertEquals("mm", c.readings().get(1).unit());
        assertTrue(f.writes.contains("\u001bFX 1\r")); assertTrue(f.lines.isEmpty());
    }
    @Test public void createRefusesDuplicateWithoutWritingFU() {
        Fake f = new Fake(); f.line("DM5E"); f.line("1"); f.line("0001 TEST");
        assertThrows(IOException.class, () -> new Dm5eProtocol(f).create(new Dm5eProtocol.NewGrid("TEST",1,2)));
        assertFalse(f.writes.stream().anyMatch(w -> w.contains("FU")));
    }
    @Test public void interruptedUploadIsNeverRetried() {
        Fake f = new Fake(); f.line("DM5E"); f.line("0"); f.failAck = 2;
        IOException e = assertThrows(IOException.class, () -> new Dm5eProtocol(f).create(new Dm5eProtocol.NewGrid("TEST",1,2)));
        assertEquals(1, Collections.frequency(f.writes, "\u001bFU TEST\r"));
        assertTrue(e.getMessage().contains("incompleto"));
        assertFalse(f.writes.contains("[D2TE] 000\r"));
    }
    @Test public void createRequiresVerifiedEmptyReadback() throws Exception {
        Fake f = new Fake(); f.line("DM5E"); f.line("0");
        f.line("DM5E"); f.line("1"); f.line("0001 TEST");
        Fake readback = fixture();
        for (byte[] line : readback.lines) {
            String body = new String(line, StandardCharsets.ISO_8859_1);
            if (body.startsWith("\u00ff\u00ff1")) f.block(body.substring(0,6) + "\u00ff".repeat(33));
            else f.lines.add(line);
        }
        Dm5eProtocol.Capture result = new Dm5eProtocol(f).create(new Dm5eProtocol.NewGrid("TEST",1,2));
        assertEquals(2, result.readings().size()); assertEquals("", result.readings().get(0).value());
        assertEquals(11, f.acknowledgements);
        assertTrue(f.writes.contains("[D2TE] 000\r"));
        assertFalse(f.writes.contains("[D2TE] 000\r\n"));
    }
    @Test public void creationInputCannotInjectCommandsOrExceedBound() {
        for (String name : new String[]{"", "ABCDEFGHIJKLMNOP", "TEST\r", "TEST X", "\u001bID"})
            assertThrows(IllegalArgumentException.class, () -> new Dm5eProtocol.NewGrid(name,2,2));
        assertThrows(IllegalArgumentException.class, () -> new Dm5eProtocol.NewGrid("TEST",0,2));
        assertThrows(IllegalArgumentException.class, () -> new Dm5eProtocol.NewGrid("TEST",100,2));
        assertThrows(IllegalArgumentException.class, () -> new Dm5eProtocol.NewGrid("TEST",1,27));
    }
    private static void directoryReply(Fake f, String... names) {
        f.line("DM5E"); f.line(Integer.toString(names.length));
        for(int i=0;i<names.length;i++) f.line(String.format(Locale.ROOT,"%04d %s",i+1,names[i]));
    }
    @Test public void singleDeletionPreservesOtherNames() throws Exception {
        Fake f=new Fake(); directoryReply(f,"KEEP","TEST"); directoryReply(f,"KEEP");
        var result=new Dm5eProtocol(f).deleteFiles(List.of("TEST"),List.of(new Dm5eProtocol.FileEntry(1,"KEEP"),new Dm5eProtocol.FileEntry(2,"TEST")));
        assertEquals("TEST",result.deleted()); assertEquals("KEEP",result.remaining().get(0).name());
        assertEquals(1,Collections.frequency(f.writes,"\u001bDF TEST\r"));
        assertFalse(f.writes.contains("\u001bDF KEEP\r"));
    }
    @Test public void deletingLastFileConfirmsEmptyDirectory() throws Exception {
        Fake f=new Fake(); directoryReply(f,"TEST"); directoryReply(f);
        assertTrue(new Dm5eProtocol(f).deleteFiles(List.of("TEST"),List.of(new Dm5eProtocol.FileEntry(1,"TEST"))).remaining().isEmpty());
    }
    @Test public void importRecoversCurrentBlockWithoutRestartingOrAcknowledgingCorruption() throws Exception {
        Fake f = fixture();
        var lines = new ArrayList<>(f.lines); lines.add(2,"[INSS] 0000000".getBytes(StandardCharsets.ISO_8859_1));
        f.lines.clear(); f.lines.addAll(lines);
        List<String> progress = new ArrayList<>();
        var capture = new Dm5eProtocol(f).importFile(new Dm5eProtocol.FileEntry(1,"TEST"), progress::add);
        assertEquals(2,capture.readings().size());
        assertEquals(1,Collections.frequency(f.writes,"\u001bFX 1\r"));
        assertEquals("NN\n\r", f.writes.get(3));
        assertEquals(1,progress.size());
    }
    @Test public void importStopsAfterThreeCorruptCopies() {
        Fake f = new Fake(); f.line("DM5E"); f.line("1");
        for(int i=0;i<3;i++) f.line("[INSS] 0000000");
        assertThrows(IOException.class, () -> new Dm5eProtocol(f).importFile(new Dm5eProtocol.FileEntry(1,"TEST"), s -> {}));
        assertEquals(2,Collections.frequency(f.writes,"NN\n\r"));
        assertFalse(f.writes.contains("AA\n\r"));
    }
    @Test public void importDoesNotRetryTransportFailure() {
        Fake f = new Fake();
        assertThrows(IOException.class, () -> new Dm5eProtocol(f).importFile(new Dm5eProtocol.FileEntry(1,"TEST"), s -> {}));
        assertEquals(List.of("\u001bID\r"),f.writes);
    }
    private Fake brokenDirectoryThenVerifiedFile() {
        Fake f = new Fake();
        for(int i=0;i<3;i++) { f.line("DM5E"); f.line("1"); f.line("0002 MIXED"); }
        f.line("DM5E"); f.line("1");
        f.lines.addAll(fixture().lines);
        return f;
    }
    @Test public void directoryRecoversNamesThroughCheckedExports() throws Exception {
        Fake f = brokenDirectoryThenVerifiedFile(); f.line("1");
        var result = new Dm5eProtocol(f).directoryWithRecovery(s -> {});
        assertEquals(List.of(new Dm5eProtocol.FileEntry(1,"TEST")),result);
        assertEquals(1,Collections.frequency(f.writes,"\u001bFX 1\r"));
        assertTrue(f.writes.stream().noneMatch(s -> s.contains("FU") || s.contains("DF") || s.contains("FW")));
    }
    @Test public void directoryRejectsChangedCountAfterRecovery() {
        Fake f = brokenDirectoryThenVerifiedFile(); f.line("2");
        assertThrows(IOException.class, () -> new Dm5eProtocol(f).directoryWithRecovery(s -> {}));
    }
    @Test public void directoryRecoveryDoesNotBypassChecksums() {
        Fake f = new Fake();
        for(int i=0;i<3;i++) { f.line("DM5E"); f.line("1"); f.line("0002 MIXED"); }
        f.line("DM5E"); f.line("1"); f.line("DM5E"); f.line("1");
        for(int i=0;i<3;i++) f.line("[INSS] 0000000");
        assertThrows(IOException.class, () -> new Dm5eProtocol(f).directoryWithRecovery(s -> {}));
        assertFalse(f.writes.contains("AA\n\r"));
    }
    @Test public void multipleDeletionTracksProgressAndPreservesUnselected() throws Exception {
        Fake f = new Fake(); directoryReply(f,"ONE","TWO","KEEP"); directoryReply(f,"TWO","KEEP"); directoryReply(f,"KEEP");
        List<String> progress = new ArrayList<>();
        var result = new Dm5eProtocol(f).deleteFiles(List.of("ONE","TWO"),
            List.of(new Dm5eProtocol.FileEntry(1,"ONE"),new Dm5eProtocol.FileEntry(2,"TWO"),new Dm5eProtocol.FileEntry(3,"KEEP")), progress::add);
        assertEquals("ONE, TWO",result.deleted()); assertEquals("KEEP",result.remaining().get(0).name());
        assertEquals(4,progress.size()); assertTrue(progress.get(2).contains("2 de 2"));
        assertEquals(1,Collections.frequency(f.writes,"\u001bDF ONE\r"));
        assertEquals(1,Collections.frequency(f.writes,"\u001bDF TWO\r"));
        assertFalse(f.writes.contains("\u001bDF KEEP\r"));
    }
    @Test public void batchStopsOnUncertainSecondDeletionAndReportsFirst() {
        Fake f = new Fake(); directoryReply(f,"ONE","TWO","THREE"); directoryReply(f,"TWO","THREE");
        IOException e = assertThrows(IOException.class, () -> new Dm5eProtocol(f).deleteFiles(List.of("ONE","TWO","THREE"),
            List.of(new Dm5eProtocol.FileEntry(1,"ONE"),new Dm5eProtocol.FileEntry(2,"TWO"),new Dm5eProtocol.FileEntry(3,"THREE"))));
        assertTrue(e.getMessage().contains("Confirmados: ONE."));
        assertEquals(1,Collections.frequency(f.writes,"\u001bDF TWO\r"));
        assertFalse(f.writes.contains("\u001bDF THREE\r"));
    }
    @Test public void allSelectedCanLeaveEmptyDirectory() throws Exception {
        Fake f = new Fake(); directoryReply(f,"ONE","TWO"); directoryReply(f,"TWO"); directoryReply(f);
        assertTrue(new Dm5eProtocol(f).deleteFiles(List.of("ONE","TWO"),
            List.of(new Dm5eProtocol.FileEntry(1,"ONE"),new Dm5eProtocol.FileEntry(2,"TWO"))).remaining().isEmpty());
    }
    @Test public void multipleOrInvalidNamesSendNothing() {
        for(List<String> targets:List.of(List.<String>of(),List.of("ONE","TWO"),List.of("BAD\rNAME"),List.of("OTHER"))) {
            Fake f=new Fake();
            assertThrows(IOException.class,()->new Dm5eProtocol(f).deleteFiles(targets,List.of(new Dm5eProtocol.FileEntry(1,"ONE"))));
            assertTrue(f.writes.isEmpty());
        }
    }
    @Test public void changedDirectoryDoesNotDelete() {
        Fake f=new Fake(); directoryReply(f,"TEST","NEW");
        assertThrows(IOException.class,()->new Dm5eProtocol(f).deleteFiles(List.of("TEST"),List.of(new Dm5eProtocol.FileEntry(1,"TEST"))));
        assertFalse(f.writes.stream().anyMatch(w->w.contains("DF")));
    }
    @Test public void deletionTimeoutNeverRetriesCommandOrConfirmation() {
        Fake f=new Fake(); directoryReply(f,"TEST");
        IOException e=assertThrows(IOException.class,()->new Dm5eProtocol(f).deleteFiles(List.of("TEST"),List.of(new Dm5eProtocol.FileEntry(1,"TEST"))));
        assertTrue(e.getMessage().contains("TEST"));
        assertEquals(1,Collections.frequency(f.writes,"\u001bDF TEST\r"));
        assertEquals(2,Collections.frequency(f.writes,"\u001bID\r"));
    }
    @Test public void damagedFrameIsNotAcknowledged() throws Exception {
        Fake f = fixture(); byte[] damaged = f.lines.stream().skip(2).findFirst().get(); damaged[1] ^= 1;
        assertThrows(IOException.class, () -> new Dm5eProtocol(f).download(new Dm5eProtocol.FileEntry(1, "TEST")));
        assertFalse(f.writes.contains("AA\n\r"));
    }
    @Test public void truncatedTransferFails() {
        Fake f = fixture(); f.lines.removeLast(); f.lines.removeLast();
        assertThrows(IOException.class, () -> new Dm5eProtocol(f).download(new Dm5eProtocol.FileEntry(1, "TEST")));
    }
    @Test public void changedFileNameNeverReturnsCapture() {
        Fake f = fixture();
        assertThrows(IOException.class, () -> new Dm5eProtocol(f).download(new Dm5eProtocol.FileEntry(1, "OLD")));
        assertTrue(f.writes.contains("\u001bFX 1\r"));
    }
    @Test public void missingIndexNeverDownloads() {
        Fake f = fixture();
        assertThrows(IOException.class, () -> new Dm5eProtocol(f).download(new Dm5eProtocol.FileEntry(2, "TEST")));
        assertFalse(f.writes.stream().anyMatch(w -> w.contains("FX")));
    }
    @Test public void directoryRetriesWholeResponseWithoutCombiningFragments() throws Exception {
        Fake f = new Fake();
        f.line("DM5E"); f.line("2"); f.line("0002 LOST");
        f.line("DM5E"); f.line("2"); f.line("0001 FIRST"); f.line("0002 SECOND");
        assertEquals(List.of(new Dm5eProtocol.FileEntry(1,"FIRST"), new Dm5eProtocol.FileEntry(2,"SECOND")),
            new Dm5eProtocol(f).directory());
        assertEquals(2, Collections.frequency(f.writes, "\u001bDR\r"));
    }
    @Test public void mergedDirectoryEntriesFailWithDiagnosticCauseAndNoMutation() {
        Fake f = new Fake();
        for (int attempt = 0; attempt < 3; attempt++) {
            f.line("DM5E"); f.line("2"); f.line("M-1220010 POSTO CASTELO");
        }
        IOException error = assertThrows(IOException.class, () -> new Dm5eProtocol(f).directory());
        assertTrue(error.getMessage().contains("3 tentativas"));
        assertTrue(error.getCause().getMessage().contains("M-1220010 POSTO CASTELO"));
        assertEquals(3, Collections.frequency(f.writes, "\u001bDR\r"));
        assertTrue(f.writes.stream().allMatch(w -> List.of("\u001bID\r", "\u001bDL\r", "\u001bDR\r").contains(w)));
    }
    @Test public void wrongCountFailsEvenWithValidChecksums() {
        Fake f = fixture(); List<byte[]> frames = new ArrayList<>(f.lines);
        for (int i = 0; i < frames.size(); i++) if (new String(frames.get(i), StandardCharsets.ISO_8859_1).startsWith("NMBR")) frames.set(i, frame(field("NMBR", "3")));
        f.lines.clear(); f.lines.addAll(frames);
        assertThrows(IOException.class, () -> new Dm5eProtocol(f).download(new Dm5eProtocol.FileEntry(1, "TEST")));
    }
    @Test public void reorderedPointsAndUnknownStatesFail() {
        for (String replacement : new String[]{"\u00ff\u00ff1\u00ff\u00ffC", "\u00ff\u00ff1\u00ff\u00ffA"}) {
            Fake f = fixture(); List<byte[]> frames = new ArrayList<>(f.lines);
            int index = frames.size() - 4;
            String original = new String(frames.get(index), StandardCharsets.ISO_8859_1);
            String body = replacement + original.substring(6, original.length() - 4);
            if (replacement.endsWith("A")) body = body.substring(0, 6) + "  OBSTR" + body.substring(13);
            frames.set(index, frame(body)); f.lines.clear(); f.lines.addAll(frames);
            assertThrows(IOException.class, () -> new Dm5eProtocol(f).download(new Dm5eProtocol.FileEntry(1, "TEST")));
        }
    }
}
