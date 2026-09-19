package com.dm5ese.usbprobe;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

/** Only session tokens, never the password. AES-GCM key stays in AndroidKeyStore. */
final class ThicknessSessionStore implements ThicknessSyncClient.SessionStore {
    private static final Object LOCK=new Object();
    private static final String ALIAS="es.medicao.integranr.session.v1";
    private static final byte[] AAD=("es-medicao/session-v1/"+ThicknessSyncRules.PRODUCTION).getBytes(StandardCharsets.UTF_8);
    private final AtomicFile file;
    private final String alias;
    ThicknessSessionStore(Context context){this(new File(context.getNoBackupFilesDir(),"integranr-session.bin"),ALIAS);}
    // Test path and key alias must be isolated from the operator's real session.
    ThicknessSessionStore(File path,String alias){file=new AtomicFile(path);this.alias=alias;}
    private SecretKey key(boolean create)throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(alias))return (SecretKey)store.getKey(alias,null);
        if(!create)throw new IOException("Session encryption key unavailable");
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    @Override public JSONObject load()throws Exception{
        synchronized(LOCK){
            if(!file.getBaseFile().exists()&&!new File(file.getBaseFile()+".bak").exists())return null;
            byte[] bytes=file.readFully();if(bytes.length>65536)throw new IOException("Invalid session size");
            try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){
                if(in.readInt()!=1)throw new IOException("Unsupported session format");
                int count=in.readUnsignedByte();if(count!=12)throw new IOException("Invalid session IV");
                byte[] iv=new byte[count];in.readFully(iv);byte[] encrypted=in.readAllBytes();
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,iv));cipher.updateAAD(AAD);
                byte[] clear=cipher.doFinal(encrypted);
                try{return validated(new JSONObject(new String(clear,StandardCharsets.UTF_8)));}finally{Arrays.fill(clear,(byte)0);}
            }
        }
    }
    static JSONObject validated(JSONObject value)throws Exception{
        // Allowlist prevents an accidental caller from persisting passwords or unrelated payloads.
        JSONObject safe=new JSONObject().put("version",1).put("origin",ThicknessSyncRules.PRODUCTION);
        if(!ThicknessSyncRules.PRODUCTION.equals(value.getString("origin")))throw new IOException("Wrong session backend");
        for(String field:new String[]{"accessToken","refreshToken","email","userId","partnerId"}){
            String text=value.getString(field);if(text.isBlank()||text.length()>24000)throw new IOException("Invalid session field");safe.put(field,text);
        }
        ThicknessSyncSnapshot.uuid(safe.getString("userId"));ThicknessSyncSnapshot.uuid(safe.getString("partnerId"));
        long expires=value.getLong("expiresAt");if(expires<=0)throw new IOException("Invalid session expiry");safe.put("expiresAt",expires);return safe;
    }
    @Override public void save(JSONObject session)throws Exception{
        synchronized(LOCK){
            byte[] clear=validated(session).toString().getBytes(StandardCharsets.UTF_8);FileOutputStream stream=null;
            try{
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));cipher.updateAAD(AAD);
                byte[] encrypted=cipher.doFinal(clear),iv=cipher.getIV();
                if(iv.length!=12)throw new IOException("Unexpected GCM nonce");
                File parent=file.getBaseFile().getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Session storage unavailable");
                stream=file.startWrite();DataOutputStream out=new DataOutputStream(stream);out.writeInt(1);out.writeByte(iv.length);out.write(iv);out.write(encrypted);out.flush();file.finishWrite(stream);
            }catch(Exception e){file.failWrite(stream);throw e;}finally{Arrays.fill(clear,(byte)0);}
        }
    }
    @Override public void clear(){synchronized(LOCK){file.delete();}}
}
