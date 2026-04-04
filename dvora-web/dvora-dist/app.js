// ── STATE ─────────────────────────────────────────────────────────────────────
let mode='shows';
let lastResults=null, lastSubtitleResults=null;
let activeTab='shows';
let imdbTimer=null, subTimer=null;
let streamState=null;

// ── THEME ─────────────────────────────────────────────────────────────────────
let isDark=true;
function toggleTheme(){
  isDark=!isDark;
  document.body.classList.toggle('light',!isDark);
  document.querySelectorAll('.tbtn').forEach(b=>b.textContent=isDark?'🌙':'☀️');
  localStorage.setItem('dvora_dark',isDark?'1':'0');
}
(function(){
  if(localStorage.getItem('dvora_dark')==='0'){isDark=false;document.body.classList.add('light');document.querySelectorAll('.tbtn').forEach(b=>b.textContent='☀️');}
})();

// ── VIEWS ─────────────────────────────────────────────────────────────────────
function showView(name){
  document.querySelectorAll('.view').forEach(v=>v.classList.remove('active'));
  document.getElementById('view-'+name).classList.add('active');
  document.getElementById('navImdb').classList.toggle('active',name==='imdb');
  document.getElementById('navSubs').classList.toggle('active',name==='subtitles');
  if(name==='imdb') setTimeout(()=>document.getElementById('imdbInput').focus(),50);
  if(name==='subtitles') setTimeout(()=>document.getElementById('subInput').focus(),50);
}

// ── MAIN SEARCH ───────────────────────────────────────────────────────────────
function setMode(m){
  mode=m;
  document.getElementById('btnShows').classList.toggle('active',m==='shows');
  document.getElementById('btnMovies').classList.toggle('active',m==='movies');
}
document.getElementById('searchInput').addEventListener('keydown',e=>{if(e.key==='Enter')doSearch();});
function setProg(p){document.getElementById('pf').style.width=p+'%';}

async function doSearch(){
  const q=document.getElementById('searchInput').value.trim();
  if(!q)return;
  const btn=document.getElementById('searchBtn');
  btn.disabled=true;btn.textContent='🐝 Scanning...';
  document.getElementById('pw').classList.add('visible');setProg(15);
  document.getElementById('statsBar').classList.remove('visible');
  document.getElementById('resultsArea').innerHTML='<div class="empty"><span class="eg">🐝</span><div class="et">Scanning the hive...</div></div>';
  streamState={query:q,cardIndex:0,foundCount:0,autoApiCount:0,manualCount:0,allResults:[]};
  try{
    const resp=await fetch('/search?q='+encodeURIComponent(q)+'&mode='+mode);
    if(!resp.ok)throw new Error('HTTP '+resp.status);
    const reader=resp.body.getReader();
    const decoder=new TextDecoder();
    let buffer='';
    while(true){
      const{done,value}=await reader.read();
      if(done)break;
      buffer+=decoder.decode(value,{stream:true});
      const lines=buffer.split('\n');
      buffer=lines.pop()||'';
      for(const line of lines){
        if(line.startsWith('data: ')){
          try{handleStreamMsg(JSON.parse(line.slice(6)));}catch(e){}
        }
      }
    }
  }catch(err){
    document.getElementById('resultsArea').innerHTML='<div class="empty"><span class="eg">⚠️</span><div class="et">Error: '+esc(err.message)+'</div></div>';
  }finally{
    btn.disabled=false;btn.textContent='🐝 Buzz';
    setProg(100);
    setTimeout(()=>{document.getElementById('pw').classList.remove('visible');setProg(0);},400);
  }
}

