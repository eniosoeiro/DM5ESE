package com.dm5ese.usbprobe;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.lang.reflect.Field;
import java.util.*;

/** Real UI with synthetic dashboard data; no uploads, credentials or USB commands. */
final class ProfessionalLayoutChecks {
    private static int passed;
    interface Work { void run() throws Exception; }
    private static Object field(Object object,String name)throws Exception{
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private static void check(boolean condition,String label)throws Exception{
        if(!condition)throw new Exception(label);passed++;
    }
    private static void main(Instrumentation i,Work work)throws Exception{
        Throwable[] error={null};i.runOnMainSync(()->{try{work.run();}catch(Throwable t){error[0]=t;}});
        if(error[0]!=null)throw new Exception("UI check failed",error[0]);
    }
    static int run(Instrumentation i)throws Exception{
        passed=0;MainActivity a=(MainActivity)i.startActivitySync(new Intent(i.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        i.waitForIdleSync();SystemClock.sleep(250);
        int[] ids={R.id.pro_nav_home,R.id.pro_nav_measurements,R.id.pro_nav_live,R.id.pro_nav_cloud,R.id.pro_nav_settings};
        String[] panes={"dashboard","measurements","live","cloud","settings"};
        Object original=field(a,"capture");String snapshot=original==null?"":original.toString();
        try{
            for(int n=0;n<ids.length;n++){
                final int index=n;
                main(i,()->{
                    Button button=a.findViewById(ids[index]);check(button!=null,"navigation button missing");
                    check(button.performClick(),"navigation not clickable");
                    for(int k=0;k<panes.length;k++)check(((View)field(a,panes[k])).getVisibility()==(k==index?View.VISIBLE:View.GONE),"wrong visible page");
                    check(button.isSelected(),"active destination not selected");
                });
                i.waitForIdleSync();
            }
            main(i,()->{
                check(a.findViewById(R.id.pro_logo)!=null,"logo missing");
                check(a.findViewById(R.id.pro_danger_section).getVisibility()==View.GONE,"instrument operations exposed by default");
                check(((View)field(a,"connectionDisclosure")).getVisibility()==View.GONE,"connection section should start collapsed");
                check(((View)field(a,"sync")).getParent()!=field(a,"measurements"),"send should have a dedicated page");
                check(a.findViewById(R.id.pro_export_csv)!=null&&a.findViewById(R.id.pro_export_json)!=null,"export actions missing");
                a.findViewById(R.id.pro_nav_home).performClick();
                a.findViewById(R.id.pro_quick_usb).performClick();
                check(((View)field(a,"connectionDisclosure")).getVisibility()==View.VISIBLE,"quick USB did not reveal configuration");
                check(((View)field(a,"settings")).getVisibility()==View.VISIBLE,"quick USB wrong destination");
                check(snapshot.equals(original==null?"":field(a,"capture").toString()),"navigation modified capture");
            });
            i.waitForIdleSync();
            main(i,()->{
                Rect previous=null;
                for(int id:ids){
                    View button=a.findViewById(id);Rect rect=new Rect();check(button.getGlobalVisibleRect(rect),"navigation not visible");
                    check(rect.height()>=ProfessionalUi.dp(a,48),"navigation target too small");
                    check(rect.width()>=ProfessionalUi.dp(a,48),"navigation target too narrow");
                    check(previous==null||rect.left>=previous.right,"navigation overlaps");previous=rect;
                }
                JSONObject seed=new JSONObject().put("captureId",UUID.randomUUID().toString()).put("file","SYNTHETIC-DESIGN")
                    .put("capturedAt","2026-09-19T12:00:00Z").put("readings",new JSONArray()
                    .put(new JSONObject().put("position","1A").put("state","OK").put("unit","mm").put("valueDecimal","5.270"))
                    .put(new JSONObject().put("position","1B").put("state","EMPTY").put("unit","mm").put("valueDecimal","")));
                String technical=seed.toString();DashboardPanel.Entry entry=DashboardPanel.entry(seed,"LOCAL");
                check(entry.measured()==1&&entry.total()==2,"empty cell incorrectly counted");
                check(entry.snapshot().getJSONArray("readings").getJSONObject(0).getString("valueDecimal").equals("5.270"),"decimal changed");
                check(!entry.date().contains("T"),"raw ISO in dashboard");
                int[] clicks={0,0,0,0,0};
                DashboardPanel panel=new DashboardPanel(a,()->clicks[0]++,()->clicks[1]++,()->clicks[2]++,()->clicks[3]++,value->{if(value==seed)clicks[4]++;});
                panel.render(List.of(entry),1,false);
                int[] tiles={R.id.pro_quick_new,R.id.pro_quick_files,R.id.pro_quick_sync,R.id.pro_quick_usb};
                for(int n=0;n<tiles.length;n++){panel.findViewById(tiles[n]).performClick();check(clicks[n]==1,"wrong quick action");}
                LinearLayout recents=panel.findViewById(R.id.pro_recent_list);recents.getChildAt(0).performClick();check(clicks[4]==1,"recent file did not open exact revision");
                panel.busy(true);for(int id:tiles){View tile=panel.findViewById(id);check(!tile.isEnabled(),"busy action not disabled");tile.performClick();}
                for(int n=0;n<4;n++)check(clicks[n]==1,"busy action still fired");
                recents.getChildAt(0).performClick();check(clicks[4]==1,"busy recent opened");
                check(technical.equals(seed.toString()),"UI mutated technical snapshot");
                panel.render(List.of(),0,false);check(recents.getChildCount()>0,"empty state missing");
                panel.render(List.of(),1,true);check(((TextView)field(panel,"totalCount")).getText().toString().equals("—"),"failure presented as zero records");
                panel.device(false,false,false);check(((TextView)field(panel,"instrumentTitle")).getText().equals(a.getString(R.string.pro_no_usb)),"offline instrument misrepresented");
                panel.device(true,false,false);check(((TextView)field(panel,"instrumentHint")).getText().equals(a.getString(R.string.pro_usb_permission)),"permission missing state");
                panel.device(true,true,false);check(((TextView)field(panel,"instrumentHint")).getText().equals(a.getString(R.string.pro_usb_authorized)),"permission state wrong");
                panel.device(true,true,true);check(((TextView)field(panel,"instrumentTitle")).getText().equals(a.getString(R.string.pro_usb_busy)),"busy state wrong");
            });
            return passed;
        }finally{main(i,()->a.finish());}
    }
    private ProfessionalLayoutChecks(){}
}
