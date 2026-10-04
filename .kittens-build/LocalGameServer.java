package com.balthazar.kittenswear;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.AtomicFile;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;

final class LocalGameServer {
 static final int PORT=18742, TRANSFER_PORT=18743, MAX_BODY=16_000_000;
 private static final long TRANSFER_TTL_MS=15*60*1000L;
 private static final String AUTOSAVE_PATH=Environment.DIRECTORY_DOWNLOADS+"/Kittens Game/Autosaves/";
 private final Context context;
 private final ServerSocket socket;
 private final java.util.concurrent.ExecutorService pool=Executors.newFixedThreadPool(6);

 private volatile ServerSocket transferSocket;
 private volatile String transferToken;
 private volatile String transferExport;
 private volatile String transferExportSha;
 private volatile String transferUrl;
 private volatile long transferExpires;
 private volatile String pendingImport;
 private volatile String pendingImportSha;
 private volatile long pendingImportId;
 private volatile String diagnosticReport="{\"state\":\"idle\"}";
 private volatile String diagnosticExport;

 LocalGameServer(Context c)throws IOException{
  context=c.getApplicationContext();
  socket=new ServerSocket();socket.setReuseAddress(true);
  socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),PORT));
  Thread t=new Thread(()->{
   while(!socket.isClosed())try{Socket s=socket.accept();pool.execute(()->serveLocal(s));}catch(IOException ignored){}
  },"Kittens assets");
  t.setDaemon(true);t.start();
 }

 private static String line(InputStream in)throws IOException{
  ByteArrayOutputStream b=new ByteArrayOutputStream();int x;
  while((x=in.read())!=-1&&x!='\n'){
   if(x!='\r')b.write(x);
   if(b.size()>16384)throw new IOException("Header line too large");
  }
  return b.toString("UTF-8");
 }
 private File backup(){return new File(context.getFilesDir(),"game-backup.json");}
 private static String esc(String s){
  if(s==null)return "";
  return s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n").replace("\t","\\t");
 }
 private static String html(String s){
  if(s==null)return "";
  return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
 }
 private static String errorJson(String op,Throwable e,String detail){
  String cause=e.getCause()!=null?e.getCause().getClass().getName()+": "+String.valueOf(e.getCause().getMessage()):"";
  return "{\"state\":\"error\",\"operation\":\""+esc(op)+"\",\"type\":\""+esc(e.getClass().getName())+"\",\"message\":\""+esc(String.valueOf(e.getMessage()))+"\",\"cause\":\""+esc(cause)+"\",\"detail\":\""+esc(detail)+"\"}";
 }

 private void serveLocal(Socket socket){
  try(Socket s=socket){
   s.setSoTimeout(15000);
   InputStream in=new BufferedInputStream(s.getInputStream());
   String[] request=line(in).split(" ");
   if(request.length<2)return;
   int length=readHeaders(in);
   if(length<0||length>MAX_BODY)throw new IOException("Body too large: "+length);
   String path=URLDecoder.decode(request[1].split("\\?")[0],"UTF-8");
   if(path.contains("..")||path.indexOf('\0')>=0){reply(s,403,"application/json","{\"state\":\"error\",\"message\":\"Invalid path\"}".getBytes(StandardCharsets.UTF_8));return;}
   byte[] data;String type="application/json";

   if(request[0].equals("POST")&&path.equals("/complication-state")){
    data=in.readNBytes(length);if(data.length!=length)throw new IOException("Incomplete complication body");
    ComplicationStore.saveSnapshot(context,new String(data,StandardCharsets.UTF_8));
    data="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
   }else if(request[0].equals("POST")&&path.equals("/open-complication-settings")){
    android.content.Intent intent=new android.content.Intent(context,ComplicationConfigActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(intent);
    data="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
   }else if(request[0].equals("POST")&&(path.equals("/backup")||path.equals("/save-manual"))){
    byte[] body=in.readNBytes(length);if(body.length!=length)throw new IOException("Incomplete save body");
    data=(path.equals("/save-manual")?manualSave(body):internalSave(body)).getBytes(StandardCharsets.UTF_8);
   }else if(request[0].equals("POST")&&path.equals("/diagnostic/report")){
    byte[] body=in.readNBytes(length);if(body.length!=length)throw new IOException("Incomplete diagnostic report");
    String report=new String(body,StandardCharsets.UTF_8);new org.json.JSONObject(report);diagnosticReport=report;
    data="{\"state\":\"success\"}".getBytes(StandardCharsets.UTF_8);
   }else if(request[0].equals("POST")&&path.equals("/diagnostic/snapshot")){
    byte[] body=in.readNBytes(length);if(body.length!=length)throw new IOException("Incomplete diagnostic snapshot");
    org.json.JSONObject parsed=new org.json.JSONObject(new String(body,StandardCharsets.UTF_8));
    diagnosticExport=parsed.getString("exportText");
    data=("{\"state\":\"success\",\"characters\":"+diagnosticExport.length()+",\"sha256\":\""+sha256(diagnosticExport)+"\"}").getBytes(StandardCharsets.UTF_8);
   }else if(path.equals("/diagnostic/status")){
    data=diagnosticReport.getBytes(StandardCharsets.UTF_8);
   }else if(request[0].equals("POST")&&path.equals("/diagnostic/start-transfer")){
    if(diagnosticExport==null)throw new IllegalStateException("No verified diagnostic snapshot is available.");
    data=startTransfer(diagnosticExport).getBytes(StandardCharsets.UTF_8);
   }else if(request[0].equals("POST")&&path.equals("/transfer/start")){
    byte[] body=in.readNBytes(length);if(body.length!=length)throw new IOException("Incomplete transfer body");
    org.json.JSONObject parsed=new org.json.JSONObject(new String(body,StandardCharsets.UTF_8));
    data=startTransfer(parsed.getString("exportText")).getBytes(StandardCharsets.UTF_8);
   }else if(path.equals("/transfer/status")){
    data=transferStatus().getBytes(StandardCharsets.UTF_8);
   }else if(path.equals("/transfer/qr")){
    byte[] png=transferQr();
    reply(s,200,"image/png",png);return;
   }else if(path.equals("/transfer/import-text")){
    data=getPendingImport().getBytes(StandardCharsets.UTF_8);
   }else if(request[0].equals("POST")&&path.equals("/transfer/clear-import")){
    pendingImport=null;pendingImportSha=null;
    data="{\"state\":\"success\"}".getBytes(StandardCharsets.UTF_8);
   }else if(request[0].equals("POST")&&path.equals("/transfer/stop")){
    stopTransfer();
    data="{\"state\":\"success\"}".getBytes(StandardCharsets.UTF_8);
   }else if(request[0].equals("POST")&&path.equals("/export")){
    byte[] body=in.readNBytes(length);if(body.length!=length)throw new IOException("Incomplete export body");
    org.json.JSONObject parsed=new org.json.JSONObject(new String(body,StandardCharsets.UTF_8));
    data=copyClipboard(parsed.getString("exportText")).getBytes(StandardCharsets.UTF_8);
   }else if(request[0].equals("POST")&&path.equals("/clipboard-copy")){
    byte[] body=in.readNBytes(length);if(body.length!=length)throw new IOException("Incomplete clipboard body");
    org.json.JSONObject parsed=new org.json.JSONObject(new String(body,StandardCharsets.UTF_8));
    data=copyClipboard(parsed.getString("text")).getBytes(StandardCharsets.UTF_8);
   }else if(path.equals("/clipboard-read")){
    data=readClipboard().getBytes(StandardCharsets.UTF_8);
   }else if(path.equals("/restore")){
    AtomicFile f=new AtomicFile(backup());
    data=f.getBaseFile().exists()?f.readFully():"null".getBytes(StandardCharsets.UTF_8);
   }else{
    if(path.equals("/"))path="/index.html";
    try(InputStream asset=context.getAssets().open("game"+path)){data=asset.readAllBytes();}
    catch(FileNotFoundException e){reply(s,404,"text/plain",new byte[0]);return;}
    type=path.endsWith(".html")?"text/html":path.endsWith(".js")?"application/javascript":path.endsWith(".css")?"text/css":path.endsWith(".json")?"application/json":path.endsWith(".png")?"image/png":path.endsWith(".gif")?"image/gif":path.endsWith(".svg")?"image/svg+xml":"application/octet-stream";
   }
   reply(s,200,type,data);
  }catch(Exception e){
   try{reply(socket,500,"application/json",errorJson("server",e,"Local request processing failed").getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}
  }
 }

 private int readHeaders(InputStream in)throws IOException{
  int length=0;String h;
  while(!(h=line(in)).isEmpty()){
   String lower=h.toLowerCase(Locale.ROOT);
   if(lower.startsWith("content-length:"))length=Integer.parseInt(h.substring(15).trim());
  }
  return length;
 }

 private synchronized String startTransfer(String exportText){
  try{
   if(exportText==null||exportText.isEmpty())throw new IllegalArgumentException("Current save export is empty.");
   stopTransfer();
   List<String> addresses=findLanAddresses();
   if(addresses.isEmpty())throw new IllegalStateException("No reachable Wi-Fi/LAN IPv4 address was found. Connect the watch to the same Wi-Fi as your phone, or connect the watch to your phone hotspot.");
   transferToken=randomToken();
   transferExport=exportText;
   transferExportSha=sha256(exportText);
   pendingImport=null;
   pendingImportSha=null;
   pendingImportId=0;
   transferExpires=System.currentTimeMillis()+TRANSFER_TTL_MS;
   transferSocket=new ServerSocket();
   transferSocket.setReuseAddress(true);
   transferSocket.bind(new InetSocketAddress("0.0.0.0",TRANSFER_PORT));
   transferUrl="http://"+addresses.get(0)+":"+TRANSFER_PORT+"/t/"+transferToken+"/";

   ServerSocket opened=transferSocket;
   Thread t=new Thread(()->{
    while(!opened.isClosed())try{Socket s=opened.accept();pool.execute(()->serveTransfer(s));}catch(IOException ignored){}
   },"Kittens transfer");
   t.setDaemon(true);t.start();

   String tokenAtStart=transferToken;
   Thread expiry=new Thread(()->{
    try{Thread.sleep(TRANSFER_TTL_MS);}catch(InterruptedException ignored){}
    synchronized(LocalGameServer.this){
     if(tokenAtStart.equals(transferToken)&&System.currentTimeMillis()>=transferExpires)stopTransfer();
    }
   },"Kittens transfer expiry");
   expiry.setDaemon(true);expiry.start();

   StringBuilder urls=new StringBuilder("[");
   for(int i=0;i<addresses.size();i++){
    if(i>0)urls.append(',');
    urls.append("\"http://").append(esc(addresses.get(i))).append(':').append(TRANSFER_PORT).append("/t/").append(transferToken).append("/\"");
   }
   urls.append(']');
   return "{\"state\":\"success\",\"url\":\""+esc(transferUrl)+"\",\"urls\":"+urls+",\"expiresSeconds\":"+(TRANSFER_TTL_MS/1000)+",\"characters\":"+transferExport.length()+",\"sha256\":\""+transferExportSha+"\"}";
  }catch(Throwable e){
   stopTransfer();
   return errorJson("transfer-start",e,"A temporary LAN server could not be started.");
  }
 }

 private synchronized void stopTransfer(){
  ServerSocket s=transferSocket;
  transferSocket=null;
  transferToken=null;
  transferExport=null;
  transferExportSha=null;
  transferUrl=null;
  transferExpires=0;
  pendingImport=null;
  pendingImportSha=null;
  pendingImportId=0;
  if(s!=null)try{s.close();}catch(IOException ignored){}
 }

 private String transferStatus(){
  boolean active=transferSocket!=null&&!transferSocket.isClosed()&&transferToken!=null&&System.currentTimeMillis()<transferExpires;
  if(!active){
   if(transferSocket!=null)stopTransfer();
   return "{\"state\":\"idle\",\"active\":false}";
  }
  String p=pendingImport;
  long remaining=Math.max(0,(transferExpires-System.currentTimeMillis()+999)/1000);
  return "{\"state\":\"success\",\"active\":true,\"url\":\""+esc(transferUrl)+"\",\"remainingSeconds\":"+remaining+",\"exportSha256\":\""+esc(transferExportSha)+"\",\"pending\":"+(p!=null)+",\"pendingCharacters\":"+(p==null?0:p.length())+",\"pendingSha256\":\""+esc(pendingImportSha)+"\",\"pendingId\":"+pendingImportId+"}";
 }

 private byte[] transferQr()throws Exception{
  String url=transferUrl;
  if(url==null||transferSocket==null||transferSocket.isClosed())throw new IllegalStateException("Transfer server is not active.");
  BitMatrix matrix=new QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,420,420);
  int w=matrix.getWidth(),h=matrix.getHeight();
  int[] pixels=new int[w*h];
  for(int y=0;y<h;y++)for(int x=0;x<w;x++)pixels[y*w+x]=matrix.get(x,y)?0xff000000:0xffffffff;
  Bitmap bitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
  ByteArrayOutputStream out=new ByteArrayOutputStream();
  if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Could not encode QR image.");
  bitmap.recycle();
  return out.toByteArray();
 }

 private String getPendingImport(){
  String p=pendingImport;
  if(p==null)return "{\"state\":\"empty\"}";
  return "{\"state\":\"success\",\"id\":"+pendingImportId+",\"characters\":"+p.length()+",\"sha256\":\""+esc(pendingImportSha)+"\",\"text\":\""+esc(p)+"\"}";
 }

 private void serveTransfer(Socket socket){
  try(Socket s=socket){
   s.setSoTimeout(20000);
   InputStream in=new BufferedInputStream(s.getInputStream());
   String[] request=line(in).split(" ");
   if(request.length<2)return;
   int length=readHeaders(in);
   if(length<0||length>MAX_BODY){reply(s,413,"text/plain","Save is too large.".getBytes(StandardCharsets.UTF_8));return;}

   String rawPath=request[1].split("\\?")[0];
   String token=transferToken;
   if(token==null||System.currentTimeMillis()>=transferExpires){reply(s,410,"text/plain","This transfer session has expired.".getBytes(StandardCharsets.UTF_8));return;}
   String prefix="/t/"+token+"/";
   if(!rawPath.startsWith(prefix)){reply(s,403,"text/plain","Invalid transfer token.".getBytes(StandardCharsets.UTF_8));return;}
   String action=rawPath.substring(prefix.length());

   if(request[0].equals("GET")&&(action.isEmpty()||action.equals("index.html"))){
    reply(s,200,"text/html",transferPage().getBytes(StandardCharsets.UTF_8));
   }else if(request[0].equals("GET")&&action.equals("download")){
    String save=transferExport;
    if(save==null)throw new IllegalStateException("No export is available.");
    String filename="KittensGame_"+timestamp()+".txt";
    Map<String,String> headers=new LinkedHashMap<>();
    headers.put("Content-Disposition","attachment; filename=\""+filename+"\"");
    reply(s,200,"text/plain",save.getBytes(StandardCharsets.UTF_8),headers);
   }else if(request[0].equals("POST")&&action.equals("import")){
    byte[] body=in.readNBytes(length);
    if(body.length!=length)throw new IOException("Import upload was incomplete.");
    String text=new String(body,StandardCharsets.UTF_8).trim();
    if(text.isEmpty())throw new IOException("Uploaded save is empty.");
    String incomingSha=sha256(text);
    synchronized(this){
     pendingImport=text;
     pendingImportSha=incomingSha;
     pendingImportId++;
    }
    reply(s,200,"application/json",("{\"state\":\"success\",\"characters\":"+text.length()+",\"sha256\":\""+incomingSha+"\",\"id\":"+pendingImportId+"}").getBytes(StandardCharsets.UTF_8));
   }else{
    reply(s,404,"text/plain","Not found".getBytes(StandardCharsets.UTF_8));
   }
  }catch(Exception e){
   try{reply(socket,500,"application/json",errorJson("transfer",e,"LAN transfer request failed").getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}
  }
 }

 private String transferPage(){
  return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Kittens Wear Transfer</title>"
   +"<style>body{font-family:system-ui,sans-serif;max-width:680px;margin:auto;padding:22px;background:#161616;color:#eee}h1{font-size:1.5rem}section{background:#242424;padding:18px;margin:14px 0;border-radius:14px}button,a.dl{display:block;box-sizing:border-box;width:100%;padding:14px;margin:10px 0;border:0;border-radius:10px;background:#ddd;color:#111;text-align:center;font-weight:700;text-decoration:none}textarea{box-sizing:border-box;width:100%;min-height:150px;padding:10px;border-radius:8px}input{width:100%;margin:10px 0}#status{white-space:pre-wrap}</style></head><body>"
   +"<h1>Kittens Wear transfer</h1><p>Direct local transfer with your watch. This page stops working when the watch transfer session expires.</p><p><b>Watch save:</b> "+transferExport.length()+" characters<br><b>SHA-256:</b> <code>"+html(transferExportSha)+"</code></p>"
   +"<section><h2>Export from watch</h2><a class=\"dl\" href=\"download\">Download current save</a></section>"
   +"<section><h2>Import to watch</h2><p>Select a Kittens Game save file, or paste a web export below.</p>"
   +"<input id=\"file\" type=\"file\" accept=\".txt,text/plain,application/json\"><textarea id=\"text\" placeholder=\"Or paste save text here…\"></textarea>"
   +"<button id=\"send\">Send save to watch</button><p id=\"status\"></p></section>"
   +"<script>const f=document.getElementById('file'),t=document.getElementById('text'),s=document.getElementById('status');"
   +"f.onchange=async()=>{if(f.files[0]){t.value=await f.files[0].text();s.textContent='Loaded '+t.value.length+' characters.'}};"
   +"document.getElementById('send').onclick=async()=>{const v=t.value.trim();if(!v){s.textContent='Choose a file or paste a save first.';return}try{s.textContent='Sending…';const r=await fetch('import',{method:'POST',headers:{'Content-Type':'text/plain;charset=utf-8'},body:v});const x=await r.json();if(!r.ok||x.state!=='success')throw new Error(x.message||'Transfer failed');s.textContent='Sent '+x.characters+' characters. Return to the watch and tap Apply received import.'}catch(e){s.textContent='Transfer failed: '+e.message}};</script>"
   +"</body></html>";
 }

 private static String sha256(String text)throws Exception{
  MessageDigest d=MessageDigest.getInstance("SHA-256");
  byte[] hash=d.digest(text.getBytes(StandardCharsets.UTF_8));
  StringBuilder out=new StringBuilder(hash.length*2);
  for(byte b:hash)out.append(String.format(Locale.US,"%02x",b&0xff));
  return out.toString();
 }

 private static String randomToken(){
  byte[] b=new byte[24];new SecureRandom().nextBytes(b);
  StringBuilder s=new StringBuilder();
  for(byte x:b)s.append(String.format(Locale.US,"%02x",x&0xff));
  return s.toString();
 }

 private static List<String> findLanAddresses()throws SocketException{
  class C {String address,name;int score;C(String a,String n,int s){address=a;name=n;score=s;}}
  List<C> found=new ArrayList<>();
  Enumeration<NetworkInterface> nets=NetworkInterface.getNetworkInterfaces();
  while(nets.hasMoreElements()){
   NetworkInterface ni=nets.nextElement();
   try{if(!ni.isUp()||ni.isLoopback())continue;}catch(Exception e){continue;}
   String n=ni.getName()==null?"":ni.getName().toLowerCase(Locale.ROOT);
   Enumeration<InetAddress> addrs=ni.getInetAddresses();
   while(addrs.hasMoreElements()){
    InetAddress a=addrs.nextElement();
    if(!(a instanceof Inet4Address)||a.isLoopbackAddress()||a.isLinkLocalAddress())continue;
    String ip=a.getHostAddress();
    int score=0;
    if(n.contains("wlan")||n.contains("wifi"))score+=100;
    if(a.isSiteLocalAddress())score+=50;
    if(n.contains("rmnet")||n.contains("cell"))score-=100;
    found.add(new C(ip,n,score));
   }
  }
  found.sort((a,b)->Integer.compare(b.score,a.score));
  LinkedHashSet<String> unique=new LinkedHashSet<>();
  for(C c:found)unique.add(c.address);
  return new ArrayList<>(unique);
 }

 private String copyClipboard(String text){
  try{
   if(text==null||text.isEmpty())throw new IllegalArgumentException("Save text is empty.");
   ClipboardManager cm=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
   if(cm==null)throw new IllegalStateException("Android ClipboardManager service is unavailable.");
   cm.setPrimaryClip(ClipData.newPlainText("Kittens Game save",text));
   return "{\"state\":\"success\",\"characters\":"+text.length()+",\"message\":\"Copied to Android clipboard\"}";
  }catch(Throwable e){return errorJson("clipboard-copy",e,"The save text is still available in the on-screen export box for manual copying.");}
 }
 private String readClipboard(){
  try{
   ClipboardManager cm=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
   if(cm==null)throw new IllegalStateException("Android ClipboardManager service is unavailable.");
   if(!cm.hasPrimaryClip()||cm.getPrimaryClip()==null||cm.getPrimaryClip().getItemCount()==0)throw new IllegalStateException("Android clipboard is empty.");
   CharSequence value=cm.getPrimaryClip().getItemAt(0).coerceToText(context);
   if(value==null||value.length()==0)throw new IllegalStateException("Clipboard contains no text.");
   String text=value.toString();
   if(text.length()>MAX_BODY)throw new IOException("Clipboard text exceeds the "+MAX_BODY+" character import limit.");
   return "{\"state\":\"success\",\"characters\":"+text.length()+",\"text\":\""+esc(text)+"\"}";
  }catch(Throwable e){return errorJson("clipboard-read",e,"Use the manual paste box in Advanced / fallback.");}
 }

 private String internalSave(byte[] body){
  try{
   AtomicFile f=new AtomicFile(backup());FileOutputStream out=null;
   try{out=f.startWrite();out.write(body);f.finishWrite(out);}
   catch(IOException e){if(out!=null)f.failWrite(out);throw e;}
   return "{\"state\":\"success\",\"internal\":true}";
  }catch(Exception e){return errorJson("background-save",e,"Internal atomic backup failed");}
 }

 private String manualSave(byte[] body){
  boolean internal=false,visible=false;String name="";StringBuilder errors=new StringBuilder();
  try{
   AtomicFile f=new AtomicFile(backup());FileOutputStream out=null;
   try{out=f.startWrite();out.write(body);f.finishWrite(out);internal=true;}
   catch(IOException e){if(out!=null)f.failWrite(out);throw e;}
  }catch(Exception e){errors.append("Internal backup failed: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append(". ");}
  try{name="KittensGame_"+timestamp()+".txt";writeDownload(name,body,AUTOSAVE_PATH);visible=true;}
  catch(Exception e){errors.append("Visible autosave failed: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append(". ");}
  if(!internal&&!visible)return "{\"state\":\"error\",\"operation\":\"save\",\"type\":\"StorageWriteError\",\"message\":\"Both the internal recovery copy and visible timestamped copy failed.\",\"internal\":false,\"visible\":false,\"detail\":\""+esc(errors.toString())+"\"}";
  return "{\"state\":\"success\",\"internal\":"+internal+",\"visible\":"+visible+",\"fileName\":\""+esc(name)+"\",\"location\":\"Download/Kittens Game/Autosaves/\",\"detail\":\""+esc(errors.toString())+"\"}";
 }

 private static String timestamp(){return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",Locale.US).format(new Date());}

 private void writeDownload(String name,byte[] bytes,String relativePath)throws IOException{
  ContentResolver cr=context.getContentResolver();ContentValues v=new ContentValues();
  v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
  v.put(MediaStore.MediaColumns.MIME_TYPE,"text/plain");
  v.put(MediaStore.MediaColumns.RELATIVE_PATH,relativePath);
  Uri u=cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
  if(u==null)throw new IOException("MediaStore returned null while creating "+relativePath+name);
  boolean ok=false;
  try(OutputStream out=cr.openOutputStream(u,"w")){
   if(out==null)throw new IOException("ContentResolver returned null output stream for "+u);
   out.write(bytes);out.flush();ok=true;
  }finally{
   if(!ok)try{cr.delete(u,null,null);}catch(Exception ignored){}
  }
 }

 private void reply(Socket s,int status,String type,byte[] data)throws IOException{reply(s,status,type,data,Collections.emptyMap());}
 private void reply(Socket s,int status,String type,byte[] data,Map<String,String> extra)throws IOException{
  OutputStream o=s.getOutputStream();
  String reason=status==200?"OK":status==403?"Forbidden":status==404?"Not Found":status==410?"Gone":status==413?"Payload Too Large":status>=500?"Server Error":"Error";
  StringBuilder h=new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason)
   .append("\r\nContent-Type: ").append(type).append("; charset=utf-8")
   .append("\r\nContent-Length: ").append(data.length)
   .append("\r\nConnection: close\r\nCache-Control: no-store")
   .append("\r\nX-Content-Type-Options: nosniff");
  if(type.equals("text/html"))h.append("\r\nContent-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'");
  for(Map.Entry<String,String> e:extra.entrySet())h.append("\r\n").append(e.getKey()).append(": ").append(e.getValue());
  h.append("\r\n\r\n");
  o.write(h.toString().getBytes(StandardCharsets.UTF_8));o.write(data);o.flush();
 }
}