function handleStreamMsg(msg){
  const area=document.getElementById('resultsArea');
  const st=streamState;if(!st)return;

  if(msg.event==='meta'){
    area.innerHTML='';
    const hdr=document.createElement('div');
    hdr.style.cssText='display:flex;align-items:baseline;gap:11px;margin-bottom:12px;';
    hdr.innerHTML='<span style="font-family:\'Sora\',sans-serif;font-size:.78rem;font-weight:700;letter-spacing:.12em;color:var(--tm)">🍯 Results for: &quot;'+esc(msg.query)+'&quot;</span><span id="foundCount" style="font-size:.74rem;font-weight:800;color:var(--hg)">0 found</span>';
    area.appendChild(hdr);
    return;
  }

  if(msg.event==='section_start'){
    let cls='';
    if(msg.section==='api') cls='api';
    else if(msg.section==='manual') cls='man';
    area.appendChild(mkSD(msg.title,cls));
    // For auto section create two sub-buckets: found on top, notfound below
    if(msg.section==='auto'){
      const foundBucket=document.createElement('div');
      foundBucket.id='auto-found-bucket';
      area.appendChild(foundBucket);
      const notFoundBucket=document.createElement('div');
      notFoundBucket.id='auto-notfound-bucket';
      area.appendChild(notFoundBucket);
    }
    return;
  }

  if(msg.event==='result'){
    const r=msg.result;
    st.allResults.push(r);

    if(r.type==='auto'){
      // Route found cards to top bucket, not-found to bottom bucket
      const foundBucket=document.getElementById('auto-found-bucket');
      const notFoundBucket=document.getElementById('auto-notfound-bucket');
      const card=mkCard(r,st.cardIndex++);
      if(r.found && foundBucket){
        foundBucket.appendChild(card);
      } else if(notFoundBucket){
        notFoundBucket.appendChild(card);
      } else {
        area.appendChild(card);
      }
    } else {
      area.appendChild(mkCard(r,st.cardIndex++));
    }

    if(r.type==='manual'){
      st.manualCount++;
    } else {
      st.autoApiCount++;
      if(r.found){
        st.foundCount++;
        const fc=document.getElementById('foundCount');
        if(fc) fc.textContent=st.foundCount+' found';
      }
    }
    const sb=document.getElementById('statsBar');sb.classList.add('visible');
    document.getElementById('statFound').textContent=st.foundCount;
    document.getElementById('statTotal').textContent=st.autoApiCount;
    document.getElementById('statManual').textContent=st.manualCount;
    return;
  }

  if(msg.event==='done'){
    lastResults={query:st.query,results:st.allResults};
    if(document.getElementById('sov').classList.contains('open')&&activeTab==='logs') renderLogs();
    if(st.allResults.length===0){
      area.innerHTML='<div class="empty"><span class="eg">🍯</span><div class="et">No sites configured.</div></div>';
    }
    return;
  }
}

function mkSD(text,cls){
  const d=document.createElement('div');d.className='sd'+(cls?' '+cls:'');
  d.innerHTML='<div class="sdl"></div><div class="sdt">'+esc(text)+'</div><div class="sdl"></div>';
  return d;
}

