package com.dm5ese.usbprobe;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;

final class Dm5eNewFileSender {
    record Draft(String name,int rows,int columns,BigDecimal velocity,Map<String,BigDecimal> values) {
        Draft { values=Map.copyOf(values); }
        List<String> records() throws IOException {
            if(!name.matches("[A-Z0-9-]{1,15}"))throw new IOException("Nome: até 15 letras A–Z, números ou hífen.");
            var records=new ArrayList<>(new Dm5eProtocol.NewGrid(name,rows,columns).records());
            int found=0;
            if(velocity==null && !values.isEmpty())throw new IOException("Informe a velocidade dos pontos antes de enviar as medições.");
            if(velocity!=null)Dm5eCellWriter.record(new Dm5eProtocol.Reading("1A","","mm",records.get(7)),BigDecimal.ONE,velocity);
            for(int i=7;i<records.size();i++) {
                String raw=records.get(i);String position=Dm5eProtocol.clean(raw.substring(0,3))+Dm5eProtocol.clean(raw.substring(3,6));
                if(values.containsKey(position)) {
                    records.set(i,Dm5eCellWriter.record(new Dm5eProtocol.Reading(position,"","mm",raw),values.get(position),velocity));found++;
                }
            }
            if(found!=values.size())throw new IOException("Célula fora da matriz.");
            return List.copyOf(records);
        }
    }
    static void verify(Draft draft,List<String> records,Dm5eProtocol.Capture result) throws IOException {
        var m=result.metadata();
        if(!draft.name().equals(result.file().name()) || !"MM".equals(m.get("UNIT")) || !"2".equals(m.get("TPNB"))
            || !"LR".equals(m.get("ADDR")) || !"1".equals(m.get("L2SI")) || !"1".equals(m.get("L3SI"))
            || !Integer.toString(draft.rows()).equals(m.get("L2NL")) || !Integer.toString(draft.columns()).equals(m.get("L3NL"))
            || result.readings().size()!=records.size()-7)throw new IOException("Nome ou dimensões diferem do rascunho.");
        for(int i=0;i<result.readings().size();i++)if(!records.get(i+7).equals(result.readings().get(i).raw()))
            throw new IOException("Conteúdo diferente na célula "+result.readings().get(i).position()+". Nenhum arquivo existente será sobrescrito.");
    }
    static Dm5eProtocol.Capture send(Dm5eProtocol.Link link,Draft draft,Consumer<String> progress) throws Exception {
        List<String> records=draft.records();var protocol=new Dm5eProtocol(link);
        var before=protocol.directoryWithRecovery(progress);
        var existing=before.stream().filter(f->f.name().equalsIgnoreCase(draft.name())).findFirst();
        if(existing.isPresent()) {
            progress.accept("Nome já existe: conferindo "+draft.name()+" sem sobrescrever…");
            var result=protocol.importFile(existing.get(),progress);verify(draft,records,result);return result;
        }
        progress.accept("Criando "+draft.name()+" no DM5E…");
        try {
            link.write("\u001bFU "+draft.name()+"\r");link.readAck();
            for(String record:records){link.write(record+"\r\n");link.readAck();}
            link.write("[D2TE] 000\r");link.readAck();link.discardInput();
            var after=protocol.directoryWithRecovery(progress);
            Set<String> expected=new HashSet<>();for(var f:before)expected.add(f.name());expected.add(draft.name());
            Set<String> actual=new HashSet<>();for(var f:after)actual.add(f.name());
            if(after.size()!=before.size()+1 || !actual.equals(expected))throw new IOException("Catálogo mudou durante o envio.");
            var entry=after.stream().filter(f->f.name().equals(draft.name())).findFirst().orElseThrow();
            var result=protocol.importFile(entry,progress);verify(draft,records,result);return result;
        } catch(Exception e) {
            throw new IOException("Criação de "+draft.name()+" não confirmada. O arquivo pode existir ou estar incompleto. "
                +"O rascunho foi preservado; nenhum envio será repetido automaticamente. "+e.getMessage(),e);
        }
    }
}
