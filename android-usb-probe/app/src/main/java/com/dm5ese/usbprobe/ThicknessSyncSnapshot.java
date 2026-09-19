package com.dm5ese.usbprobe;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;

/** Validation only. Never edits the technical snapshot or converts empty cells to zero. */
final class ThicknessSyncSnapshot {
    static String uuid(String value) throws Exception {
        if (value == null || !value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
            throw new ThicknessSyncRules.Failure("Identificação de captura/conta inválida.", false);
        return UUID.fromString(value).toString();
    }
    static String lineage(JSONObject snapshot) throws Exception {
        for (String key : new String[]{"lineageId", "draftId", "parentCaptureId", "captureId"}) {
            String candidate = snapshot.optString(key, "");
            if (!candidate.isBlank()) return uuid(candidate);
        }
        throw new ThicknessSyncRules.Failure("Captura sem identidade de origem.", false);
    }
    static JSONObject envelope(JSONObject snapshot) throws Exception {
        String revision = uuid(snapshot.getString("captureId"));
        if (snapshot.optInt("schemaVersion", -1) != 1) throw new ThicknessSyncRules.Failure("Versão de captura não suportada.", false);
        JSONArray readings = snapshot.getJSONArray("readings");
        if (readings.length() < 1 || readings.length() > 10_000) throw new ThicknessSyncRules.Failure("Envie uma matriz de 1 a 10.000 células.", false);
        Set<String> positions = new HashSet<>();
        for (int i = 0; i < readings.length(); i++) {
            JSONObject p = readings.getJSONObject(i);
            String position = p.getString("position"), state = p.getString("state"), value = p.getString("valueDecimal");
            if (position.isBlank() || !positions.add(position) || !"mm".equals(p.getString("unit")))
                throw new ThicknessSyncRules.Failure("Posição repetida/ausente ou unidade diferente de mm.", false);
            if ("EMPTY".equals(state)) {
                if (!value.isEmpty()) throw new ThicknessSyncRules.Failure("Célula vazia não pode conter um valor.", false);
            } else if (!("OK".equals(state) || "LOCAL_MEASURED".equals(state)) || !value.matches("[0-9]+([.][0-9]+)?") || new BigDecimal(value).signum() <= 0) {
                throw new ThicknessSyncRules.Failure("Leitura inválida: espessura deve ser decimal positivo em mm.", false);
            }
        }
        JSONObject result = new JSONObject().put("schema", "es-thickness-sync-1").put("revisionId", revision)
            .put("lineageId", lineage(snapshot)).put("contentSha256", ThicknessSyncQueue.sha256(snapshot.toString())).put("snapshot", snapshot);
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > ThicknessSyncRules.MAX_BYTES)
            throw new ThicknessSyncRules.Failure("Captura excede 3 MiB. Nenhuma evidência foi truncada; preserve a exportação local.", false);
        return result;
    }
    static void validateReceipt(JSONObject receipt) throws Exception {
        uuid(receipt.getString("id"));
        if (!("received".equals(receipt.optString("receipt")) || "already_received".equals(receipt.optString("receipt")))
            || !receipt.optString("contentSha256").matches("[0-9a-f]{64}"))
            throw new ThicknessSyncRules.Failure("O servidor não confirmou um recibo válido. Tente novamente para conferir a revisão.", true);
        java.time.Instant.parse(receipt.getString("receivedAt"));
    }
    private ThicknessSyncSnapshot() {}
}
