package com.dm5ese.usbprobe;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONObject;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Session remembered by explicit choice, never a plaintext password. */
final class ThicknessAccountDialog {
    static final class Choice {
        final String email,password;
        final JSONObject saved;
        final boolean remember;
        final ThicknessSessionStore store;
        Choice(String email,String password,JSONObject saved,boolean remember,ThicknessSessionStore store){
            this.email=email;this.password=password;this.saved=saved;this.remember=remember;this.store=store;
        }
    }
    private static boolean visible;
    private static Context theme(Activity a){return new ContextThemeWrapper(a,R.style.SyncAccountTheme);}
    static void show(Activity a,Executor worker,int count,Consumer<Choice> selected){
        if(visible)return;visible=true;
        ThicknessSessionStore store=new ThicknessSessionStore(a);
        ProgressDialog loading=ProgressDialog.show(a,"IntegraNR","Verificando conexão salva…",true,false);
        worker.execute(()->{
            JSONObject saved=null;boolean invalid=false;
            try{saved=store.load();}catch(Exception e){invalid=true;store.clear();}
            JSONObject account=saved;boolean damaged=invalid;
            a.runOnUiThread(()->{
                if(a.isDestroyed()||a.isFinishing()){visible=false;return;}
                loading.dismiss();
                if(account==null)login(a,count,store,damaged,selected);else connected(a,worker,count,store,account,selected);
            });
        });
    }
    private static void connected(Activity a,Executor worker,int count,ThicknessSessionStore store,JSONObject saved,Consumer<Choice> selected){
        var dialog=new MaterialAlertDialogBuilder(theme(a)).setTitle("Conectado ao IntegraNR")
            .setMessage(saved.optString("email")+"\n\n"+count+" arquivo(s) selecionado(s). Sua sessão será conferida antes do envio.")
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Conferir e enviar",(d,w)->selected.accept(new Choice(null,null,saved,true,store)))
            .setNeutralButton("Sair e esquecer",(d,w)->{
                ProgressDialog wait=ProgressDialog.show(a,"IntegraNR","Removendo conexão deste dispositivo…",true,false);
                worker.execute(()->{
                    try(ThicknessSyncClient client=new ThicknessSyncClient()){client.disconnectSaved(saved,store);}catch(Exception e){store.clear();}
                    a.runOnUiThread(()->{if(!a.isDestroyed()&&!a.isFinishing()){wait.dismiss();login(a,count,store,false,selected);}});
                });
            }).create();
        dialog.setOnDismissListener(d->visible=false);dialog.show();
    }
    private static void login(Activity a,int count,ThicknessSessionStore store,boolean invalid,Consumer<Choice> selected){
        Context c=theme(a);LinearLayout form=SyncSheetUi.vertical(c);
        int pad=SyncSheetUi.dp(c,24);form.setPadding(pad,pad,pad,0);
        EditText email=new EditText(c);email.setHint("E-mail do IntegraNR");email.setInputType(33);
        email.setAutofillHints(View.AUTOFILL_HINT_EMAIL_ADDRESS);form.addView(email);
        EditText password=new EditText(c);password.setHint("Senha");password.setInputType(129);
        password.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);form.addView(password);
        CheckBox remember=new CheckBox(c);remember.setText("Manter conectado neste dispositivo");remember.setChecked(true);form.addView(remember);
        form.addView(SyncSheetUi.text(c,"A conexão é protegida pelo Android. A senha não será gravada. Você pode sair e esquecer a conta.",12,SyncSheetUi.MUTED,false));
        CheckBox consent=new CheckBox(c);consent.setText("Confirmo o envio dos "+count+" arquivos selecionados para minha conta/empresa.");form.addView(consent);
        ScrollView scroll=new ScrollView(c);scroll.setFillViewport(true);scroll.addView(form);
        var dialog=new MaterialAlertDialogBuilder(c).setTitle("Entrar no IntegraNR")
            .setMessage(invalid?"A conexão salva não pôde ser recuperada. Entre novamente.":"Seus arquivos continuam salvos no celular.")
            .setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Conferir e enviar",null).create();
        dialog.setOnDismissListener(d->visible=false);
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            if(email.getText().toString().trim().isEmpty()){email.setError("Informe o e-mail");return;}
            if(password.getText().toString().isEmpty()){password.setError("Informe a senha");return;}
            if(!consent.isChecked()){consent.setError("Confirme o envio à sua conta");return;}
            Choice choice=new Choice(email.getText().toString().trim(),password.getText().toString(),null,remember.isChecked(),store);
            password.setText("");dialog.dismiss();selected.accept(choice);
        }));dialog.show();
    }
    private ThicknessAccountDialog(){}
}
