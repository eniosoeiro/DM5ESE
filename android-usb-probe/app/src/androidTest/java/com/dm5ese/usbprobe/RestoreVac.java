package com.dm5ese.usbprobe;

import android.app.Instrumentation;
import android.hardware.usb.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** One explicitly authorized restoration. No deletion and no upload retry. */
final class RestoreVac {
    static void run(Instrumentation test, String expectedHash) throws Exception {
        var ctx=test.getTargetContext();
        byte[] bytes=Files.readAllBytes(new File(ctx.getFilesDir(),"VAC-103007-recuperado.json").toPath());
        if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expectedHash))
            throw new IOException("Backup hash mismatch");
        var original=CaptureStore.originalSnapshot(new JSONObject(new String(bytes,StandardCharsets.UTF_8)));
        Dm5eCellWriter.validate(original);
        if(!original.file().name().equals("VAC-103007") || original.readings().size()!=16
            || !original.metadata().get("L2NL").equals("4") || !original.metadata().get("L3NL").equals("4")
            || !original.metadata().get("DESC").isEmpty())throw new IOException("Unexpected backup");
        var usb=(UsbManager)ctx.getSystemService(android.content.Context.USB_SERVICE);
        var devices=usb.getDeviceList().values().stream().filter(d->d.getVendorId()==0xc251 && d.getProductId()==0x1705).toList();
        if(devices.size()!=1 || !usb.hasPermission(devices.get(0)))throw new IOException("Exactly one authorized DM5E required");
        try(var audit=new PrintWriter(new File(ctx.getFilesDir(),"VAC-103007-restore-audit.txt"),"UTF-8");
            var link=new Dm5eUsb(usb,devices.get(0),new AtomicBoolean(),900000)) {
            var protocol=new Dm5eProtocol(link);
            var before=protocol.directoryWithRecovery(s->{audit.println(s);audit.flush();});
            audit.println("BACKUP_SHA256 "+expectedHash);audit.println("BEFORE "+before);audit.flush();
            var existing=before.stream().filter(f->f.name().equalsIgnoreCase("VAC-103007")).findFirst();
            Dm5eProtocol.Capture after;
            if(existing.isPresent()) {
                after=protocol.importFile(existing.get(),s->audit.println(s));
                audit.println("ALREADY_EXISTS: no write sent");
            } else {
                if(before.isEmpty())throw new IOException("Cannot establish instrument serial from existing file");
                var identity=protocol.importFile(before.get(0),s->audit.println(s));
                if(!Objects.equals(identity.metadata().get("SRNM"),original.metadata().get("SRNM")))throw new IOException("Different instrument serial");
                var records=new ArrayList<>(new Dm5eProtocol.NewGrid("VAC-103007",4,4).records().subList(0,7));
                for(var point:original.readings())records.add(point.raw());
                audit.println("START_FU_ONCE VAC-103007; 7 header records + 16 original records");audit.flush();
                link.write("\u001bFU VAC-103007\r");link.readAck();
                for(String record:records) {link.write(record+"\r\n");link.readAck();}
                link.write("[D2TE] 000\r");link.readAck();
                audit.println("UPLOAD_ACKNOWLEDGED");audit.flush();
                link.discardInput();
                var listing=protocol.directoryWithRecovery(s->{audit.println(s);audit.flush();});
                Set<String> expected=new HashSet<>();for(var file:before)expected.add(file.name());expected.add("VAC-103007");
                Set<String> actual=new HashSet<>();for(var file:listing)actual.add(file.name());
                if(listing.size()!=before.size()+1 || !actual.equals(expected))throw new IOException("Directory differs after upload");
                after=protocol.importFile(listing.stream().filter(f->f.name().equals("VAC-103007")).findFirst().orElseThrow(),s->audit.println(s));
                audit.println("AFTER "+listing);
            }
            for(String key:List.of("SRNM","SFVR","FLNM","UNIT","VELC","L2NL","L3NL","L2SI","L3SI","ADDR","RCFM","NMBR","DESC"))
                if(!Objects.equals(original.metadata().get(key),after.metadata().get(key)))throw new IOException("Metadata mismatch: "+key);
            if(!original.readings().equals(after.readings()))throw new IOException("Raw point mismatch after restore");
            new CaptureStore(ctx).save(after);
            audit.println("PASS: all 16 complete original records and metadata verified; other names preserved");audit.flush();
        }
    }
}