function mkCard(r,idx){
  const card=document.createElement('div');card.className='rc';card.style.animationDelay=(idx*42)+'ms';
  let cbcls,semoji,stxt,scls,bcls,btxt;
  if(r.type==='manual'){cbcls='m';semoji='🔍';stxt='Manual Check';scls='stm';bcls='bm';btxt='MANUAL';card.classList.add('man');}
  else if(r.type==='api'){cbcls=r.found?'f':'a';semoji=r.found?'✅':'🟡';stxt=r.found?'Found!':'Not Found';scls=r.found?'stf':'stnf';bcls=r.found?'bf':'ba';btxt=r.found?'FOUND':'API';card.classList.add(r.found?'found':'notfound');}
  else{cbcls=r.found?'f':'nf';semoji=r.found?'✅':'🟡';stxt=r.found?'Found!':'Not Found';scls=r.found?'stf':'stnf';bcls=r.found?'bf':'bnf';btxt=r.found?'FOUND':'—';card.classList.add(r.found?'found':'notfound');}
  const linkURL=r.movie_url||r.url;
  const urlS=r.url.length>90?r.url.substring(0,90)+'…':r.url;
  let extra='';
  if(r.matches&&r.matches.length) extra=r.matches.map(m=>'<a href="'+esc(m.url)+'" target="_blank" class="cmu">→ '+esc(m.name)+'</a>').join('');
  else if(r.movie_url) extra='<a href="'+esc(r.movie_url)+'" target="_blank" class="cmu">→ '+esc(r.movie_url)+'</a>';
  if(r.details) extra+='<div class="cdet">'+esc(r.details)+'</div>';
  if(r.error) extra+='<div class="cerr">⚠️ '+esc(r.error)+'</div>';
  const hasL=r.logs&&r.logs.length&&r.type!=='manual';
  card.innerHTML='<div class="cm" onclick="ccClick(event,\''+esc(linkURL)+'\')">'
    +'<div class="cb '+cbcls+'"></div>'
    +'<div class="cd"><div class="csr"><span class="se">'+semoji+'</span><span class="st '+scls+'">'+stxt+'</span></div>'
    +'<a href="'+esc(linkURL)+'" target="_blank" class="cu" onclick="event.stopPropagation()">'+esc(urlS)+'</a>'+extra+'</div>'
    +'<div class="cr">'+(hasL?'<button class="lt" onclick="togLog(this)">Logs ▾</button>':'')+'<span class="badge '+bcls+'">'+btxt+'</span></div>'
    +'</div>'+(hasL?'<div class="lp">'+mkLogLines(r.logs)+'</div>':'');
  return card;
}

function ccClick(e,url){if(e.target.closest('.lt')||e.target.closest('a'))return;window.open(url,'_blank');}
function togLog(btn){const p=btn.closest('.rc').querySelector('.lp');p.classList.toggle('open');btn.classList.toggle('open');btn.textContent=p.classList.contains('open')?'Logs ▴':'Logs ▾';}
function mkLogLines(logs){return logs.map(l=>{const isV=l.level==='verdict';const vc=isV?(l.message.startsWith('FOUND')?'vf':'vnf'):'';return '<div class="ll log-'+l.level+(isV?' log-verdict '+vc:'')+'"><span class="lpr">'+l.level+'</span><span class="lmsg">'+esc(l.message)+'</span></div>';}).join('');}

// ── SUBTITLES ─────────────────────────────────────────────────────────────────

document.getElementById('subInput').addEventListener('keydown',e=>{
  if(e.key==='Enter'){clearTimeout(subTimer);doSubSearch();}
});
function debSub(){
  clearTimeout(subTimer);
  const q=document.getElementById('subInput').value.trim();
  if(!q){
    document.getElementById('subArea').innerHTML='<div class="empty"><span class="eg">🎞️</span><div class="et">Search for Hebrew subtitles on Wizdom</div></div>';
    document.getElementById('subpw').classList.remove('visible');
    return;
  }
  document.getElementById('subpw').classList.add('visible');
  subTimer=setTimeout(doSubSearch,400);
}

