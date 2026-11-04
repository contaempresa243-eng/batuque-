/* Batuque · service worker (offline) */
const CACHE='batuque-offline-v3';
const CORE=['./','./index.html','./manifest.webmanifest','./icon-192.png','./icon-512.png'];

self.addEventListener('install',e=>{
  e.waitUntil((async()=>{
    const c=await caches.open(CACHE);
    // um ficheiro em falta não pode impedir a instalação
    await Promise.all(CORE.map(async u=>{try{
      const r=await fetch(new Request(u,{cache:'reload'}));
      if(r&&r.ok)await c.put(u,r.clone());
      if(u==='./'&&r&&r.ok)await c.put('./index.html',r.clone());
    }catch(_){}}));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate',e=>{
  e.waitUntil((async()=>{
    const ks=await caches.keys();
    await Promise.all(ks.filter(k=>k!==CACHE).map(k=>caches.delete(k)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch',e=>{
  const req=e.request;
  if(req.method!=='GET')return;
  const url=new URL(req.url);
  if(url.origin!==location.origin)return;

  // Abrir a app (ecrã inicial, atalho, ícone instalado): rede primeiro, cache se falhar
  if(req.mode==='navigate'){
    e.respondWith((async()=>{
      const c=await caches.open(CACHE);
      try{
        const r=await fetch(req);
        if(r&&r.ok){c.put('./index.html',r.clone());}
        return r;
      }catch(_){
        return (await c.match(req,{ignoreSearch:true}))
            || (await c.match('./index.html'))
            || (await c.match('./'))
            || new Response('Sem ligação e a app ainda não foi guardada. Abre uma vez com internet.',{status:503,headers:{'Content-Type':'text/plain; charset=utf-8'}});
      }
    })());
    return;
  }

  // Restantes ficheiros: cache primeiro, atualiza em segundo plano
  e.respondWith((async()=>{
    const c=await caches.open(CACHE);
    const hit=await c.match(req,{ignoreSearch:true});
    const net=fetch(req).then(r=>{if(r&&r.ok)c.put(req,r.clone());return r;}).catch(()=>null);
    return hit||(await net)||new Response('',{status:504});
  })());
});
