package com.dm5ese.usbprobe;

import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.widget.*;
import org.json.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit selection and account binding. Only foreground sends; local collection stays offline. */
final class ThicknessSyncDialog {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile Run activeRun;
    private static ThicknessSelectionSheet activeSelection;
    private static Activity selectionOwner;
    private static final class Run {
        final Activity activity; final ThicknessSyncClient client; final ProgressDialog progress;
        final AtomicBoolean stopped = new AtomicBoolean();
        volatile java.util.concurrent.CompletableFuture<Boolean> decision; volatile android.app.Dialog confirmation;
        Run(Activity a, ThicknessSyncClient c, ProgressDialog p) { activity=a; client=c; progress=p; }
        void stop() { stopped.set(true); client.cancel(); if(decision!=null)decision.complete(false);if(confirmation!=null)confirmation.dismiss();progress.dismiss(); }
    }
    static void pause(Activity activity) { Run r=activeRun; if(r!=null && r.activity==activity) r.stop(); }
    private static LinearLayout column(Activity a) {
        LinearLayout view=new LinearLayout(a);view.setOrientation(LinearLayout.VERTICAL);
        int p=(int)(20*a.getResources().getDisplayMetrics().density);view.setPadding(p,p,p,0);return view;
    }
    private static void message(Activity a,String title,String text) {
        if(!a.isFinishing() && !a.isDestroyed())new AlertDialog.Builder(a).setTitle(title).setMessage(text).setPositiveButton("OK",null).show();
    }
    static void show(Activity a, CaptureStore store, Executor worker, Runnable refresh) {
        if(BUSY.get()){message(a,"Sincronização","Há um envio em andamento. Aguarde o resultado ou use Pausar.");return;}
        if(!new ThicknessSyncClient().configured()){message(a,"Configuração de sincronização","Instale o APK atualizado com configuração pública do IntegraNR.");return;}
        if(activeSelection!=null && activeSelection.isShowing())return;
        selectionOwner=a;
        activeSelection=ThicknessSelectionSheet.create(a,store,worker,picked->{
            if(activeSelection!=null){activeSelection.dismiss();activeSelection=null;}
            selectionOwner=null;
            credentials(a,worker,refresh,picked);
        });
        activeSelection.show();
    }
    static void dismissSelection(Activity a){
        if(selectionOwner==a && activeSelection!=null){activeSelection.dismiss();activeSelection=null;selectionOwner=null;}
    }

