package com.anki.ledaaudio;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int PICK_DECK=101,PICK_CREDENTIALS=102,PICK_AUDIO=103,CREATE_OUTPUT=104;
    private Uri deckUri,audioZipUri;
    private JSONObject credentials;
    private ApkgProcessor.Plan plan;
    private int planMode=-1;
    private final AtomicBoolean cancelled=new AtomicBoolean(false);
    private boolean busy=false;
    private TextView deckLabel,credsLabel,audioLabel,status,description;
    private EditText project;
    private Spinner mode;
    private Button analyzeButton,exportButton,cancelButton;
    private android.os.PowerManager.WakeLock wakeLock;
    private String previousCrashNotice;

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        installCrashReporter();
        getWindow().setStatusBarColor(Color.rgb(14,51,66));
        getWindow().setNavigationBarColor(Color.rgb(14,51,66));
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad=dp(18); root.setPadding(pad,pad,pad,pad);
        root.setBackgroundColor(Color.rgb(246,249,251));
        scroll.addView(root);setContentView(scroll);
        TextView title=text("Anki · Leda Audio",24,true);title.setTextColor(Color.rgb(10,65,82));root.addView(title);
        TextView intro=text("Update Vietnamese audio directly on Android.\nOpen an AnkiDroid export, generate or reuse Google Leda MP3s, and save a new .apkg. Your live deck is never edited.",14,false);
        root.addView(intro);
        spacer(root,12);

        section(root,"1 · Your current deck");
        Button deck=button("Choose Anki .apkg");root.addView(deck);
        deckLabel=text("No package selected",13,false);root.addView(deckLabel);
        deck.setOnClickListener(v->choose(PICK_DECK,"application/*"));

        section(root,"2 · What to update");
        mode=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,
            new String[]{"Repair only missing/broken audio","Replace all non-Leda audio + repair missing"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mode.setAdapter(adapter);root.addView(mode);
        description=text("Default mode makes the fewest Google Cloud requests. Replacement mode keeps already-labelled Leda audio unchanged.",13,false);
        root.addView(description);
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int pos,long id){
                if(plan!=null && planMode!=pos)status.setText("Mode changed. Tap Analyze again before export.");
                updateButtons();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });

        section(root,"3 · Google Cloud access (for new recordings)");
        Button creds=button("Choose local Google credentials JSON");root.addView(creds);
        credsLabel=text("None selected. Import a Google ADC authorized_user or service-account JSON; nothing is uploaded except normal authenticated requests to Google.",12,false);
        root.addView(credsLabel);creds.setOnClickListener(v->choose(PICK_CREDENTIALS,"application/json"));
        TextView projectTitle=text("Google Cloud quota project",13,true);root.addView(projectTitle);
        project=new EditText(this);project.setSingleLine(true);project.setText("autovoice-cct9");
        project.setTextSize(16);project.setHint("Project ID");root.addView(project);
        TextView privateInfo=text("Credentials are kept only in memory, never saved to the output deck. Google Cloud Chirp 3 HD synthesis may incur charges.",12,false);
        root.addView(privateInfo);

        section(root,"4 · Optional: reuse existing Leda recordings");
        Button audio=button("Choose Leda audio ZIP (optional)");root.addView(audio);
        audioLabel=text("Use a chirp3_leda_audio.zip from the earlier generator to avoid synthesizing matching clips.",12,false);
        root.addView(audioLabel);audio.setOnClickListener(v->choose(PICK_AUDIO,"application/zip"));

        section(root,"5 · Analyze and export");
        analyzeButton=button("Analyze selected deck");root.addView(analyzeButton);
        analyzeButton.setOnClickListener(v->analyze());
        exportButton=button("Generate audio & save updated .apkg");root.addView(exportButton);
        exportButton.setOnClickListener(v->confirmExport());
        cancelButton=button("Cancel current job");root.addView(cancelButton);
        cancelButton.setOnClickListener(v->{cancelled.set(true);status.setText("Cancelling after current request…");});
        section(root,"Progress / audit");
        status=text(previousCrashNotice==null?"Select an .apkg file to begin.":previousCrashNotice,13,false);
        status.setTextIsSelectable(true);status.setPadding(dp(12),dp(12),dp(12),dp(12));
        status.setBackgroundColor(Color.WHITE);root.addView(status);
        updateButtons();
    }
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(String s,int size,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);
        t.setTextColor(Color.rgb(36,49,57));
        if(bold)t.setTypeface(null,1);
        t.setPadding(0,dp(5),0,dp(6));
        return t;
    }
    private void section(LinearLayout root,String heading){
        spacer(root,12);TextView t=text(heading,16,true);t.setTextColor(Color.rgb(10,65,82));root.addView(t);
    }
    private void spacer(LinearLayout root,int height){
        View v=new View(this);root.addView(v,new LinearLayout.LayoutParams(1,dp(height)));
    }
    private Button button(String label){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(15);return b;
    }
    private void choose(int request,String type){
        if(busy)return;
        // Many Android document providers label .json as text/plain or octet-stream.
        // Wildcard + EXTRA_MIME_TYPES is handled inconsistently by file picker apps.
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{
            startActivityForResult(i,request);
        }catch(RuntimeException e){
            error(new IOException("Could not open the Android file picker ("+
                e.getClass().getSimpleName()+"). Try the Files app or another document provider."));
        }
    }
    private void updateButtons(){
        if(analyzeButton==null)return;
        analyzeButton.setEnabled(!busy && deckUri!=null);
        exportButton.setEnabled(!busy && plan!=null && planMode==mode.getSelectedItemPosition());
        cancelButton.setEnabled(busy);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri u=data.getData();
        try{
            if(request==PICK_DECK){
                deckUri=u;plan=null;planMode=-1;
                deckLabel.setText("Selected: "+u.getLastPathSegment());
                status.setText("Ready to analyze. The original package stays unchanged.");
                updateButtons();
            }else if(request==PICK_CREDENTIALS){
                importCredentials(u);
            }else if(request==PICK_AUDIO){
                audioZipUri=u;audioLabel.setText("Selected: "+u.getLastPathSegment());
                status.setText("Leda cache ZIP selected. Files will be matched by spoken Vietnamese text.");
            }else if(request==CREATE_OUTPUT){
                if(deckUri!=null && deckUri.equals(u)){
                    status.setText("Choose a different output name. The original package will never be overwritten.");
                    return;
                }
                exportTo(u);
            }
        }catch(Exception e){error(e);}
    }
    /**
     * Run provider I/O and JSON parsing off the main/UI thread. Providers such as
     * Downloads and Drive can take time to stream a selected document.
     * No credential bytes, OAuth tokens or credential text are logged or persisted.
     */

    /** Read credentials independently from all deck-processing code. */
    private static byte[] readCredentialFile(InputStream stream) throws IOException {
        ByteArrayOutputStream result=new ByteArrayOutputStream();
        byte[] chunk=new byte[8192];
        int count;
        while((count=stream.read(chunk))!=-1){
            if(result.size()+count>1024*1024)
                throw new IOException("Credentials JSON is larger than 1 MB.");
            result.write(chunk,0,count);
        }
        if(result.size()==0)throw new IOException("The selected credentials file was empty.");
        return result.toByteArray();
    }

    private void importCredentials(Uri uri){
        if(busy)return;
        launch(()->{
            log("Reading Google credentials securely…");
            byte[] bytes;
            try(InputStream stream=getContentResolver().openInputStream(uri)){
                if(stream==null)throw new IOException("The selected document provider returned no file.");
                // Avoid loading the entire APKG processor just to read JSON.
                bytes=readCredentialFile(stream);
            }
            JSONObject parsed;
            try{
                parsed=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            }catch(Exception invalid){
                throw new IOException("The selected file is not valid JSON. Select application_default_credentials.json.");
            }
            String type=parsed.optString("type");
            if("authorized_user".equals(type)){
                if(parsed.optString("refresh_token").isEmpty() ||
                   parsed.optString("client_id").isEmpty() ||
                   parsed.optString("client_secret").isEmpty())
                    throw new IOException("ADC authorized_user JSON is missing required credentials. Run gcloud auth application-default login again.");
            }else if("service_account".equals(type)){
                if(parsed.optString("client_email").isEmpty() ||
                   parsed.optString("private_key").isEmpty())
                    throw new IOException("Service account JSON is missing client_email or private_key.");
            }else{
                throw new IOException("This is not a Google ADC authorized_user or service_account JSON. The filename alone is not sufficient.");
            }
            String quotaProject=parsed.optString("quota_project_id");
            // Retain the previous valid credential until parsing/validation succeeds.
            runOnUiThread(()->{
                credentials=parsed;
                if(!quotaProject.isEmpty())project.setText(quotaProject);
                credsLabel.setText("Loaded Google "+type+" credentials (kept in memory only).");
                status.setText("Credentials imported successfully. Ready to generate Leda audio.");
            });
        });
    }

    /**
     * Minimal, credential-safe crash diagnostic shown on next app launch.
     * Stores ONLY the exception class and application stack frame, never message
     * text, paths, JSON or tokens. This also catches crashes outside the picker.
     */
    private void installCrashReporter(){
        File report=new File(getFilesDir(),"last_crash.txt");
        if(report.isFile()){
            try(BufferedReader reader=new BufferedReader(new FileReader(report))){
                String message=reader.readLine();
                if(message!=null)previousCrashNotice="Previous crash: "+message+
                    "\nIf it happens again, tell me this code (do not share your credentials).";
            }catch(IOException ignored){}
            report.delete();
        }
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,throwable)->{
            try{
                String clazz=throwable.getClass().getSimpleName();
                String frame="unknown";
                for(StackTraceElement element:throwable.getStackTrace()){
                    if(element.getClassName().startsWith("com.anki.ledaaudio.")){
                        frame=element.getClassName().substring("com.anki.ledaaudio.".length())+
                            ":"+element.getLineNumber();
                        break;
                    }
                }
                Throwable cause=throwable.getCause();
                String reason=cause==null?"":" caused by "+cause.getClass().getSimpleName();
                try(FileWriter writer=new FileWriter(report,false)){
                    writer.write(clazz+" at "+frame+reason+"\n");
                }
            }catch(Throwable ignored){}
            if(previous!=null)previous.uncaughtException(thread,throwable);
            else android.os.Process.killProcess(android.os.Process.myPid());
        });
    }

    private void launch(Worker action){
        busy=true;cancelled.set(false);updateButtons();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        wakeLock=power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"AnkiLedaAudio:Export");
        wakeLock.acquire(60*60*1000L);
        new Thread(()->{
            try{action.run();}
            catch(Exception | LinkageError ex){runOnUiThread(()->error(ex));}
            finally{
                runOnUiThread(()->{
                    busy=false;updateButtons();
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
                });
            }
        },"LedaApkgWorker").start();
    }
    interface Worker{void run()throws Exception;}
    private void log(String msg){runOnUiThread(()->status.setText(msg));}
    private void analyze(){
        if(deckUri==null||busy)return;
        Uri selected=deckUri;int selectedMode=mode.getSelectedItemPosition();
        launch(()->{
            log("Reading selected APKG…");
            ApkgProcessor.Plan result=ApkgProcessor.inspect(this,selected,selectedMode,this::log);
            ApkgProcessor.Plan previous=plan;plan=result;planMode=selectedMode;
            if(previous!=null){previous.source.delete();previous.db.delete();}
            log(result.summary(selectedMode)+"\n\nChoose Generate to build a new package.");
        });
    }
    private void confirmExport(){
        if(plan==null||busy||planMode!=mode.getSelectedItemPosition())return;
        if(plan.edits.isEmpty()){
            new AlertDialog.Builder(this).setTitle("Already complete")
                .setMessage("No cards need updating in this mode. Your source deck was not changed.")
                .setPositiveButton("OK",null).show();return;
        }
        String caution=plan.uniqueJobs()+" distinct Leda recordings may be needed. Existing verified clips are reused. Google Cloud may charge for new synthesis. This operation never overwrites the imported package.";
        new AlertDialog.Builder(this).setTitle("Create updated Anki package?")
            .setMessage(caution)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Continue", (d,w)->{
                Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");
                i.putExtra(Intent.EXTRA_TITLE,"Vietnamese-Leda-Updated.apkg");
                startActivityForResult(i,CREATE_OUTPUT);
            }).show();
    }
    private void exportTo(Uri output){
        if(plan==null)return;
        ApkgProcessor.Plan current=plan;
        Uri previousAudio=audioZipUri;
        JSONObject googleCred=credentials;
        String projectId=project.getText().toString().trim();
        launch(()->ApkgProcessor.build(this,current,output,previousAudio,googleCred,projectId,this::log,cancelled));
    }
    private void error(Throwable ex){
        String msg=ex.getMessage();
        if(ex instanceof LinkageError){
            Throwable cause=ex.getCause();
            msg=ex.getClass().getSimpleName()+
                (cause==null?"":" (cause: "+cause.getClass().getSimpleName()+")");
        }else if(msg==null||msg.trim().isEmpty())msg=ex.toString();
        status.setText("ERROR: "+msg+"\n\nYour original .apkg has not been edited. Reopen a fresh source export to retry after a failed build.");
    }
    @Override protected void onDestroy(){
        cancelled.set(true);
        super.onDestroy();
    }
}
