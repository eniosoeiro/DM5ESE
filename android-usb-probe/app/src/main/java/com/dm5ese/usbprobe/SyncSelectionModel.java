package com.dm5ese.usbprobe;

import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Presentation state only: filters never change selection or touch the queue. */
final class SyncSelectionModel {
    enum Filter { ALL, PENDING, FAILED, RECEIVED, SELECTED }
    enum Order { RECENT, NAME }
    enum Toggle { SELECTED, REMOVED, LIMIT, UNKNOWN }
    static final int LIMIT = 10;
    static final class Item {
        final String id, name, status, timestamp;
        final int cells, measured, empty, invalid;
        final long time;
        Item(String id, String name, String status, String timestamp, int cells, int measured, int empty, int invalid) {
            this.id=id; this.name=name; this.status=status; this.timestamp=timestamp;
            this.cells=cells; this.measured=measured; this.empty=empty; this.invalid=invalid;
            long parsed;
            try { parsed=Instant.parse(timestamp).toEpochMilli(); } catch(Exception e) { parsed=Long.MIN_VALUE; }
            this.time=parsed;
        }
        String shortId(){return id.substring(0,Math.min(8,id.length()));}
        String date(ZoneId zone) {
            if(time==Long.MIN_VALUE)return "";
            return DateTimeFormatter.ofPattern("dd/MM/yyyy '\u00e0s' HH:mm", new Locale("pt","BR"))
                .withZone(zone).format(Instant.ofEpochMilli(time));
        }
    }
    private final LinkedHashMap<String,Item> all=new LinkedHashMap<>();
    private final LinkedHashSet<String> selected=new LinkedHashSet<>();
    private Filter filter=Filter.ALL;
    private Order order=Order.RECENT;
    private String query="";
    SyncSelectionModel(List<Item> items){for(Item item:items)if(item.id!=null&&!item.id.isBlank())all.putIfAbsent(item.id,item);}
    List<Item> visible(){
        List<Item> result=new ArrayList<>();String term=normalize(query);
        for(Item item:all.values()){
            boolean matches=switch(filter){
                case ALL -> true;
                case PENDING -> Set.of("LOCAL","PENDING","SENDING").contains(item.status);
                case FAILED -> Set.of("FAILED","CONFLICT","OTHER_ACCOUNT").contains(item.status);
                case RECEIVED -> "RECEIVED".equals(item.status);
                case SELECTED -> selected.contains(item.id);
            };
            if(matches && normalize(item.name+" "+item.id+" "+item.date(ZoneId.systemDefault())).contains(term))result.add(item);
        }
        Comparator<Item> newest=Comparator.comparingLong((Item i)->i.time).reversed().thenComparing(i->i.id);
        result.sort(order==Order.NAME?Comparator.comparing((Item i)->normalize(i.name)).thenComparing(newest):newest);
        return result;
    }
    static String normalize(String s){return Normalizer.normalize(s,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT).trim();}
    Toggle toggle(String id){
        if(!all.containsKey(id))return Toggle.UNKNOWN;
        if(selected.remove(id))return Toggle.REMOVED;
        if(selected.size()>=LIMIT)return Toggle.LIMIT;
        selected.add(id);return Toggle.SELECTED;
    }
    boolean isSelected(String id){return selected.contains(id);}
    List<String> selectedIds(){return List.copyOf(selected);}
    int count(){return selected.size();}
    int total(){return all.size();}
    int hiddenSelected(){int shown=0;for(Item i:visible())if(isSelected(i.id))shown++;return count()-shown;}
    void clear(){selected.clear();}
    Filter filter(){return filter;}
    void filter(Filter filter){this.filter=filter;}
    Order order(){return order;}
    void order(Order order){this.order=order;}
    void query(String query){this.query=query==null?"":query;}
}
