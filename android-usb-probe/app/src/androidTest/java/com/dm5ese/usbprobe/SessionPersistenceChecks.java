package com.dm5ese.usbprobe;
import android.app.Instrumentation;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.*;

/** Uses only synthetic credentials and an isolated Keystore alias/file. No remote network. */
final class SessionPersistenceChecks {
    static int passed;
    interface Work{void run()throws Exception;}
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);passed++;}
    static void refuses(Work work,String name)throws Exception{try{work.run();}catch(Exception e){passed++;return;}throw new AssertionError(name);}
    static final String USER="11111111-1111-4111-8111-111111111111",PARTNER="22222222-2222-4222-8222-222222222222",OTHER="33333333-3333-4333-8333-333333333333";
    static JSONObject saved()throws Exception{return new JSONObject().put("origin",ThicknessSyncRules.PRODUCTION).put("email","session-test@example.invalid")
        .put("userId",USER).put("partnerId",PARTNER).put("accessToken","synthetic-access-one").put("refreshToken","synthetic-refresh-one").put("expiresAt",System.currentTimeMillis()+3600000);}
    static final class Memory implements ThicknessSyncClient.SessionStore{
        JSONObject value;int writes,clears;
        public JSONObject load()throws Exception{return value==null?null:new JSONObject(value.toString());}
        public void save(JSONObject next)throws Exception{value=ThicknessSessionStore.validated(next);writes++;}
        public void clear(){value=null;clears++;}
    }
    static final class Fake implements ThicknessSyncClient.Transport{
        int logins,refreshes,logouts;boolean failRefresh,offline;String partner=PARTNER,returnedUser=USER;
        Memory memory;JSONObject incoming,lastReceipt,lastRequest;String previewStatus="new";
        public String request(String method,String path,JSONObject payload,String token)throws Exception{
            if(offline)throw new IOException("synthetic offline");
            if(path.contains("grant_type=password")){logins++;return tokenPair(false);}
            if(path.contains("grant_type=refresh_token")){
                refreshes++;if(failRefresh)throw new ThicknessSyncRules.Failure("synthetic rejected",false,401);return tokenPair(true);
            }
            if(path.equals("/auth/v1/user"))return new JSONObject().put("id",returnedUser).toString();
            if(path.contains("mobile_access_status"))return "{\"allowed\":true}";
            if(path.startsWith("/rest/v1/profiles"))return new JSONArray().put(new JSONObject().put("parceiro_id",partner)).toString();
            if(path.equals("/auth/v1/logout?scope=local")){logouts++;return "";}
            if(path.contains("preview_es_thickness_capture"))return new JSONObject().put("status",previewStatus).put("fileSha256","f".repeat(64))
                .put("existingId",OTHER).put("existingHash","e".repeat(64)).toString();
            if(path.contains("receive_es_thickness_capture_v2")){
                lastRequest=payload;incoming=payload.getJSONObject("p_envelope");lastReceipt=new JSONObject().put("id",OTHER).put("receipt","already_received")
                    .put("receivedAt","2026-09-19T18:00:00Z").put("contentSha256","b".repeat(64)).put("fileSha256","f".repeat(64))
                    .put("requestSha256","d".repeat(64)).put("requestRevisionId",incoming.getString("revisionId"))
                    .put("requestLineageId",incoming.getString("lineageId")).put("requestClientSha256",incoming.getString("contentSha256"));return lastReceipt.toString();
            }
            if(path.startsWith("/rest/v1/es_thickness_upload_receipts"))return new JSONArray().put(new JSONObject().put("capture_id",OTHER)
                .put("revision_id",incoming.getString("revisionId")).put("lineage_id",incoming.getString("lineageId"))
                .put("request_sha256","d".repeat(64)).put("client_sha256",incoming.getString("contentSha256"))).toString();
            if(path.startsWith("/rest/v1/es_thickness_captures"))return new JSONArray().put(new JSONObject().put("content_sha256","b".repeat(64))
                .put("file_sha256","f".repeat(64)).put("is_current",true)).toString();
            throw new IOException("Unexpected synthetic request");
        }
        private String tokenPair(boolean fresh)throws Exception{return new JSONObject().put("access_token",fresh?"synthetic-access-two":"synthetic-access-one")
            .put("refresh_token",fresh?"synthetic-refresh-two":"synthetic-refresh-one").put("expires_in",3600).put("user",new JSONObject().put("id",returnedUser)).toString();}
    }
    static int run(Instrumentation instrumentation)throws Exception{
        passed=0;File dir=Files.createTempDirectory(instrumentation.getTargetContext().getCacheDir().toPath(),"session-check-").toFile();
        String alias="es.medicao.test."+UUID.randomUUID();File file=new File(dir,"test-session.bin");
        try{
            ThicknessSessionStore store=new ThicknessSessionStore(file,alias);
            check(store.load()==null,"fresh store empty");JSONObject source=saved().put("password","do-not-persist-this");
            store.save(source);byte[] first=Files.readAllBytes(file.toPath());String encoded=new String(first,java.nio.charset.StandardCharsets.ISO_8859_1);
            for(String sensitive:new String[]{"synthetic-access-one","synthetic-refresh-one","session-test@example.invalid","do-not-persist-this"})check(!encoded.contains(sensitive),"ciphertext hides "+sensitive);
            JSONObject read=store.load();check(!read.has("password"),"password field excluded");check(read.getString("refreshToken").equals(source.getString("refreshToken")),"decrypt roundtrip");
            store.save(source);byte[] second=Files.readAllBytes(file.toPath());check(!Arrays.equals(first,second),"random IV per save");
            check(new ThicknessSessionStore(file,alias).load().getString("userId").equals(USER),"session survives instance recreation");
            second[second.length-1]^=1;Files.write(file.toPath(),second);refuses(()->store.load(),"tamper fails authentication");
            store.clear();check(store.load()==null,"forget removes encrypted record");
            refuses(()->store.save(saved().put("origin","https://example.invalid")),"wrong backend refused");
            refuses(()->store.save(saved().put("expiresAt",0)),"invalid expiry refused");
            Memory memory=new Memory();Fake fake=new Fake();
            try(ThicknessSyncClient client=new ThicknessSyncClient(ThicknessSyncRules.PRODUCTION,"sb_publishable_test",fake)){
                client.login("session-test@example.invalid","synthetic-only");client.remember(memory);
            }
            check(fake.logins==1&&fake.logouts==0,"remembered session not logged out after upload");
            check(!memory.value.has("password"),"client never persists password");
            try(ThicknessSyncClient client=new ThicknessSyncClient(ThicknessSyncRules.PRODUCTION,"sb_publishable_test",fake)){client.restore(memory.load(),memory);}
            check(fake.logins==1&&fake.refreshes==0&&fake.logouts==0,"resume without password login");
            memory.value.put("expiresAt",1);
            try(ThicknessSyncClient client=new ThicknessSyncClient(ThicknessSyncRules.PRODUCTION,"sb_publishable_test",fake)){client.restore(memory.load(),memory);}
            check(fake.refreshes==1,"expired access refreshes once");check(memory.value.getString("refreshToken").equals("synthetic-refresh-two"),"rotated refresh saved");
            check(memory.value.getLong("expiresAt")>System.currentTimeMillis(),"new expiry saved");
            fake.offline=true;
            try(ThicknessSyncClient client=new ThicknessSyncClient(ThicknessSyncRules.PRODUCTION,"sb_publishable_test",fake)){
                refuses(()->client.restore(memory.load(),memory),"offline restore fails safely");
            }
            check(memory.value!=null,"offline does not discard saved connection");fake.offline=false;fake.partner=OTHER;
            try(ThicknessSyncClient client=new ThicknessSyncClient(ThicknessSyncRules.PRODUCTION,"sb_publishable_test",fake)){
                refuses(()->client.restore(memory.load(),memory),"partner change denied");
            }
            fake.partner=PARTNER;fake.returnedUser=OTHER;
            try(ThicknessSyncClient client=new ThicknessSyncClient(ThicknessSyncRules.PRODUCTION,"sb_publishable_test",fake)){
                refuses(()->client.restore(memory.load(),memory),"user change denied");
            }
            fake.returnedUser=USER;fake.failRefresh=true;memory.value.put("expiresAt",1);
            try(ThicknessSyncClient client=new ThicknessSyncClient(ThicknessSyncRules.PRODUCTION,"sb_publishable_test",fake)){
                refuses(()->client.restore(memory.load(),memory),"revoked refresh denied");
            }
            check(memory.value==null,"invalid saved connection cleared");fake.failRefresh=false;
            try(ThicknessSyncClient client=new ThicknessSyncClient(ThicknessSyncRules.PRODUCTION,"sb_publishable_test",fake)){
                client.login("session-test@example.invalid","synthetic-only");
                fake.previewStatus="duplicate";JSONObject snapshot=CloudSyncChecks.sample();JSONObject preview=client.preview(snapshot);
                JSONObject confirmed=client.send(snapshot,preview);
                check(fake.lastRequest.getBoolean("p_overwrite"),"explicit replacement option sent");
                check(OTHER.equals(fake.lastRequest.getString("p_existing_id")),"expected file identity bound");
                check("e".repeat(64).equals(fake.lastRequest.getString("p_expected_hash")),"expected hash bound");
                check("f".repeat(64).equals(confirmed.getString("fileSha256")),"server hash returned to Android");
                fake.previewStatus="locked";JSONObject locked=client.preview(snapshot);JSONObject before=fake.lastRequest;
                refuses(()->client.send(snapshot,locked),"locked preview cannot upload");check(before==fake.lastRequest,"locked file has no write request");
                Memory forget=new Memory();client.remember(forget);JSONObject stored=forget.load();client.disconnectSaved(stored,forget);
                check(forget.value==null,"explicit forget clears device session");
            }
            check(fake.logouts==1,"disconnect only this auth session");
            Memory offlineForget=new Memory();offlineForget.save(saved());fake.offline=true;
            try(ThicknessSyncClient client=new ThicknessSyncClient(ThicknessSyncRules.PRODUCTION,"sb_publishable_test",fake)){client.disconnectSaved(offlineForget.load(),offlineForget);}
            check(offlineForget.value==null,"offline forget still clears device session");
            return passed;
        }finally{
            try(var paths=Files.walk(dir.toPath())){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);if(keys.containsAlias(alias))keys.deleteEntry(alias);
        }
    }
    private SessionPersistenceChecks(){}
}
