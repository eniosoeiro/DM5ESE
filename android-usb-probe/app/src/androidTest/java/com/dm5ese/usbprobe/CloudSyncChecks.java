package com.dm5ese.usbprobe;
import android.app.Instrumentation;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Isolated synthetic transport tests: no login, USB or production writes. */
final class CloudSyncChecks {
    static int passed;
    static final String OWNER="11111111-1111-4111-8111-111111111111", PARTNER="22222222-2222-4222-8222-222222222222", OTHER="33333333-3333-4333-8333-333333333333";
    interface Work { void run() throws Exception; }
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);passed++;}
    static void refuses(Work work,String label)throws Exception{try{work.run();}catch(Exception expected){passed++;return;}throw new AssertionError(label);}
    static JSONObject sample()throws Exception{
        String id=UUID.randomUUID().toString();
        return new JSONObject().put("schemaVersion",1).put("captureId",id).put("lineageId",id).put("file","SYNTHETIC_SYNC_TEST").put("source","android_usb")
            .put("readings",new JSONArray().put(new JSONObject().put("position","5AA").put("unit","mm").put("state","OK").put("valueDecimal","12.345000"))
                .put(new JSONObject().put("position","5AB").put("unit","mm").put("state","EMPTY").put("valueDecimal","")));
    }
    static JSONObject receipt()throws Exception{return new JSONObject().put("id",UUID.randomUUID().toString()).put("receipt","received").put("contentSha256","b".repeat(64)).put("receivedAt",Instant.now().toString());}
    static int run(Instrumentation instrumentation)throws Exception{
        passed=0;File dir=Files.createTempDirectory(instrumentation.getTargetContext().getCacheDir().toPath(),"cloud-sync-check-").toFile();
        try{
            JSONObject source=sample();String before=source.toString();JSONObject envelope=ThicknessSyncSnapshot.envelope(source);
            check(before.equals(source.toString()),"snapshot immutable");
            check(envelope.getJSONObject("snapshot").getJSONArray("readings").getJSONObject(0).getString("valueDecimal").equals("12.345000"),"precision retained");
            check(envelope.getJSONObject("snapshot").getJSONArray("readings").getJSONObject(1).getString("valueDecimal").isEmpty(),"empty retained");
            JSONObject bad=new JSONObject(before);bad.getJSONArray("readings").getJSONObject(0).put("unit","inch");refuses(()->ThicknessSyncSnapshot.envelope(bad),"unit");
            JSONObject decimal=new JSONObject(before);decimal.getJSONArray("readings").getJSONObject(0).put("valueDecimal","1,23");refuses(()->ThicknessSyncSnapshot.envelope(decimal),"decimal");
            JSONObject empty=new JSONObject(before);empty.getJSONArray("readings").getJSONObject(1).put("valueDecimal","0");refuses(()->ThicknessSyncSnapshot.envelope(empty),"empty cannot be zero");
            JSONObject duplicate=new JSONObject(before);duplicate.getJSONArray("readings").getJSONObject(1).put("position","5AA");refuses(()->ThicknessSyncSnapshot.envelope(duplicate),"duplicate");
            JSONObject huge=new JSONObject(before).put("rawEvidence","x".repeat(ThicknessSyncRules.MAX_BYTES));refuses(()->ThicknessSyncSnapshot.envelope(huge),"bounded payload");
            ThicknessSyncQueue q=new ThicknessSyncQueue(dir);q.setAccount(OWNER,PARTNER);
            JSONObject selected=q.enqueue(OWNER,PARTNER,source,false);check(new File(dir,source.getString("captureId")+".json").isFile(),"queued before network");
            JSONObject sent=q.begin(selected,100);check(sent!=null,"starts eligible");check(q.begin(selected,101)==null,"no parallel resend");
            ThicknessSyncQueue reopened=new ThicknessSyncQueue(dir);check(reopened.begin(selected,120099)==null,"interruption grace");
            JSONObject recovered=reopened.begin(selected,120100);check(recovered!=null,"resume after interruption");
            q.failure(recovered,new IOException("not printed"),120101);check(q.begin(selected,120102)==null,"backoff respected");
            JSONObject retry=q.begin(selected,124101);check(retry!=null,"retry due");
            q.receipt(retry,receipt());check("RECEIVED".equals(q.statusForLastAccount(source)),"receipt durable");
            q.failure(retry,new IOException("late failure"),200000);check("RECEIVED".equals(q.statusForLastAccount(source)),"late failure cannot clear ACK");
            check(q.begin(selected,999999)==null,"received never resent");
            check("RECEIVED".equals(new ThicknessSyncQueue(dir).enqueue(OWNER,PARTNER,source,false).getString("state")),"reopen retains receipt");
            refuses(()->q.enqueue(OTHER,PARTNER,source,true),"other owner denied");refuses(()->q.enqueue(OWNER,OTHER,source,true),"other partner denied");
            JSONObject altered=new JSONObject(before);altered.getJSONArray("readings").getJSONObject(0).put("valueDecimal","9");refuses(()->q.enqueue(OWNER,PARTNER,altered,true),"same revision modified");
            JSONObject newer=new JSONObject(before).put("captureId",UUID.randomUUID().toString());q.enqueue(OWNER,PARTNER,newer,false);check("PENDING".equals(q.statusForLastAccount(newer)),"new revision remains pending");
            q.setAccount(OTHER,PARTNER);check("OTHER_ACCOUNT".equals(q.statusForLastAccount(source)),"badge scoped to account");q.setAccount(OWNER,PARTNER);
            JSONObject failed=q.begin(q.enqueue(OWNER,PARTNER,newer,false),1);q.failure(failed,ThicknessSyncRules.httpFailure(403),2);
            check("FAILED".equals(q.statusForLastAccount(newer)),"auth failure no retry");check(q.begin(failed,999999)==null,"failed no automatic retry");
            JSONObject reset=q.enqueue(OWNER,PARTNER,newer,true);check(q.begin(reset,999999)!=null,"explicit selected retry");
            refuses(()->q.receipt(reset,new JSONObject().put("id","bad")),"invalid receipt");
            JSONObject max=sample(), task=q.enqueue(OWNER,PARTNER,max,false);long now=1000;
            for(int i=0;i<5;i++){JSONObject attempt=q.begin(task,now);check(attempt!=null,"attempt "+i);q.failure(attempt,new IOException(),now);now+=400000;}
            check("FAILED".equals(q.statusForLastAccount(max)),"five attempts bounded");
            JSONObject interrupted=sample(), last=q.enqueue(OWNER,PARTNER,interrupted,false);long tick=1;
            for(int i=0;i<5;i++){JSONObject attempt=q.begin(last,tick);check(attempt!=null,"interrupted attempt "+i);if(i<4)q.failure(attempt,new IOException(),tick);tick+=400000;}
            JSONObject resumed=q.enqueue(OWNER,PARTNER,interrupted,true);
            check(q.begin(resumed,System.currentTimeMillis())!=null,"manual recovery after final interrupted attempt");
            class Fake implements ThicknessSyncClient.Transport{
                final List<String> calls=new ArrayList<>();JSONObject receivedEnvelope;boolean wrongReceipt;
                public String request(String method,String path,JSONObject body,String token)throws Exception{
                    calls.add(method+" "+path);
                    if(path.startsWith("/auth/v1/token"))return "{\"access_token\":\"synthetic-session\"}";
                    if(path.equals("/auth/v1/user"))return new JSONObject().put("id",OWNER).toString();
                    if(path.contains("mobile_access_status"))return "{\"allowed\":true}";
                    if(path.startsWith("/rest/v1/profiles"))return new JSONArray().put(new JSONObject().put("parceiro_id",PARTNER)).toString();
                    if(path.contains("receive_es_thickness_capture")){receivedEnvelope=body.getJSONObject("p_envelope");return receipt().toString();}
                    if(path.startsWith("/rest/v1/es_thickness_captures"))return new JSONArray().put(new JSONObject().put("revision_id",wrongReceipt?OTHER:receivedEnvelope.getString("revisionId"))
                        .put("lineage_id",receivedEnvelope.getString("lineageId")).put("client_content_sha256",receivedEnvelope.getString("contentSha256")).put("content_sha256","b".repeat(64))).toString();
                    if(path.equals("/auth/v1/logout?scope=local"))return "";
                    throw new IOException("Unexpected fake request");
                }
            }
            Fake fake=new Fake();try(ThicknessSyncClient c=new ThicknessSyncClient(ThicknessSyncRules.PRODUCTION,"sb_publishable_synthetic",fake)){
                c.login("synthetic@example.invalid","synthetic-password");c.send(source);check(before.equals(source.toString()),"client preserves snapshot");
                fake.wrongReceipt=true;refuses(()->c.send(source),"mismatched cloud receipt");
            }
            check(fake.calls.stream().noneMatch(s->s.contains("claim_mobile_session")),"no acquisition lease takeover");
            check(fake.calls.get(fake.calls.size()-1).endsWith("logout?scope=local"),"logout this session only");
            check(!new ThicknessSyncClient("http://localhost","sb_publishable_synthetic",fake).configured(),"https backend bound");
            check(!new ThicknessSyncClient(ThicknessSyncRules.PRODUCTION,"sb_secret_synthetic",fake).configured(),"admin key rejected");
            ThicknessSyncClient cancelled=new ThicknessSyncClient(ThicknessSyncRules.PRODUCTION,"sb_publishable_synthetic",fake);cancelled.cancel();int callCount=fake.calls.size();
            refuses(()->cancelled.login("synthetic@example.invalid","synthetic-password"),"cancel before request");check(fake.calls.size()==callCount,"cancelled no network");
            HttpURLConnection connection=new HttpURLConnection(new URL("https://example.invalid")){
                public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
            };
            ThicknessSyncClient.configureHeaders(connection,"public-test","synthetic-session","/rest/v1/rpc/receive_es_thickness_capture");
            check("nr13".equals(connection.getRequestProperty("Content-Profile")),"write schema header");
            check("nr13".equals(connection.getRequestProperty("Accept-Profile")),"read schema header");
            android.content.ContextWrapper isolated=new android.content.ContextWrapper(instrumentation.getTargetContext()){
                @Override public File getFilesDir(){return new File(dir,"isolated-files");}
            };
            CaptureStore store=new CaptureStore(isolated);JSONObject draft=store.createDraft("SYNCTEST",1,1,null);
            JSONObject measured=store.saveManualCell(draft,"1A",new java.math.BigDecimal("5.123"));
            check(draft.getString("lineageId").equals(measured.getString("lineageId")),"manual revision keeps lineage");
            check("manual_local".equals(measured.getJSONArray("readings").getJSONObject(0).getString("source")),"manual origin retained");
            check(!draft.getString("captureId").equals(measured.getString("captureId")),"new revision unique");
            var physical=new Dm5eProtocol.Capture(new Dm5eProtocol.FileEntry(1,"SYNCTEST"),Map.of(),List.of(new Dm5eProtocol.Reading("1A","5.123","mm","synthetic")),List.of());
            JSONObject confirmed=store.markDraftSent(measured,physical);
            check(draft.getString("lineageId").equals(confirmed.getString("lineageId")),"instrument confirmation keeps lineage");
            return passed;
        }finally{
            // Only this invocation's randomly named cache fixture directory is removed.
            try(var paths=Files.walk(dir.toPath())){
                paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});
            }
        }
    }
    private CloudSyncChecks(){}
}
