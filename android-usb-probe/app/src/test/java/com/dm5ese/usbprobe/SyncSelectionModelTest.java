package com.dm5ese.usbprobe;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.ZoneId;
import java.util.*;

public class SyncSelectionModelTest {
    private SyncSelectionModel.Item item(String id,String name,String state,String at){return new SyncSelectionModel.Item(id,name,state,at,16,8,8,0);}
    private SyncSelectionModel model(){return new SyncSelectionModel(List.of(
        item("a","Vaso de pressão","LOCAL","2026-09-17T08:29:00Z"),
        item("b","Arquivo B","RECEIVED","2026-09-17T10:00:00Z"),
        item("c","Vaso de pressão","FAILED","2026-09-17T07:00:00Z"),
        item("d","Arquivo D","PENDING","bad")));
    }
    @Test public void startsWithoutPreselection(){assertEquals(0,model().count());}
    @Test public void selectionSurvivesFilteringAndSearch(){
        var m=model();m.toggle("a");m.filter(SyncSelectionModel.Filter.RECEIVED);m.query("B");
        assertEquals(List.of("a"),m.selectedIds());assertEquals(1,m.hiddenSelected());
        m.query("");m.filter(SyncSelectionModel.Filter.ALL);assertTrue(m.isSelected("a"));
    }
    @Test public void searchIgnoresCaseAccentsAndWhitespace(){
        var m=model();m.query("  PRESSAO  ");assertEquals(2,m.visible().size());m.query("c");assertTrue(m.visible().stream().anyMatch(i->i.id.equals("c")));
    }
    @Test public void sameNameDifferentRevisionIsNotMerged(){
        var m=model();m.toggle("a");m.toggle("c");assertEquals(2,m.count());
    }
    @Test public void maximumTenDoesNotEvictSelection(){
        List<SyncSelectionModel.Item> items=new ArrayList<>();for(int i=0;i<12;i++)items.add(item("id"+i,"Capture "+i,"LOCAL","2026-01-01T00:00:00Z"));
        var m=new SyncSelectionModel(items);for(int i=0;i<10;i++)assertEquals(SyncSelectionModel.Toggle.SELECTED,m.toggle("id"+i));
        assertEquals(SyncSelectionModel.Toggle.LIMIT,m.toggle("id10"));assertEquals(10,m.count());assertFalse(m.isSelected("id10"));
        assertEquals(SyncSelectionModel.Toggle.REMOVED,m.toggle("id0"));assertEquals(SyncSelectionModel.Toggle.SELECTED,m.toggle("id10"));
    }
    @Test public void unknownIdsNeverEnterSelection(){var m=model();assertEquals(SyncSelectionModel.Toggle.UNKNOWN,m.toggle("missing"));assertEquals(0,m.count());}
    @Test public void selectedFilterAndClear(){var m=model();m.toggle("a");m.toggle("b");m.filter(SyncSelectionModel.Filter.SELECTED);assertEquals(2,m.visible().size());m.clear();assertTrue(m.visible().isEmpty());}
    @Test public void statusesRemainDistinct(){var m=model();m.filter(SyncSelectionModel.Filter.PENDING);assertEquals(2,m.visible().size());m.filter(SyncSelectionModel.Filter.FAILED);assertEquals("c",m.visible().get(0).id);}
    @Test public void sortingIsStableAndInvalidDateLast(){
        var m=model();assertEquals(List.of("b","a","c","d"),m.visible().stream().map(i->i.id).toList());
        m.toggle("a");m.order(SyncSelectionModel.Order.NAME);assertEquals(List.of("b","d","a","c"),m.visible().stream().map(i->i.id).toList());assertTrue(m.isSelected("a"));
    }
    @Test public void datesUseLocalCalendarRatherThanRawIso(){
        var i=item("1234567890","Test","LOCAL","2026-09-17T08:29:02.975449Z");
        assertEquals("17/09/2026 às 05:29",i.date(ZoneId.of("America/Sao_Paulo")));assertEquals("12345678",i.shortId());
    }
    @Test public void duplicateIdDoesNotProduceDuplicateSend(){
        var i=item("same","Test","LOCAL","");var m=new SyncSelectionModel(List.of(i,i));assertEquals(1,m.total());m.toggle("same");assertEquals(1,m.selectedIds().size());
    }
    @Test public void returnedSelectionCannotBeMutated(){var m=model();m.toggle("a");assertThrows(UnsupportedOperationException.class,()->m.selectedIds().clear());assertEquals(1,m.count());}
}
