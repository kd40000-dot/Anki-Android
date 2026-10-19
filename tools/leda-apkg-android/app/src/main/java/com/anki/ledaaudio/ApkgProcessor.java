package com.anki.ledaaudio;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.text.Html;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import java.util.zip.*;

final class ApkgProcessor {
    static final Pattern SOUND=Pattern.compile("\\[sound:([^\\]]+)\\]",Pattern.CASE_INSENSITIVE);
    static final Pattern CLOZE=Pattern.compile("\\{\\{c\\d+::([^}:]+)(?:::[^}]*)?}}",Pattern.CASE_INSENSITIVE);
    static final int MISSING=0, ALL=1;
    interface Status{void onStatus(String message);}
    static final class MediaInfo {
        String name; long size; byte[] sha1; int index;
        MediaInfo(String n,long s,byte[] h,int i){name=n;size=s;sha1=h;index=i;}
    }
    static final class Edit {
        long id; String[] fields; String text; String filename; int audioField; boolean isSentence;
        Edit(long id,String[] parts,String value,String output,int field,boolean sentence){
            this.id=id;fields=parts;text=value;filename=output;audioField=field;isSentence=sentence;
        }
        String updatedFields(){
            String[] parts=fields.clone();
            String remaining=SOUND.matcher(parts[audioField]).replaceAll("").trim();
            parts[audioField]=(remaining.isEmpty()?"":remaining+"\n")+"[sound:"+filename+"]";
            return String.join("\u001f",parts);
        }
    }
    static final class Plan{
        File source; File db; Map<String,MediaInfo> media; byte[] mediaRaw;
        ArrayList<Edit> edits=new ArrayList<>();
        int notes, cards, silent, broken, alreadyLeda, reviewLogs;
        int uniqueJobs(){return (int)edits.stream().map(e->e.filename).distinct().count();}
        String summary(int mode){
            return "Notes: "+notes+"\nCards: "+cards+"\nSaved reviews: "+reviewLogs+
            "\nNotes without audio: "+silent+"\nBroken audio references: "+broken+
            "\nAlready using Leda filenames: "+alreadyLeda+
            "\nNotes to update: "+edits.size()+"\nDistinct Leda MP3s needed: "+uniqueJobs()+
            "\nMode: "+(mode==MISSING?"Repair missing/broken only":"Replace non-Leda audio, plus missing");
        }
    }
    static Plan inspect(Context context,Uri uri,int mode,Status log) throws Exception{
        File input=new File(context.getCacheDir(),"leda-input-"+System.currentTimeMillis()+".apkg");
        try(InputStream source=context.getContentResolver().openInputStream(uri);
            OutputStream dst=new FileOutputStream(input)){
            if(source==null)throw new IOException("Could not open the selected deck.");
            copy(source,dst);
        }
        Plan p=new Plan();p.source=input;
        p.db=new File(context.getCacheDir(),"leda-collection-"+System.currentTimeMillis()+".sqlite");
        try(ZipFile zip=new ZipFile(input)){
            if(zip.getEntry("collection.anki21b")==null||zip.getEntry("media")==null||zip.getEntry("meta")==null)
                throw new IOException("This app currently supports modern AnkiDroid APKG exports (collection.anki21b + media).");
            log.onStatus("Reading Anki collection…");
            try(InputStream x=unpack(zip.getInputStream(zip.getEntry("collection.anki21b")));
                OutputStream y=new FileOutputStream(p.db)){copy(x,y);}
            log.onStatus("Reading media references…");
            p.mediaRaw=readAll(unpack(zip.getInputStream(zip.getEntry("media"))),40*1024*1024);
            p.media=readManifest(p.mediaRaw);
            if(p.media.size()==0)throw new IOException("The media manifest is empty.");
            SQLiteDatabase db=SQLiteDatabase.openDatabase(p.db.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);
            try{
                if(!"ok".equalsIgnoreCase(singleText(db,"PRAGMA integrity_check")))
                    throw new IOException("Original collection failed SQLite integrity check.");
                p.cards=singleCount(db,"SELECT count(*) FROM cards");
                p.reviewLogs=singleCount(db,"SELECT count(*) FROM revlog");
                try(Cursor cur=db.rawQuery("SELECT id,flds FROM notes",null)){
                    while(cur.moveToNext()){
                        p.notes++;
                        String[] fields=cur.getString(1).split("\u001f",-1);
                        if(fields.length!=2 && fields.length!=3) continue; // unrecognized model: preserve
                        int targetField=fields.length==3?2:1;
                        Matcher matcher=SOUND.matcher(fields[targetField]);
                        boolean soundFound=matcher.find();
                        boolean missing=!soundFound;
                        boolean broken=false,led=false;
                        matcher.reset();
                        while(matcher.find()){
                            String name=Html.fromHtml(matcher.group(1),Html.FROM_HTML_MODE_LEGACY).toString().trim();
                            MediaInfo item=p.media.get(name);
                            if(item==null || zip.getEntry(String.valueOf(item.index))==null)broken=true;
                            if(name.startsWith("leda_vi_") && item!=null)led=true;
                        }
                        if(missing)p.silent++;
                        if(broken)p.broken++;
                        if(led)p.alreadyLeda++;
                        if(mode==MISSING && !missing && !broken)continue;
                        if(mode==ALL && led && !broken)continue;
                        boolean sentence=fields.length==2;
                        String sourceText=sentence?fields[0]:fields[1];
                        String text=cleanVietnamese(sourceText,sentence);
                        if(text.isEmpty())continue;
                        String file=filename(text,sentence);
                        p.edits.add(new Edit(cur.getLong(0),fields,text,file,targetField,sentence));
                    }
                }
            }finally{db.close();}
        }catch(Exception e){
            input.delete();p.db.delete();throw e;
        }
        return p;
    }
    static void build(Context context,Plan p,Uri output,Uri optionalZip,JSONObject credentials,String project,
                      Status log,AtomicBoolean cancelled)throws Exception {
        Map<String,byte[]> supplied=loadSupplied(context,optionalZip,log);
        File cache=new File(context.getFilesDir(),"verified_leda_mp3");
        if(!cache.exists()&&!cache.mkdirs())throw new IOException("Cannot create audio cache.");
        LinkedHashMap<String,Edit> jobs=new LinkedHashMap<>();
        for(Edit e:p.edits)jobs.putIfAbsent(e.filename,e);
        LinkedHashMap<String,byte[]> audio=new LinkedHashMap<>();
        GoogleTts google=credentials==null?null:new GoogleTts(credentials,project);
        int index=0;
        for(Map.Entry<String,Edit> pair:jobs.entrySet()){
            if(cancelled.get())throw new IOException("Cancelled. Previously generated audio remains cached.");
            index++;
            Edit e=pair.getValue();String name=e.filename;
            byte[] mp3=null;
            File cached=new File(cache,name);
            if(cached.isFile() && cached.length()>256){
                byte[] bytes=readAll(new FileInputStream(cached),30*1024*1024);
                if(looksLikeMp3(bytes))mp3=bytes;
            }
            if(mp3==null){
                mp3=supplied.get(e.text);
                if(mp3!=null && looksLikeMp3(mp3)){
                    try(OutputStream dest=new FileOutputStream(cached)){dest.write(mp3);}
                }else mp3=null;
            }
            if(mp3==null){
                if(google==null)throw new IOException("Audio missing for \""+e.text+"\". Import Google ADC credentials or a Leda audio ZIP.");
                log.onStatus("Generating "+index+"/"+jobs.size()+": "+e.text);
                mp3=google.synthesize(e.text);
                try(OutputStream dest=new FileOutputStream(cached)){dest.write(mp3);}
            }else{
                log.onStatus("Reusing "+index+"/"+jobs.size()+": "+e.text);
            }
            audio.put(name,mp3);
        }
        if(cancelled.get())throw new IOException("Cancelled.");
        log.onStatus("Updating only audio fields in collection…");
        SQLiteDatabase db=SQLiteDatabase.openDatabase(p.db.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE);
        try{
            db.beginTransaction();
            try{
                for(Edit e:p.edits){
                    db.execSQL("UPDATE notes SET flds=?,mod=?,usn=-1 WHERE id=?",
                        new Object[]{e.updatedFields(),System.currentTimeMillis()/1000L,e.id});
                }
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            if(!"ok".equalsIgnoreCase(singleText(db,"PRAGMA integrity_check")))
                throw new IOException("Updated collection failed SQLite integrity check.");
            if(singleCount(db,"SELECT count(*) FROM cards")!=p.cards||
               singleCount(db,"SELECT count(*) FROM revlog")!=p.reviewLogs)
                throw new IOException("Card or review counts changed unexpectedly. Export aborted.");
        }finally{db.close();}
        // Preserve original media numbering and every unmodified archive item.
        TreeMap<String,byte[]> additions=new TreeMap<>();
        for(Map.Entry<String,byte[]> e:audio.entrySet()){
            MediaInfo current=p.media.get(e.getKey());
            if(current==null)additions.put(e.getKey(),e.getValue());
            else{
                if(current.size!=e.getValue().length||
                   !Arrays.equals(current.sha1,digest("SHA-1",e.getValue())))
                    throw new IOException("Existing audio filename collision: "+e.getKey());
            }
        }
        ByteArrayOutputStream manifest=new ByteArrayOutputStream();
        manifest.write(p.mediaRaw);
        int indexStart=p.media.size();
        int offset=0;
        for(Map.Entry<String,byte[]> e:additions.entrySet()){
            byte[] entry=mediaEntry(e.getKey(),e.getValue());
            writeField(manifest,1,entry);
            offset++;
        }
        log.onStatus("Writing new Anki package; preserving "+p.cards+" cards and "+p.reviewLogs+" reviews…");
        File staged=new File(context.getCacheDir(),"leda-ready-"+System.currentTimeMillis()+".apkg");
        try{
            try(ZipFile src=new ZipFile(p.source);
                ZipOutputStream out=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(staged)))){
                Enumeration<? extends ZipEntry> entries=src.entries();
                byte[] buf=new byte[8192];
                while(entries.hasMoreElements()){
                    ZipEntry entry=entries.nextElement();
                    if(cancelled.get())throw new IOException("Cancelled during export.");
                    ZipEntry result=new ZipEntry(entry.getName());
                    out.putNextEntry(result);
                    if(entry.getName().equals("collection.anki21b")){
                        try(InputStream in=new FileInputStream(p.db)){writeCompressed(in,out);}
                    }else if(entry.getName().equals("media")){
                        writeCompressed(new ByteArrayInputStream(manifest.toByteArray()),out);
                    }else{
                        try(InputStream in=src.getInputStream(entry)){
                            int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
                        }
                    }
                    out.closeEntry();
                }
                int i=indexStart;
                for(Map.Entry<String,byte[]> e:additions.entrySet()){
                    if(cancelled.get())throw new IOException("Cancelled during export.");
                    out.putNextEntry(new ZipEntry(String.valueOf(i++)));
                    writeCompressed(new ByteArrayInputStream(e.getValue()),out);
                    out.closeEntry();
                }
            }
            // Verify the complete package before opening the user's destination document.
            try(ZipFile verify=new ZipFile(staged)){
                if(verify.getEntry("collection.anki21b")==null||verify.getEntry("media")==null)
                    throw new IOException("Output archive is incomplete.");
                byte[] checked=readAll(unpack(verify.getInputStream(verify.getEntry("media"))),40*1024*1024);
                Map<String,MediaInfo> confirmed=readManifest(checked);
                if(confirmed.size()!=p.media.size()+additions.size())
                    throw new IOException("Exported media count does not match additions.");
                for(MediaInfo item:confirmed.values()){
                    if(verify.getEntry(String.valueOf(item.index))==null)
                        throw new IOException("Exported package missing media entry "+item.index);
                }
            }
            if(cancelled.get())throw new IOException("Cancelled before saving.");
            try(InputStream ready=new BufferedInputStream(new FileInputStream(staged));
                OutputStream saved=context.getContentResolver().openOutputStream(output,"w")){
                if(saved==null)throw new IOException("Could not open selected output document.");
                copy(ready,saved);
            }
        }finally{staged.delete();}
        log.onStatus("SUCCESS: "+p.edits.size()+" notes updated; "+additions.size()+
                     " new MP3s packaged; card IDs and review history preserved.");
    }
    static Map<String,byte[]> loadSupplied(Context context,Uri uri,Status log) throws Exception{
        Map<String,byte[]> out=new HashMap<>();
        if(uri==null)return out;
        log.onStatus("Reading already-generated Leda audio ZIP…");
        File file=new File(context.getCacheDir(),"leda-existing.zip");
        try(InputStream in=context.getContentResolver().openInputStream(uri);
            OutputStream dest=new FileOutputStream(file)){copy(in,dest);}
        try(ZipFile zip=new ZipFile(file)){
            ZipEntry manifest=zip.getEntry("audio_manifest.json");
            if(manifest==null)throw new IOException("The audio ZIP needs audio_manifest.json from the Leda generator.");
            JSONObject data=new JSONObject(new String(readAll(zip.getInputStream(manifest),5*1024*1024),StandardCharsets.UTF_8));
            String voice=data.optString("voice_name");
            if(!GoogleTts.VOICE.equals(voice))
                throw new IOException("Audio ZIP reports a different voice ("+voice+"). Refusing non-Leda recordings.");
            JSONArray jobs=data.getJSONArray("jobs");
            for(int i=0;i<jobs.length();i++){
                JSONObject job=jobs.getJSONObject(i);
                String name=job.getString("filename");String text=job.getString("text").trim();
                ZipEntry entry=zip.getEntry(name);
                if(entry==null)entry=zip.getEntry("audio/"+name);
                if(entry==null)continue;
                byte[] bytes=readAll(zip.getInputStream(entry),20*1024*1024);
                if(looksLikeMp3(bytes))out.put(text,bytes);
            }
        }finally{file.delete();}
        log.onStatus("Imported "+out.size()+" existing Leda audio clips.");
        return out;
    }
    static String cleanVietnamese(String value,boolean sentence){
        value=CLOZE.matcher(value).replaceAll("$1");
        value=SOUND.matcher(value).replaceAll("");
        value=Html.fromHtml(value,Html.FROM_HTML_MODE_LEGACY).toString();
        value=value.replace('\u00a0',' ').replaceAll("\\s+"," ").trim();
        if(!sentence && value.contains("|"))value=value.split("\\|",2)[0].trim();
        return value;
    }
    static String filename(String text,boolean sentence) throws Exception{
        byte[] hash=digest("SHA-256",text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();
        for(int i=0;i<6;i++)hex.append(String.format(Locale.US,"%02x",hash[i]&0xff));
        String ascii=Normalizer.normalize(text.replace("đ","d").replace("Đ","D"),Normalizer.Form.NFD)
            .replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+","_").replaceAll("^_+|_+$","");
        if(ascii.length()>38)ascii=ascii.substring(0,38).replaceAll("_+$","");
        return "leda_vi_"+(sentence?"sentence":"word")+"_"+hex+"_"+ascii+".mp3";
    }
    static Map<String,MediaInfo> readManifest(byte[] bytes)throws IOException{
        LinkedHashMap<String,MediaInfo> list=new LinkedHashMap<>();
        int[] p={0};int index=0;
        while(p[0]<bytes.length){
            long field=varint(bytes,p);
            if(field!=10)throw new IOException("Unexpected Anki media protobuf field: "+field);
            byte[] record=lenBytes(bytes,p);
            int[] r={0};String name=null;long size=-1;byte[] sha=null;
            while(r[0]<record.length){
                long key=varint(record,r);
                if(key==10)name=new String(lenBytes(record,r),StandardCharsets.UTF_8);
                else if(key==16)size=varint(record,r);
                else if(key==26)sha=lenBytes(record,r);
                else throw new IOException("Unknown Anki media entry field "+key);
            }
            if(name==null||sha==null||sha.length!=20||size<0||list.containsKey(name))
                throw new IOException("Malformed Anki media manifest entry "+index);
            list.put(name,new MediaInfo(name,size,sha,index++));
        }
        return list;
    }
    static byte[] mediaEntry(String name,byte[] audio) throws Exception{
        ByteArrayOutputStream record=new ByteArrayOutputStream();
        writeField(record,1,name.getBytes(StandardCharsets.UTF_8));
        writeVarint(record,2<<3);
        writeVarint(record,audio.length);
        writeField(record,3,digest("SHA-1",audio));
        return record.toByteArray();
    }
    static void writeField(OutputStream out,int tag,byte[] data)throws IOException{
        writeVarint(out,((long)tag<<3)|2);
        writeVarint(out,data.length);out.write(data);
    }
    static void writeVarint(OutputStream out,long num)throws IOException{
        while((num&~127L)!=0){out.write(((int)num&127)|128);num>>>=7;}
        out.write((int)num);
    }
    static long varint(byte[] data,int[] p)throws IOException{
        long x=0;int shift=0;
        while(shift<70 && p[0]<data.length){
            int b=data[p[0]++]&255;x|=(long)(b&127)<<shift;
            if((b&128)==0)return x;
            shift+=7;
        }
        throw new IOException("Malformed protobuf varint");
    }
    static byte[] lenBytes(byte[] d,int[] p)throws IOException{
        long n=varint(d,p);if(n<0||n>20*1024*1024||n>d.length-p[0])throw new IOException("Invalid protobuf length");
        byte[] bytes=Arrays.copyOfRange(d,p[0],p[0]+(int)n);p[0]+=(int)n;return bytes;
    }
    static InputStream unpack(InputStream stream)throws IOException{
        BufferedInputStream b=new BufferedInputStream(stream);
        b.mark(4);byte[] header=new byte[4];int n=b.read(header);b.reset();
        if(n==4 && header[0]==0x28&&(header[1]&255)==0xb5&&header[2]==0x2f&&(header[3]&255)==0xfd)
            return new ZstdInputStream(b);
        return b;
    }
    static void writeCompressed(InputStream source,OutputStream dst)throws IOException{
        // ZstdOutputStream.close() would close ZipOutputStream; use a temporary buffer.
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();
        try(ZstdOutputStream zstd=new ZstdOutputStream(compressed)){copy(source,zstd);}
        dst.write(compressed.toByteArray());
    }
    static String singleText(SQLiteDatabase db,String query){
        try(Cursor c=db.rawQuery(query,null)){return c.moveToFirst()?c.getString(0):"";}
    }
    static int singleCount(SQLiteDatabase db,String query){
        try(Cursor c=db.rawQuery(query,null)){return c.moveToFirst()?c.getInt(0):0;}
    }
    static boolean looksLikeMp3(byte[] data){
        return data.length>256 && ((data[0]=='I'&&data[1]=='D'&&data[2]=='3') ||
               ((data[0]&255)==255 && (data[1]&0xe0)==0xe0));
    }
    static byte[] digest(String algo,byte[] data)throws Exception{return MessageDigest.getInstance(algo).digest(data);}
    static void copy(InputStream in,OutputStream out)throws IOException{
        byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
    }
    static byte[] readAll(InputStream in,int limit)throws IOException{
        try(InputStream source=in){
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            byte[] buf=new byte[8192];int n;
            while((n=source.read(buf))!=-1){
                if(b.size()+n>limit)throw new IOException("Input exceeds safety limit: "+limit);
                b.write(buf,0,n);
            }
            return b.toByteArray();
        }
    }
}
