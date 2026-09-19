package com.dm5ese.usbprobe;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Verified Grid LR / 01.24 layout: seven CRLF header records (83 bytes), 41 bytes per point. */
final class Dm5eCellWriter {
    interface Backup { void save(Dm5eProtocol.Capture capture) throws Exception; }
    static String field(String value, int width) throws IOException {
        if (value.length() > width) throw new IOException("Valor excede o campo do DM5E.");
        return "\u00ff".repeat(width-value.length()) + value;
    }
    static String record(Dm5eProtocol.Reading point, BigDecimal value, BigDecimal velocity) throws IOException {
        if (value.signum() <= 0 || value.scale() > 3 || velocity.compareTo(new BigDecimal("100")) < 0
            || velocity.compareTo(new BigDecimal("20000")) > 0) throw new IOException("Espessura ou velocidade inválida.");
        // Auxiliary fields were not acquired by MS: explicitly leave them undefined (FF).
        return point.raw().substring(0,6) + field(value.toPlainString(),7) + field(velocity.toPlainString(),7) + "M" + "\u00ff".repeat(18);
    }
    static int offset(int index) throws IOException {
        if (index < 0 || index >= 100) throw new IOException("Posição fora do limite de 100 pontos.");
        return 83 + index * 41;
    }
    static void validate(Dm5eProtocol.Capture capture) throws IOException {
        Dm5eProtocol.validateLayout(capture.metadata());
        if (!"01.24".equals(capture.metadata().get("SFVR")) || !"MM".equals(capture.metadata().get("UNIT"))
            || !"2.1".equals(capture.metadata().get("VERS")) || !"3 3 7 7 1 4 14".equals(capture.metadata().get("RCFM"))
            || !capture.file().name().matches("[A-Z0-9-]{1,15}") || capture.readings().isEmpty() || capture.readings().size()>100)
            throw new IOException("Gravação validada apenas para firmware 01.24, Grid LR em mm, até 100 pontos e nome A–Z, 0–9 ou hífen.");
        for (var point:capture.readings()) if(point.raw().length()!=39 || point.raw().indexOf('\r')>=0 || point.raw().indexOf('\n')>=0)
            throw new IOException("Formato do registro não reconhecido.");
    }
    static boolean same(Dm5eProtocol.Capture a, Dm5eProtocol.Capture b) {
        return a.file().name().equals(b.file().name()) && a.metadata().equals(b.metadata())
            && a.readings().equals(b.readings());
    }
    static Dm5eProtocol.Capture write(Dm5eProtocol.Link link, Dm5eProtocol.Capture expected,
                                     String position, BigDecimal value, BigDecimal velocity, Backup backup) throws Exception {
        validate(expected);
        int index=-1;
        for(int i=0;i<expected.readings().size();i++) if(expected.readings().get(i).position().equals(position)) {
            if(index!=-1)throw new IOException("Posição duplicada."); index=i;
        }
        if(index<0)throw new IOException("Posição não encontrada.");
        String replacement=record(expected.readings().get(index),value,velocity);
        Dm5eProtocol p=new Dm5eProtocol(link);
        var entry=p.directory().stream().filter(f->f.name().equals(expected.file().name())).findFirst()
            .orElseThrow(()->new IOException("Arquivo não encontrado no DM5E."));
        var before=p.download(entry);
        if(!same(expected,before))throw new IOException("Arquivo ou instrumento mudou. Importe novamente antes de gravar.");
        backup.save(before); // A durable local backup must succeed before any write command.
        boolean opened=false;
        try {
            link.write("\u001bFO "+entry.name()+"\r"); opened=true; link.readAck();
            link.write("\u001bFS "+offset(index)+"\r"); link.readAck();
            link.write("\u001bFW\r"); link.readAck();
            link.write(replacement+"\r\n\u001a");
            String report=new String(link.readLine(),StandardCharsets.ISO_8859_1);
            if(!"41 byte(s) is wrote to file.".equals(report))throw new IOException("Contagem de escrita inesperada: "+report);
            link.write("\u001bFC\r"); link.readAck(); opened=false;
            link.discardInput();
            var after=p.download(entry);
            if(!before.metadata().equals(after.metadata()) || after.readings().size()!=before.readings().size())throw new IOException("Cabeçalho ou dimensões mudaram.");
            for(int i=0;i<after.readings().size();i++) {
                String wanted=i==index?replacement:before.readings().get(i).raw();
                if(!wanted.equals(after.readings().get(i).raw()))throw new IOException("Conferência falhou na célula "+after.readings().get(i).position());
            }
            return after;
        } catch(Exception e) {
            // Do not blindly send FC while FW may still be waiting for bytes.
            throw new IOException("Gravação no DM5E não confirmada. O arquivo pode ter sido alterado. "
                + "Não repita a escrita; confira o medidor e importe novamente. " + e.getMessage(),e);
        }
    }
}
