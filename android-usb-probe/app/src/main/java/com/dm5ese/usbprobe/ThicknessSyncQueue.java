package com.dm5ese.usbprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Atomic durable tasks, separate from captures. No passwords or tokens. */
final class ThicknessSyncQueue {
    private static final Object LOCK = new Object();
    private final File directory;
    private final SharedPreferences legacy;
    ThicknessSyncQueue(Context context) {
        directory = new File(context.getFilesDir(), "thickness-sync-v2");
        legacy = context.getSharedPreferences("es-thickness-sync", Context.MODE_PRIVATE);
    }
    ThicknessSyncQueue(File testDirectory) { directory = testDirectory; legacy = null; }
    static String sha256(String text) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(64);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
        return out.toString();
    }
    private File file(String revision) throws Exception { return new File(directory, ThicknessSyncSnapshot.uuid(revision) + ".json"); }
    private JSONObject read(File file) throws Exception {
        AtomicFile atomic = new AtomicFile(file);
        if (!file.exists() && !new File(file + ".bak").exists()) return null;
        return new JSONObject(new String(atomic.readFully(), StandardCharsets.UTF_8));
    }
    private void write(File file, JSONObject value) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private queue");
        AtomicFile atomic = new AtomicFile(file); FileOutputStream stream = null;
        try { stream = atomic.startWrite(); stream.write(value.toString().getBytes(StandardCharsets.UTF_8)); atomic.finishWrite(stream); }
        catch (Exception e) { atomic.failWrite(stream); throw e; }
    }
    void setAccount(String owner, String partner) throws Exception {
        synchronized (LOCK) {
            write(new File(directory, "account.json"), new JSONObject().put("owner", ThicknessSyncSnapshot.uuid(owner)).put("partner", ThicknessSyncSnapshot.uuid(partner)));
        }
    }
    JSONObject enqueue(String owner, String partner, JSONObject snapshot, boolean retryFailed) throws Exception {
        synchronized (LOCK) {
            owner = ThicknessSyncSnapshot.uuid(owner); partner = ThicknessSyncSnapshot.uuid(partner);
            JSONObject envelope = ThicknessSyncSnapshot.envelope(snapshot);
            String revision = envelope.getString("revisionId"), hash = envelope.getString("contentSha256");
            if (legacy != null) for (Map.Entry<String, ?> entry : legacy.getAll().entrySet()) {
                if (entry.getKey().startsWith("task.") && entry.getKey().endsWith("." + revision)) {
                    JSONObject old = new JSONObject(String.valueOf(entry.getValue()));
                    if (!owner.equals(old.optString("owner"))) throw new ThicknessSyncRules.Failure("Esta revisão já está vinculada a outra conta. Use a conta original.", false);
                    if (!hash.equals(old.optString("hash"))) throw new ThicknessSyncRules.Failure("Conteúdo alterado: crie uma nova revisão antes de enviar.", false);
                }
            }
            JSONObject task = read(file(revision));
            if (task != null) {
                same(task, owner, partner, revision, hash);
                // An interrupted final attempt must remain manually recoverable.
                if ("SENDING".equals(task.optString("state")) && task.optInt("attempts") >= ThicknessSyncRules.MAX_ATTEMPTS
                    && System.currentTimeMillis() >= task.optLong("nextAttemptAt")) {
                    task.put("state", "FAILED"); write(file(revision), task);
                }
                if (retryFailed && "FAILED".equals(task.optString("state"))) {
                    task.put("state", "PENDING").put("attempts", 0).put("nextAttemptAt", 0);
                    write(file(revision), task);
                }
                return task;
            }
            task = new JSONObject().put("owner", owner).put("partner", partner).put("revision", revision).put("hash", hash)
                .put("snapshot", new JSONObject(snapshot.toString())).put("state", "PENDING").put("attempts", 0).put("nextAttemptAt", 0);
            write(file(revision), task); // durable BEFORE the first HTTP request
            return task;
        }
    }
    private void same(JSONObject task, String owner, String partner, String revision, String hash) throws Exception {
        if (!owner.equals(task.optString("owner")) || !partner.equals(task.optString("partner")))
            throw new ThicknessSyncRules.Failure("Revisão vinculada a outra conta/empresa. Use a conta original; nada foi reenviado.", false);
        if (!revision.equals(task.optString("revision")) || !hash.equals(task.optString("hash")))
            throw new ThicknessSyncRules.Failure("Conflito de revisão local. Preserve a captura já vinculada.", false);
    }
    JSONObject recheckSelected(JSONObject selected)throws Exception {
        synchronized(LOCK){
            JSONObject task=read(file(selected.getString("revision")));if(task==null)throw new IOException("Task missing");
            same(task,selected.getString("owner"),selected.getString("partner"),selected.getString("revision"),selected.getString("hash"));
            if("RECEIVED".equals(task.optString("state"))){task.put("state","PENDING").put("attempts",0).put("nextAttemptAt",0);write(file(task.getString("revision")),task);}
            return task;
        }
    }
    String fileHashForLastAccount(JSONObject snapshot){
        synchronized(LOCK){
            try{
                if(!"RECEIVED".equals(statusForLastAccount(snapshot)))return "";
                JSONObject task=read(file(snapshot.getString("captureId")));JSONObject receipt=task.optJSONObject("receipt");
                String hash=receipt==null?"":receipt.optString("fileSha256","");return hash.matches("[0-9a-f]{64}")?hash:"";
            }catch(Exception e){return "";}
        }
    }
    JSONObject begin(JSONObject selected, long now) throws Exception {
        synchronized (LOCK) {
            JSONObject task = read(file(selected.getString("revision")));
            if (task == null) throw new IOException("Task missing");
            same(task, selected.getString("owner"), selected.getString("partner"), selected.getString("revision"), selected.getString("hash"));
            if (!ThicknessSyncRules.due(task.optString("state"), task.optInt("attempts"), task.optLong("nextAttemptAt"), now)) return null;
            if (!task.getString("hash").equals(sha256(task.getJSONObject("snapshot").toString())))
                throw new ThicknessSyncRules.Failure("Snapshot da fila não corresponde à revisão. Preserve a exportação local.", false);
            task.put("state", "SENDING").put("attempts", task.optInt("attempts") + 1)
                .put("attemptId", UUID.randomUUID().toString()).put("nextAttemptAt", now + 120_000L);
            write(file(task.getString("revision")), task); return task;
        }
    }
    void receipt(JSONObject sent, JSONObject receipt) throws Exception {
        synchronized (LOCK) {
            ThicknessSyncSnapshot.validateReceipt(receipt);
            JSONObject task = read(file(sent.getString("revision")));
            if (task == null) throw new IOException("Task missing");
            same(task, sent.getString("owner"), sent.getString("partner"), sent.getString("revision"), sent.getString("hash"));
            task.put("state", "RECEIVED").put("receipt", receipt).put("receivedAt", System.currentTimeMillis()).remove("error");
            write(file(task.getString("revision")), task);
        }
    }
    void failure(JSONObject sent, Exception error, long now) throws Exception {
        synchronized (LOCK) {
            JSONObject task = read(file(sent.getString("revision")));
            if (task == null) throw new IOException("Task missing");
            same(task, sent.getString("owner"), sent.getString("partner"), sent.getString("revision"), sent.getString("hash"));
            if ("RECEIVED".equals(task.optString("state")) || !task.optString("attemptId").equals(sent.optString("attemptId"))) return;
            boolean retry = ThicknessSyncRules.retryable(error) && task.optInt("attempts") < ThicknessSyncRules.MAX_ATTEMPTS;
            task.put("state", retry ? "PENDING" : "FAILED").put("error", ThicknessSyncRules.safeMessage(error))
                .put("nextAttemptAt", now + ThicknessSyncRules.delay(task.optInt("attempts")));
            write(file(task.getString("revision")), task);
        }
    }
    String statusForLastAccount(JSONObject snapshot) {
        synchronized (LOCK) {
            try {
                JSONObject task = read(file(snapshot.getString("captureId")));
                if (task == null) return "LOCAL";
                JSONObject account = read(new File(directory, "account.json"));
                if (account == null || !account.optString("owner").equals(task.optString("owner")) || !account.optString("partner").equals(task.optString("partner"))) return "OTHER_ACCOUNT";
                if (!sha256(snapshot.toString()).equals(task.optString("hash"))) return "CONFLICT";
                String state = task.optString("state", "PENDING");
                return "SENDING".equals(state) ? "PENDING" : state;
            } catch (Exception e) { return "FAILED"; }
        }
    }
    static String label(String state) {
        switch (state) {
            case "RECEIVED": return "Recebido na conta vinculada · conferir no site";
            case "FAILED": return "Falha · selecione para tentar novamente";
            case "CONFLICT": return "Conflito de revisão · arquivo preservado";
            case "OTHER_ACCOUNT": return "Vinculado a outra conta · use a conta original";
            case "PENDING": return "Pendente · selecione para retomar o envio";
            default: return "Salvo no celular · ainda não enviado";
        }
    }
}
