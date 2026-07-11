// ── STATE ─────────────────────────────────────────────────────────────────────
let mode='shows', subMode='shows';
let lastResults=null, lastSubtitleResults=null;
let activeTab='shows';
let imdbTimer=null, subTimer=null, searchSugTimer=null;
let streamState=null;
let justSelectedSuggestion=false;

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

function debSearch(){
  clearTimeout(searchSugTimer);
  const q=document.getElementById('searchInput').value.trim();
  if(justSelectedSuggestion){justSelectedSuggestion=false;return;}
  if(!q){hideSearchDropdown();return;}
  searchSugTimer=setTimeout(()=>fetchSearchSuggestions(q),400);
}

function fetchSearchSuggestions(q){
  fetch('/imdb?q='+encodeURIComponent(q))
    .then(r=>r.json())
    .then(data=>renderSearchDropdown(data))
    .catch(()=>hideSearchDropdown());
}

function renderSearchDropdown(data){
  const dd=document.getElementById('searchDropdown');
  dd.innerHTML='';
  if(!data.results||!data.results.length){hideSearchDropdown();return;}
  data.results.slice(0,6).forEach(item=>{
    const d=document.createElement('div');
    d.className='sddi';
    let posterHTML=item.posterUrl
      ?'<img class="sddi-poster" src="'+esc(item.posterUrl)+'" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\'">'
       +'<div class="sddi-ph" style="display:none">'+(item.mediaType&&item.mediaType.toLowerCase().includes('tv')?'📺':'🎬')+'</div>'
      :'<div class="sddi-ph">'+(item.mediaType&&item.mediaType.toLowerCase().includes('tv')?'📺':'🎬')+'</div>';
    d.innerHTML=posterHTML
      +'<div class="sddi-info">'
      +'<div class="sddi-title">'+esc(item.title)+'</div>'
      +'<div class="sddi-meta">'+(item.year?esc(item.year)+' · ':'')+esc(item.mediaType||'')+'</div>'
      +'<div class="sddi-id">'+esc(item.imdbId)+'</div>'
      +'</div>';
    d.addEventListener('mousedown',e=>{
      e.preventDefault();
      justSelectedSuggestion=true;
      document.getElementById('searchInput').value=item.title;
      hideSearchDropdown();
    });
    dd.appendChild(d);
  });
  dd.classList.add('visible');
}

function hideSearchDropdown(){
  const dd=document.getElementById('searchDropdown');
  dd.classList.remove('visible');
  dd.innerHTML='';
}

document.addEventListener('click',e=>{
  const dd=document.getElementById('searchDropdown');
  const inp=document.getElementById('searchInput');
  if(!dd.contains(e.target)&&e.target!==inp) hideSearchDropdown();
});

document.getElementById('searchInput').addEventListener('keydown',e=>{
  if(e.key==='Enter'){hideSearchDropdown();doSearch();}
  if(e.key==='Escape') hideSearchDropdown();
});

function setProg(p){document.getElementById('pf').style.width=p+'%';}