async function doSubSearch(){
  const q=document.getElementById('subInput').value.trim();
  if(!q) return;
  document.getElementById('subpw').classList.add('visible');
  document.getElementById('subArea').innerHTML='<div class="empty"><span class="eg">🐝</span><div class="et">Searching Wizdom...</div></div>';

  const sessionLogs=[];
  const lg=(level,msg)=>sessionLogs.push({level,message:msg});

  try {
    lg('info','Server search: /subtitles?q='+q);
    const sResp=await fetch('/subtitles?q='+encodeURIComponent(q));
    if(!sResp.ok) throw new Error('Server error: HTTP '+sResp.status);
    const sData=await sResp.json();

    // Server already verified each result against /api/releases/{imdbId}
    // and only returns found=true entries with real subtitles.
    const found=(sData.results||[]).filter(r=>r.found);
    lg('info','Server returned '+found.length+' verified result(s) with subtitles');

    found.forEach((r,i)=>{
      lg('match','Result #'+(i+1)+': "'+r.title+'" ('+r.imdbId+') · '+r.subsCount+' sub version(s)');
      lg('info','Type: '+(r.type||'?')+' · Rating: '+(r.rating||'?')+' · Year: '+(r.year||'?'));
      lg('verdict','FOUND — '+r.url);
    });

    lastSubtitleResults={query:q,results:found,sessionLogs};
    if(document.getElementById('sov').classList.contains('open')&&activeTab==='logs') renderLogs();

    document.getElementById('subpw').classList.remove('visible');
    renderSub(sData.query, found);

  } catch(err){
    lg('warn','Fatal error: '+err.message);
    lg('verdict','NOT FOUND — request failed');
    lastSubtitleResults={query:q,results:[],sessionLogs};
    if(document.getElementById('sov').classList.contains('open')&&activeTab==='logs') renderLogs();
    document.getElementById('subpw').classList.remove('visible');
    document.getElementById('subArea').innerHTML='<div class="empty"><span class="eg">⚠️</span><div class="et">Error: '+esc(err.message)+'</div></div>';
  }
}

function renderSub(query, found){
  const area=document.getElementById('subArea');area.innerHTML='';
  if(!found.length){
    area.innerHTML='<div class="sub-banner-nf">✗ No subtitles found for &quot;'+esc(query)+'&quot;</div>'
      +'<div class="sub-empty-msg">No Hebrew subtitles found on Wizdom for this title.</div>';
    return;
  }
  const banner=document.createElement('div');
  banner.className='sub-banner-f';
  banner.textContent='✅ SUBTITLES FOUND — '+found.length+' result'+(found.length>1?'s':'')+' for "'+query+'"';
  area.appendChild(banner);
  const hdr=document.createElement('div');
  hdr.style.cssText='display:flex;align-items:baseline;gap:10px;margin-bottom:11px;flex-wrap:wrap;';
  hdr.innerHTML='<span style="font-family:\'Sora\',sans-serif;font-size:.78rem;font-weight:700;letter-spacing:.12em;color:var(--tm)">🎞️ &quot;'+esc(query)+'&quot;</span>'
    +'<span style="font-size:.74rem;font-weight:800;color:var(--sub)">'+found.length+' found</span>';
  area.appendChild(hdr);
  found.forEach((r,i)=>area.appendChild(mkSubCard(r,i)));
}

// Single unified card — all rich data now comes directly from the server's
// /api/releases response, so no secondary Wizdom fetch needed.
function mkSubCard(r,idx){
  const typeIcon=r.type==='movie'?'🎬':'📺';
  const rating=r.rating?'⭐ '+parseFloat(r.rating).toFixed(1):'';
  const year=r.year?'· '+r.year:'';
  const typeLabel=r.type?'· '+r.type:'';
  const subsLabel=r.subsCount?'📝 '+r.subsCount+' version'+(r.subsCount!==1?'s':''):'';
  let posterHTML;
  if(r.posterUrl){
    posterHTML='<img class="sub-poster" src="'+esc(r.posterUrl)+'" alt="" '
      +'onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\'">'
      +'<div class="sub-poster-ph" style="display:none">'+typeIcon+'</div>';
  } else {
    posterHTML='<div class="sub-poster-ph">'+typeIcon+'</div>';
  }
  const card=document.createElement('div');
  card.className='sub-rich sfound';
  card.style.animationDelay=(idx*40)+'ms';
  card.onclick=()=>window.open(r.url,'_blank');
  card.innerHTML=posterHTML
    +'<div class="sub-ri">'
      +'<div class="sub-ren">'
        +'<a href="'+esc(r.url)+'" target="_blank" onclick="event.stopPropagation()">'+esc(r.title||'—')+'</a>'
      +'</div>'
      +'<div class="sub-rmeta">'+typeIcon+' '+esc(rating)+' '+esc(year)+' '+esc(typeLabel)+'</div>'
      +(r.genres?'<div class="sub-rgenres">'+esc(r.genres)+'</div>':'')
      +(subsLabel?'<div class="sub-rmeta" style="color:var(--sub);margin-top:2px">'+esc(subsLabel)+'</div>':'')
      +'<div class="sub-rurl">→ '+esc(r.url)+'</div>'
    +'</div>'
    +'<div class="sub-ractions">'
      +'<span class="sub-rbadge yes">✓ SUBS</span>'
      +'<button class="cpbtn" onclick="event.stopPropagation();cp(\''+esc(r.url)+'\',this)">📋 Copy link</button>'
    +'</div>';
  return card;
}

