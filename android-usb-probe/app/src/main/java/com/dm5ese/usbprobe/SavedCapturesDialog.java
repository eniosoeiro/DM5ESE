package com.dm5ese.usbprobe;

import android.app.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.File;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/** Offline library: latest capture per instrument/file, with older versions kept accessible. */
final class SavedCapturesDialog {
    private final Activity activity;
    private final Consumer<JSONObject> open;
    private final Map<String,List<JSONObject>> groups=new LinkedHashMap<>();
    private final LinearLayout list;
    private final TextView subtitle;
    private final EditText search;
    private final Button back;
    private final AlertDialog dialog;
    private final ScrollView scroll;
    private int copies, unreadable;
    SavedCapturesDialog(Activity activity, CaptureStore store, Consumer<JSONObject> open, Runnable summary) {
        this.activity=activity; this.open=open;
        List<JSONObject> loaded=new ArrayList<>();Map<String,String> draftGroups=new HashMap<>();
        for(File file:store.history())try {
            JSONObject capture=store.load(file);
            String serial=capture.getJSONObject("metadata").optString("SRNM");String name=capture.getString("file");
            if(capture.has("draftId"))draftGroups.putIfAbsent(capture.getString("draftId"),serial.isEmpty()?"draft:"+capture.getString("draftId"):serial+"\n"+name);
            loaded.add(capture);
        } catch(Exception e) { unreadable++; }
        for(var capture:loaded) {
            String key=capture.has("draftId") ? draftGroups.get(capture.optString("draftId")) : capture.optJSONObject("metadata").optString("SRNM")+"\n"+capture.optString("file");
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(capture);copies++;
        }
        for(var versions:groups.values())versions.sort(Comparator.comparing(SavedCapturesDialog::time).reversed());
        LinearLayout root=column();root.setPadding(dp(20),dp(20),dp(20),dp(12));root.setBackgroundColor(InstrumentStyle.PAGE);
        TextView title=text("Salvos no celular",24);title.setTypeface(null,Typeface.BOLD);root.addView(title);
        TextView offline=text("●  Disponíveis sem USB e sem internet",12);offline.setTextColor(InstrumentStyle.TEAL);root.addView(offline);
        subtitle=text("",13);subtitle.setPadding(0,dp(10),0,dp(12));root.addView(subtitle);
        back=button("‹  Voltar aos arquivos",()->showFiles());root.addView(back);
        search=new EditText(activity);search.setSingleLine(true);search.setTextSize(16);search.setHint("Buscar pelo nome do arquivo");
        search.setContentDescription("Buscar arquivo salvo");search.setTextColor(InstrumentStyle.INK);search.setHintTextColor(InstrumentStyle.MUTED);
        search.setPadding(dp(14),dp(12),dp(14),dp(12));search.setBackground(InstrumentStyle.surface(Color.WHITE,InstrumentStyle.LINE,dp(12)));
        root.addView(search,new LinearLayout.LayoutParams(-1,dp(52)));
        scroll=new ScrollView(activity);scroll.setFillViewport(true);
        list=column();list.setPadding(0,dp(12),0,dp(8));scroll.addView(list);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=new LinearLayout(activity);footer.setPadding(0,dp(8),0,0);
        Button report=button("Último download",summary);footer.addView(report,new LinearLayout.LayoutParams(0,dp(50),1));
        Button close=button("Fechar",()->dismiss());LinearLayout.LayoutParams closeParams=new LinearLayout.LayoutParams(0,dp(50),1);closeParams.leftMargin=dp(8);footer.addView(close,closeParams);root.addView(footer);
        dialog=new AlertDialog.Builder(activity).setView(root).create();
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){showFiles();}
            public void afterTextChanged(Editable e){}
        });
        showFiles();
    }
    void show() {
        dialog.show();Window window=dialog.getWindow();
        if(window!=null) {
            window.setBackgroundDrawable(InstrumentStyle.surface(InstrumentStyle.PAGE,0,dp(20)));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            window.setLayout(activity.getResources().getDisplayMetrics().widthPixels-dp(24),
                (int)(activity.getResources().getDisplayMetrics().heightPixels*.88));
        }
    }
    private void dismiss(){dialog.dismiss();}
    private void showFiles() {
        list.removeAllViews();back.setVisibility(View.GONE);search.setVisibility(View.VISIBLE);
        subtitle.setText(groups.size()+" arquivos · "+copies+" cópias guardadas\nVersão mais recente de cada arquivo");
        String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown=0;
        for(var versions:groups.values()) {
            JSONObject latest=versions.get(0);
            if(!latest.optString("file").toLowerCase(Locale.ROOT).contains(query))continue;
            addCard(latest,versions,false);shown++;
        }
        if(shown==0)list.addView(text(query.isEmpty()?"Ainda não há arquivos salvos neste celular.":"Nenhum arquivo encontrado. Tente outro nome.",16));
        if(unreadable>0)list.addView(text(unreadable+" cópias não puderam ser abertas. Os arquivos foram preservados.",12));
        scroll.scrollTo(0,0);
    }
    private void showVersions(List<JSONObject> versions) {
        list.removeAllViews();back.setVisibility(View.VISIBLE);search.setVisibility(View.GONE);
        subtitle.setText(versions.get(0).optString("file")+" · "+versions.size()+" versões\nDa mais recente para a mais antiga");
        for(var capture:versions)addCard(capture,versions,true);
        scroll.scrollTo(0,0);
    }
    private void addCard(JSONObject capture,List<JSONObject> versions,boolean versionView) {
        LinearLayout card=column();card.setPadding(dp(16),dp(14),dp(16),dp(12));
        card.setBackground(InstrumentStyle.surface(Color.WHITE,InstrumentStyle.LINE,dp(14)));
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.bottomMargin=dp(12);list.addView(card,layout);
        TextView name=text(capture.optString("file"),19);name.setTypeface(null,Typeface.BOLD);card.addView(name);
        JSONObject meta=capture.optJSONObject("metadata");JSONArray points=capture.optJSONArray("readings");
        card.addView(text((points==null?0:points.length())+" pontos · "+(meta==null?"":meta.optString("L2NL")+" × "+meta.optString("L3NL"))+" · mm",13));
        card.addView(text("Salvo em "+date(capture,versionView),13));
        if(meta!=null)card.addView(text(capture.optBoolean("newFileDraft") ? "Criado no celular · sem vínculo com instrumento" : "Instrumento "+meta.optString("SRNM","—"),12));
        int pending=0;try { pending=CaptureStore.pending(capture).size(); }catch(Exception ignored){}
        if(capture.optBoolean("newFileDraft")) {
            TextView badge=text("NOVO · aguardando envio ao DM5E",12);badge.setTextColor(InstrumentStyle.TEAL);card.addView(badge);
        }
        if(pending>0) {
            TextView draft=text("RASCUNHO · "+pending+" células pendentes de envio",12);draft.setTextColor(0xff805300);draft.setPadding(0,dp(8),0,dp(4));card.addView(draft);
        }
        String cloud=new ThicknessSyncQueue(activity).statusForLastAccount(capture);
        TextView cloudBadge=text(ThicknessSyncQueue.label(cloud),12);
        cloudBadge.setTextColor("RECEIVED".equals(cloud) ? InstrumentStyle.TEAL : "FAILED".equals(cloud) ? 0xffb42318 : 0xff7a5a00);card.addView(cloudBadge);
        Button select=button(versionView?"Abrir esta versão":"Abrir planilha",()->{dismiss();open.accept(capture);});
        InstrumentStyle.button(select,true,false);
        LinearLayout actions=new LinearLayout(activity);LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-1,-2);actionParams.topMargin=dp(10);card.addView(actions,actionParams);
        actions.addView(select,new LinearLayout.LayoutParams(0,dp(48),1));
        if(!versionView && versions.size()>1) {
            LinearLayout.LayoutParams versionParams=new LinearLayout.LayoutParams(0,dp(48),1);versionParams.leftMargin=dp(8);
            actions.addView(button("Ver "+versions.size()+" versões",()->showVersions(versions)),versionParams);
        }
    }
    private static Instant time(JSONObject c) {
        try{return Instant.parse(c.optString("editedAt",c.optString("capturedAt")));}catch(Exception e){return Instant.EPOCH;}
    }
    private String date(JSONObject c,boolean detailed) {
        Instant instant=time(c);if(instant.equals(Instant.EPOCH))return "data não disponível";
        return DateTimeFormatter.ofPattern(detailed?"dd/MM/yyyy 'às' HH:mm:ss":"dd/MM/yyyy 'às' HH:mm",new Locale("pt","BR")).withZone(ZoneId.systemDefault()).format(instant);
    }
    private LinearLayout column(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(String value,int size){TextView view=new TextView(activity);view.setText(value);view.setTextSize(size);view.setTextColor(size>=19?InstrumentStyle.INK:InstrumentStyle.MUTED);view.setPadding(0,dp(2),0,dp(2));return view;}
    private Button button(String label,Runnable action){Button view=new Button(activity);view.setText(label);view.setAllCaps(false);view.setTextSize(14);view.setMinHeight(dp(48));InstrumentStyle.button(view,false,false);view.setOnClickListener(v->action.run());return view;}
    private int dp(int value){return (int)(value*activity.getResources().getDisplayMetrics().density+.5f);}
}
