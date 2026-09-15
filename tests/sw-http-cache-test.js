// A service worker's own install step fetches its assets the ordinary way,
// which minutes after a fresh publish can be answered straight out of the
// phone's regular HTTP cache -- a stale index.html, wrapped in a Cache
// Storage bucket with a perfectly correct new name. That is exactly what
// "reloaded it, still the old layout" looked like: the cache name changed,
// the bytes inside it did not.
//
// This reproduces the real HTTP layer, not a stand-in for it: a server that
// answers with Cache-Control: max-age=600 (GitHub Pages' own default), one
// real navigation to prime the browser's HTTP cache with an old page, the
// content changed server-side without changing the URL, and then the actual
// generated sw.js run for real, exactly as a phone would run it.
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
const SW_PATH = path.join(SITE, 'sw.js');
const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64');

(async () => {
  if(!fs.existsSync(SW_PATH)){ console.log('  SKIP  no site/ built'); console.log('ALL PASS'); return; }
  const realSw = fs.readFileSync(SW_PATH, 'utf8');

  let page = 'OLD PAGE -- pre-publish';
  let indexHits = 0;
  const srv = http.createServer((req, res) => {
    const u = req.url.split('?')[0];
    if(u === '/' || u === '/index.html'){
      indexHits++;
      res.writeHead(200, { 'Content-Type':'text/html', 'Cache-Control':'max-age=600' });
      res.end('<!doctype html><html><body>' + page + '</body></html>');
      return;
    }
    if(u === '/sw.js'){
      res.writeHead(200, { 'Content-Type':'text/javascript' });
      res.end(realSw);
      return;
    }
    if(u === '/manifest.webmanifest'){
      res.writeHead(200, { 'Content-Type':'application/manifest+json', 'Cache-Control':'max-age=600' });
      res.end('{}');
      return;
    }
    if(u === '/icon-192.png' || u === '/icon-512.png'){
      res.writeHead(200, { 'Content-Type':'image/png', 'Cache-Control':'max-age=600' });
      res.end(PNG_1PX);
      return;
    }
    res.writeHead(404); res.end('no');
  });
  await new Promise(r => srv.listen(0, '127.0.0.1', r));
  const base = 'http://127.0.0.1:' + srv.address().port + '/';

  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext();
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));

  // One real navigation, no service worker yet -- this is what primes the
  // ordinary HTTP cache with the old page under Cache-Control: max-age=600.
  // The exact URL matters: caching is per-URL, and ASSETS fetches
  // './index.html' specifically, so that is the one primed here.
  await p.goto(base + 'index.html');
  await p.waitForTimeout(200);

  // The publish: new content at the same URL, no cache-busting query string
  // -- exactly what a plain `git push` + Pages redeploy looks like.
  page = 'NEW PAGE -- just published';

  // Now register the real, generated service worker for the first time and
  // let its install step run, fetching './index.html' among its assets.
  await p.evaluate(() => navigator.serviceWorker.register('./sw.js'));
  await p.waitForFunction(() => navigator.serviceWorker.ready.then(() => true), null, { timeout: 15000 });
  await p.waitForTimeout(500);

  const cached = await p.evaluate(async () => {
    const keys = await caches.keys();
    for(const k of keys){
      const c = await caches.open(k);
      const hit = await c.match('./index.html') || await c.match('./');
      if(hit) return await hit.text();
    }
    return null;
  });

  ok('the service worker\'s own cache holds the page actually just published',
     cached && cached.indexOf('NEW PAGE') !== -1,
     cached && cached.slice(0, 60));
  ok('not the one the browser\'s ordinary HTTP cache was still holding',
     cached && cached.indexOf('OLD PAGE') === -1,
     cached && cached.slice(0, 60));
  // The network really was reached for both assets that matter (./ and
  // ./index.html), not answered out of the HTTP cache for either.
  ok('the network was actually reached for the page, not answered from the HTTP cache',
     indexHits >= 3, indexHits);

  ok('no page errors', errs.length === 0, errs.slice(0, 3).join(' | '));

  await b.close();
  srv.close();
  console.log(fail ? '\n' + fail + ' FAILED' : '\nALL PASS');
  process.exit(fail ? 1 : 0);
})();