async function doSearch(){
  const q=document.getElementById('searchInput').value.trim();
  if(!q)return;
  hideSearchDropdown();
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
      const foundBucket=document.getElementById('auto-found-bucket');
      const notFoundBucket=document.getElementById('auto-notfound-bucket');
      const card=mkCard(r,st.cardIndex++);
      if(r.found&&foundBucket) foundBucket.appendChild(card);
      else if(notFoundBucket) notFoundBucket.appendChild(card);
      else area.appendChild(card);
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
function setSubMode(m){
  subMode=m;
  document.getElementById('subBtnShows').classList.toggle('active',m==='shows');
  document.getElementById('subBtnMovies').classList.toggle('active',m==='movies');
  const q=document.getElementById('subInput').value.trim();
  if(q) doSubSearch();
}
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
  document.getElementById('subArea').innerHTML='<div class="empty"><span class="eg">🐝</span><div class="et">Searching Wizdom + IMDb...</div></div>';
  const sessionLogs=[];
  const lg=(level,msg)=>sessionLogs.push({level,message:msg});
  try{
    const apiUrl='/subtitles?q='+encodeURIComponent(q)+'&mode='+subMode;
    lg('info','GET '+apiUrl);
    const sResp=await fetch(apiUrl);
    if(!sResp.ok) throw new Error('Server error: HTTP '+sResp.status);
    const sData=await sResp.json();
    const allResults=sData.results||[];
    const foundResults=allResults.filter(r=>r.found&&r.imdbId);
    lg('info','Server returned '+allResults.length+' candidate(s), '+foundResults.length+' verified with subtitles');
    if(foundResults.length===0){
      lg('verdict','NOT FOUND — no verified Hebrew subtitles on Wizdom');
      lastSubtitleResults={query:q,mode:subMode,results:[],sessionLogs};
      if(document.getElementById('sov').classList.contains('open')&&activeTab==='logs') renderLogs();
      document.getElementById('subpw').classList.remove('visible');
      document.getElementById('subArea').innerHTML=
        '<div class="sub-banner-nf">✗ No subtitles found for &quot;'+esc(q)+'&quot;</div>'
        +'<div class="sub-empty-msg">No matching titles with Hebrew subtitles found on Wizdom.</div>';
      return;
    }
    foundResults.forEach((r,i)=>{
      lg('info','#'+(i+1)+': "'+r.title+'"'+(r.imdbId?' ('+r.imdbId+')':'')+(r.rating?' ⭐'+r.rating:'')+(r.subsCount?' · '+r.subsCount+(r.type==='movie'?' versions':' seasons'):''));
      lg('verdict','FOUND — '+r.url);
    });
    lastSubtitleResults={query:q,mode:subMode,results:foundResults,sessionLogs};
    if(document.getElementById('sov').classList.contains('open')&&activeTab==='logs') renderLogs();
    document.getElementById('subpw').classList.remove('visible');
    renderSub(sData);
  }catch(err){
    lg('warn','Fatal: '+err.message);
    lg('verdict','NOT FOUND — request failed');
    lastSubtitleResults={query:q,mode:subMode,results:[],sessionLogs};
    if(document.getElementById('sov').classList.contains('open')&&activeTab==='logs') renderLogs();
    document.getElementById('subpw').classList.remove('visible');
    document.getElementById('subArea').innerHTML='<div class="empty"><span class="eg">⚠️</span><div class="et">Error: '+esc(err.message)+'</div></div>';
  }
}

function renderSub(sData){
  const area=document.getElementById('subArea');area.innerHTML='';
  const found=(sData.results||[]).filter(r=>r.found);
  if(!found.length){
    area.innerHTML='<div class="sub-banner-nf">✗ No subtitles found for &quot;'+esc(sData.query)+'&quot;</div>'
      +'<div class="sub-empty-msg">No matching titles found on Wizdom or IMDb.</div>';
    return;
  }
  const banner=document.createElement('div');
  banner.className='sub-banner-f';
  banner.textContent='✅ SUBTITLES FOUND — '+found.length+' result'+(found.length>1?'s':'')+' for "'+sData.query+'"';
  area.appendChild(banner);
  const hdr=document.createElement('div');
  hdr.style.cssText='display:flex;align-items:baseline;gap:10px;margin-bottom:11px;flex-wrap:wrap;';
  hdr.innerHTML='<span style="font-family:\'Sora\',sans-serif;font-size:.78rem;font-weight:700;letter-spacing:.12em;color:var(--tm)">🎞️ &quot;'+esc(sData.query)+'&quot;</span>'
    +'<span style="font-size:.74rem;font-weight:800;color:var(--sub)">'+found.length+' found</span>';
  area.appendChild(hdr);
  found.forEach((r,i)=>{
    const isRich=!!(r.posterUrl||r.rating||r.genres||r.titleHe||r.year);
    area.appendChild(isRich?mkRichSubCard(r,i):mkSimpleSubCard(r,i));
  });
}

