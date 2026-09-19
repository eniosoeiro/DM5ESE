package com.dm5ese.usbprobe;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

final class CaptureStore {
    private final File directory;
    CaptureStore(Context context) { directory = new File(context.getFilesDir(), "captures"); }
    JSONObject save(Dm5eProtocol.Capture capture) throws Exception {
        return save(capture,null);
    }
    private JSONObject save(Dm5eProtocol.Capture capture,JSONObject draft) throws Exception {
        String id = UUID.randomUUID().toString();
        JSONArray points = new JSONArray();
        for (Dm5eProtocol.Reading reading : capture.readings()) {
            points.put(new JSONObject().put("position", reading.position()).put("valueDecimal", reading.value())
                .put("unit", reading.unit()).put("state", reading.value().isEmpty() ? "EMPTY" : "OK")
                .put("rawRecordBase64", Base64.getEncoder().encodeToString(reading.raw().getBytes(StandardCharsets.ISO_8859_1))));
        }
        JSONObject payload = new JSONObject().put("schemaVersion", 1).put("captureId", id).put("lineageId", id)
            .put("capturedAt", Instant.now().toString()).put("source", "android_usb")
            .put("file", capture.file().name()).put("fileNumber", capture.file().number())
            .put("metadata", new JSONObject(capture.metadata())).put("readings", points)
            .put("rawFramesBase64", new JSONArray(capture.rawFrames()));
        if(draft!=null)payload.put("draftId",draft.getString("draftId")).put("lineageId",draft.optString("lineageId",draft.getString("draftId"))).put("parentCaptureId",draft.getString("captureId")).put("newFileDraft",false);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Sem espaço para salvar a captura.");
        AtomicFile file = new AtomicFile(new File(directory, System.currentTimeMillis() + "-" + id + ".json"));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write(payload.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream);
        } catch (Exception e) { file.failWrite(stream); throw e; }
        return payload;
    }
    List<File> history() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return List.of();
        Arrays.sort(files, Comparator.comparing(File::getName).reversed()); return Arrays.asList(files);
    }
    private JSONObject persistDraft(JSONObject payload) throws Exception {
        if(!directory.isDirectory() && !directory.mkdirs())throw new IOException("Não foi possível salvar o rascunho.");
        AtomicFile file=new AtomicFile(new File(directory,System.currentTimeMillis()+"-"+payload.getString("captureId")+".json"));
        FileOutputStream stream=null;
        try {stream=file.startWrite();stream.write(payload.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(stream);}
        catch(Exception e){file.failWrite(stream);throw e;}
        return payload;
    }
    JSONObject createDraft(String name,int rows,int columns,java.math.BigDecimal velocity) throws Exception {
        var draft=new Dm5eNewFileSender.Draft(name,rows,columns,velocity,Map.of());draft.records();
        for(var old:history())if(name.equalsIgnoreCase(load(old).optString("file")))throw new IOException("Já há uma cópia com esse nome no celular. Escolha outro nome.");
        JSONObject meta=new JSONObject().put("FLNM",name).put("UNIT","MM").put("TPNB","2").put("ADDR","LR")
            .put("L2SI","1").put("L3SI","1").put("L2NL",String.valueOf(rows)).put("L3NL",String.valueOf(columns))
            .put("NMBR",String.valueOf(rows*columns)).put("VELC",velocity==null ? "" : velocity.toPlainString()).put("VERS","2.1").put("RCFM","3 3 7 7 1 4 14");
        JSONArray points=new JSONArray();
        for(int r=1;r<=rows;r++)for(int c=1;c<=columns;c++)points.put(new JSONObject().put("position",r+Dm5eProtocol.alpha(c)).put("valueDecimal","").put("unit","mm").put("state","EMPTY"));
        String lineage=UUID.randomUUID().toString();
        return persistDraft(new JSONObject().put("schemaVersion",1).put("captureId",UUID.randomUUID().toString()).put("lineageId",lineage)
            .put("draftId",lineage).put("newFileDraft",true).put("source","offline_new_grid")
            .put("capturedAt",Instant.now().toString()).put("file",name).put("fileNumber",0).put("metadata",meta)
            .put("readings",points).put("rawFramesBase64",new JSONArray()));
    }
    List<JSONObject> newDrafts() throws Exception {
        Map<String,JSONObject> latest=new LinkedHashMap<>();
        for(var file:history()) {var c=load(file);String id=c.optString("draftId","");if(!id.isEmpty())latest.putIfAbsent(id,c);}
        List<JSONObject> result=new ArrayList<>();for(var c:latest.values())if(c.optBoolean("newFileDraft"))result.add(c);return result;
    }
    JSONObject setDraftVelocity(JSONObject source,java.math.BigDecimal velocity) throws Exception {
        JSONObject updated=new JSONObject(source.toString());
        updated.getJSONObject("metadata").put("VELC",velocity.toPlainString());
        newFileData(updated).records();
        updated.put("captureId",UUID.randomUUID().toString()).put("capturedAt",Instant.now().toString());
        return persistDraft(updated);
    }
    static Dm5eNewFileSender.Draft newFileData(JSONObject source) throws Exception {
        if(!source.optBoolean("newFileDraft"))throw new IOException("Não é um arquivo novo pendente.");
        var m=source.getJSONObject("metadata");Map<String,java.math.BigDecimal> values=new LinkedHashMap<>();
        var points=source.getJSONArray("readings");
        for(int i=0;i<points.length();i++){var p=points.getJSONObject(i);if(!p.getString("valueDecimal").isEmpty())values.put(p.getString("position"),new java.math.BigDecimal(p.getString("valueDecimal")));}
        return new Dm5eNewFileSender.Draft(source.getString("file"),m.getInt("L2NL"),m.getInt("L3NL"),m.optString("VELC").isBlank()?null:new java.math.BigDecimal(m.getString("VELC")),values);
    }
    JSONObject markDraftSent(JSONObject draft,Dm5eProtocol.Capture confirmed) throws Exception {
        return save(confirmed,draft);
    }
    static Dm5eProtocol.Capture originalSnapshot(JSONObject source) throws Exception {
        Map<String,String> meta=new LinkedHashMap<>(); JSONObject metadata=source.getJSONObject("metadata");
        Iterator<String> keys=metadata.keys(); while(keys.hasNext()) { String key=keys.next(); meta.put(key,metadata.getString(key)); }
        List<String> raw=new ArrayList<>(); List<Dm5eProtocol.Reading> points=new ArrayList<>();
        JSONArray frames=source.getJSONArray("rawFramesBase64"); boolean data=false;
        for(int i=0;i<frames.length();i++) {
            String encoded=frames.getString(i); raw.add(encoded);
            String body=Dm5eProtocol.checkedBody(Base64.getDecoder().decode(encoded));
            if(body.startsWith("[D2TE]"))data=false;
            else if(data)points.add(Dm5eProtocol.reading(body,meta,points.size()));
            if(body.startsWith("RCFM "))data=true;
        }
        if(points.size()!=Integer.parseInt(meta.get("NMBR")))throw new IOException("Captura original incompleta.");
        return new Dm5eProtocol.Capture(new Dm5eProtocol.FileEntry(source.getInt("fileNumber"),source.getString("file")),meta,points,raw);
    }
    /** Save a new local revision; imported captures and USB raw evidence stay untouched. */
    static Map<String,java.math.BigDecimal> pending(JSONObject source) throws Exception {
        Map<String,java.math.BigDecimal> edits=new LinkedHashMap<>();
        JSONArray points=source.getJSONArray("readings");
        for(int i=0;i<points.length();i++) {
            JSONObject point=points.getJSONObject(i);
            if("LOCAL_MEASURED".equals(point.optString("state"))) {
                if(!"mm".equals(point.getString("unit")))throw new IOException("Unidade local incompatível.");
                if(edits.put(point.getString("position"),new java.math.BigDecimal(point.getString("valueDecimal")))!=null)
                    throw new IOException("Célula local duplicada.");
            }
        }
        return Collections.unmodifiableMap(edits);
    }
    JSONObject saveCell(JSONObject source, String position, Dm5eLiveReading reading, String measuredAt) throws Exception {
        if (!reading.coupled() || reading.micrometres() <= 0) throw new IOException("Leitura sem acoplamento.");
        return saveLocal(source,position,reading.millimetres(),measuredAt,reading.raw(),"usb_live_local");
    }
    JSONObject saveManualCell(JSONObject source, String position, java.math.BigDecimal value) throws Exception {
        if(value.signum()<=0 || value.scale()>3 || value.toPlainString().length()>7)throw new IOException("Espessura positiva, até 3 casas e 7 caracteres.");
        return saveLocal(source,position,value,Instant.now().toString(),"","manual_local");
    }
    private JSONObject saveLocal(JSONObject source, String position, java.math.BigDecimal value, String measuredAt, String raw, String origin) throws Exception {
        JSONObject copy = new JSONObject(source.toString());
        JSONArray points = copy.getJSONArray("readings");
        JSONObject target = null;
        for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.getJSONObject(i);
            if (position.equals(point.getString("position"))) {
                if (target != null) throw new IOException("Posição duplicada.");
                target = point;
            }
        }
        if (target == null || !"mm".equals(target.getString("unit"))) throw new IOException("Célula inválida ou unidade diferente de mm.");
        JSONArray edits = copy.optJSONArray("localEdits");
        if (edits == null) { edits = new JSONArray(); copy.put("localEdits", edits); }
        edits.put(new JSONObject().put("position", position).put("previous", new JSONObject(target.toString()))
            .put("valueDecimal", value.toPlainString()).put("measuredAt", measuredAt)
            .put("rawMS", raw).put("source",origin).put("velocityStatus", "not_queried"));
        target.remove("rawRecordBase64");
        target.put("valueDecimal", value.toPlainString()).put("state", "LOCAL_MEASURED")
            .put("source", origin).put("measuredAt", measuredAt).put("rawMS", raw)
            .put("velocityStatus", "not_queried");
        String id = UUID.randomUUID().toString();
        copy.put("parentCaptureId", source.getString("captureId")).put("captureId", id)
            .put("lineageId", source.optString("lineageId", source.getString("captureId")))
            .put("source", "android_usb_local_revision").put("editedAt", Instant.now().toString())
            .put("rawFramesSource", "original_import");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Não foi possível salvar.");
        AtomicFile file = new AtomicFile(new File(directory, System.currentTimeMillis() + "-" + id + ".json"));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write(copy.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream);
        } catch (Exception e) { file.failWrite(stream); throw e; }
        return copy;
    }
    JSONObject load(File file) throws Exception {
        if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) throw new IOException("Caminho inválido.");
        return new JSONObject(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
    }
    static String csv(JSONObject capture) throws JSONException {
        StringBuilder out = new StringBuilder("\ufeffarquivo;capturado_em;posicao;espessura;unidade;estado;velocidade_cabecalho_m_s\r\n");
        JSONArray readings = capture.getJSONArray("readings");
        for (int i = 0; i < readings.length(); i++) {
            JSONObject r = readings.getJSONObject(i);
            out.append(cell(capture.getString("file"))).append(';').append(cell(capture.getString("capturedAt"))).append(';')
                .append(cell(r.getString("position"))).append(';').append(cell(r.getString("valueDecimal").replace('.', ','))).append(';')
                .append(cell(r.getString("unit"))).append(';').append(cell(r.getString("state"))).append(';')
                .append(cell(capture.getJSONObject("metadata").optString("VELC", "").replace('.', ','))).append("\r\n");
        }
        return out.toString();
    }
    private static String cell(String s) {
        if (!s.trim().isEmpty() && "=+-@".indexOf(s.trim().charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
