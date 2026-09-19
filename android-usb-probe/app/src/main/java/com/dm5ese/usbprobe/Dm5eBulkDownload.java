package com.dm5ese.usbprobe;

import java.util.*;
import java.util.function.*;

/** Saves each verified file independently. Stops if the serial stream becomes uncertain. */
final class Dm5eBulkDownload {
    interface Read { Dm5eProtocol.Capture read(Dm5eProtocol.FileEntry file) throws Exception; }
    interface Save { void save(Dm5eProtocol.Capture capture) throws Exception; }
    record Result(List<String> saved, List<String> remaining, String error) {
        String summary() {
            return (remaining.isEmpty() ? "Download concluído. " : "Download interrompido. ")
                +saved.size()+" de "+(saved.size()+remaining.size())+" arquivos salvos para consulta offline."
                +(saved.isEmpty()?"":"\nSalvos: "+String.join(", ",saved))
                +(remaining.isEmpty()?"":"\nNão concluídos: "+String.join(", ",remaining))
                +(error.isEmpty()?"":"\n"+error)
                +"\nAbra Histórico para consultar. Os arquivos completos já salvos permanecem no celular.";
        }
    }
    static Result run(List<Dm5eProtocol.FileEntry> files, Read read, Save save,
                      BooleanSupplier cancelled, Consumer<String> progress) {
        List<String> saved=new ArrayList<>(); String error="";
        for(var file:List.copyOf(files)) {
            if(cancelled.getAsBoolean()) { error="Operação cancelada."; break; }
            progress.accept("Baixando "+(saved.size()+1)+" de "+files.size()+": "+file.name()+"…");
            try {
                var capture=read.read(file);
                if(cancelled.getAsBoolean()) {error="Operação cancelada.";break;}
                save.save(capture); // Count only after durable storage succeeds.
                saved.add(file.name());
                progress.accept(saved.size()+" de "+files.size()+" salvos · "+file.name());
            } catch(Exception e) { error="Falha em "+file.name()+": "+e.getMessage(); break; }
        }
        List<String> remaining=new ArrayList<>();
        for(int i=saved.size();i<files.size();i++)remaining.add(files.get(i).name());
        return new Result(List.copyOf(saved),List.copyOf(remaining),error);
    }
}
