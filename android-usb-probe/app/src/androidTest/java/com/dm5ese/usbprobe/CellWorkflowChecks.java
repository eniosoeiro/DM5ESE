package com.dm5ese.usbprobe;

import android.app.Instrumentation;
import android.content.*;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Synthetic readings, isolated local storage, and real Activity controls. Never opens USB. */
final class CellWorkflowChecks {
    static Object get(Object a, String name) throws Exception { Field f = a.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(a); }
    static void set(Object a, String name, Object value) throws Exception { Field f = a.getClass().getDeclaredField(name); f.setAccessible(true); f.set(a, value); }
    static void call(Object a, String name) throws Exception { Method m = a.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(a); }
    interface Checked { void run() throws Exception; }
    static void main(Instrumentation i, Checked code) throws Exception {
        Exception[] failure = {null}; i.runOnMainSync(() -> { try { code.run(); } catch (Exception e) { failure[0] = e; } });
        if (failure[0] != null) throw failure[0];
    }
    static void require(boolean ok, String message) throws Exception { if (!ok) throw new Exception(message); }
    @SuppressWarnings("unchecked") static void select(MainActivity a, String position) throws Exception {
        ((Map<String, TextView>)get(a,"matrixCells")).get(position).performClick();
    }
    static void sample(MainActivity a, boolean coupled, long age) throws Exception {
        set(a,"liveRunning",true); set(a,"busy",true);
        ((CellMeasurement)get(a,"cellMeasurement")).accept(new Dm5eLiveReading(9123,0,coupled,"9123,0,"+(coupled?"C":"U")),SystemClock.elapsedRealtime()-age);
        call(a,"updateCellPreview");
    }
    static void awaitSave(Instrumentation i, MainActivity a) throws Exception {
        for(int n=0;n<100;n++) { boolean[] done={false}; main(i,()->done[0]=!(boolean)get(a,"savingCell")); if(done[0])return; SystemClock.sleep(50); }
        throw new Exception("Save timeout");
    }
    static void run(Instrumentation i) throws Exception {
        Context ctx=i.getTargetContext(); File temp=new File(ctx.getFilesDir(),"cell-test-"+UUID.randomUUID());
        CaptureStore isolated=new CaptureStore(new ContextWrapper(ctx) { @Override public File getFilesDir(){return temp;} });
        JSONObject seed=new JSONObject().put("captureId","test-source").put("capturedAt","2026-09-17T00:00:00Z").put("file","TESTE LOCAL")
            .put("metadata",new JSONObject().put("L2NL","1").put("L3NL","2").put("L2SI","1").put("L3SI","1").put("VELC","5996.8"))
            .put("readings",new JSONArray().put(new JSONObject().put("position","1A").put("valueDecimal","5.270").put("unit","mm").put("rawRecordBase64","original"))
                .put(new JSONObject().put("position","1B").put("valueDecimal","").put("unit","mm"))).put("rawFramesBase64",new JSONArray().put("original"));
        String original=seed.toString();
        MainActivity a=(MainActivity)i.startActivitySync(new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Object[] backup=new Object[2]; main(i,()-> { backup[0]=get(a,"capture"); backup[1]=get(a,"store"); });
        try {
            main(i,()-> {
                set(a,"capture",seed); set(a,"store",isolated); call(a,"renderCapture"); select(a,"1A"); sample(a,true,0);
                require(((Button)get(a,"cellSave")).isEnabled(),"Fresh coupled disabled");
                require(seed.toString().equals(original),"Preview mutated saved capture");
                sample(a,false,0); require(!((Button)get(a,"cellSave")).isEnabled(),"Uncoupled allowed");
                sample(a,true,1501); require(!((Button)get(a,"cellSave")).isEnabled(),"Stale allowed");
                select(a,"1B"); require(!((Button)get(a,"cellSave")).isEnabled(),"Selection reused reading");
                sample(a,true,0); ((Button)get(a,"cellSave")).performClick();
            });
            awaitSave(i,a); require(isolated.history().size()==1,"Empty cell not persisted");
            JSONObject saved=isolated.load(isolated.history().get(0));
            require(saved.getJSONArray("readings").getJSONObject(1).getString("valueDecimal").equals("9.123"),"Wrong saved value");
            require(saved.getJSONArray("readings").getJSONObject(0).getString("valueDecimal").equals("5.270"),"Wrong cell overwritten");
            require(seed.toString().equals(original),"Source changed");
            require(saved.getString("parentCaptureId").equals("test-source"),"Missing lineage");
            main(i,()-> { select(a,"1A"); sample(a,true,0); ((Button)get(a,"cellSave")).performClick(); });
            i.waitForIdleSync(); i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); i.waitForIdleSync();
            require(isolated.history().size()==1,"Cancel wrote a revision");
            main(i,()-> { sample(a,true,0); ((Button)get(a,"cellSave")).performClick(); });
            i.waitForIdleSync();
            AccessibilityNodeInfo root=i.getUiAutomation().getRootInActiveWindow();
            for(int n=0;root==null && n<30;n++) { SystemClock.sleep(100); root=i.getUiAutomation().getRootInActiveWindow(); }
            require(root!=null,"Accessibility window unavailable");
            List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText("CONFIRMAR GRAVAÇÃO");
            if(nodes.isEmpty())nodes=root.findAccessibilityNodeInfosByText("Confirmar gravação");
            require(!nodes.isEmpty(),"Confirmation missing");
            main(i,()->sample(a,true,0));
            require(nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK),"Confirmation click failed");
            i.waitForIdleSync(); awaitSave(i,a);
            require(isolated.history().size()==2,"Confirmed replacement not saved");
            JSONObject latest=isolated.load(isolated.history().get(0));
            require(latest.getJSONArray("readings").getJSONObject(0).getString("valueDecimal").equals("9.123"),"Replacement value wrong");
            require(latest.getJSONArray("localEdits").length()==2,"Audit missing");
            // Manual entry already exists in the production baseline. Stopping must
            // discard the USB sample, not disable the separate manual-entry workflow.
            main(i,()-> {
                call(a,"cancel");
                require(!((CellMeasurement)get(a,"cellMeasurement")).canSave(SystemClock.elapsedRealtime()),"Stopped USB sample is still saveable");
                Button manual=(Button)get(a,"cellSave");
                require(manual.getText().toString().equals("Informar valor no celular"),"Stopped control is not labelled as manual entry");
                manual.performClick();
            });
            i.waitForIdleSync();
            AccessibilityNodeInfo manualRoot=i.getUiAutomation().getRootInActiveWindow();
            require(manualRoot!=null&&!manualRoot.findAccessibilityNodeInfosByText("Valor informado manualmente.").isEmpty(),"Manual confirmation missing");
            require(isolated.history().size()==2,"Opening manual form wrote an old USB value");
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);i.waitForIdleSync();
            require(isolated.history().size()==2,"Cancelling manual entry wrote data");
        } finally {
            main(i,()-> { call(a,"cancel"); set(a,"capture",backup[0]); set(a,"store",backup[1]); call(a,"renderCapture"); });
            for(File f:isolated.history()) if(!f.delete())throw new Exception("Test cleanup failed");
            new File(temp,"captures").delete(); temp.delete();
        }
    }
}