// ── IMDB ──────────────────────────────────────────────────────────────────────
function debImdb(){
  clearTimeout(imdbTimer);
  const q=document.getElementById('imdbInput').value.trim();
  if(!q){
    document.getElementById('imdbArea').innerHTML='<div class="empty" style="padding:20px 20px 30px"><span class="eg" style="background:#F5C518;color:#000;border-radius:8px;padding:6px 14px;font-size:.95rem;font-family:\'Sora\',sans-serif;font-weight:900;">IMDb</span><div class="et" style="margin-top:14px">Type a title to search IMDb</div></div>';
    document.getElementById('imdbLoad').classList.remove('visible');
    return;
  }
  document.getElementById('imdbLoad').classList.add('visible');
  imdbTimer=setTimeout(doImdb,350);
}
function doImdb(){
  const q=document.getElementById('imdbInput').value.trim();
  if(!q)return;
  document.getElementById('imdbLoad').classList.add('visible');
  fetch('/imdb?q='+encodeURIComponent(q))
    .then(r=>r.json()).then(data=>{document.getElementById('imdbLoad').classList.remove('visible');renderImdb(data);})
    .catch(err=>{document.getElementById('imdbLoad').classList.remove('visible');document.getElementById('imdbArea').innerHTML='<div class="empty" style="padding:20px 20px 30px"><span class="eg">⚠️</span><div class="et">Error: '+esc(err.message)+'</div></div>';});
}
function renderImdb(data){
  const area=document.getElementById('imdbArea');area.innerHTML='';
  if(!data.results||!data.results.length){area.innerHTML='<div class="empty" style="padding:20px 20px 30px"><span class="eg">🔍</span><div class="et">No results for &quot;'+esc(data.query)+'&quot;</div></div>';return;}
  data.results.forEach((item,i)=>{
    const card=document.createElement('div');card.className='imdbcard';card.style.animationDelay=(i*40)+'ms';
    card.onclick=()=>window.open(item.imdbUrl,'_blank');
    const poster=item.posterUrl
      ?'<img src="'+esc(item.posterUrl)+'" alt="'+esc(item.title)+'" onerror="this.parentNode.innerHTML=\'<div class=\\\'imdbph\\\'>🎬</div>\'">'
      :'<div class="imdbph">🎬</div>';
    card.innerHTML='<div class="imdbinner">'
      +'<div class="imdbposter">'+poster+'</div>'
      +'<div class="imdbinfo">'
      +'<div class="imdbtitle">'+esc(item.title)+'</div>'
      +'<div class="imdbmeta">'+(item.year?'<span class="imdbyear">'+esc(item.year)+'</span>':'')+'<span class="imdbtype">'+esc(item.mediaType||'Movie')+'</span></div>'
      +'<div class="imdbidrow"><span class="imdbbadge">IMDb</span><span class="imdbidtx">'+esc(item.imdbId)+'</span></div>'
      +'</div>'
      +'<div class="imdbactions" onclick="event.stopPropagation()">'
      +'<button class="cpbtn" onclick="cp(\''+esc(item.title)+'\',this)">📋 Title</button>'
      +'<button class="cpbtn" onclick="cp(\''+esc(item.imdbId)+'\',this)">📋 ID</button>'
      +'<button class="cpbtn" onclick="cp(\''+esc(item.imdbUrl)+'\',this)">📋 URL</button>'
      +'</div></div>';
    area.appendChild(card);
  });
}

