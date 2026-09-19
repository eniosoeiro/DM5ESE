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
        Run(Activity a, ThicknessSyncClient c, ProgressDialog p) { activity=a; client=c; progress=p; }
        void stop() { stopped.set(true); client.cancel(); progress.dismiss(); }
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
        LinearLayout form=column(a);EditText email=new EditText(a);email.setHint("E-mail do IntegraNR");email.setInputType(33);form.addView(email);
        EditText password=new EditText(a);password.setHint("Senha");password.setInputType(129);form.addView(password);
        CheckBox consent=new CheckBox(a);consent.setText("Vincular estas revisões à minha conta/empresa e enviar. Repetir falhas selecionadas; nunca enviar pendências de outra conta.");form.addView(consent);
        AlertDialog login=new AlertDialog.Builder(a).setTitle("Enviar para minha conta").setMessage("A senha não é salva. Após o envio: Med.Online → Medição de espessuras. Mantenha o aplicativo aberto.")
            .setView(form).setNegativeButton("Cancelar",null).setPositiveButton("Vincular e enviar",null).create();
        login.setOnShowListener(v->login.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(z->{
            String e=email.getText().toString().trim(),pw=password.getText().toString();
            if(e.isEmpty()||pw.isEmpty()||!consent.isChecked()){consent.setError("Confirme a conta e o envio das revisões selecionadas.");return;}
            if(!BUSY.compareAndSet(false,true))return;
            password.setText("");login.dismiss();
            ProgressDialog progress=new ProgressDialog(a);progress.setTitle("ES Medição → IntegraNR");progress.setMessage("Validando conta…");progress.setIndeterminate(true);progress.setCancelable(false);
            ThicknessSyncClient client=new ThicknessSyncClient();Run run=new Run(a,client,progress);activeRun=run;
            progress.setButton(AlertDialog.BUTTON_NEGATIVE,"Pausar",(d,w)->run.stop());progress.show();
            worker.execute(()->sendSelected(run,e,pw,picked,refresh));
        }));login.show();
    }
    private static void sendSelected(Run run,String email,String password,List<JSONObject> picked,Runnable refresh){
        Activity a=run.activity;ThicknessSyncQueue queue=new ThicknessSyncQueue(a);StringBuilder errors=new StringBuilder();int received=0,waiting=0;
        try(ThicknessSyncClient client=run.client){
            client.login(email,password);queue.setAccount(client.userId(),client.partnerId());
            List<JSONObject> tasks=new ArrayList<>();
            // All selected tasks are saved before sending any capture, so interruption is recoverable.
            for(JSONObject snapshot:picked)try{tasks.add(queue.enqueue(client.userId(),client.partnerId(),snapshot,true));}
            catch(Exception ex){errors.append("\n").append(snapshot.optString("file","Arquivo")).append(": ").append(ThicknessSyncRules.safeMessage(ex));}
            for(JSONObject selected:tasks){
                if(run.stopped.get()||Thread.currentThread().isInterrupted()){waiting++;continue;}
                if("RECEIVED".equals(selected.optString("state"))){received++;continue;}
                JSONObject task=queue.begin(selected,System.currentTimeMillis());
                if(task==null){waiting++;continue;}
                JSONObject snapshot=task.getJSONObject("snapshot");String name=snapshot.optString("file","Captura");
                a.runOnUiThread(()->{if(!run.stopped.get())run.progress.setMessage("Enviando e conferindo recibo: "+name);});
                try{JSONObject receipt=client.send(snapshot);queue.receipt(task,receipt);received++;}
                catch(Exception ex){queue.failure(task,ex,System.currentTimeMillis());errors.append("\n").append(name).append(": ").append(ThicknessSyncRules.safeMessage(ex));}
            }
        }catch(Exception ex){errors.append("\n").append(ThicknessSyncRules.safeMessage(ex));}
        finally{BUSY.set(false);if(activeRun==run)activeRun=null;}
        String result=received+" de "+picked.size()+" revisões com recibo confirmado. "+(waiting>0?waiting+" pendente(s) aguardando nova tentativa. ":"")
            +"Os arquivos continuam salvos no celular. Para retomar, selecione as pendências em Sincronizar; o intervalo entre tentativas é respeitado."+errors;
        a.runOnUiThread(()->{
            if(a.isDestroyed()||a.isFinishing())return;run.progress.dismiss();refresh.run();if(run.stopped.get())return;
            new AlertDialog.Builder(a).setTitle("Resultado da sincronização").setMessage(result).setPositiveButton("OK",null)
                .setNeutralButton("Abrir IntegraNR",(d,w)->a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://app.gestaonr13.com.br/calibracao/med-online?tipo=espessuras")))).show();
        });
    }
    private ThicknessSyncDialog(){}
}
