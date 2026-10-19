package com.anki.ledaaudio;

import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;

final class GoogleTts {
    static final String VOICE = "vi-VN-Chirp3-HD-Leda";
    private final JSONObject credentials;
    private final String project;
    private String accessToken;
    private long expiration;
    GoogleTts(JSONObject credentials, String project) {
        this.credentials=credentials; this.project=project.trim();
    }
    byte[] synthesize(String text) throws Exception {
        JSONObject body = new JSONObject();
        body.put("input", new JSONObject().put("text", text));
        body.put("voice", new JSONObject().put("languageCode","vi-VN").put("name", VOICE));
        body.put("audioConfig", new JSONObject().put("audioEncoding","MP3"));
        Exception failure=null;
        for(int attempt=0;attempt<6;attempt++) {
            try {
                JSONObject response = request("https://texttospeech.googleapis.com/v1/text:synthesize",
                    body.toString(),"Bearer " + token(),true);
                String encoded=response.getString("audioContent");
                byte[] bytes=Base64.decode(encoded,Base64.DEFAULT);
                if(bytes.length<256 || !ApkgProcessor.looksLikeMp3(bytes))
                    throw new IOException("Cloud TTS returned invalid/empty MP3 audio.");
                return bytes;
            } catch(IOException e) {
                failure=e;
                String msg=e.getMessage()==null?"":e.getMessage();
                if(msg.contains("401") && attempt==0) {accessToken=null;continue;}
                if(!(msg.contains("429")||msg.contains("500")||msg.contains("502")||
                        msg.contains("503")||msg.contains("504"))) throw e;
                Thread.sleep(Math.min(30000,1000L << attempt));
            }
        }
        throw failure;
    }
    private String token() throws Exception {
        if(accessToken!=null && System.currentTimeMillis()<expiration) return accessToken;
        String type=credentials.optString("type");
        String form;
        if("authorized_user".equals(type)){
            form="grant_type=refresh_token"
                +"&refresh_token="+enc(credentials.getString("refresh_token"))
                +"&client_id="+enc(credentials.getString("client_id"))
                +"&client_secret="+enc(credentials.getString("client_secret"));
        }else if("service_account".equals(type)){
            long now=System.currentTimeMillis()/1000L;
            String header=new JSONObject().put("alg","RS256").put("typ","JWT").toString();
            String claim=new JSONObject().put("iss",credentials.getString("client_email"))
                .put("scope","https://www.googleapis.com/auth/cloud-platform")
                .put("aud","https://oauth2.googleapis.com/token")
                .put("iat",now).put("exp",now+3600).toString();
            String unsigned=webB64(header.getBytes(StandardCharsets.UTF_8))+"."+webB64(claim.getBytes(StandardCharsets.UTF_8));
            String pem=credentials.getString("private_key").replaceAll("-----[^-]+-----","").replaceAll("\\s","");
            PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(pem,Base64.DEFAULT)));
            Signature signer=Signature.getInstance("SHA256withRSA");
            signer.initSign(key);signer.update(unsigned.getBytes(StandardCharsets.UTF_8));
            String jwt=unsigned+"."+webB64(signer.sign());
            form="grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="+enc(jwt);
        } else {
            throw new IOException("Credentials must be Google ADC authorized_user or service_account JSON.");
        }
        JSONObject o=request("https://oauth2.googleapis.com/token",form,null,false);
        accessToken=o.getString("access_token");
        expiration=System.currentTimeMillis()+Math.max(100, o.optLong("expires_in",3600)-120)*1000;
        return accessToken;
    }
    static String webB64(byte[] data){return Base64.encodeToString(data,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    static String enc(String s)throws Exception{return URLEncoder.encode(s,"UTF-8");}
    private JSONObject request(String dest, String data, String auth, boolean json) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(dest).openConnection();
        c.setRequestMethod("POST");c.setConnectTimeout(20000);c.setReadTimeout(90000);c.setDoOutput(true);
        c.setRequestProperty("Content-Type",json?"application/json; charset=utf-8":"application/x-www-form-urlencoded");
        if(auth!=null)c.setRequestProperty("Authorization",auth);
        if(json && !project.isEmpty())c.setRequestProperty("x-goog-user-project",project);
        try {
            byte[] bytes=data.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=c.getOutputStream()){out.write(bytes);}
            int code=c.getResponseCode();
            InputStream response=code>=400?c.getErrorStream():c.getInputStream();
            String answer=response==null?"":new String(ApkgProcessor.readAll(response,1024*1024*5),StandardCharsets.UTF_8);
            if(code>=400)throw new IOException("Google Cloud returned HTTP "+code+": "+answer.substring(0,Math.min(380,answer.length())));
            return new JSONObject(answer);
        } finally {c.disconnect();}
    }
}
