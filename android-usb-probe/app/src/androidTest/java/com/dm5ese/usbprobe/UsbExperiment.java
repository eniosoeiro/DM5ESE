package com.dm5ese.usbprobe;

import android.app.Instrumentation;
import android.content.Context;
import android.hardware.usb.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Separate test APK, never part of the distributed application.
 * Run only with explicit user authorization for physical creation/deletion tests.
 * Deletes only new names created and verified within this invocation.
 */
public final class UsbExperiment extends Instrumentation {
    private Bundle arguments;
    private PrintWriter audit;
    private final long started = SystemClock.elapsedRealtime();
    @Override public void onCreate(Bundle args) { arguments = args; start(); }
    private void log(String event, Object detail) throws Exception {
        JSONObject entry = new JSONObject().put("ms",SystemClock.elapsedRealtime()-started).put("event",event).put("detail",detail);
        audit.println(entry); audit.flush();
        Bundle update = new Bundle(); update.putString("stream", entry + "\n"); sendStatus(0,update);
    }
    private static Set<String> names(List<Dm5eProtocol.FileEntry> files) {
        Set<String> result = new TreeSet<>(); for (var file : files) result.add(file.name()); return result;
    }
    private final class Trace implements Dm5eProtocol.Link {
        final Dm5eUsb usb;
        boolean injected;
        boolean rejectDirectoryLine;
        int forcedDirectories;
        Trace(Dm5eUsb usb) { this.usb=usb; }
        private void record(String event, String data) throws IOException {
            try { log(event,data); } catch(Exception e) { throw new IOException(e); }
        }
        public void write(String s) throws IOException {
            if (s.equals("\u001bDR\r") && "READ_ONLY".equals(arguments.getString("authorization"))
                && arguments.containsKey("forceDirectoryFailure") && forcedDirectories++ < 3) rejectDirectoryLine=true;
            record("tx",s); usb.write(s);
        }
        public byte[] readLine() throws IOException {
            byte[] b=usb.readLine(); record("rx",Base64.getEncoder().encodeToString(b));
            if (rejectDirectoryLine) {
                rejectDirectoryLine=false;
                record("injected_directory_failure","Local test only");
                return "INVALID".getBytes(StandardCharsets.ISO_8859_1);
            }
            if (!injected && "READ_ONLY".equals(arguments.getString("authorization"))
                && arguments.containsKey("injectChecksum") && new String(b,StandardCharsets.ISO_8859_1).startsWith(
                    "data".equals(arguments.getString("injectChecksum")) ? "\u00ff\u00ff2\u00ff\u00ffC" : "[INSS]")) {
                injected=true; b=b.clone(); b[0]='!'; record("injected_corruption","Local test only; no altered bytes sent to instrument");
            }
            return b;
        }
        public void readAck() throws IOException { usb.readAck(); record("ack","CR"); }
        public void discardInput() throws IOException { usb.discardInput(); }
    }
    private void cleanupRecordedRun(Context ctx, UsbManager manager, UsbDevice device, String source) throws Exception {
        if(!source.matches("usb-experiment-[0-9]+\\.jsonl")) throw new IOException("Invalid audit path");
        Set<String> original=new TreeSet<>(); List<String> created=new ArrayList<>();
        try(BufferedReader reader=new BufferedReader(new FileReader(new File(ctx.getFilesDir(),source)))) {
            String line;
            while((line=reader.readLine())!=null) {
                JSONObject entry=new JSONObject(line);
                if(entry.getString("event").equals("baseline")) {
                    JSONArray a=entry.getJSONArray("detail"); for(int i=0;i<a.length();i++)original.add(a.getString(i));
                }
                if(entry.getString("event").equals("create_start")) created.add(entry.getString("detail"));
            }
        }
        if(original.isEmpty() || created.isEmpty() || created.size()>3) throw new IOException("Missing ownership evidence");
        for(String name:created) if(!name.matches("XT[A-Z0-9]{5,13}") || original.contains(name)) throw new IOException("Ownership guard");
        try(Dm5eUsb usb=new Dm5eUsb(manager,device,new AtomicBoolean())) {
            Trace trace=new Trace(usb); Dm5eProtocol protocol=new Dm5eProtocol(trace);
            Set<String> expected=names(protocol.directory());
            Set<String> extra=new TreeSet<>(expected); extra.removeAll(original);
            if(!expected.containsAll(original) || !new HashSet<>(created).containsAll(extra)) throw new IOException("Unexpected files; stop");
            log("cleanup_baseline",new JSONArray(expected));
            for(String name:created) {
                if(!expected.contains(name))continue;
                if(!names(protocol.directory()).equals(expected))throw new IOException("Directory changed");
                log("delete_start",name); trace.write("\u001bDF "+name+"\r"); usb.awaitDeletion();
                expected.remove(name);
                if(!names(protocol.directory()).equals(expected))throw new IOException("Deletion not confirmed; no retry");
                log("delete_verified",name);
            }
            if(!names(protocol.directory()).equals(original))throw new IOException("Final directory differs");
            log("PASS","Recorded run cleaned; original names preserved");
        }
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            if ("SYNC_UX_TEST".equals(arguments.getString("authorization"))) {
                int passed=SyncSelectionUiChecks.run(this);
                result.putString("stream", "PASS: " + passed + " selector UX checks; no network, USB or saved capture changes");
                finish(0,result); return;
            }
            if ("SESSION_HASH_TEST".equals(arguments.getString("authorization"))) {
                int passed=SessionPersistenceChecks.run(this);
                result.putString("stream","PASS: "+passed+" session/hash checks; isolated Keystore and fake HTTP, no real login or uploads");
                finish(0,result);return;
            }
            if ("SYNC_LOCAL_TEST".equals(arguments.getString("authorization"))) {
                int passed=CloudSyncChecks.run(this);
                result.putString("stream", "PASS: " + passed + " isolated cloud-sync checks; synthetic transport, no USB or production writes");
                finish(0,result); return;
            }
            if ("RESTORE_VAC_BACKUP".equals(arguments.getString("authorization"))) {
                RestoreVac.run(this,arguments.getString("backupSha256"));
                result.putString("stream","PASS: VAC-103007 restored/verified, 16 original records; no deletion");
                finish(0,result); return;
            }
            if ("LOCAL_CELL_TEST".equals(arguments.getString("authorization"))) {
                CellWorkflowChecks.run(this);
                result.putString("stream", "PASS: cell preview, uncoupled/stale guards, selection, empty save, cancel/confirm replacement, persistence, source preserved, stop; synthetic data, no USB");
                finish(0,result); return;
            }
            boolean readOnly = "READ_ONLY".equals(arguments.getString("authorization"));
            boolean cellProbe = "CREATE_AND_WRITE_NEW_ONLY".equals(arguments.getString("authorization"));
            boolean writerTest = "OWNED_CELL_WRITER_TEST".equals(arguments.getString("authorization"));
            if (!readOnly && !cellProbe && !writerTest && !"CREATE_AND_DELETE_NEW_ONLY".equals(arguments.getString("authorization"))) throw new IOException("Missing explicit test authorization");
            Context ctx=getTargetContext();
            File file=new File(ctx.getFilesDir(),"usb-experiment-"+System.currentTimeMillis()+".jsonl");
            audit=new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
            UsbManager manager=(UsbManager)ctx.getSystemService(Context.USB_SERVICE);
            List<UsbDevice> devices=new ArrayList<>();
            for (UsbDevice d:manager.getDeviceList().values()) if(d.getVendorId()==0xc251 && d.getProductId()==0x1705) devices.add(d);
            if(devices.size()!=1 || !manager.hasPermission(devices.get(0))) throw new IOException("Exactly one authorized DM5E required");
            UsbDevice device=devices.get(0);
            boolean continuous = "continuous".equals(arguments.getString("mode"));
            String prefix="XT"+Long.toString(System.currentTimeMillis(),36).toUpperCase(Locale.ROOT);
            Set<String> baseline;
            List<String> owned=new ArrayList<>();
            log("device",device.getDeviceName()); log("audit_file",file.getName());
            if(writerTest) {
                String ownedFixture="XWMU55A8O7";
                File prior=new File(ctx.getFilesDir(),"usb-experiment-1789626286888.jsonl");
                String evidence=new String(java.nio.file.Files.readAllBytes(prior.toPath()),StandardCharsets.UTF_8);
                if(!evidence.contains("\"event\":\"create_start\",\"detail\":\""+ownedFixture+"\""))throw new IOException("Ownership evidence missing");
                try(Dm5eUsb usb=new Dm5eUsb(manager,device,new AtomicBoolean())) {
                    Trace trace=new Trace(usb); Dm5eProtocol p=new Dm5eProtocol(trace);
                    var entry=p.directory().stream().filter(f->f.name().equals(ownedFixture)).findFirst().orElseThrow();
                    var current=p.download(entry);
                    if(current.readings().size()!=4 || current.readings().subList(0,3).stream().anyMatch(r->!r.value().isEmpty()) || !current.readings().get(3).value().equals("13.02"))throw new IOException("ownedFixture fixture no longer empty; no replay");
                    for(int n=0;n<3;n++) {
                        String position=current.readings().get(n).position();
                        current=Dm5eCellWriter.write(trace,current,position,new java.math.BigDecimal(new String[]{"13.123","14.234","15.345"}[n]),new java.math.BigDecimal("5996.8"), c->log("backup_raw_frames",new JSONArray(c.rawFrames())));
                        log("cell_verified",position+"="+current.readings().get(n).value());
                    }
                    log("PASS","Three isolated cell writes verified on ownedFixture fixture; unmeasured auxiliary fields FF; "+ownedFixture);
                }
                result.putString("stream","PASS writer: "+file.getName()); finish(0,result); return;
            }
            if (cellProbe) {
                String name = "XW" + Long.toString(System.currentTimeMillis(),36).toUpperCase(Locale.ROOT);
                boolean repair = arguments.containsKey("cellRepairAudit");
                Set<String> repairBaseline = new TreeSet<>();
                if (repair) {
                    String source = arguments.getString("cellRepairAudit");
                    if (!source.matches("usb-experiment-[0-9]+\\.jsonl")) throw new IOException("Invalid audit path");
                    String verified = null;
                    try (BufferedReader input = new BufferedReader(new FileReader(new File(ctx.getFilesDir(),source)))) {
                        String line;
                        while ((line=input.readLine())!=null) {
                            JSONObject event=new JSONObject(line);
                            if(event.getString("event").equals("create_verified")) verified=event.getString("detail");
                            if(event.getString("event").equals("baseline")) {
                                JSONArray a=event.getJSONArray("detail"); for(int n=0;n<a.length();n++)repairBaseline.add(a.getString(n));
                            }
                        }
                    }
                    if(verified==null || !verified.matches("XW[A-Z0-9]{5,13}") || repairBaseline.isEmpty() || repairBaseline.contains(verified))throw new IOException("Ownership evidence missing");
                    name=verified;
                }
                String reference = null;
                CaptureStore captures = new CaptureStore(ctx);
                for (File saved : captures.history()) {
                    JSONObject payload = captures.load(saved); JSONArray points = payload.getJSONArray("readings");
                    for (int j=0;j<points.length();j++) {
                        JSONObject p=points.getJSONObject(j);
                        if ("mm".equals(p.optString("unit")) && !p.optString("valueDecimal").isEmpty() && p.has("rawRecordBase64")) {
                            String candidate = new String(Base64.getDecoder().decode(p.getString("rawRecordBase64")), StandardCharsets.ISO_8859_1);
                            if (candidate.length()==39 && candidate.charAt(20)=='M') { reference=candidate; break; }
                        }
                    }
                    if(reference!=null)break;
                }
                if(reference==null)throw new IOException("No original reference record available");
                String record="\u00ff\u00ff2\u00ff\u00ffB"+reference.substring(6);
                if(record.indexOf(26)>=0)throw new IOException("Terminator in record");
                try(Dm5eUsb usb=new Dm5eUsb(manager,device,new AtomicBoolean())) {
                    Trace trace=new Trace(usb); Dm5eProtocol protocol=new Dm5eProtocol(trace);
                    Set<String> original=names(protocol.directory()); log("baseline",new JSONArray(original));
                    Dm5eProtocol.Capture empty;
                    if (repair) {
                        Set<String> expectedNames=new TreeSet<>(repairBaseline); expectedNames.add(name);
                        if(!original.equals(expectedNames))throw new IOException("Directory differs from owned test baseline");
                        final String ownedName=name;
                        Dm5eProtocol.FileEntry entry=protocol.directory().stream().filter(f->f.name().equals(ownedName)).findFirst().orElseThrow();
                        List<Dm5eProtocol.Reading> blank=new ArrayList<>();
                        List<String> records=new Dm5eProtocol.NewGrid(name,2,2).records();
                        for(int n=0;n<4;n++) blank.add(new Dm5eProtocol.Reading((n/2+1)+Dm5eProtocol.alpha(n%2+1),"","mm",records.get(7+n)));
                        empty=new Dm5eProtocol.Capture(entry,Map.of(),blank,List.of());
                        original.remove(name); log("repair_owned_test",name);
                    } else {
                        if(original.contains(name))throw new IOException("Name collision");
                        log("create_start",name);
                        empty=protocol.create(new Dm5eProtocol.NewGrid(name,2,2)); log("create_verified",name);
                    }
                    log("test_record_copied_not_new_measurement",Base64.getEncoder().encodeToString(record.getBytes(StandardCharsets.ISO_8859_1)));
                    // Only this invocation's freshly created and downloaded empty grid can be opened.
                    trace.write("\u001bFO "+name+"\r"); trace.readAck();
                    trace.write("\u001bFS " + (repair ? 83 : 206) + "\r"); trace.readAck();
                    trace.write("\u001bFW\r"); trace.readAck();
                    String payload=record+"\r\n";
                    if (repair) payload=empty.readings().get(0).raw()+"\r\n"+empty.readings().get(1).raw()+"\r\n"+empty.readings().get(2).raw()+"\r\n"+payload;
                    trace.write(payload+"\u001a");
                    String report=new String(trace.readLine(),StandardCharsets.ISO_8859_1); log("write_report",report);
                    trace.write("\u001bFC\r"); trace.readAck();
                    usb.discardInput();
                    Dm5eProtocol.Capture after=protocol.download(empty.file());
                    for(int j=0;j<4;j++) {
                        String expected=j==3?record:empty.readings().get(j).raw();
                        if(!expected.equals(after.readings().get(j).raw()))throw new IOException("Readback differs at "+j);
                    }
                    Set<String> expected=new TreeSet<>(original); expected.add(name);
                    if(!names(protocol.directory()).equals(expected))throw new IOException("Directory changed");
                    log("PASS","Physical 2B write verified, other three points unchanged; no deletion. Test file: "+name);
                }
                result.putString("stream","PASS physical cell test: "+file.getName()); finish(0,result); return;
            }
            if(readOnly && "repeat".equals(arguments.getString("mode"))) {
                Set<String> expected=null;
                int passed=0;
                for(int i=0;i<10;i++) {
                    try(Dm5eUsb usb=new Dm5eUsb(manager,device,new AtomicBoolean())) {
                        Set<String> current=names(new Dm5eProtocol(usb).directory());
                        if(expected==null)expected=current;
                        if(!expected.equals(current))throw new IOException("Directory changed");
                        passed++; log("read_ok",new JSONArray(current));
                    } catch(IOException e) { log("read_failed",android.util.Log.getStackTraceString(e)); }
                    SystemClock.sleep(500);
                }
                log("summary",passed+"/10 read-only connections passed");
                result.putString("stream",passed+"/10 read-only connections: "+file.getName()); finish(0,result); return;
            }
            if(readOnly) {
                try(Dm5eUsb usb=new Dm5eUsb(manager,device,new AtomicBoolean())) {
                    Dm5eProtocol protocol=new Dm5eProtocol(new Trace(usb));
                    Set<String> first=names(protocol.directoryWithRecovery(message -> {
                        try { log("directory_progress",message); } catch(Exception e) { throw new RuntimeException(e); }
                    })); log("directory_read_1",new JSONArray(first));
                    if(arguments.containsKey("downloadFile")) {
                        String name=arguments.getString("downloadFile");
                        var entry=protocol.directory().stream().filter(f->f.name().equals(name)).findFirst().orElseThrow();
                        var downloaded=protocol.importFile(entry, message -> {
                            try { log("import_progress",message); } catch(Exception e) { throw new RuntimeException(e); }
                        });
                        log("download_verified",name+": "+downloaded.readings().size()+" points");
                    }
                    if(arguments.containsKey("verifyCellTest")) {
                        String name=arguments.getString("verifyCellTest");
                        if(!name.matches("XW[A-Z0-9]{5,13}"))throw new IOException("Not a cell-test name");
                        Dm5eProtocol.FileEntry entry=protocol.directory().stream().filter(f->f.name().equals(name)).findFirst().orElseThrow();
                        Dm5eProtocol.Capture saved=protocol.download(entry);
                        if(saved.readings().size()!=4)throw new IOException("Wrong test grid");
                        for(int j=0;j<4;j++) {
                            Dm5eProtocol.Reading point=saved.readings().get(j);
                            log("persisted_point",point.position()+"="+point.value());
                            if(!point.value().equals(j==3?"13.02":""))throw new IOException("Persisted cell differs");
                        }
                        log("cell_persistence_verified",name);
                    }
                    SystemClock.sleep(1000);
                    Set<String> second=names(protocol.directory()); log("directory_read_2",new JSONArray(second));
                    if(!first.equals(second))throw new IOException("Read-only directory mismatch");
                    log("PASS","Read-only: two matching directories; "+first.size()+" files");
                }
                result.putString("stream","PASS read-only: "+file.getName()); finish(0,result); return;
            }
            if(arguments.containsKey("cleanupAudit")) {
                cleanupRecordedRun(ctx,manager,device,arguments.getString("cleanupAudit"));
                result.putString("stream","PASS cleanup: "+file.getName()); finish(0,result); return;
            }
            log("mode",continuous ? "v0.8.3 sequence without extra pauses" : "extra responsiveness probes and 1500 ms between deletions");
            try(Dm5eUsb usb=new Dm5eUsb(manager,device,new AtomicBoolean())) {
                Dm5eProtocol protocol=new Dm5eProtocol(new Trace(usb));
                baseline=names(protocol.directory()); log("baseline",new JSONArray(baseline));
                for(int i=0;i<3;i++) {
                    String name=prefix+(char)('A'+i);
                    if(baseline.contains(name)) throw new IOException("Name collision");
                    log("create_start",name);
                    Dm5eProtocol.Capture c=protocol.create(new Dm5eProtocol.NewGrid(name,2,2));
                    if(c.readings().size()!=4 || c.readings().stream().anyMatch(r->!r.value().isEmpty())) throw new IOException("Unexpected test content");
                    owned.add(name); log("create_verified",name);
                }
            }
            // First two emulate one batch/connection; third opens a separate connection.
            Set<String> expected=new TreeSet<>(baseline); expected.addAll(owned);
            for(int session=0;session<2;session++) {
                try(Dm5eUsb usb=new Dm5eUsb(manager,device,new AtomicBoolean())) {
                    Trace trace=new Trace(usb); Dm5eProtocol protocol=new Dm5eProtocol(trace);
                    for(int i=session==0?0:2;i<(session==0?2:3);i++) {
                        String name=owned.get(i);
                        if(baseline.contains(name) || !name.startsWith(prefix)) throw new IOException("Deletion guard");
                        if(!names(protocol.directory()).equals(expected)) throw new IOException("Directory changed before deletion");
                        log("delete_start",name);
                        trace.write("\u001bDF "+name+"\r");
                        // Exactly the v0.8.3 wait and flush. Stop the whole experiment on first fault.
                        usb.awaitDeletion();
                        if(!continuous) protocol.identify();
                        expected.remove(name);
                        if(!names(protocol.directory()).equals(expected)) throw new IOException("Unexpected directory after deletion");
                        log("delete_verified",name);
                        if(!continuous) {
                            SystemClock.sleep(1500);
                            protocol.identify(); log("responsive_after_delete",name);
                        }
                    }
                }
                log("connection_closed",session);
            }
            try(Dm5eUsb usb=new Dm5eUsb(manager,device,new AtomicBoolean())) {
                if(!names(new Dm5eProtocol(new Trace(usb)).directory()).equals(baseline)) throw new IOException("Final directory differs");
            }
            log("PASS","Three new empty grids deleted; original names preserved; reopened connection verified");
            result.putString("stream","PASS: "+file.getName()); finish(0,result);
        } catch(Exception e) {
            try { if(audit!=null)log("STOP",e.toString()); } catch(Exception ignored) { }
            result.putString("stream","STOP (no destructive retries): "+e); finish(1,result);
        } finally { if(audit!=null)audit.close(); }
    }
}
