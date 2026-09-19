package com.dm5ese.usbprobe;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;

/** Reconcile before writing; an interrupted batch is never blindly replayed. */
final class Dm5eBatchWriter {
    static Dm5eProtocol.Capture send(Dm5eProtocol.Link link, Dm5eProtocol.Capture original,
            Map<String,BigDecimal> edits, BigDecimal velocity, Dm5eCellWriter.Backup backup,
            Consumer<String> progress) throws Exception {
        Dm5eCellWriter.validate(original);
        if(edits.isEmpty()) throw new IOException("Nenhuma célula pendente.");
        Map<String,String> wanted=new LinkedHashMap<>();
        for(var point:original.readings()) if(edits.containsKey(point.position()))
            wanted.put(point.position(),Dm5eCellWriter.record(point,edits.get(point.position()),velocity));
        if(wanted.size()!=edits.size())throw new IOException("Posição pendente ausente na matriz original.");
        Dm5eProtocol protocol=new Dm5eProtocol(link);
        progress.accept("Conferindo o arquivo no DM5E antes do envio…");
        var entry=protocol.directoryWithRecovery(progress).stream().filter(f->f.name().equals(original.file().name()))
            .findFirst().orElseThrow(()->new IOException("Arquivo não encontrado no DM5E."));
        var current=protocol.importFile(entry,progress);
        if(!original.metadata().equals(current.metadata()) || original.readings().size()!=current.readings().size())
            throw new IOException("Instrumento ou estrutura do arquivo mudou. Envio cancelado; rascunho preservado.");
        List<String> pending=new ArrayList<>();
        for(int i=0;i<original.readings().size();i++) {
            var old=original.readings().get(i); var remote=current.readings().get(i);
            String target=wanted.get(old.position());
            if(target!=null && target.equals(remote.raw())) continue; // Already applied, verified remotely.
            if(!old.raw().equals(remote.raw()))throw new IOException("Conflito na célula "+old.position()+". O DM5E mudou; nenhum envio iniciado.");
            if(target!=null)pending.add(old.position());
        }
        backup.save(current);
        int confirmed=edits.size()-pending.size();
        for(String position:pending) {
            progress.accept("Enviando "+position+" · "+(confirmed+1)+" de "+edits.size()+". Aguarde a conferência…");
            try {
                current=Dm5eCellWriter.write(link,current,position,edits.get(position),velocity,backup);
                confirmed++;
            } catch(Exception e) {
                throw new IOException("Envio interrompido. "+confirmed+" de "+edits.size()
                    +" células confirmadas. Rascunho preservado. Ao tentar novamente, o app relerá o DM5E antes de enviar. "
                    +"A última célula pode já ter sido gravada. "+e.getMessage(),e);
            }
        }
        return current;
    }
}
