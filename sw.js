/* Batuque · service worker — faz a app abrir e funcionar sem internet.
   Estratégia: rede primeiro (3 s) para teres sempre a versão nova quando há net;
   sem net (ou net lenta) usa a cópia guardada. */
const CACHE='batuque-shell';
const SHELL=['./','./index.html','./manifest.webmanifest','./icon-192.png','./icon-512.png'];

self.addEventListener('install',e=>{
  e.waitUntil(caches.open(CACHE).then(c=>Promise.all(SHELL.map(u=>
    c.add(new Request(u,{cache:'reload'})).catch(()=>{})))).then(()=>self.skipWaiting()));
});
self.addEventListener('activate',e=>{
  e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==CACHE).map(k=>caches.delete(k))))
    .then(()=>self.clients.claim()));
});
self.addEventListener('fetch',e=>{
  const r=e.request;
  if(r.method!=='GET'||new URL(r.url).origin!==location.origin)return;
  e.respondWith((async()=>{
    const cache=await caches.open(CACHE);
    const fromCache=async()=>(await cache.match(r,{ignoreSearch:true}))||
      (r.mode==='navigate'?(await cache.match('./index.html'))||(await cache.match('./')):undefined);
    try{
      const res=await Promise.race([
        fetch(r),
        new Promise((_,rej)=>setTimeout(()=>rej(new Error('timeout')),3000))
      ]);
      if(res&&res.ok){
        const key=r.mode==='navigate'?'./index.html':r;
        cache.put(key,res.clone()).catch(()=>{});
      }
      return res;
    }catch(err){
      const c=await fromCache();
      if(c)return c;
      return new Response('Sem ligação e sem cópia guardada.',{status:503,headers:{'Content-Type':'text/plain; charset=utf-8'}});
    }
  })());
});

/* Notificação personalizada: os botões enviam o comando para a app aberta */
self.addEventListener('notificationclick',e=>{
  const a=e.action||'open';
  e.notification.close();
  e.waitUntil((async()=>{
    const cs=await clients.matchAll({type:'window',includeUncontrolled:true});
    if(a==='open'){
      if(cs.length){try{await cs[0].focus();}catch(x){}}
      else await clients.openWindow('./index.html');
      return;
    }
    cs.forEach(c=>c.postMessage({batuque:a}));
  })());
});