    private static void credentials(Activity a,Executor worker,Runnable refresh,List<JSONObject> picked){
        ThicknessAccountDialog.show(a,worker,picked.size(),choice->{
            if(!BUSY.compareAndSet(false,true))return;
            ProgressDialog progress=new ProgressDialog(a);progress.setTitle("ES Medição → IntegraNR");
            progress.setMessage("Conferindo conta e arquivos…");progress.setIndeterminate(true);progress.setCancelable(false);
            ThicknessSyncClient client=new ThicknessSyncClient();Run run=new Run(a,client,progress);activeRun=run;
            progress.setButton(AlertDialog.BUTTON_NEGATIVE,"Pausar",(d,w)->run.stop());progress.show();
            try{worker.execute(()->sendSelected(run,choice,picked,refresh));}
            catch(java.util.concurrent.RejectedExecutionException e){run.stop();BUSY.set(false);activeRun=null;}
        });
    }
    private static boolean confirmReplacement(Run run,JSONObject preview,String file)throws Exception{
        var answer=new java.util.concurrent.CompletableFuture<Boolean>();run.decision=answer;
        run.activity.runOnUiThread(()->{
            if(run.stopped.get()||run.activity.isDestroyed()||run.activity.isFinishing()){answer.complete(false);return;}
            run.progress.hide();boolean identical="duplicate".equals(preview.optString("status"));
            String detail=file+"\n\n"+(identical?"Este arquivo já existe na nuvem com o mesmo conteúdo. Confirmar reutiliza o registro existente, sem criar outra cópia.":
                "Uma versão deste arquivo já existe na nuvem. A nova versão ficará no lugar da atual; a anterior será preservada no histórico.")
                +"\n\nHash atual: "+preview.optString("existingHash")+"\n\nHash do envio: "+preview.optString("fileSha256");
            var context=new android.view.ContextThemeWrapper(run.activity,R.style.SyncAccountTheme);
            var alert=new com.google.android.material.dialog.MaterialAlertDialogBuilder(context).setTitle("Arquivo já existe. Sobrescrever?").setMessage(detail)
                .setNegativeButton("Manter existente",(d,w)->answer.complete(false))
                .setPositiveButton("Sobrescrever",(d,w)->answer.complete(true)).create();
            alert.setOnCancelListener(d->answer.complete(false));alert.setOnDismissListener(d->answer.complete(false));run.confirmation=alert;alert.show();
        });
        boolean confirmed;
        try{confirmed=answer.get(3,java.util.concurrent.TimeUnit.MINUTES);}
        catch(java.util.concurrent.TimeoutException e){confirmed=false;}
        finally{
            run.decision=null;
            run.activity.runOnUiThread(()->{if(run.confirmation!=null){run.confirmation.dismiss();run.confirmation=null;}if(!run.stopped.get()&&!run.activity.isDestroyed())run.progress.show();});
        }
        return confirmed&&!run.stopped.get();
    }
    private static void sendSelected(Run run,ThicknessAccountDialog.Choice choice,List<JSONObject> picked,Runnable refresh){
        Activity a=run.activity;ThicknessSyncQueue queue=new ThicknessSyncQueue(a);StringBuilder notes=new StringBuilder();int received=0,waiting=0;
        try(ThicknessSyncClient client=run.client){
            if(choice.saved!=null)client.restore(choice.saved,choice.store);
            else{client.login(choice.email,choice.password);if(choice.remember)client.remember(choice.store);}
            queue.setAccount(client.userId(),client.partnerId());
            for(JSONObject snapshot:picked){
                if(run.stopped.get()||Thread.currentThread().isInterrupted()){waiting++;continue;}
                String name=snapshot.optString("file","Captura");JSONObject task=null;
                try{
                    a.runOnUiThread(()->{if(!run.stopped.get())run.progress.setMessage("Conferindo hash: "+name);});
                    JSONObject preview=client.preview(snapshot);String status=preview.getString("status");
                    if("archived".equals(status)||"locked".equals(status)){
                        waiting++;notes.append("\n").append(name).append(" — ").append("archived".equals(status)?"revisão histórica; a versão atual foi preservada.":"já incorporado à inspeção; não pode ser sobrescrito.");continue;
                    }
                    if(!"new".equals(status)&&!confirmReplacement(run,preview,name)){
                        waiting++;notes.append("\n").append(name).append(" — arquivo existente mantido.");continue;
                    }
                    if(run.stopped.get()){waiting++;continue;}
                    JSONObject selected=queue.enqueue(client.userId(),client.partnerId(),snapshot,true);
                    selected=queue.recheckSelected(selected);
                    task=queue.begin(selected,System.currentTimeMillis());
                    if(task==null){waiting++;continue;}
                    JSONObject receipt=client.send(task.getJSONObject("snapshot"),preview);queue.receipt(task,receipt);received++;
                    notes.append("\n\n").append(name).append(" — confirmado\nHash: ").append(receipt.getString("fileSha256"));
                }catch(Exception e){if(task!=null)queue.failure(task,e,System.currentTimeMillis());notes.append("\n").append(name).append(": ").append(ThicknessSyncRules.safeMessage(e));}
            }
        }catch(Exception e){notes.append("\n").append(ThicknessSyncRules.safeMessage(e));}
        finally{BUSY.set(false);if(activeRun==run)activeRun=null;}
        String result=received+" de "+picked.size()+" arquivo(s) confirmado(s). "+(waiting>0?waiting+" não enviado(s). ":"")
            +"As capturas continuam no celular. O mesmo hash identifica o arquivo no Med.Online."+notes;
        a.runOnUiThread(()->{
            if(a.isDestroyed()||a.isFinishing())return;run.progress.dismiss();refresh.run();if(run.stopped.get())return;
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(new android.view.ContextThemeWrapper(a,R.style.SyncAccountTheme))
                .setTitle("Resultado da sincronização").setMessage(result).setPositiveButton("OK",null)
                .setNeutralButton("Abrir IntegraNR",(d,w)->a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://app.gestaonr13.com.br/calibracao/med-online?tipo=espessuras")))).show();
        });
    }
    private ThicknessSyncDialog(){}
}