function mkRichSubCard(r,idx){
  const wizURL=r.url;
  const typeIcon=r.type==='movie'?'🎬':'📺';
  let posterHTML;
  if(r.posterUrl){
    posterHTML='<img class="sub-poster" src="'+esc(r.posterUrl)+'" alt="" '
      +'onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\'">'
      +'<div class="sub-poster-ph" style="display:none">'+typeIcon+'</div>';
  } else {
    posterHTML='<div class="sub-poster-ph">'+typeIcon+'</div>';
  }
  const rating=r.rating?'⭐ '+parseFloat(r.rating).toFixed(1):'';
  const year=r.year?'· '+r.year:'';
  const typeLabel=r.type?'· '+r.type:'';
  const card=document.createElement('div');
  card.className='sub-rich sfound';
  card.style.animationDelay=(idx*40)+'ms';
  card.onclick=()=>window.open(wizURL,'_blank');
  card.innerHTML=posterHTML
    +'<div class="sub-ri">'
      +'<div class="sub-ren">'
        +'<a href="'+esc(wizURL)+'" target="_blank" onclick="event.stopPropagation()">'+esc(r.title||'—')+'</a>'
      +'</div>'
      +(r.titleHe?'<div class="sub-rhe">'+esc(r.titleHe)+'</div>':'')
      +'<div class="sub-rmeta">'+typeIcon+' '+esc(rating)+' '+esc(year)+' '+esc(typeLabel)+'</div>'
      +(r.genres?'<div class="sub-rgenres">'+esc(r.genres)+'</div>':'')
      +'<div class="sub-rurl">→ '+esc(wizURL)+'</div>'
    +'</div>'
    +'<div class="sub-ractions">'
      +'<span class="sub-rbadge yes">✓ SUBS</span>'
      +'<button class="cpbtn" onclick="event.stopPropagation();cp(\''+esc(wizURL)+'\',this)">📋 Copy link</button>'
    +'</div>';
  return card;
}

