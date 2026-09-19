package com.dm5ese.usbprobe;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Dm5eBulkDownloadTest {
    final List<Dm5eProtocol.FileEntry> files=List.of(new Dm5eProtocol.FileEntry(1,"ONE"),new Dm5eProtocol.FileEntry(2,"TWO"));
    Dm5eProtocol.Capture capture(Dm5eProtocol.FileEntry file) {return new Dm5eProtocol.Capture(file,Map.of(),List.of(),List.of());}
    @Test public void savesEveryFileBeforeCountingSuccess() {
        List<String> saved=new ArrayList<>();
        var result=Dm5eBulkDownload.run(files,this::capture,c->saved.add(c.file().name()),()->false,s->{});
        assertEquals(List.of("ONE","TWO"),saved); assertEquals(saved,result.saved()); assertTrue(result.remaining().isEmpty());
    }
    @Test public void readFailureKeepsEarlierFilesAndDoesNotSavePartial() {
        List<String> saved=new ArrayList<>();
        var result=Dm5eBulkDownload.run(files,f->{if(f.number()==2)throw new IOException("checksum");return capture(f);},c->saved.add(c.file().name()),()->false,s->{});
        assertEquals(List.of("ONE"),saved); assertEquals(List.of("TWO"),result.remaining());assertTrue(result.error().contains("checksum"));
    }
    @Test public void diskFailureIsNotReportedAsSaved() {
        var result=Dm5eBulkDownload.run(files,this::capture,c->{throw new IOException("disk full");},()->false,s->{});
        assertTrue(result.saved().isEmpty());assertEquals(List.of("ONE","TWO"),result.remaining());
    }
    @Test public void cancellationPreservesCompletedFiles() {
        AtomicBoolean stop=new AtomicBoolean();
        var result=Dm5eBulkDownload.run(files,this::capture,c->stop.set(true),stop::get,s->{});
        assertEquals(List.of("ONE"),result.saved());assertEquals(List.of("TWO"),result.remaining());
    }
    @Test public void cancellationDuringReadDoesNotSaveIncompleteWork() {
        AtomicBoolean stop=new AtomicBoolean();List<String> saved=new ArrayList<>();
        var result=Dm5eBulkDownload.run(files,f->{stop.set(true);return capture(f);},c->saved.add(c.file().name()),stop::get,s->{});
        assertTrue(saved.isEmpty());assertEquals(2,result.remaining().size());
    }
    @Test public void emptyDirectoryDoesNotReadOrWrite() {
        var result=Dm5eBulkDownload.run(List.of(),f->{throw new AssertionError();},c->{throw new AssertionError();},()->false,s->{});
        assertTrue(result.remaining().isEmpty());assertTrue(result.saved().isEmpty());
    }
}