// ── SETTINGS ──────────────────────────────────────────────────────────────────
function openSettings(){document.getElementById('sov').classList.add('open');document.getElementById('settingsBtn').classList.add('active');loadCfgs();if(activeTab==='logs')renderLogs();}
function closeSettings(){document.getElementById('sov').classList.remove('open');document.getElementById('settingsBtn').classList.remove('active');}
function ovClick(e){if(e.target===document.getElementById('sov'))closeSettings();}
document.addEventListener('keydown',e=>{if(e.key==='Escape')closeSettings();});
function switchTab(name){
  activeTab=name;
  document.querySelectorAll('.stab').forEach((b,i)=>b.classList.toggle('active',['shows','movies','manual','api','logs'][i]===name));
  document.querySelectorAll('.stabc').forEach(c=>c.classList.remove('active'));
  document.getElementById('tab-'+name).classList.add('active');
  if(name==='logs')renderLogs();
}
const fm={shows:'shows.txt',movies:'movies.txt',manual:'manual_checks.txt',api:'api_sites.txt'};
function loadCfgs(){['shows','movies','manual','api'].forEach(k=>{fetch('/config?file='+fm[k]).then(r=>r.json()).then(d=>{document.getElementById('ta-'+k).value=d.content||'';}).catch(()=>{});});}
function saveCfg(file,taId,ssId){
  const content=document.getElementById(taId).value;
  const el=document.getElementById(ssId);el.textContent='Saving...';el.className='ss';
  fetch('/config?file='+file,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({content})})
    .then(r=>r.json()).then(d=>{
      if(d.status==='ok'){el.textContent='✓ Saved!';el.className='ss ok';setTimeout(()=>el.textContent='',2500);}
      else{el.textContent='✗ '+(d.error||'unknown');el.className='ss err';}
    }).catch(e=>{el.textContent='✗ '+e.message;el.className='ss err';});
}

