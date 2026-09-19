package com.dm5ese.usbprobe;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Explicit HTTPS transfers, optional encrypted session and confirmed file replacement. */
final class ThicknessSyncClient implements AutoCloseable {
    interface Transport { String request(String method,String path,JSONObject payload,String token)throws Exception; }
    interface SessionStore { JSONObject load()throws Exception;void save(JSONObject value)throws Exception;void clear()throws Exception; }
    private final String base,key;
    private final Transport transport;
    private String token,refreshToken,userId,partnerId,emailAddress;
    private long expiresAt;
    private SessionStore sessionStore;
    private boolean keepSession;
    private volatile HttpURLConnection active;
    private volatile boolean cancelled;
    ThicknessSyncClient(){this(BuildConfig.ES_SYNC_URL,BuildConfig.ES_SYNC_PUBLISHABLE_KEY,null);}
    ThicknessSyncClient(String base,String key,Transport testTransport){
        this.base=base.replaceAll("/$","");this.key=key;
        transport=testTransport==null?this::http:testTransport;
    }
    String userId(){return userId;}
    String partnerId(){return partnerId;}
    boolean configured(){
        if(!ThicknessSyncRules.PRODUCTION.equals(base))return false;
        if(key.startsWith("sb_publishable_"))return true;
        try{return "anon".equals(new JSONObject(new String(java.util.Base64.getUrlDecoder().decode(key.split("\\.")[1]),StandardCharsets.UTF_8)).optString("role"));}
        catch(Exception e){return false;}
    }
    void login(String email,String password)throws Exception{
        if(!configured())throw new ThicknessSyncRules.Failure("Configuração pública indisponível.",false);
        emailAddress=email.trim();
        JSONObject credentials=new JSONObject().put("email",emailAddress).put("password",password);
        acceptTokens(callRaw("POST","/auth/v1/token?grant_type=password",credentials,false));
        verifyUser(null);validateAccess();
    }
    void remember(SessionStore store)throws Exception{
        if(refreshToken==null||refreshToken.isBlank())throw new ThicknessSyncRules.Failure("Sessão não renovável. Entre novamente.",false);
        store.save(session());sessionStore=store;keepSession=true;
    }
    void restore(JSONObject saved,SessionStore store)throws Exception{
        saved=ThicknessSessionStore.validated(saved);
        if(!configured())throw new ThicknessSyncRules.Failure("Configuração do IntegraNR inválida.",false);
        token=saved.getString("accessToken");refreshToken=saved.getString("refreshToken");expiresAt=saved.getLong("expiresAt");
        userId=saved.getString("userId");partnerId=saved.getString("partnerId");emailAddress=saved.getString("email");
        sessionStore=store;keepSession=true;String expected=userId;
        try{ensureFresh();verifyUser(expected);validateAccess();}
        catch(ThicknessSyncRules.Failure e){if(e.httpStatus==401)store.clear();throw e;}
    }
    private JSONObject session()throws Exception{
        return new JSONObject().put("origin",base).put("email",emailAddress).put("userId",userId).put("partnerId",partnerId)
            .put("accessToken",token).put("refreshToken",refreshToken).put("expiresAt",expiresAt);
    }
    private void acceptTokens(String response)throws Exception{
        JSONObject session=new JSONObject(response);String next=session.getString("access_token");
        if(next.isBlank())throw new ThicknessSyncRules.Failure("Sessão recebida é inválida.",false);
        JSONObject user=session.optJSONObject("user");
        if(userId!=null&&user!=null&&!userId.equals(user.optString("id")))throw new ThicknessSyncRules.Failure("A identidade da sessão mudou. Entre novamente.",false);
        token=next;refreshToken=session.optString("refresh_token",refreshToken==null?"":refreshToken);
        long expiry=session.optLong("expires_at",0);
        expiresAt=expiry>0?expiry*1000:System.currentTimeMillis()+Math.max(1,session.optLong("expires_in",3600))*1000;
    }
    private void verifyUser(String expected)throws Exception{
        JSONObject user=new JSONObject(call("GET","/auth/v1/user",null,true));
        String id=ThicknessSyncSnapshot.uuid(user.getString("id"));
        if(expected!=null&&!expected.equals(id))throw new ThicknessSyncRules.Failure("A identidade da sessão mudou. Entre novamente.",false);
        userId=id;JSONArray factors=user.optJSONArray("factors");
        for(int i=0;factors!=null&&i<factors.length();i++)if("verified".equals(factors.getJSONObject(i).optString("status")))
            throw new ThicknessSyncRules.Failure("Esta conta exige segundo fator. O fluxo móvel com MFA ainda não está disponível.",false);
    }
    private void validateAccess()throws Exception{
        JSONObject access=new JSONObject(call("POST","/rest/v1/rpc/mobile_access_status",new JSONObject(),true));
        if(!access.optBoolean("allowed"))throw new ThicknessSyncRules.Failure("Conta sem acesso vigente. Arquivos locais preservados.",false);
        JSONArray profiles=new JSONArray(call("GET","/rest/v1/profiles?select=parceiro_id&id=eq."+userId+"&limit=1",null,true));
        if(profiles.length()!=1)throw new ThicknessSyncRules.Failure("Conta sem empresa vinculada.",false);
        String current=ThicknessSyncSnapshot.uuid(profiles.getJSONObject(0).getString("parceiro_id"));
        if(partnerId!=null&&!partnerId.equals(current))throw new ThicknessSyncRules.Failure("A empresa da conta mudou. Desconecte e confira o destino.",false);
        partnerId=current;
    }
    private synchronized void renew()throws Exception{
        if(refreshToken==null||refreshToken.isBlank())throw new ThicknessSyncRules.Failure("Sua sessão expirou. Entre novamente.",false,401);
        try{
            acceptTokens(callRaw("POST","/auth/v1/token?grant_type=refresh_token",new JSONObject().put("refresh_token",refreshToken),false));
            if(keepSession&&sessionStore!=null)sessionStore.save(session());
        }catch(ThicknessSyncRules.Failure e){
            if(e.httpStatus==400||e.httpStatus==401||e.httpStatus==403){
                if(sessionStore!=null)sessionStore.clear();
                throw new ThicknessSyncRules.Failure("Sua conexão expirou. Entre novamente para reconectar.",false,401);
            }
            throw e;
        }
    }
    private void ensureFresh()throws Exception{if(expiresAt>0&&System.currentTimeMillis()+60000>=expiresAt)renew();}
    private void requireLogin()throws Exception{
        if(token==null||userId==null||partnerId==null)throw new ThicknessSyncRules.Failure("Entre com sua conta antes de enviar.",false);
    }
    JSONObject preview(JSONObject snapshot)throws Exception{
        requireLogin();validateAccess();
        JSONObject body=new JSONObject().put("p_envelope",ThicknessSyncSnapshot.envelope(snapshot));
        JSONObject result=new JSONObject(call("POST","/rest/v1/rpc/preview_es_thickness_capture",body,true));
        String status=result.optString("status");
        if(!java.util.Set.of("new","duplicate","replacement","locked","archived").contains(status)||!result.optString("fileSha256").matches("[0-9a-f]{64}"))
            throw new ThicknessSyncRules.Failure("Prévia de arquivo inválida; nenhum envio realizado.",false);
        if(!"new".equals(status))ThicknessSyncSnapshot.uuid(result.getString("existingId"));
        if(java.util.Set.of("duplicate","replacement","locked").contains(status)&&!result.optString("existingHash").matches("[0-9a-f]{64}"))
            throw new ThicknessSyncRules.Failure("Hash do arquivo existente não confirmado.",false);
        return result;
    }
    JSONObject send(JSONObject snapshot)throws Exception{return send(snapshot,null);}
    JSONObject send(JSONObject snapshot,JSONObject approvedPreview)throws Exception{
        requireLogin();JSONObject envelope=ThicknessSyncSnapshot.envelope(snapshot);validateAccess();
        JSONObject request=new JSONObject().put("p_envelope",envelope);
        if(approvedPreview!=null&&!"new".equals(approvedPreview.getString("status"))){
            if(!java.util.Set.of("duplicate","replacement").contains(approvedPreview.getString("status")))
                throw new ThicknessSyncRules.Failure("Arquivo histórico ou incorporado não pode ser substituído.",false);
            request.put("p_overwrite",true).put("p_existing_id",approvedPreview.getString("existingId"))
                .put("p_expected_hash",approvedPreview.getString("existingHash"));
        }
        JSONObject receipt=new JSONObject(call("POST","/rest/v1/rpc/receive_es_thickness_capture_v2",request,true));
        ThicknessSyncSnapshot.validateReceipt(receipt);
        if(!receipt.optString("fileSha256").matches("[0-9a-f]{64}")||!envelope.getString("revisionId").equals(receipt.optString("requestRevisionId"))
            ||!envelope.getString("lineageId").equals(receipt.optString("requestLineageId"))||!envelope.getString("contentSha256").equals(receipt.optString("requestClientSha256")))
            throw new ThicknessSyncRules.Failure("Recibo não corresponde à revisão enviada.",false);
        if(approvedPreview!=null&&!approvedPreview.getString("fileSha256").equals(receipt.getString("fileSha256")))
            throw new ThicknessSyncRules.Failure("Hash mudou entre conferência e envio. Confira novamente.",false);
        JSONArray rows=new JSONArray(call("GET","/rest/v1/es_thickness_upload_receipts?select=capture_id,revision_id,lineage_id,request_sha256,client_sha256&revision_id=eq."+envelope.getString("revisionId")+"&limit=1",null,true));
        if(rows.length()!=1)throw new ThicknessSyncRules.Failure("Não foi possível conferir o recibo. Repita sem duplicar o arquivo.",true);
        JSONObject row=rows.getJSONObject(0);
        if(!receipt.getString("id").equals(row.optString("capture_id"))||!receipt.getString("requestSha256").equals(row.optString("request_sha256"))
            ||!envelope.getString("revisionId").equals(row.optString("revision_id"))||!envelope.getString("lineageId").equals(row.optString("lineage_id"))
            ||!envelope.getString("contentSha256").equals(row.optString("client_sha256")))
            throw new ThicknessSyncRules.Failure("Recibo não confirmado para a sua revisão/conta.",false);
        JSONArray captures=new JSONArray(call("GET","/rest/v1/es_thickness_captures?select=revision_id,content_sha256,file_sha256,is_current&id=eq."+receipt.getString("id")+"&limit=1",null,true));
        if(captures.length()!=1||!receipt.getString("fileSha256").equals(captures.getJSONObject(0).optString("file_sha256"))
            ||!receipt.getString("contentSha256").equals(captures.getJSONObject(0).optString("content_sha256")))
            throw new ThicknessSyncRules.Failure("Hash do arquivo na nuvem não confirmado.",true);
        receipt.put("isCurrent",captures.getJSONObject(0).optBoolean("is_current"));return receipt;
    }
    private String callRaw(String method,String path,JSONObject payload,boolean authenticated)throws Exception{
        if(cancelled||Thread.currentThread().isInterrupted())throw new ThicknessSyncRules.Failure("Envio pausado. Pendências preservadas.",true);
        return transport.request(method,path,payload,authenticated?token:null);
    }
    private String call(String method,String path,JSONObject payload,boolean authenticated)throws Exception{
        if(authenticated)ensureFresh();
        try{return callRaw(method,path,payload,authenticated);}
        catch(ThicknessSyncRules.Failure e){
            if(authenticated&&e.httpStatus==401&&refreshToken!=null&&!refreshToken.isBlank()){
                renew();return callRaw(method,path,payload,true);
            }
            throw e;
        }
    }
    void cancel(){cancelled=true;HttpURLConnection c=active;if(c!=null)c.disconnect();}
    void disconnectSaved(JSONObject saved,SessionStore store)throws Exception{
        store.clear();
        String oldToken=saved==null?null:saved.optString("accessToken",null);
        try{if(oldToken!=null)transport.request("POST","/auth/v1/logout?scope=local",null,oldToken);}catch(Exception ignored){}
        keepSession=false;token=null;refreshToken=null;
    }
    @Override public void close(){
        try{if(!keepSession&&token!=null&&!cancelled)transport.request("POST","/auth/v1/logout?scope=local",null,token);}catch(Exception ignored){}
        token=null;refreshToken=null;userId=null;partnerId=null;emailAddress=null;
    }
    static void configureHeaders(HttpURLConnection c, String key, String bearer, String path) {
        c.setRequestProperty("apikey", key); c.setRequestProperty("Accept", "application/json");
        if (path.startsWith("/rest/")) { c.setRequestProperty("Content-Profile", "nr13"); c.setRequestProperty("Accept-Profile", "nr13"); }
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
    }
    private String http(String method, String path, JSONObject payload, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(base + path).openConnection(); active = c;
        try {
            c.setConnectTimeout(10_000); c.setReadTimeout(20_000); c.setInstanceFollowRedirects(false); c.setUseCaches(false); c.setRequestMethod(method);
            configureHeaders(c, key, bearer, path);
            if (payload != null) {
                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream o = c.getOutputStream()) { o.write(bytes); }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) { ThicknessSyncRules.Failure error=ThicknessSyncRules.httpFailure(code); throw new ThicknessSyncRules.Failure(error.getMessage(),error.retryable,code); }
            if (code == 204) return "";
            try (InputStream input = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096]; int count;
                while ((count = input.read(buf)) != -1) {
                    if (out.size() + count > 131_072) throw new ThicknessSyncRules.Failure("Resposta do servidor excedeu o limite seguro.", true);
                    out.write(buf, 0, count);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally { c.disconnect(); if (active == c) active = null; }
    }
}
