(function(){
 'use strict';
 const KEY='com.nuclearunicorn.kittengame.savedata';
 const IMPORT_TX_KEY='com.balthazar.kittenswear.importtx', IMPORT_ROLLBACK_KEY='com.balthazar.kittenswear.rollback';
 let ready=false, suspended=false, lastTabList='', page='play', lastComplicationSync=0, transferPoll=0, lastSelfTest=null;
 const $id=id=>document.getElementById(id);
 function node(tag,attrs,text){let n=document.createElement(tag);Object.assign(n,attrs||{});if(text!==undefined)n.textContent=text;return n;}
 function button(text,fn,parent){let b=node('button',{type:'button',className:'wear-button'},text);b.onclick=fn;parent.appendChild(b);return b;}
 function status(text){$id('wearStatus').textContent=text;}
 function detail(html){$id('wearDetailBody').innerHTML=html;$id('wearDetail').hidden=false;$id('wearDetail').scrollTop=0;}
 function go(next){page=next;document.body.dataset.page=next;['wearResources','wearSettings'].forEach(id=>$id(id).hidden=true);$id('wearQuick').hidden=next!=='play';$id('wearTabs').hidden=next!=='play';if(next==='resources')$id('wearResources').hidden=false;if(next==='settings')$id('wearSettings').hidden=false;document.querySelectorAll('.wear-nav button').forEach(b=>b.classList.toggle('active',b.dataset.page===next));window.scrollTo(0,0);update();}
 const header=node('header',{id:'wearHeader'});header.append(node('strong',{},'Kittens'),node('span',{id:'wearSeason'},'Your village, on your wrist'));
 const nav=node('nav',{className:'wear-nav','ariaLabel':'Game sections'});
 [['play','Play'],['resources','Resources'],['log','Log'],['settings','Settings']].forEach(([p,label])=>{let b=button(label,()=>go(p),nav);b.dataset.page=p;});document.body.prepend(header);
 const quick=node('div',{id:'wearQuick'});quick.innerHTML='<label>CATNIP</label><b id="wearNip">0</b><span id="wearNipRate"></span>';header.after(quick);
 const tabs=node('select',{id:'wearTabs','ariaLabel':'Game category'});quick.after(tabs);tabs.onchange=()=>{game.ui.activeTabId=tabs.value;game.render();};
 const resources=node('section',{id:'wearResources',hidden:true});tabs.after(resources);
 const settings=node('section',{id:'wearSettings',hidden:true});resources.after(settings);
 settings.append(node('div',{className:'wear-label'},'Your game'));
 function wearEsc(v){return String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
 async function waitNative(url){for(;;){await new Promise(r=>setTimeout(r,500));const q=await fetch(url,{cache:'no-store'});let x;try{x=await q.json();}catch(e){throw new Error('Native operation returned invalid JSON: '+e.message);}if(x.state!=='waiting')return x;}}
 function opError(title,x){detail('<h2>'+wearEsc(title)+'</h2><p><b>Operation:</b> '+wearEsc(x.operation||'unknown')+'</p><p><b>Error type:</b> '+wearEsc(x.type||'unknown')+'</p><p><b>Message:</b> '+wearEsc(x.message||'No message')+'</p>'+(x.cause?'<p><b>Cause:</b> '+wearEsc(x.cause)+'</p>':'')+(x.detail?'<p><b>Details:</b> '+wearEsc(x.detail)+'</p>':''));}
 function methodOf(obj,name){
  if(!obj)return null;
  if(typeof obj[name]==='function')return obj[name];
  let p=Object.getPrototypeOf(obj);
  while(p){const d=Object.getOwnPropertyDescriptor(p,name);if(d&&typeof d.value==='function')return d.value;p=Object.getPrototypeOf(p);}
  return null;
 }
 function invoke(obj,name,args,label){const fn=methodOf(obj,name);if(!fn)throw new Error((label||name)+' has no callable '+name+' method');return fn.apply(obj,args||[]);}
 function gameRoot(){
  const out=[],seen=new Set();
  const add=x=>{if(x&&typeof x==='object'&&!seen.has(x)){seen.add(x);out.push(x);}};
  add(window.gamePage);add(window.game);
  for(let i=0;i<out.length&&i<40;i++){
   const x=out[i];for(const k of ['game','resPool','calendar','village','bld','science','workshop','ui','server','time','religion']){try{add(x[k]&&x[k].game?x[k].game:null);}catch(e){}}
  }
  for(const x of out){if(x.resPool&&x.calendar&&x.village&&Array.isArray(x.managers)&&x.server&&x.console)return x;}
  throw new Error('Could not locate the live Kittens Game state root. Candidates checked: '+out.length);
 }
 function validateSaveObject(d){
  if(!d||typeof d!=='object'||Array.isArray(d))throw new Error('Save root is not an object.');
  if(d.saveVersion===undefined||d.saveVersion===null)throw new Error('Save has no saveVersion.');
  if(!Array.isArray(d.resources)||d.resources.length===0)throw new Error('Save has no resource list.');
  if(!d.game||typeof d.game!=='object')throw new Error('Save has no game section.');
  if(!d.calendar||typeof d.calendar!=='object')throw new Error('Save has no calendar section.');
  if(!d.resources.some(r=>r&&r.name==='catnip'))throw new Error('Save resource list has no catnip entry.');
  return d;
 }
 function lz(){
  if(!window.LZString||typeof LZString.compressToBase64!=='function'||typeof LZString.decompressFromBase64!=='function')throw new Error('Bundled LZString library is unavailable.');
  return LZString;
 }
 function encodeSaveObject(data){return lz().compressToBase64(JSON.stringify(validateSaveObject(data)));}
 function decodeSaveBlob(text){
  if(typeof text!=='string')throw new Error('Save input is not text.');
  const trimmed=text.trim();if(!trimmed)throw new Error('Save input is empty.');
  let json=trimmed[0]==='{'?trimmed:null;
  if(!json){const compact=trimmed.replace(/\s/g,'');try{json=lz().decompressFromBase64(compact);}catch(e){}if(!json||json[0]!=='{'){try{json=lz().decompressFromUTF16(trimmed);}catch(e){}}}
  if(!json||json[0]!=='{')throw new Error('Save is neither JSON nor a supported Kittens Game LZ export.');
  let parsed;try{parsed=JSON.parse(json);}catch(e){throw new Error('Save JSON could not be parsed: '+e.message);}
  return validateSaveObject(parsed);
 }
 function serializeLiveGame(){
  const g=gameRoot();
  if(g.currentSaveIsBroken)throw new Error('The running game marks its current save as broken.');
  g.ticksBeforeSave=g.autosaveFrequency;
  const d={saveVersion:g.saveVersion};
  invoke(g.server,'save',[d],'server');invoke(g.resPool,'save',[d],'resources');invoke(g.village,'save',[d],'village');invoke(g.calendar,'save',[d],'calendar');invoke(g.console,'save',[d],'console');
  if(g.telemetry)invoke(g.telemetry,'save',[d],'telemetry');
  for(let i=0;i<g.managers.length;i++)invoke(g.managers[i],'save',[d],'manager['+i+']');
  d.game={isCMBREnabled:g.isCMBREnabled,colorScheme:g.colorScheme,unlockedSchemes:g.unlockedSchemes,karmaKittens:g.karmaKittens,karmaZebras:g.karmaZebras,ironWill:g.ironWill,deadKittens:g.deadKittens,cheatMode:g.cheatMode,startedWithoutChronospheres:g.startedWithoutChronospheres,opts:g.opts,lastBackup:g.lastBackup};
  const pub=methodOf(g,'_publish');if(pub)pub.call(g,'game/beforesave',d);
  const uiSave=g.ui&&methodOf(g.ui,'save');if(uiSave)try{uiSave.call(g.ui);}catch(e){console.warn('UI settings save failed',e);}
  return {root:g,data:validateSaveObject(d)};
 }
 function near(a,b){a=Number(a);b=Number(b);if(!Number.isFinite(a)||!Number.isFinite(b))return true;return Math.abs(a-b)<=Math.max(1e-6,Math.max(1,Math.abs(a),Math.abs(b))*1e-9);}
 function verifiedSnapshot(){
  const x=serializeLiveGame(),g=x.root,data=x.data,exportText=encodeSaveObject(data),round=decodeSaveBlob(exportText);
  if(String(round.saveVersion)!==String(data.saveVersion))throw new Error('Round-trip saveVersion mismatch.');
  if(Number(round.calendar.year)!==Number(g.calendar.year))throw new Error('Round-trip calendar year mismatch.');
  const liveCat=Number(g.resPool.get('catnip').value),savedCat=Number(round.resources.find(r=>r.name==='catnip').value);
  if(!near(liveCat,savedCat))throw new Error('Round-trip catnip mismatch: live='+liveCat+', saved='+savedCat);
  LCstorage[KEY]=exportText;
  return {data,exportText,year:Number(round.calendar.year),catnip:savedCat,resources:round.resources.length,characters:exportText.length};
 }
 async function postDiagnostic(report,snapshot){
  try{await fetch('/diagnostic/report',{method:'POST',body:JSON.stringify(report),keepalive:true});}catch(e){}
  if(snapshot)try{await fetch('/diagnostic/snapshot',{method:'POST',body:JSON.stringify({exportText:snapshot.exportText}),keepalive:true});}catch(e){}
 }
 postDiagnostic({state:'loading',stage:'wear-js-loaded',version:'1.1.8'},null);
 async function runSaveSelfTest(){
  try{
   const snap=verifiedSnapshot();
   const root=gameRoot();
   const report={state:'success',stage:'roundtrip',version:'1.1.8',year:snap.year,catnip:snap.catnip,resources:snap.resources,characters:snap.characters,rootConstructor:(root.constructor&&root.constructor.name)||'unknown'};
   lastSelfTest=report;await postDiagnostic(report,snap);return {report,snapshot:snap};
  }catch(e){
   const report={state:'error',stage:'save-self-test',version:'1.1.8',type:e.name||'Error',message:e.message||String(e)};lastSelfTest=report;await postDiagnostic(report,null);throw Object.assign(e,{details:report});
  }
 }
 function importTx(){try{return JSON.parse(localStorage.getItem(IMPORT_TX_KEY)||'null');}catch(e){return null;}}
 function rollbackPendingImport(reason){
  const tx=importTx(),old=localStorage.getItem(IMPORT_ROLLBACK_KEY);
  if(tx&&tx.state==='pending'&&old){LCstorage[KEY]=old;localStorage.setItem(IMPORT_TX_KEY,JSON.stringify({state:'rolledback',reason:String(reason||'Imported save failed to boot')}));location.reload();return true;}
  return false;
 }
 function finishImportTransaction(){
  const tx=importTx();if(!tx)return null;
  if(tx.state==='pending'){localStorage.removeItem(IMPORT_ROLLBACK_KEY);localStorage.removeItem(IMPORT_TX_KEY);fetch('/transfer/clear-import',{method:'POST'}).catch(()=>{});return 'Imported save from phone successfully';}
  if(tx.state==='rolledback'){const reason=tx.reason||'Imported save failed to boot';localStorage.removeItem(IMPORT_ROLLBACK_KEY);localStorage.removeItem(IMPORT_TX_KEY);return 'Import failed; previous village restored · '+reason;}
  return null;
 }
 function stopTransferPoll(){if(transferPoll){clearInterval(transferPoll);transferPoll=0;}}
 async function safetyBackup(){
  const snap=verifiedSnapshot();
  const r=await fetch('/save-manual',{method:'POST',body:JSON.stringify(snap.data),keepalive:true});
  let x;try{x=await r.json();}catch(e){throw new Error('Safety backup returned invalid JSON: '+e.message);}
  if(!r.ok||x.state==='error')throw Object.assign(new Error(x.message||'Safety backup failed'),{details:x});
  return x;
 }
 async function showTransferSave(){
  stopTransferPoll();
  try{
   const tested=await runSaveSelfTest();
   const exportText=tested.snapshot.exportText;
   const r=await fetch('/transfer/start',{method:'POST',body:JSON.stringify({exportText})});
   const x=await r.json();
   if(!r.ok||x.state!=='success'){opError('Transfer could not start',x);return;}
   const box=node('div');
   box.append(node('h2',{},'Transfer save'));
   box.append(node('p',{},'Save self-test: OK · '+tested.snapshot.characters+' characters'+(x.sha256?' · SHA-256 '+x.sha256.slice(0,12)+'…':'')));
   const qr=node('img',{src:'/transfer/qr?'+Date.now(),alt:'QR code for transfer page'});
   qr.style.width='180px';qr.style.height='180px';qr.style.display='block';qr.style.margin='8px auto';qr.style.background='#fff';qr.style.borderRadius='8px';
   box.append(qr);
   box.append(node('p',{},'On your Fairphone, scan this QR code while both devices are on the same Wi-Fi. You can also connect the watch to your Fairphone hotspot.'));
   const url=node('textarea',{readOnly:true,rows:3,value:x.url});url.style.width='100%';url.style.boxSizing='border-box';url.style.fontSize='11px';url.onclick=()=>{url.focus();url.select();};box.append(url);
   if(Array.isArray(x.urls)&&x.urls.length>1){
    const alt=node('details');alt.append(node('summary',{},'Alternative addresses'));
    x.urls.slice(1).forEach(u=>alt.append(node('p',{},u)));box.append(alt);
   }
   const state=node('p',{id:'wearTransferState'},'Transfer server running for 15 minutes.');box.append(state);
   const apply=button('Apply received import',async()=>{
    try{
     apply.disabled=true;
     const ir=await fetch('/transfer/import-text',{cache:'no-store'}), incoming=await ir.json();
     if(incoming.state!=='success')throw new Error('No received save is waiting.');
     if(!confirm('Replace your current village with the save received from your phone?')){apply.disabled=false;return;}
     state.textContent='Creating safety backup…';
     const backup=await safetyBackup();
     state.textContent='Validating and importing received save…';
     await importSave(incoming.text);
     await fetch('/transfer/clear-import',{method:'POST'});
     state.textContent='Import complete. Previous village backed up as '+(backup.fileName||'a timestamped save')+'.';
     apply.hidden=true;
     status('Imported save from phone');
    }catch(e){
     apply.disabled=false;
     const x=e.details||{operation:'LAN import',type:e.name||'ImportError',message:e.message||String(e),detail:'The incoming save was not applied. Your existing village remains available.'};
     opError('Received import failed',x);
    }
   },box);
   apply.hidden=true;
   button('Stop transfer',async()=>{
    stopTransferPoll();
    try{await fetch('/transfer/stop',{method:'POST'});}catch(e){}
    state.textContent='Transfer stopped.';
    apply.hidden=true;
    status('Transfer stopped');
   },box);
   box.append(node('p',{},'The phone page can download the current save or send a .txt/web save back to the watch. A received import is not applied until you approve it here.'));
   $id('wearDetailBody').replaceChildren(box);$id('wearDetail').hidden=false;$id('wearDetail').scrollTop=0;
   const poll=async()=>{
    const el=$id('wearTransferState');if(!el){stopTransferPoll();return;}
    try{
     const sr=await fetch('/transfer/status',{cache:'no-store'}), st=await sr.json();
     if(!st.active){el.textContent='Transfer session ended or expired.';apply.hidden=true;stopTransferPoll();return;}
     const mins=Math.floor(st.remainingSeconds/60),secs=st.remainingSeconds%60;
     if(st.pending){
      el.textContent='Save received from phone · '+st.pendingCharacters+' characters · '+mins+':'+String(secs).padStart(2,'0')+' remaining';
      apply.hidden=false;
     }else{
      el.textContent='Waiting for phone · '+mins+':'+String(secs).padStart(2,'0')+' remaining';
      apply.hidden=true;
     }
    }catch(e){el.textContent='Could not read transfer status: '+e.message;}
   };
   await poll();transferPoll=setInterval(poll,1000);
  }catch(e){
   opError('Transfer failed',e.details||{operation:'transfer-start',type:e.name||'TransferError',message:e.message||String(e),detail:'The save self-test or LAN startup failed. Transfer never exposes an unverified save.'});
  }
 }
 function showAdvanced(){
  const box=node('div');box.append(node('h2',{},'Advanced / fallback'),node('p',{},'Clipboard transfer is kept as a fallback when LAN transfer is unavailable.'));
  button('Export via clipboard',async()=>{
   try{
    const text=(await runSaveSelfTest()).snapshot.exportText;showExportBox(text);
    const response=await fetch('/export',{method:'POST',body:JSON.stringify({exportText:text})});
    const x=await response.json();const msg=$id('wearExportStatus');
    if(x.state==='success'){msg.textContent='Copied to Android clipboard · '+x.characters+' characters';status('Save copied to clipboard');}
    else msg.textContent='Automatic clipboard copy failed. Long-press the box and copy manually.';
   }catch(e){opError('Clipboard export failed',{operation:'clipboard-export',type:e.name,message:e.message,detail:'The save could not be copied automatically.'});}
  },box);
  button('Import via clipboard',()=>showImportBox(),box);
  $id('wearDetailBody').replaceChildren(box);$id('wearDetail').hidden=false;$id('wearDetail').scrollTop=0;
 }
 function showExportBox(text){
  const box=node('div');box.append(node('h2',{},'Export save'));
  box.append(node('p',{id:'wearExportStatus'},'Preparing Android clipboard…'));
  const area=node('textarea',{id:'wearExportText',readOnly:true,rows:10,value:text});
  area.style.width='100%';area.style.minHeight='180px';area.style.fontSize='11px';area.style.boxSizing='border-box';
  area.onclick=()=>{area.focus();area.select();};
  box.append(area);
  button('Copy again',async()=>{try{const r=await fetch('/clipboard-copy',{method:'POST',body:JSON.stringify({text:area.value})});const x=await r.json();if(x.state==='success'){status('Save copied to clipboard');$id('wearExportStatus').textContent='Copied to Android clipboard · '+x.characters+' characters';}else opError('Clipboard copy failed',x);}catch(e){opError('Clipboard copy failed',{operation:'clipboard-copy',type:e.name,message:e.message,detail:'Long-press the export box and copy manually.'});}},box);
  box.append(node('p',{},'Fallback: tap the text box to select it, then use Copy. This is the same compressed save string used by the web version.'));
  $id('wearDetailBody').replaceChildren(box);$id('wearDetail').hidden=false;$id('wearDetail').scrollTop=0;
 }
 function showImportBox(){
  const box=node('div');box.append(node('h2',{},'Import save'),node('p',{},'Paste a Kittens Game export string or save JSON below.'));
  const area=node('textarea',{id:'wearImportText',rows:10,placeholder:'Paste save here…'});
  area.style.width='100%';area.style.minHeight='180px';area.style.fontSize='11px';area.style.boxSizing='border-box';box.append(area);
  button('Paste clipboard',async()=>{try{const r=await fetch('/clipboard-read',{cache:'no-store'});const x=await r.json();if(x.state==='success'){area.value=x.text;status('Pasted '+x.characters+' characters');area.focus();}else opError('Clipboard paste failed',x);}catch(e){opError('Clipboard paste failed',{operation:'clipboard-read',type:e.name,message:e.message,detail:'Long-press the import box and paste manually.'});}},box);
  button('Import pasted save',async()=>{const text=area.value.trim();if(!text){opError('Import failed',{operation:'import',type:'EmptyInput',message:'The import box is empty.',detail:'Paste a Kittens Game export string or save JSON first.'});return;}try{if(!confirm('Replace your current village with this pasted save?'))return;await save(true);await importSave(text);detail('<h2>Import complete</h2><p>The pasted save was validated and loaded successfully.</p><p><b>Characters:</b> '+wearEsc(text.length)+'</p>');}catch(e){opError('Import failed',{operation:'import',type:e.name||'ImportError',message:e.message||String(e),detail:'The pasted text was not accepted as a valid Kittens Game save. Your previous village was retained when validation failed.'});}},box);
  button('Clear',()=>{area.value='';area.focus();},box);
  $id('wearDetailBody').replaceChildren(box);$id('wearDetail').hidden=false;$id('wearDetail').scrollTop=0;setTimeout(()=>area.focus(),100);
 }
 button('Save now',async()=>{await save(true);},settings);
 button('Transfer save',()=>showTransferSave(),settings);
 button('Advanced / fallback',()=>showAdvanced(),settings);
 button('Building filters',()=>{const list=node('div');list.append(node('h2',{},'Building filters'));game.bld.getBuildingGroups(true).forEach(group=>button(group.title,()=>{game.bldTab.activeGroup=group.name;game.render();$id('wearDetail').hidden=true;go('play');},list));$id('wearDetailBody').replaceChildren(list);$id('wearDetail').hidden=false;},settings);
 button('Game options',()=>{$('#optionsDiv').show();game.ui.updateOptions();},settings);
 button('Pause / resume',()=>{game.togglePause();status(game.isPaused?'Game paused':'Game running');syncComplication(true);},settings);
 button('Complication resource',async()=>{try{const r=await fetch('/open-complication-settings',{method:'POST'});if(!r.ok)throw Error();}catch(e){status('Could not open complication settings');}},settings);
 button('Reset / prestige',()=>game.reset(),settings);
 settings.append(node('p',{},'Swipe up to scroll. Tap Details for costs and effects. Transfer save opens a temporary local web page for your phone. Your game saves every 10 seconds and when you leave. Offline progress follows the original game rules.'));
 button('About & credits',()=>detail('<h2>Kittens Wear 1.1.8</h2><p>Personal offline adaptation for Wear OS. Original game by bloodrizer and contributors.</p><p>Based on Kittens Game '+version+'. Bundles Mozilla GeckoView (MPL 2.0).</p><p>Original game: kittensgame.com/web/</p><p>Source: github.com/nuclear-unicorn/kittensgame</p><p>Game code retains its WET PAWS LICENSE; this build is for personal use.</p><p>Engine sources: archive.mozilla.org/pub/firefox/releases/140.0.4/source/</p><p>All original acknowledgements:</p>'+$id('creditsDiv').innerHTML),settings);
 const state=node('p',{id:'wearStatus'},'Starting your forest…');document.body.append(state);
 const menu=node('section',{id:'wearMenu',hidden:true});menu.append(node('h2',{},'Your village'),nav);button('Back to game',()=>menu.hidden=true,menu);document.body.append(menu);nav.addEventListener('click',()=>menu.hidden=true);const top=button('Menu',()=>{menu.hidden=false;menu.scrollTop=0;},document.body);top.id='wearHome';
 const sheet=node('section',{id:'wearDetail',hidden:true});sheet.append(node('div',{id:'wearDetailBody'}));button('Close',()=>sheet.hidden=true,sheet);document.body.append(sheet);
 function fmt(x){return game.getDisplayValueExt(x);}
 function update(){if(!ready)return;
  const cat=game.resPool.get('catnip');$id('wearNip').textContent=fmt(cat.value);$id('wearNipRate').textContent=' / '+fmt(cat.maxValue);
  $id('wearSeason').textContent='Year '+game.calendar.year+' · '+game.calendar.getCurSeason().title+(game.isPaused?' · Paused':'');
  let visible=game.tabs.filter(t=>t.visible);let list=visible.map(t=>t.tabId+':'+t.tabName).join('|');
  if(list!==lastTabList){tabs.replaceChildren(...visible.map(t=>node('option',{value:t.tabId},t.tabName.replace(/<[^>]+>/g,''))));lastTabList=list;}
  tabs.value=game.ui.activeTabId;tabs.style.display=visible.length>1 && page==='play'?'block':'none';
  if(page==='resources'){
   resources.replaceChildren();
   game.resPool.resources.filter(r=>(r.visible || r.craftable) && (r.unlocked || r.value>0 || r.name==='catnip' || (r.name==='kittens' && r.maxValue>0))).forEach(r=>{
    let card=node('div',{className:'resource-card'}),line=node('div',{className:'resource-line'});line.append(node('span',{},r.title||r.name),node('b',{},fmt(r.value)));card.append(line);
    let rate=game.getResourcePerTick(r.name,true)*game.ticksPerSecond;card.append(node('small',{},(r.maxValue?'Capacity '+fmt(r.maxValue)+' · ':'')+(rate>=0?'+':'')+fmt(rate)+'/s'));
    if(r.maxValue>0){let meter=node('div',{className:'resource-meter'}),fill=node('i');fill.style.width=Math.min(100,r.value/r.maxValue*100)+'%';meter.append(fill);card.append(meter);}resources.append(card);
   });
  }
 }
 async function save(manual){
  if(!ready || game.currentSaveIsBroken){if(manual)opError('Save failed',{operation:'save',type:'GameStateError',message:'The game is not ready to save or reports the current save as broken.',detail:'No new save file was created.'});return;}
  try{
   const snap=verifiedSnapshot(),endpoint=manual?'/save-manual':'/backup';
   const r=await fetch(endpoint,{method:'POST',body:JSON.stringify(snap.data),keepalive:true});let x;try{x=await r.json();}catch(e){throw new Error('Save service returned invalid JSON: '+e.message);}
   if(!r.ok||x.state==='error'){if(manual)opError('Save failed',x);return;}
   if(manual){let msg='<h2>Save complete</h2><p><b>'+wearEsc(x.fileName||'KittensGame save')+'</b></p><p>Location: <b>'+wearEsc(x.location||'internal storage')+'</b></p><p>Internal recovery copy: <b>'+(x.internal?'OK':'FAILED')+'</b><br>Visible timestamped copy: <b>'+(x.visible?'OK':'FAILED')+'</b></p><p>Serialization round-trip: <b>OK</b></p>';if(x.detail)msg+='<p><b>Warnings:</b> '+wearEsc(x.detail)+'</p>';detail(msg);}
  }catch(e){if(manual)opError('Save failed',e.details||{operation:'save',type:e.name||'SaveError',message:e.message||String(e),detail:'Live-state serialization or native backup failed.'});}
 }
 async function importSave(text){
  const parsed=decodeSaveBlob(text),normalized=encodeSaveObject(parsed),round=decodeSaveBlob(normalized);
  validateSaveObject(round);
  let previous=LCstorage[KEY];if(!previous)previous=verifiedSnapshot().exportText;
  localStorage.setItem(IMPORT_ROLLBACK_KEY,previous);
  localStorage.setItem(IMPORT_TX_KEY,JSON.stringify({state:'pending',at:Date.now(),year:round.calendar.year,saveVersion:round.saveVersion}));
  LCstorage[KEY]=normalized;
  try{await fetch('/transfer/stop',{method:'POST'});}catch(e){}
  location.reload();
  return new Promise(()=>{});
 }
 function installDetails(){
  com.nuclearunicorn.game.ui.ContentRowRenderer.prototype.initRenderer=function(content){this.content=content;this.twoRows=false;};
  const attach=UIUtils.attachTooltip;
  UIUtils.attachTooltip=function(g,container,top,left,provider){container._wearTip=()=>provider.call(g);return provider;};
  const original=com.nuclearunicorn.game.ui.Button.prototype.render;
  com.nuclearunicorn.game.ui.Button.prototype.render=function(parent){original.call(this,parent);const self=this;
   const info=node('button',{type:'button',className:'wear-info'},'Details');
   info.onclick=function(e){e.preventDefault();e.stopPropagation();let html;
    if(self.domNode._wearTip)html=self.domNode._wearTip();
    else{const m=self.controller.fetchModel(self.opts);let box=node('div');box.append(node('h2',{},m.name),node('p',{},m.description||''));(m.prices||[]).forEach(p=>box.append(node('p',{},(gTitle(p.name))+': '+fmt(p.val))));html=box.innerHTML;}
    detail(html);
   };this.domNode.append(info);
  };
 }
 function gTitle(name){let r=game.resPool.get(name);return r?r.title||name:name;}
 async function syncComplication(force){
  if(!ready)return;const now=Date.now();if(!force && now-lastComplicationSync<5000)return;lastComplicationSync=now;
  try{
   const list=game.resPool.resources.filter(r=>(r.visible||r.craftable||r.unlocked||r.value>0||r.name==='catnip')).map(r=>({
    name:r.name,title:r.title||r.name,value:Number(r.value)||0,max:Number(r.maxValue)||0,
    rate:game.isPaused?0:(Number(game.getResourcePerTick(r.name,true))*Number(game.ticksPerSecond)||0)
   }));
   await fetch('/complication-state',{method:'POST',body:JSON.stringify({resources:list}),keepalive:true});
  }catch(e){console.warn('Complication sync failed',e);}
 }
 let wearBootStarted=false,wearDetailsInstalled=false,wearBootDeadline=Date.now()+60000;
 async function wearBootReady(){
  if(wearBootStarted)return;
  wearBootStarted=true;
  try{
   await postDiagnostic({state:'loading',stage:'game-detected',version:'1.1.8',hasGame:!!window.game,hasResPool:!!(window.game&&window.game.resPool)},null);
   const g=gameRoot();

   if(!LCstorage[KEY]){
    try{
     const rr=await fetch('/restore',{cache:'no-store'}),backup=await rr.json();
     if(backup&&backup.saveVersion){
      validateSaveObject(backup);
      LCstorage[KEY]=encodeSaveObject(backup);
      await postDiagnostic({state:'loading',stage:'native-backup-restored',version:'1.1.8'},null);
      location.reload();return;
     }
    }catch(e){console.warn('Native recovery backup unavailable',e);}
   }

   if(g.currentSaveIsBroken){
    if(rollbackPendingImport('Imported save was rejected by the game engine'))return;
    throw Error('Game engine reports the current save as broken');
   }

   try{
    if(window.classes&&classes.game&&classes.game.Server){
     classes.game.Server.prototype.refresh=function(){};
     classes.game.Server.prototype.fetchBcoinPrice=function(){return $.Deferred().resolve().promise();};
    }
   }catch(e){console.warn('Offline server patch failed',e);}

   if(!wearDetailsInstalled){
    try{installDetails();wearDetailsInstalled=true;g.render();}catch(e){console.warn('Wear details enhancement failed',e);}
   }

   ready=true;
   g.opts.disableTelemetry=true;
   g.opts.enableRedshift=true;
   g.opts.useWorkers=false;
   g.autosaveFrequency=50;

   const importNotice=finishImportTransaction();
   status(importNotice||'Offline · saved on this watch');
   go('play');
   syncComplication(true);

   await postDiagnostic({state:'loading',stage:'save-self-test-start',version:'1.1.8'},null);
   const tested=await runSaveSelfTest();
   status(importNotice||'Offline · save system verified');
   await postDiagnostic(Object.assign({},tested.report,{state:'success',stage:'ready'}),tested.snapshot);

   setInterval(()=>{if(!document.hidden){update();syncComplication(false);}},1000);
   setInterval(()=>{if(!document.hidden)save(false);},10000);
  }catch(e){
   ready=false;
   if(rollbackPendingImport(e.message))return;
   status('Startup failed: '+e.message);
   console.error(e);
   await postDiagnostic(e.details||{state:'error',stage:'wear-boot',version:'1.1.8',type:e.name||'Error',message:e.message||String(e)},null);
  }
 }
 function wearBootPoll(){
  if(wearBootStarted)return;
  try{
   const g=window.game;
   if(g&&g.resPool&&g.calendar&&g.village&&g.server&&g.console){wearBootReady();return;}
  }catch(e){}
  if(Date.now()>wearBootDeadline){
   wearBootStarted=true;
   status('Startup failed: game engine was not detected');
   postDiagnostic({state:'error',stage:'wait-for-game',version:'1.1.8',type:'TimeoutError',message:'Kittens Game did not expose its initialized game object within 60 seconds.'},null);
   return;
  }
  setTimeout(wearBootPoll,100);
 }
 postDiagnostic({state:'loading',stage:'wear-script-loaded',version:'1.1.8'},null).catch(()=>{});
 wearBootPoll();

 document.addEventListener('visibilitychange',()=>{
  if(!ready)return;
  if(document.hidden){syncComplication(true);save(false);clearInterval(game._mainTimer);game._mainTimer=null;suspended=true;}
  else if(suspended){suspended=false;if(!game.isPaused)game.time.calculateRedshift();game.start();update();syncComplication(true);}
 });
 window.addEventListener('pagehide',()=>{if(ready)save(false);});
 document.body.dataset.page='play';
})();