// ── LOGS ──────────────────────────────────────────────────────────────────────
function renderLogs(){
  const empty=document.getElementById('logEmpty');
  const meta=document.getElementById('logMeta');
  const subMeta=document.getElementById('logSubMeta');

  if(!lastResults&&!lastSubtitleResults){
    empty.style.display='block';meta.style.display='none';subMeta.style.display='none';return;
  }
  empty.style.display='none';

  if(lastResults){
    meta.style.display='block';
    const foundN=lastResults.results.filter(r=>r.type!='manual'&&r.found).length;
    const totalN=lastResults.results.filter(r=>r.type!='manual').length;
    document.getElementById('logQ').textContent='"'+lastResults.query+'"  ['+foundN+' found / '+totalN+' scanned]';
    const con=document.getElementById('logBlocks');con.innerHTML='';
    lastResults.results.forEach(r=>{
      const blk=document.createElement('div');blk.className='lsb';
      let dc,vc,vt;
      if(r.type==='manual'){dc='var(--man)';vc='manual';vt='MANUAL';}
      else if(r.found){dc='var(--fgl)';vc='found';vt='FOUND';}
      else{dc='var(--tm)';vc='notfound';vt='NOT FOUND';}
      const tb=r.type==='api'?' [API]':r.type==='manual'?' [MANUAL]':'';
      const us=r.url.length>80?r.url.substring(0,80)+'…':r.url;
      const logs=r.logs||[];const hl=logs.length>0;
      blk.innerHTML='<div class="lsh" onclick="togLB(this)">'
        +'<div class="lsd" style="background:'+dc+';'+(r.found&&r.type!=='manual'?'box-shadow:0 0 6px '+dc:'')+'"></div>'
        +'<div class="lsu">'+esc(us)+esc(tb)+'</div>'
        +'<span class="lsv '+vc+'">'+vt+'</span>'
        +'<span class="lexp">'+(hl?'▾':'')+'</span>'
        +'</div>'+(hl?'<div class="lsbody">'+mkLogLines(logs)+'</div>':'');
      con.appendChild(blk);
    });
  } else {
    meta.style.display='none';
  }

  if(lastSubtitleResults){
    subMeta.style.display='block';
    const sr=lastSubtitleResults;
    const foundN=sr.results.length;
    document.getElementById('logSubQ').textContent='"'+sr.query+'"  ['+foundN+' found]';
    const con=document.getElementById('logSubBlocks');con.innerHTML='';

    if(sr.sessionLogs&&sr.sessionLogs.length){
      const blk=document.createElement('div');blk.className='lsb';
      const overall=foundN>0;
      blk.innerHTML='<div class="lsh" onclick="togLB(this)">'
        +'<div class="lsd" style="background:'+(overall?'var(--sub)':'var(--tm)')+'"></div>'
        +'<div class="lsu">🎞️ Subtitle search — "'+esc(sr.query)+'"</div>'
        +'<span class="lsv '+(overall?'submatch':'notfound')+'">'+(overall?'FOUND':'NOT FOUND')+'</span>'
        +'<span class="lexp">▾</span>'
        +'</div><div class="lsbody">'+mkLogLines(sr.sessionLogs)+'</div>';
      con.appendChild(blk);
    }

    sr.results.forEach(r=>{
      const blk=document.createElement('div');blk.className='lsb';
      const label=esc(r.title||'Unknown')+' <span style="color:var(--tm);font-size:.7em">'+esc(r.imdbId)+'</span>';
      const perLogs=[
        {level:'info',message:'imdbId: '+r.imdbId},
        {level:'info',message:'Type: '+(r.type||'?')+' · Year: '+(r.year||'?')+' · Rating: '+(r.rating||'?')},
        {level:'match',message:'✓ '+r.subsCount+' subtitle version(s) available'},
        {level:'verdict',message:'FOUND — '+r.url}
      ];
      blk.innerHTML='<div class="lsh" onclick="togLB(this)">'
        +'<div class="lsd" style="background:var(--sub);box-shadow:0 0 5px var(--sub)"></div>'
        +'<div class="lsu">'+label+'</div>'
        +'<span class="lsv submatch">MATCH</span>'
        +'<span class="lexp">▾</span>'
        +'</div><div class="lsbody">'+mkLogLines(perLogs)+'</div>';
      con.appendChild(blk);
    });

    if(!sr.results.length&&(!sr.sessionLogs||!sr.sessionLogs.length)){
      const blk=document.createElement('div');blk.className='lsb';
      blk.innerHTML='<div class="lsh">'
        +'<div class="lsd" style="background:var(--tm)"></div>'
        +'<div class="lsu">No results for "'+esc(sr.query)+'"</div>'
        +'<span class="lsv notfound">NOT FOUND</span>'
        +'<span class="lexp"></span>'
        +'</div>';
      con.appendChild(blk);
    }
  } else {
    subMeta.style.display='none';
  }
}

function togLB(h){
  const b=h.nextElementSibling;
  if(!b||!b.classList.contains('lsbody'))return;
  b.classList.toggle('open');
  h.querySelector('.lexp').textContent=b.classList.contains('open')?'▴':'▾';
}

// ── UTILS ─────────────────────────────────────────────────────────────────────
function cp(text,btn){
  navigator.clipboard.writeText(text)
    .then(()=>{const o=btn.textContent;btn.textContent='✓ Copied!';setTimeout(()=>btn.textContent=o,1500);})
    .catch(()=>{const ta=document.createElement('textarea');ta.value=text;document.body.appendChild(ta);ta.select();document.execCommand('copy');document.body.removeChild(ta);const o=btn.textContent;btn.textContent='✓ Copied!';setTimeout(()=>btn.textContent=o,1500);});
}
function esc(s){if(!s)return '';return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');}