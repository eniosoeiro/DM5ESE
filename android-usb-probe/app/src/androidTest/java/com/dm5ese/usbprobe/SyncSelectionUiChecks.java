package com.dm5ese.usbprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic selector checks. No login, queue mutation, network or instrument commands. */
final class SyncSelectionUiChecks {
    private static int checks;
    private static void check(boolean ok,String label) throws IOException {if(!ok)throw new IOException("UX check failed: "+label);checks++;}
    static int run(Instrumentation inst) throws Exception {
        checks=0;List<ThicknessSelectionSheet.Source> sources=new ArrayList<>();List<String> before=new ArrayList<>();
        for(int i=0;i<14;i++){
            String id=String.format(Locale.ROOT,"%08d-1111-4111-8111-%012d",i,i);
            JSONObject snapshot=new JSONObject().put("captureId",id).put("file",i<2?"UX SAME NAME":"UX FILE "+i)
                .put("capturedAt",String.format(Locale.ROOT,"2026-09-19T12:00:%02dZ",i))
                .put("readings",new JSONArray().put(new JSONObject().put("position","1A").put("state","EMPTY").put("valueDecimal","").put("unit","mm"))
                    .put(new JSONObject().put("position","1B").put("state","OK").put("valueDecimal","12.345000").put("unit","mm")));
            sources.add(new ThicknessSelectionSheet.Source(snapshot,i==0?"FAILED":i==1?"RECEIVED":"LOCAL"));before.add(snapshot.toString());
        }
        Activity activity=inst.startActivitySync(new Intent(inst.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicReference<ThicknessSelectionSheet> ref=new AtomicReference<>();AtomicReference<List<JSONObject>> chosen=new AtomicReference<>();
        inst.runOnMainSync(()->{var sheet=new ThicknessSelectionSheet(activity,Runnable::run,()->new ThicknessSelectionSheet.Loaded(sources,14,0),chosen::set);ref.set(sheet);sheet.show();});
        inst.waitForIdleSync();Thread.sleep(500);ThicknessSelectionSheet sheet=ref.get();
        try{
            View proceed=sheet.findViewById(R.id.sync_continue_button);RecyclerView list=sheet.findViewById(R.id.sync_capture_list);
            check(!proceed.isEnabled(),"zero selected disables CTA");check(list.getAdapter().getItemCount()==14,"all rows");
            for(int index=0;index<10;index++) clickRow(inst,list,index);
            check(((TextView)proceed).getText().toString().contains("(10)"),"CTA count");
            clickRow(inst,list,10);
            check(((TextView)proceed).getText().toString().contains("(10)"),"eleventh not added");
            TextView notice=sheet.findViewById(R.id.sync_selection_notice);check(notice.getVisibility()==View.VISIBLE,"limit message");
            inst.runOnMainSync(()->((EditText)sheet.findViewById(R.id.sync_search_field)).setText("NOT_FOUND"));inst.waitForIdleSync();
            check(list.getAdapter().getItemCount()==0,"empty search");check(proceed.isEnabled(),"hidden selection retained");check(notice.getText().toString().contains("fora"),"hidden selection explained");
            inst.runOnMainSync(()->((EditText)sheet.findViewById(R.id.sync_search_field)).setText(""));inst.waitForIdleSync();
            inst.runOnMainSync(()->sheet.findViewById(R.id.sync_clear_selection).performClick());inst.waitForIdleSync();check(!proceed.isEnabled(),"clear selection");
            clickRow(inst,list,0);
            AccessibilityNodeInfo info=list.findViewHolderForAdapterPosition(0).itemView.createAccessibilityNodeInfo();
            check(info.isCheckable()&&info.isChecked(),"accessible checked state");
            inst.runOnMainSync(()->sheet.getWindow().getDecorView().findViewWithTag("FAILED").performClick());inst.waitForIdleSync();
            check(list.getAdapter().getItemCount()==1,"failed filter");check(proceed.isEnabled(),"filter does not drop selected ID");
            inst.runOnMainSync(()->sheet.getWindow().getDecorView().findViewWithTag("RECEIVED").performClick());inst.waitForIdleSync();check(list.getAdapter().getItemCount()==1,"received filter");
            inst.runOnMainSync(()->sheet.getWindow().getDecorView().findViewWithTag("SELECTED").performClick());inst.waitForIdleSync();check(list.getAdapter().getItemCount()==1,"selected filter");
            Rect buttonRect=new Rect(),listRect=new Rect();proceed.getGlobalVisibleRect(buttonRect);list.getGlobalVisibleRect(listRect);
            check(buttonRect.height()>=Math.round(48*activity.getResources().getDisplayMetrics().density),"CTA touch height");
            check(listRect.bottom<=buttonRect.top,"footer outside scroll area");
            inst.runOnMainSync(proceed::performClick);inst.waitForIdleSync();check(chosen.get()!=null&&chosen.get().size()==1,"exact selected list delivered");
            check(chosen.get().get(0).getString("captureId").equals(sources.get(13).snapshot().getString("captureId")),"correct revision after filters");
            for(int i=0;i<sources.size();i++)check(before.get(i).equals(sources.get(i).snapshot().toString()),"snapshot unchanged "+i);
        }finally{inst.runOnMainSync(()->{sheet.dismiss();activity.finish();});}
        return checks;
    }
    private static void clickRow(Instrumentation inst,RecyclerView list,int index) throws Exception {
        inst.runOnMainSync(()->list.scrollToPosition(index));inst.waitForIdleSync();Thread.sleep(80);
        AtomicReference<View> row=new AtomicReference<>();inst.runOnMainSync(()->{var holder=list.findViewHolderForAdapterPosition(index);if(holder!=null)row.set(holder.itemView);});
        check(row.get()!=null,"row bound "+index);inst.runOnMainSync(()->row.get().performClick());inst.waitForIdleSync();
    }
    private SyncSelectionUiChecks(){}
}
