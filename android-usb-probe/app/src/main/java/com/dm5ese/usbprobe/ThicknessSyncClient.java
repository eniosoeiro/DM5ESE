package com.dm5ese.usbprobe;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** HTTPS, exact backend, nr13 schema, no mobile acquisition lease and no persisted secrets. */
final class ThicknessSyncClient implements AutoCloseable {
    interface Transport { String request(String method, String path, JSONObject payload, String token) throws Exception; }
    private final String base, key;
    private final Transport transport;
    private String token, userId, partnerId;
    private volatile HttpURLConnection active;
    private volatile boolean cancelled;
    ThicknessSyncClient() { this(BuildConfig.ES_SYNC_URL, BuildConfig.ES_SYNC_PUBLISHABLE_KEY, null); }
    ThicknessSyncClient(String base, String key, Transport testTransport) {
        this.base = base.replaceAll("/$", ""); this.key = key;
        transport = testTransport == null ? this::http : testTransport;
    }
    boolean configured() {
        if (!ThicknessSyncRules.PRODUCTION.equals(base)) return false;
        if (key.startsWith("sb_publishable_")) return true;
        try {
            JSONObject claims = new JSONObject(new String(java.util.Base64.getUrlDecoder().decode(key.split("\\.")[1]), StandardCharsets.UTF_8));
            return "anon".equals(claims.optString("role"));
        } catch (Exception ignored) { return false; }
    }
    String userId() { return userId; }
    String partnerId() { return partnerId; }
    void login(String email, String password) throws Exception {
        if (!configured()) throw new ThicknessSyncRules.Failure("APK sem configuração pública válida do IntegraNR.", false);
        JSONObject session = new JSONObject(call("POST", "/auth/v1/token?grant_type=password", new JSONObject().put("email",email.trim()).put("password",password), false));
        token = session.getString("access_token");
        JSONObject user = new JSONObject(call("GET", "/auth/v1/user", null, true));
        userId = ThicknessSyncSnapshot.uuid(user.getString("id"));
        JSONArray factors = user.optJSONArray("factors");
        for (int i = 0; factors != null && i < factors.length(); i++)
            if ("verified".equals(factors.getJSONObject(i).optString("status"))) throw new ThicknessSyncRules.Failure("Esta conta exige segundo fator. O envio móvel com MFA ainda não está disponível.", false);
        validateAccess();
    }
    private void validateAccess() throws Exception {
        JSONObject access = new JSONObject(call("POST", "/rest/v1/rpc/mobile_access_status", new JSONObject(), true));
        if (!access.optBoolean("allowed")) throw new ThicknessSyncRules.Failure("Conta sem acesso vigente para sincronizar. Os arquivos locais foram preservados.", false);
        JSONArray profiles = new JSONArray(call("GET", "/rest/v1/profiles?select=parceiro_id&id=eq." + userId + "&limit=1", null, true));
        if (profiles.length() != 1) throw new ThicknessSyncRules.Failure("Conta sem empresa vinculada. Confira seu acesso no IntegraNR.", false);
        String current = ThicknessSyncSnapshot.uuid(profiles.getJSONObject(0).getString("parceiro_id"));
        if (partnerId != null && !partnerId.equals(current)) throw new ThicknessSyncRules.Failure("A empresa da conta mudou. Pare e confira o destino do envio.", false);
        partnerId = current;
    }
    JSONObject send(JSONObject snapshot) throws Exception {
        if (token == null || userId == null || partnerId == null) throw new ThicknessSyncRules.Failure("Entre com sua conta antes de enviar.", false);
        JSONObject envelope = ThicknessSyncSnapshot.envelope(snapshot);
        validateAccess();
        JSONObject receipt = new JSONObject(call("POST", "/rest/v1/rpc/receive_es_thickness_capture", new JSONObject().put("p_envelope", envelope), true));
        ThicknessSyncSnapshot.validateReceipt(receipt);
        // Confirm the exact revision and both checksums through owner-scoped RLS before ACK.
        JSONArray rows = new JSONArray(call("GET", "/rest/v1/es_thickness_captures?select=revision_id,lineage_id,client_content_sha256,content_sha256&id=eq." + receipt.getString("id") + "&limit=1", null, true));
        if (rows.length() != 1) throw new ThicknessSyncRules.Failure("Recibo recebido, mas a revisão ainda não pôde ser conferida. Repita o envio sem duplicar os dados.", true);
        JSONObject row = rows.getJSONObject(0);
        if (!envelope.getString("revisionId").equals(row.optString("revision_id")) || !envelope.getString("lineageId").equals(row.optString("lineage_id"))
            || !envelope.getString("contentSha256").equals(row.optString("client_content_sha256")) || !receipt.getString("contentSha256").equals(row.optString("content_sha256")))
            throw new ThicknessSyncRules.Failure("Recibo não corresponde à revisão enviada. Não foi marcado como recebido.", false);
        return receipt;
    }
    private String call(String method, String path, JSONObject payload, boolean authenticated) throws Exception {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new ThicknessSyncRules.Failure("Envio pausado. As pendências continuam salvas.", true);
        return transport.request(method, path, payload, authenticated ? token : null);
    }
    void cancel() { cancelled = true; HttpURLConnection c = active; if (c != null) c.disconnect(); }
    @Override public void close() {
        // This sign-in is only for this foreground batch. Never revoke other devices.
        try { if (token != null && !cancelled) transport.request("POST", "/auth/v1/logout?scope=local", null, token); } catch (Exception ignored) { }
        token = null; userId = null; partnerId = null;
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
            if (code < 200 || code >= 300) throw ThicknessSyncRules.httpFailure(code);
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