function mkSimpleSubCard(r,idx){
  const card=document.createElement('div');
  card.className='sub-rich sfound';
  card.style.animationDelay=(idx*40)+'ms';
  card.onclick=()=>window.open(r.url,'_blank');
  card.innerHTML='<div class="sub-poster-ph">🎞️</div>'
    +'<div class="sub-ri">'
      +'<div class="sub-ren">'
        +'<a href="'+esc(r.url)+'" target="_blank" onclick="event.stopPropagation()">'+esc(r.title||'—')+'</a>'
      +'</div>'
      +(r.imdbId
        ?'<div style="display:flex;align-items:center;gap:5px;margin-top:4px;">'
          +'<span class="imdbbadge">IMDb</span>'
          +'<span style="font-size:.7rem;color:var(--tm);font-weight:600;">'+esc(r.imdbId)+'</span>'
          +'</div>'
        :'')
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
  document.querySelectorAll('.stab').forEach((b,i)=>b.classList.toggle('active',['shows','movies','manual','api','exclusions','logs'][i]===name));
  document.querySelectorAll('.stabc').forEach(c=>c.classList.remove('active'));
  document.getElementById('tab-'+name).classList.add('active');
  if(name==='logs')renderLogs();
}
const fm={shows:'shows.txt',movies:'movies.txt',manual:'manual_checks.txt',api:'api_sites.txt',exclusions:'exclusions.txt'};
function loadCfgs(){['shows','movies','manual','api','exclusions'].forEach(k=>{fetch('/config?file='+fm[k]).then(r=>r.json()).then(d=>{document.getElementById('ta-'+k).value=d.content||'';}).catch(()=>{});});}
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
  if(!lastResults&&!lastSubtitleResults){empty.style.display='block';meta.style.display='none';subMeta.style.display='none';return;}
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
  } else {meta.style.display='none';}
  if(lastSubtitleResults){
    subMeta.style.display='block';
    const sr=lastSubtitleResults;
    const foundN=sr.results.filter(r=>r.found).length;
    document.getElementById('logSubQ').textContent='"'+sr.query+'"  ['+foundN+' found · '+sr.mode+']';
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
      const label=esc(r.title||'Unknown')+' <span style="color:var(--tm);font-size:.7em">'+esc(r.imdbId||'')+'</span>';
      const hasRich=!!(r.posterUrl||r.rating||r.genres);
      const perLogs=[
        {level:'info',message:'imdbId: '+(r.imdbId||'—')},
        {level:'info',message:'Wizdom URL: '+r.url},
        hasRich
          ?{level:'match',message:'�� Verified — title="'+r.title+'" year='+(r.year||'?')+' rating='+(r.rating||'N/A')+(r.subsCount?' subs='+r.subsCount+(r.type==='movie'?' versions':' seasons'):'')}
          :{level:'info',message:'Verified — title="'+(r.title||'Unknown')+'" (no rich metadata)'},
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
  } else {subMeta.style.display='none';}
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

// ── CUSTOM API TYPES ──────────────────────────────────────────────────────────
// CustomApiType persistence + ApiEntry serialization + UI (dialog, wizard, list)

const CUSTOM_TYPES_KEY='dvora_custom_api_types';
let customApiTypes=[];
let cmState={selectedKeys:new Set(),fetchedJson:null,flattenedKeys:[]};
let addApiState={step:1,chosenType:null,chosenCustom:null};

// ── CustomApiType localStorage ──
function loadCustomApiTypes(){
  try{
    const raw=localStorage.getItem(CUSTOM_TYPES_KEY);
    customApiTypes=raw?JSON.parse(raw):[];
    if(!Array.isArray(customApiTypes))customApiTypes=[];
  }catch(e){customApiTypes=[];}
}
function saveCustomApiTypes(){
  try{localStorage.setItem(CUSTOM_TYPES_KEY,JSON.stringify(customApiTypes));}catch(e){}
}
function getCustomTypeById(id){
  return customApiTypes.find(t=>t.id===id)||null;
}
function deleteCustomApiType(id){
  const t=getCustomTypeById(id);
  if(!t)return;
  if(!confirm('Delete custom API type "'+t.name+'"?\n\nThis removes the type definition. API sources already using it will remain in your list but will no longer be editable via the wizard.'))return;
  customApiTypes=customApiTypes.filter(x=>x.id!==id);
  saveCustomApiTypes();
  renderCustomTypesList();
  renderAddApiTypeChooser();
}
function renderCustomTypesList(){
  const section=document.getElementById('customTypesSection');
  const list=document.getElementById('customTypesList');
  if(!section||!list)return;
  if(customApiTypes.length===0){
    section.style.display='none';
    list.innerHTML='';
    return;
  }
  section.style.display='';
  list.innerHTML='';
  customApiTypes.forEach(t=>{
    const item=document.createElement('div');
    item.className='custom-type-item';
    const domain=extractDomain(t.apiUrl)||t.apiUrl;
    const keys=(t.matchKeys||[]).join(', ');
    item.innerHTML='<div class="custom-type-info">'
      +'<div class="custom-type-name">✦ '+esc(t.name)+'</div>'
      +'<div class="custom-type-meta">'+esc(domain)+(keys?' · keys: '+esc(keys):'')+'</div>'
      +'</div>'
      +'<button class="custom-type-del" onclick="deleteCustomApiType(\''+esc(t.id)+'\')">✕ Delete</button>';
    list.appendChild(item);
  });
}
// Load on startup
loadCustomApiTypes();

// ── ApiEntry model ──
function makeApiEntry(opts){
  return {
    type:opts.type||'v1',
    apiUrl:opts.apiUrl||'',
    landingUrl:opts.landingUrl||null,
    matchKeys:opts.matchKeys||[],
    customName:opts.customName||null,
  };
}
function apiEntryDisplayType(e){
  if(e.type==='stremio')return 'Stremio';
  if(e.type==='custom')return e.customName||'Custom';
  return 'V1 JSON API';
}
// Serialize: type:apiUrl|landingUrl#matchKeys,joined@customName
function serializeApiEntry(e){
  let s=e.type+':'+e.apiUrl;
  if(e.landingUrl)s+='|'+e.landingUrl;
  if(e.matchKeys&&e.matchKeys.length)s+='#'+e.matchKeys.join(',');
  if(e.customName)s+='@'+e.customName;
  return s;
}
// Deserialize one line into ApiEntry (or null)
function deserializeApiEntry(line){
  line=line.trim();
  if(!line)return null;
  let type='v1',rest=line,customName=null,matchKeys=[],landingUrl=null,apiUrl='';
  if(line.startsWith('stremio:')){type='stremio';rest=line.slice('stremio:'.length);}
  else if(line.startsWith('custom:')){type='custom';rest=line.slice('custom:'.length);}
  else if(line.startsWith('v1:')){type='v1';rest=line.slice('v1:'.length);}
  else{type='v1';rest=line;}
  // @customName
  const atIdx=rest.lastIndexOf('@');
  if(atIdx!==-1){customName=rest.slice(atIdx+1);rest=rest.slice(0,atIdx);}
  // #matchKeys
  const hashIdx=rest.indexOf('#');
  if(hashIdx!==-1){const mk=rest.slice(hashIdx+1);rest=rest.slice(0,hashIdx);matchKeys=mk.split(',').map(k=>k.trim()).filter(k=>k);}
  // |landingUrl
  const pipeIdx=rest.indexOf('|');
  if(pipeIdx!==-1){apiUrl=rest.slice(0,pipeIdx).trim();landingUrl=rest.slice(pipeIdx+1).trim();}
  else{apiUrl=rest.trim();}
  if(!apiUrl)return null;
  return makeApiEntry({type,apiUrl,landingUrl:landingUrl||null,matchKeys,customName});
}

// ── Utility: extractDomain ──
function extractDomain(url){
  if(!url)return '';
  const noScheme=url.split('://')[1]||url;
  return noScheme.split('/')[0]||url.trim();
}

// ── Utility: flattenJson ──
function flattenJson(element,prefix){
  if(prefix===undefined)prefix='';
  const result=[];
  if(Array.isArray(element)){
    if(element.length>0){
      result.push(...flattenJson(element[0],prefix+'[0]'));
    }else{
      result.push({path:prefix,sampleValue:'[] (empty array)'});
    }
  }else if(element!==null&&typeof element==='object'){
    for(const[key,value]of Object.entries(element)){
      const newPrefix=prefix?prefix+'.'+key:key;
      result.push(...flattenJson(value,newPrefix));
    }
  }else{
    result.push({path:prefix,sampleValue:String(element).slice(0,80)});
  }
  return result;
}

// ── API SOURCES LIST RENDERING ──
function renderApiList(){
  renderCustomTypesList();
  const list=document.getElementById('apiList');
  if(!list)return;
  const raw=(document.getElementById('ta-api')||{}).value||'';
  const entries=[];
  raw.split('\n').forEach(line=>{
    const e=deserializeApiEntry(line);
    if(e)entries.push(e);
  });
  if(entries.length===0){
    list.innerHTML='<div class="api-empty">No API sources configured. Click "Add API Source" above to add one.</div>';
    return;
  }
  list.innerHTML='';
  entries.forEach((e,idx)=>{
    const item=document.createElement('div');
    item.className='api-item';
    const apiDomain=extractDomain(e.apiUrl);
    const landDomain=e.landingUrl?extractDomain(e.landingUrl):null;
    const isSplit=e.landingUrl&&e.landingUrl!==e.apiUrl;
    const isCustom=e.type==='custom';
    const displayType=apiEntryDisplayType(e);
    let badges='<span class="badge-type">'+esc(displayType)+'</span>';
    if(isSplit)badges+='<span class="badge-split">SPLIT</span>';
    if(isCustom)badges+='<span class="badge-custom">CUSTOM</span>';
    let landingLine='';
    if(isSplit&&landDomain){
      landingLine='<div class="api-item-landing">🔗 '+esc(landDomain)+'</div>';
    }
    item.innerHTML='<div class="api-item-info">'
      +'<div class="api-item-domain">🔍 '+esc(apiDomain)+'</div>'
      +landingLine
      +'<div class="api-item-badges">'+badges+'</div>'
      +'</div>'
      +'<div class="api-item-actions">'
      +'<button class="api-del" onclick="removeApiEntry('+idx+')">✕ Remove</button>'
      +'</div>';
    list.appendChild(item);
  });
}

function removeApiEntry(idx){
  const ta=document.getElementById('ta-api');
  if(!ta)return;
  const lines=ta.value.split('\n').filter(l=>l.trim());
  if(idx<0||idx>=lines.length)return;
  lines.splice(idx,1);
  ta.value=lines.join('\n');
  renderApiList();
  // Auto-save to server
  saveCfg('api_sites.txt','ta-api','ss-api');
}

function togApiRaw(btn){
  const wrap=document.getElementById('apiRawWrap');
  wrap.classList.toggle('open');
  btn.textContent=wrap.classList.contains('open')?'▴ Raw editor (advanced)':'▾ Raw editor (advanced)';
}

// ── CREATE CUSTOM API TYPE DIALOG ──
function openCreateCustomDialog(){openCreateCustom();}
function openCreateCustom(){
  // Reset state
  cmState={selectedKeys:new Set(),fetchedJson:null,flattenedKeys:[]};
  document.getElementById('cmApiUrl').value='';
  document.getElementById('cmName').value='';
  document.getElementById('cmLandingUrl').value='';
  document.getElementById('cmJsonPreview').textContent='Fetch a URL to see the raw JSON response here.';
  document.getElementById('cmJsonPreview').className='json-preview empty';
  document.getElementById('cmKeyPicker').innerHTML='<div class="key-picker-empty">Fetch JSON first to see available keys.</div>';
  document.getElementById('cmFetchStatus').textContent='';
  document.getElementById('cmSaveStatus').textContent='';
  document.getElementById('cmFetchBtn').disabled=false;
  document.getElementById('customModal').classList.add('open');
}
function closeCreateCustom(){
  document.getElementById('customModal').classList.remove('open');
}
function cmOnApiInput(){
  // Clear previous fetch results when URL changes
  if(cmState.fetchedJson){
    cmState.fetchedJson=null;
    cmState.flattenedKeys=[];
    cmState.selectedKeys=new Set();
    document.getElementById('cmJsonPreview').textContent='Fetch a URL to see the raw JSON response here.';
    document.getElementById('cmJsonPreview').className='json-preview empty';
    document.getElementById('cmKeyPicker').innerHTML='<div class="key-picker-empty">Fetch JSON first to see available keys.</div>';
  }
  document.getElementById('cmFetchStatus').textContent='';
}
async function cmFetchJson(){
  const urlInput=document.getElementById('cmApiUrl').value.trim();
  const status=document.getElementById('cmFetchStatus');
  const preview=document.getElementById('cmJsonPreview');
  const picker=document.getElementById('cmKeyPicker');
  if(!urlInput){
    status.textContent='Enter a URL first.';status.className='modal-status err';return;
  }
  // Replace DVORA with "interstellar" for testing
  const testUrl=urlInput.replace(/DVORA/g,'interstellar');
  const btn=document.getElementById('cmFetchBtn');
  btn.disabled=true;status.textContent='Fetching...';status.className='modal-status info';
  preview.textContent='';preview.className='json-preview empty';
  picker.innerHTML='<div class="key-picker-empty">Fetching...</div>';
  try{
    const resp=await fetch('/proxy?url='+encodeURIComponent(testUrl));
    const text=await resp.text();
    let data;
    try{data=JSON.parse(text);}catch(e){
      // Maybe the proxy returned an error JSON
      try{
        const errObj=JSON.parse(text);
        if(errObj.error){
          status.textContent='✗ '+errObj.error;status.className='modal-status err';
          preview.textContent=text.slice(0,2000);preview.className='json-preview';
          picker.innerHTML='<div class="key-picker-empty">Could not parse JSON.</div>';
          return;
        }
      }catch(_){}
      status.textContent='✗ Invalid JSON response';status.className='modal-status err';
      preview.textContent=text.slice(0,2000);preview.className='json-preview';
      picker.innerHTML='<div class="key-picker-empty">Could not parse JSON.</div>';
      return;
    }
    cmState.fetchedJson=data;
    const pretty=JSON.stringify(data,null,2);
    preview.textContent=pretty.length>2000?pretty.slice(0,2000)+'\n… (truncated)':pretty;
    preview.className='json-preview';
    status.textContent='✓ JSON fetched ('+pretty.length+' chars)';status.className='modal-status ok';
    // Flatten and render key picker
    cmState.flattenedKeys=flattenJson(data);
    cmState.selectedKeys=new Set();
    renderCmKeyPicker();
  }catch(err){
    status.textContent='✗ '+err.message;status.className='modal-status err';
    picker.innerHTML='<div class="key-picker-empty">Fetch failed.</div>';
  }finally{
    btn.disabled=false;
  }
}
function renderCmKeyPicker(){
  const picker=document.getElementById('cmKeyPicker');
  if(!cmState.flattenedKeys||cmState.flattenedKeys.length===0){
    picker.innerHTML='<div class="key-picker-empty">No keys found in JSON.</div>';
    return;
  }
  picker.innerHTML='';
  cmState.flattenedKeys.forEach(k=>{
    const card=document.createElement('div');
    card.className='key-card'+(cmState.selectedKeys.has(k.path)?' selected':'');
    card.innerHTML='<div class="key-check"></div>'
      +'<div class="key-info">'
      +'<div class="key-path">'+esc(k.path)+'</div>'
      +'<div class="key-sample">sample: '+esc(k.sampleValue)+'</div>'
      +'</div>';
    card.onclick=()=>{
      if(cmState.selectedKeys.has(k.path))cmState.selectedKeys.delete(k.path);
      else cmState.selectedKeys.add(k.path);
      card.classList.toggle('selected');
    };
    picker.appendChild(card);
  });
}
function cmSave(){
  const status=document.getElementById('cmSaveStatus');
  const apiUrl=document.getElementById('cmApiUrl').value.trim();
  const name=document.getElementById('cmName').value.trim();
  const landingUrl=document.getElementById('cmLandingUrl').value.trim();
  if(!apiUrl){status.textContent='✗ API URL is required';status.className='modal-status err';return;}
  if(!apiUrl.includes('DVORA')){status.textContent='✗ API URL must contain DVORA placeholder';status.className='modal-status err';return;}
  if(!name){status.textContent='✗ Name is required';status.className='modal-status err';return;}
  if(!landingUrl){status.textContent='✗ Landing URL is required';status.className='modal-status err';return;}
  if(cmState.selectedKeys.size===0){status.textContent='✗ Select at least one match key';status.className='modal-status err';return;}
  const matchKeys=Array.from(cmState.selectedKeys);
  const customType={
    id:'custom_'+Date.now(),
    name:name,
    apiUrl:apiUrl,
    landingUrl:landingUrl,
    matchKeys:matchKeys,
  };
  customApiTypes.push(customType);
  saveCustomApiTypes();
  renderCustomTypesList();
  status.textContent='✓ Saved! "'+name+'" is now available in Add API Source';status.className='modal-status ok';
  setTimeout(()=>{closeCreateCustom();},900);
}

// ── ADD API SOURCE WIZARD ──
function openAddApiWizard(){
  addApiState={step:1,chosenType:null,chosenCustom:null};
  document.getElementById('addApiStatus').textContent='';
  document.getElementById('addApiStep1').style.display='';
  document.getElementById('addApiStep2').style.display='none';
  document.getElementById('addApiBackBtn').style.display='none';
  document.getElementById('addApiNextBtn').textContent='Next →';
  document.getElementById('addApiUrl').value='';
  document.getElementById('addApiLanding').value='';
  renderAddApiTypeChooser();
  document.getElementById('addApiModal').classList.add('open');
}
function closeAddApiWizard(){
  document.getElementById('addApiModal').classList.remove('open');
}
function renderAddApiTypeChooser(){
  const chooser=document.getElementById('addApiTypeChooser');
  chooser.innerHTML='';
  const options=[
    {key:'v1',icon:'🟡',name:'V1 JSON API',desc:'Standard JSON API with data[].t / data[].y structure',mono:''},
    {key:'stremio',icon:'🎬',name:'Stremio Addon',desc:'Stremio catalog endpoint (e.g. https://v3-cinemeta.strem.io)',mono:''},
  ];
  customApiTypes.forEach(t=>{
    options.push({key:'custom:'+t.id,icon:'✦',name:t.name,desc:'Custom API type',mono:t.apiUrl,customId:t.id});
  });
  options.forEach(opt=>{
    const el=document.createElement('div');
    el.className='type-option'+(addApiState.chosenType===opt.key?' selected':'');
    el.innerHTML='<div class="type-icon">'+opt.icon+'</div>'
      +'<div class="type-text">'
      +'<div class="type-name">'+esc(opt.name)+'</div>'
      +'<div class="type-desc">'+esc(opt.desc)+'</div>'
      +(opt.mono?'<div class="type-desc mono">'+esc(opt.mono)+'</div>':'')
      +'</div>'
      +(opt.customId?'<button class="type-option-del" onclick="event.stopPropagation();deleteCustomApiType(\''+esc(opt.customId)+'\')">✕</button>':'');
    el.onclick=()=>{
      addApiState.chosenType=opt.key;
      renderAddApiTypeChooser();
    };
    chooser.appendChild(el);
  });
}
function addApiNext(){
  const status=document.getElementById('addApiStatus');
  if(addApiState.step===1){
    if(!addApiState.chosenType){status.textContent='✗ Choose a type first';status.className='modal-status err';return;}
    // Move to step 2
    addApiState.step=2;
    document.getElementById('addApiStep1').style.display='none';
    document.getElementById('addApiStep2').style.display='';
    document.getElementById('addApiBackBtn').style.display='';
    document.getElementById('addApiNextBtn').textContent='✓ Add Source';
    status.textContent='';
    // Pre-fill if custom
    if(addApiState.chosenType.startsWith('custom:')){
      const id=addApiState.chosenType.slice('custom:'.length);
      const t=getCustomTypeById(id);
      if(t){
        addApiState.chosenCustom=t;
        document.getElementById('addApiUrl').value=t.apiUrl;
        document.getElementById('addApiLanding').value=t.landingUrl;
      }
    }else if(addApiState.chosenType==='stremio'){
      document.getElementById('addApiUrl').value='https://v3-cinemeta.strem.io';
      document.getElementById('addApiLanding').value='';
    }else{
      // v1 — leave blank for user to fill
      document.getElementById('addApiUrl').value='';
      document.getElementById('addApiLanding').value='';
    }
    return;
  }
  // Step 2: save
  const apiUrl=document.getElementById('addApiUrl').value.trim();
  const landingUrl=document.getElementById('addApiLanding').value.trim();
  if(!apiUrl){status.textContent='✗ API URL is required';status.className='modal-status err';return;}
  if(!landingUrl){status.textContent='✗ Landing URL is required';status.className='modal-status err';return;}
  let entry;
  if(addApiState.chosenType.startsWith('custom:')){
    const t=addApiState.chosenCustom;
    if(!t){status.textContent='✗ Custom type not found';status.className='modal-status err';return;}
    entry=makeApiEntry({
      type:'custom',
      apiUrl:apiUrl,
      landingUrl:landingUrl,
      matchKeys:t.matchKeys,
      customName:t.name,
    });
  }else if(addApiState.chosenType==='stremio'){
    entry=makeApiEntry({type:'stremio',apiUrl:apiUrl,landingUrl:null,matchKeys:[],customName:null});
  }else{
    entry=makeApiEntry({type:'v1',apiUrl:apiUrl,landingUrl:landingUrl,matchKeys:[],customName:null});
  }
  const line=serializeApiEntry(entry);
  const ta=document.getElementById('ta-api');
  const current=ta.value.trim();
  ta.value=current?(current+'\n'+line):line;
  renderApiList();
  saveCfg('api_sites.txt','ta-api','ss-api');
  status.textContent='✓ Added!';status.className='modal-status ok';
  setTimeout(()=>{closeAddApiWizard();},700);
}
function addApiBack(){
  addApiState.step=1;
  document.getElementById('addApiStep1').style.display='';
  document.getElementById('addApiStep2').style.display='none';
  document.getElementById('addApiBackBtn').style.display='none';
  document.getElementById('addApiNextBtn').textContent='Next →';
  document.getElementById('addApiStatus').textContent='';
}

// ── Hook API list rendering into settings load ──
const _origLoadCfgs=loadCfgs;
loadCfgs=function(){
  ['shows','movies','manual','api','exclusions'].forEach(k=>{
    fetch('/config?file='+fm[k]).then(r=>r.json()).then(d=>{
      document.getElementById('ta-'+k).value=d.content||'';
      if(k==='api')renderApiList();
    }).catch(()=>{});
  });
};
// Re-render list when switching to api tab
const _origSwitchTab=switchTab;
switchTab=function(name){
  activeTab=name;
  document.querySelectorAll('.stab').forEach((b,i)=>b.classList.toggle('active',['shows','movies','manual','api','exclusions','logs'][i]===name));
  document.querySelectorAll('.stabc').forEach(c=>c.classList.remove('active'));
  document.getElementById('tab-'+name).classList.add('active');
  if(name==='logs')renderLogs();
  if(name==='api')renderApiList();
};
// Escape closes modals too
document.addEventListener('keydown',e=>{
  if(e.key==='Escape'){
    if(document.getElementById('customModal').classList.contains('open'))closeCreateCustom();
    if(document.getElementById('addApiModal').classList.contains('open'))closeAddApiWizard();
  }
});
function esc(s){if(!s)return '';return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');}