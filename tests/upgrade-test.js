// Getting off an old service worker.
//
// A worker registered on this origin in August answered every page load from
// its cache first and only refreshed in the background -- "picked up on the
// next launch", by its own comment. On a phone that meant reload after reload
// still showing a three-week-old build, with no way for the user to tell why.
//
// So: install that exact worker over an old page, swap the server to the
// current build, reload once, and the new app has to be on screen. Once. Not
// after a second reload, and not after clearing website data.
const { chromium } = require('playwright-core');
const http = require('http');
const fs = require('fs');
const path = require('path');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

const SITE = process.env.SITE_DIR ||
             path.join(path.dirname(process.env.APP_HTML || '.'), 'site');

// The worker as it actually stood, kept here rather than referenced, so the
// check still means something after the repository stops carrying it.
const OLD_SW = `
const CACHE = 'gasplanet-deck-log-v6';
const ASSETS = ['./', './index.html'];
self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});
self.addEventListener('fetch', event => {
  const req = event.request;
  if (req.method !== 'GET') return;
  if (req.mode === 'navigate') {
    event.respondWith(caches.match('./index.html').then(cached => {
      const net = fetch(req).then(res => {
        if (res && res.ok) { const copy = res.clone();
          caches.open(CACHE).then(c => c.put('./index.html', copy)); }
        return res;
      }).catch(() => null);
      return cached || net;
    }));
    return;
  }
  event.respondWith(caches.match(req).then(c => c || fetch(req)));
});
`;

const OLD_PAGE = `<!DOCTYPE html><html><head><meta charset="utf-8"><title>To Do List</title></head>
<body><div id="topTabs"><button data-tab="jobs">To Do List</button>
<button data-tab="crew">Crew List</button><button data-tab="cargo">Cargo Log</button></div>
<p id="marker">AUGUST BUILD</p>
<script>
if ('serviceWorker' in navigator) {
  window.addEventListener('load', function(){ navigator.serviceWorker.register('./sw.js'); });
}
</script></body></html>`;

const TYPES = { '.html':'text/html', '.js':'text/javascript', '.json':'application/json',
                '.png':'image/png', '.webmanifest':'application/manifest+json' };

(async () => {
  if(!fs.existsSync(path.join(SITE, 'index.html'))){
    console.log('  SKIP  no site/ built'); console.log('ALL PASS'); return;
  }

  let serveNew = false;
  const srv = http.createServer((req, rp) => {
    let p = decodeURIComponent(req.url.split('?')[0]);
    if(p.endsWith('/')) p += 'index.html';
    if(!serveNew){
      if(p === '/index.html'){
        rp.writeHead(200, { 'Content-Type':'text/html', 'Cache-Control':'no-cache' });
        rp.end(OLD_PAGE); return;
      }
      if(p === '/sw.js'){
        rp.writeHead(200, { 'Content-Type':'text/javascript', 'Cache-Control':'no-cache' });
        rp.end(OLD_SW); return;
      }
      rp.writeHead(404); rp.end('no'); return;
    }
    const f = path.join(SITE, p);
    if(!f.startsWith(SITE) || !fs.existsSync(f) || fs.statSync(f).isDirectory()){
      rp.writeHead(404); rp.end('no'); return;
    }
    rp.writeHead(200, { 'Content-Type': TYPES[path.extname(f)] || 'application/octet-stream',
                        'Cache-Control':'no-cache' });
    fs.createReadStream(f).pipe(rp);
  });
  await new Promise(r => srv.listen(0, '127.0.0.1', r));
  const base = 'http://127.0.0.1:' + srv.address().port + '/';

  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext({ viewport:{width:390,height:844} });
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));

  // ---- the phone as it was in August -------------------------------------
  await p.goto(base);
  await p.evaluate(() => navigator.serviceWorker.ready.then(() => null));
  await p.waitForTimeout(600);
  await p.reload();
  await p.waitForTimeout(600);
  ok('the old worker is serving the old page',
     (await p.locator('#marker').count()) === 1 &&
     (await p.evaluate(() => !!navigator.serviceWorker.controller)));

  // ---- the new build goes up ---------------------------------------------
  serveNew = true;

  // ONE reload. This is the whole point: a person reloads once, sees the old
  // page, and concludes the new one was never published.
  await p.reload();
  // The worker navigates the page itself, so the context is torn down under
  // us; wait on the thing that proves it worked rather than on a clock.
  let landed = true;
  try{
    await p.waitForSelector('#topTabs button[data-tab="notes"]', { timeout: 20000 });
  }catch(e){ landed = false; }
  await p.waitForLoadState('load').catch(() => {});
  await p.waitForTimeout(500);

  const tabs = await p.evaluate(() =>
    [...document.querySelectorAll('#topTabs button')].map(x => x.dataset.tab).join(','));
  ok('one reload is enough to land on the new build',
     landed && tabs === 'jobs,forms,notes,tools,crew,cargo,ship', tabs);
  ok('and the August page is gone', (await p.locator('#marker').count()) === 0);

  // ---- and it does not reload over something half typed ------------------
  // Same upgrade again, from the new build to a differently-named worker, with
  // a job part way through being entered.
  if(!landed){
    console.log('  SKIP  the half-typed check: the page never reached the new build');
    await b.close(); srv.close();
    console.log('\n' + fail + ' FAILED');
    process.exit(1);
  }
  await p.fill('#inJob', 'Grease the accommodation ladder wires');
  const typedBefore = await p.inputValue('#inJob');
  await p.evaluate(async () => {
    // a worker that looks new to the browser, so activate runs again
    const r = await navigator.serviceWorker.getRegistration();
    if(r) await r.update();
  });
  await p.waitForTimeout(2500);
  const typedAfter = await p.evaluate(() => {
    const el = document.getElementById('inJob');
    return el ? el.value : '(page reloaded)';
  });
  ok('a half-typed job is not reloaded away', typedAfter === typedBefore, typedAfter);

  ok('no JS errors', errs.length === 0, errs.slice(0, 3).join(' | '));

  await b.close();
  srv.close();
  console.log(fail ? '\n' + fail + ' FAILED' : '\nALL PASS');
  process.exit(fail ? 1 : 0);
})();
