package com.balthazar.kittenswear;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.AtomicFile;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

final class LocalGameServer {
 static final int PORT=18742;
 private static final String RELATIVE_PATH=Environment.DIRECTORY_DOWNLOADS+"/Kittens Game/";
 private final Context context; private final ServerSocket socket;
 LocalGameServer(Context c)throws IOException{context=c.getApplicationContext();socket=new ServerSocket();socket.setReuseAddress(true);socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),PORT));var pool=Executors.newFixedThreadPool(4);Thread t=new Thread(()->{while(!socket.isClosed())try{Socket s=socket.accept();pool.execute(()->serve(s));}catch(IOException ignored){}},"Kittens assets");t.setDaemon(true);t.start();}
 private static String line(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int x;while((x=in.read())!=-1&&x!='\n'){if(x!='\r')b.write(x);if(b.size()>8192)throw new IOException("Header too large");}return b.toString("UTF-8");}
 private File backup(){return new File(context.getFilesDir(),"game-backup.json");}
 private void serve(Socket socket){
  try(Socket s=socket){s.setSoTimeout(10000);InputStream in=new BufferedInputStream(s.getInputStream());String[] request=line(in).split(" ");if(request.length<2)return;int length=0;String h;while(!(h=line(in)).isEmpty())if(h.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(h.substring(15).trim());if(length<0||length>16000000)throw new IOException("Body too large");String path=URLDecoder.decode(request[1].split("\\?")[0],"UTF-8");if(path.contains("..")||path.indexOf('\0')>=0){reply(s,403,"text/plain",new byte[0]);return;}byte[] data;String type="application/json";
   if(request[0].equals("POST")&&path.equals("/complication-state")){data=in.readNBytes(length);if(data.length!=length)throw new IOException("Incomplete body");ComplicationStore.saveSnapshot(context,new String(data,StandardCharsets.UTF_8));data="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);}
   else if(request[0].equals("POST")&&path.equals("/open-complication-settings")){android.content.Intent intent=new android.content.Intent(context,ComplicationConfigActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(intent);data="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);}
   else if(request[0].equals("POST")&&(path.equals("/backup")||path.equals("/export"))){data=in.readNBytes(length);if(data.length!=length)throw new IOException("Incomplete body");org.json.JSONObject parsed=new org.json.JSONObject(new String(data,StandardCharsets.UTF_8));if(path.equals("/backup")){AtomicFile f=new AtomicFile(backup());FileOutputStream out=null;try{out=f.startWrite();out.write(data);f.finishWrite(out);}catch(IOException e){if(out!=null)f.failWrite(out);throw e;}data="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);}else{byte[] save=parsed.getString("exportText").getBytes(StandardCharsets.UTF_8);writeDownload("kittens-save.txt",save);data="{\"ok\":true,\"path\":\"Download/Kittens Game/kittens-save.txt\"}".getBytes(StandardCharsets.UTF_8);}}
   else if(path.equals("/restore")){AtomicFile f=new AtomicFile(backup());data=f.getBaseFile().exists()?f.readFully():"null".getBytes();}
   else if(path.equals("/import")){Uri u=findDownload("kittens-import.txt");if(u==null){reply(s,404,"text/plain","Put kittens-import.txt in Download/Kittens Game/ first.".getBytes(StandardCharsets.UTF_8));return;}try(InputStream f=context.getContentResolver().openInputStream(u)){if(f==null)throw new FileNotFoundException();data=f.readNBytes(16000000);}type="text/plain";}
   else{if(path.equals("/"))path="/index.html";try(InputStream asset=context.getAssets().open("game"+path)){data=asset.readAllBytes();}catch(FileNotFoundException e){reply(s,404,"text/plain",new byte[0]);return;}type=path.endsWith(".html")?"text/html":path.endsWith(".js")?"application/javascript":path.endsWith(".css")?"text/css":path.endsWith(".json")?"application/json":path.endsWith(".png")?"image/png":path.endsWith(".gif")?"image/gif":path.endsWith(".svg")?"image/svg+xml":"application/octet-stream";}
   reply(s,200,type,data);
  }catch(Exception ignored){}
 }
 private Uri findDownload(String name){
  ContentResolver cr=context.getContentResolver();Uri base=MediaStore.Downloads.EXTERNAL_CONTENT_URI;
  String[] projection={MediaStore.MediaColumns._ID};String selection=MediaStore.MediaColumns.DISPLAY_NAME+"=? AND "+MediaStore.MediaColumns.RELATIVE_PATH+"=?";
  try(Cursor c=cr.query(base,projection,selection,new String[]{name,RELATIVE_PATH},MediaStore.MediaColumns.DATE_MODIFIED+" DESC")){if(c!=null&&c.moveToFirst())return Uri.withAppendedPath(base,Long.toString(c.getLong(0)));}catch(Exception ignored){}return null;
 }
 private void writeDownload(String name,byte[] bytes)throws IOException{
  ContentResolver cr=context.getContentResolver();Uri u=findDownload(name);
  if(u==null){ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);v.put(MediaStore.MediaColumns.MIME_TYPE,"text/plain");v.put(MediaStore.MediaColumns.RELATIVE_PATH,RELATIVE_PATH);u=cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(u==null)throw new IOException("Could not create save file");}
  try(OutputStream out=cr.openOutputStream(u,"wt")){if(out==null)throw new IOException("Could not open save file");out.write(bytes);out.flush();}
 }
 private void reply(Socket s,int status,String type,byte[] data)throws IOException{OutputStream o=s.getOutputStream();String csp="default-src 'self' data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; object-src 'none'; frame-src 'none'; base-uri 'self'";o.write(("HTTP/1.1 "+status+" OK\r\nContent-Type: "+type+"; charset=utf-8\r\nContent-Length: "+data.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nContent-Security-Policy: "+csp+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));o.write(data);o.flush();}
}
