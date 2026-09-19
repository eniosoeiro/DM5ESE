package com.dm5ese.usbprobe;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import static com.dm5ese.usbprobe.ProfessionalUi.*;

final class DashboardPanel extends LinearLayout {
    record Entry(JSONObject snapshot,String status,int measured,int total,String date){}
    private final TextView instrumentTitle,instrumentHint,totalCount,pendingCount,receivedCount;
    private final LinearLayout recent;
    private final List<View> actions=new ArrayList<>();
    private final Consumer<JSONObject> open;
    private boolean busy;
    DashboardPanel(Context c,Runnable create,Runnable files,Runnable sync,Runnable usb,Consumer<JSONObject> open){
        super(c);this.open=open;setOrientation(VERTICAL);setId(R.id.pro_dashboard);
        LinearLayout hero=column(c);hero.setPadding(dp(c,20),dp(c,18),dp(c,20),dp(c,18));
        GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{NAVY,0xff125666});gradient.setCornerRadius(dp(c,22));hero.setBackground(gradient);
        var hp=lp(-1,-2);hp.bottomMargin=dp(c,16);addView(hero,hp);
        TextView eyebrow=text(c,c.getString(R.string.pro_hero_eyebrow),10,0xff85e4d5,true);eyebrow.setLetterSpacing(.16f);hero.addView(eyebrow);
        TextView headline=text(c,c.getString(R.string.pro_hero_title),24,Color.WHITE,true);headline.setPadding(0,dp(c,10),0,dp(c,10));hero.addView(headline);
        hero.addView(text(c,c.getString(R.string.pro_hero_hint),13,0xffd1e4eb,false));
        LinearLayout device=card(this);device.setOrientation(HORIZONTAL);device.setGravity(Gravity.CENTER_VERTICAL);
        ImageView usbIcon=icon(c,R.drawable.ic_pro_usb,TEAL);usbIcon.setPadding(dp(c,10),dp(c,10),dp(c,10),dp(c,10));usbIcon.setBackground(shape(c,MINT,0,12));device.addView(usbIcon,lp(dp(c,46),dp(c,46)));
        LinearLayout connection=column(c);connection.setPadding(dp(c,12),0,dp(c,8),0);device.addView(connection,new LinearLayout.LayoutParams(0,-2,1));
        instrumentTitle=text(c,c.getString(R.string.pro_no_usb),15,INK,true);connection.addView(instrumentTitle);
        instrumentHint=text(c,c.getString(R.string.pro_usb_offline),12,MUTED,false);instrumentHint.setPadding(0,dp(c,4),0,0);connection.addView(instrumentHint);
        device.addView(icon(c,R.drawable.ic_pro_chevron,MUTED),lp(dp(c,20),dp(c,20)));device.setOnClickListener(v->usb.run());device.setFocusable(true);actions.add(device);
        LinearLayout first=new LinearLayout(c),second=new LinearLayout(c);addView(first,lp(-1,-2));addView(second,lp(-1,-2));
        tile(first,R.id.pro_quick_new,R.drawable.ic_pro_plus,R.string.pro_new,R.string.pro_new_hint,create,true);
        tile(first,R.id.pro_quick_files,R.drawable.ic_pro_history,R.string.pro_files,R.string.pro_files_hint,files,false);
        tile(second,R.id.pro_quick_sync,R.drawable.ic_pro_cloud,R.string.pro_sync,R.string.pro_sync_hint,sync,false);
        tile(second,R.id.pro_quick_usb,R.drawable.ic_pro_usb,R.string.pro_connect,R.string.pro_connect_hint,usb,false);
        LinearLayout stats=card(this);stats.setOrientation(HORIZONTAL);stats.setPadding(dp(c,8),dp(c,16),dp(c,8),dp(c,16));
        totalCount=metric(stats,R.string.pro_revisions);pendingCount=metric(stats,R.string.pro_pending);receivedCount=metric(stats,R.string.pro_received);
        TextView counted=text(c,c.getString(R.string.pro_statistics_hint),11,MUTED,false);counted.setPadding(0,0,0,dp(c,10));addView(counted,lp(-1,-2));
        LinearLayout top=new LinearLayout(c);top.setGravity(Gravity.CENTER_VERTICAL);addView(top,lp(-1,-2));
        TextView title=text(c,c.getString(R.string.pro_recent),18,INK,true);title.setAccessibilityHeading(true);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button all=button(c,R.string.pro_view_all,0,false,files);all.setBackgroundColor(Color.TRANSPARENT);all.setTextColor(TEAL);top.addView(all,lp(-2,-2));actions.add(all);
        recent=column(c);recent.setId(R.id.pro_recent_list);addView(recent,lp(-1,-2));recent.addView(text(c,c.getString(R.string.pro_loading),13,MUTED,false));
        LinearLayout safety=card(this);safety.setBackground(shape(c,MINT,0,18));safety.setOrientation(HORIZONTAL);safety.setGravity(Gravity.CENTER_VERTICAL);
        safety.addView(icon(c,R.drawable.ic_pro_shield,TEAL),lp(dp(c,30),dp(c,30)));
        LinearLayout copy=column(c);copy.setPadding(dp(c,12),0,0,0);safety.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        copy.addView(text(c,c.getString(R.string.pro_ownership_title),14,TEAL,true));copy.addView(text(c,c.getString(R.string.pro_ownership_hint),12,MUTED,false));
    }
    private void tile(LinearLayout parent,int id,int symbol,int title,int hint,Runnable action,boolean primary){
        Context c=getContext();LinearLayout tile=column(c);tile.setId(id);tile.setPadding(dp(c,16),dp(c,16),dp(c,16),dp(c,16));
        tile.setMinimumHeight(dp(c,116));tile.setBackground(shape(c,primary?TEAL:Color.WHITE,primary?0:LINE,18));
        var p=new LinearLayout.LayoutParams(0,-2,1);p.bottomMargin=dp(c,12);if(parent.getChildCount()==0)p.rightMargin=dp(c,10);parent.addView(tile,p);
        tile.addView(icon(c,symbol,primary?Color.WHITE:TEAL),lp(dp(c,28),dp(c,28)));
        TextView label=text(c,c.getString(title),16,primary?Color.WHITE:INK,true);label.setPadding(0,dp(c,12),0,dp(c,4));tile.addView(label);
        tile.addView(text(c,c.getString(hint),12,primary?0xffd2eee7:MUTED,false));tile.setOnClickListener(v->{if(!busy)action.run();});tile.setFocusable(true);actions.add(tile);
    }
    private TextView metric(LinearLayout parent,int label){
        Context c=getContext();LinearLayout col=column(c);col.setGravity(Gravity.CENTER);parent.addView(col,new LinearLayout.LayoutParams(0,-2,1));
        TextView number=text(c,"—",24,INK,true);col.addView(number);col.addView(text(c,c.getString(label),11,MUTED,false));return number;
    }
    void device(boolean detected,boolean permitted,boolean active){
        instrumentTitle.setText(active?R.string.pro_usb_busy:detected?R.string.pro_usb_found:R.string.pro_no_usb);
        instrumentHint.setText(!detected?R.string.pro_usb_offline:permitted?R.string.pro_usb_authorized:R.string.pro_usb_permission);
    }
    void busy(boolean value){busy=value;for(View view:actions){view.setEnabled(!value);view.setAlpha(value?.5f:1f);}}
    static Entry entry(JSONObject snapshot,String status){
        JSONArray points=snapshot.optJSONArray("readings");int total=points==null?0:points.length(),valid=0;
        for(int n=0;n<total;n++){JSONObject p=points.optJSONObject(n);if(p==null)continue;
            if(("OK".equals(p.optString("state"))||"LOCAL_MEASURED".equals(p.optString("state")))&&"mm".equals(p.optString("unit"))){
                try{if(new java.math.BigDecimal(p.optString("valueDecimal")).signum()>0)valid++;}catch(NumberFormatException ignored){}
            }
        }
        String date="";try{date=DateTimeFormatter.ofPattern("dd/MM/yyyy · HH:mm",new Locale("pt","BR")).withZone(ZoneId.systemDefault()).format(Instant.parse(snapshot.optString("editedAt",snapshot.optString("capturedAt"))));}catch(Exception ignored){}
        return new Entry(snapshot,status,valid,total,date);
    }
    void render(List<Entry> data,int total,boolean failed){
        Context c=getContext();int received=0,pending=0;
        for(Entry e:data){if("RECEIVED".equals(e.status))received++;else pending++;}
        totalCount.setText(String.valueOf(total));pendingCount.setText(String.valueOf(pending));receivedCount.setText(String.valueOf(received));
        recent.removeAllViews();
        if(failed){totalCount.setText("—");pendingCount.setText("—");receivedCount.setText("—");recent.addView(text(c,c.getString(R.string.pro_read_error),13,MUTED,false));return;}
        if(data.isEmpty()){LinearLayout blank=card(recent);blank.addView(text(c,c.getString(R.string.pro_no_captures),16,INK,true));blank.addView(text(c,c.getString(R.string.pro_no_captures_hint),13,MUTED,false));return;}
        for(Entry e:data.subList(0,Math.min(4,data.size()))){
            LinearLayout row=card(recent);row.setOrientation(HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(c,14),dp(c,14),dp(c,14),dp(c,14));
            ImageView symbol=icon(c,R.drawable.ic_pro_grid,TEAL);symbol.setPadding(dp(c,9),dp(c,9),dp(c,9),dp(c,9));symbol.setBackground(shape(c,MINT,0,12));row.addView(symbol,lp(dp(c,42),dp(c,42)));
            LinearLayout labels=column(c);labels.setPadding(dp(c,12),0,dp(c,8),0);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            TextView name=text(c,e.snapshot.optString("file",c.getString(R.string.pro_no_selected)),15,INK,true);name.setMaxLines(2);labels.addView(name);
            TextView date=text(c,e.date.isEmpty()?c.getString(R.string.pro_empty_revision):e.date,11,MUTED,false);date.setPadding(0,dp(c,4),0,dp(c,4));labels.addView(date);
            labels.addView(text(c,c.getString(R.string.pro_cells,e.measured,e.total),12,MUTED,false));
            row.addView(icon(c,R.drawable.ic_pro_chevron,MUTED),lp(dp(c,18),dp(c,18)));row.setFocusable(true);row.setTag(e.snapshot.optString("captureId"));
            row.setOnClickListener(v->{if(!busy)open.accept(e.snapshot);});
        }
        if(total>data.size())recent.addView(text(c,c.getString(R.string.pro_recent_limit,data.size()),12,MUTED,false));
    }
}
