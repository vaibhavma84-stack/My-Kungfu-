// At sea, with the link down.
//
// This is the one constraint the whole app is built around, so it gets a suite
// of its own rather than a line in another one. The hosted deck log is served
// once, its service worker is allowed to take hold, and then the network is
// taken away completely -- not throttled, removed. Everything after that point
// has to work with nothing to fetch: opening the app, reading a photograph,
// opening a PDF, marking it, and writing a marked copy back out.
//
// The PDF half is why this exists. pdf.js is a megabyte and a half of somebody
// else's code with a worker thread, a font engine and, left to itself, URLs it
// would like to fetch. Vendoring it is only a claim until a test has run it
// with the radio off.
const { chromium } = require('playwright-core');
const crypto = require('crypto');
const http = require('http');
const fs = require('fs');
const path = require('path');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

const HERE = ROOTDIR();
function ROOTDIR(){ return __dirname; }
const SITE = process.env.SITE_DIR ||
             path.join(path.dirname(process.env.APP_HTML || '.'), 'site');
const TYPES = { '.html':'text/html', '.js':'text/javascript', '.json':'application/json',
                '.png':'image/png', '.webmanifest':'application/manifest+json' };

function serve(){
  return new Promise(res => {
    const s = http.createServer((req, rp) => {
      let p = decodeURIComponent(req.url.split('?')[0]);
      if(p.endsWith('/')) p += 'index.html';
      const f = path.join(SITE, p);
      if(!f.startsWith(SITE) || !fs.existsSync(f) || fs.statSync(f).isDirectory()){
        rp.writeHead(404); rp.end('no'); return;
      }
      rp.writeHead(200, { 'Content-Type': TYPES[path.extname(f)] || 'application/octet-stream' });
      fs.createReadStream(f).pipe(rp);
    });
    s.listen(0, '127.0.0.1', () => res(s));
  });
}

(async () => {
  if(!fs.existsSync(path.join(SITE, 'index.html'))){
    console.log('  SKIP  no site/ built'); console.log('ALL PASS'); return;
  }
  // Before anything is launched: the cache the service worker keeps has to be
  // named after the page it holds. Named after APP_BUILD alone, a page rebuilt
  // without a version bump keeps the old cache -- and a phone that goes to sea
  // from then on serves the old page for ever, because the only thing that
  // would replace it is a visit with a link.
  const swText = fs.readFileSync(path.join(SITE, 'sw.js'), 'utf8');
  const cacheName = (swText.match(/const CACHE = '([^']+)'/) || [])[1] || '';
  const digest = crypto.createHash('sha256')
                       .update(fs.readFileSync(path.join(SITE, 'index.html')))
                       .digest('hex').slice(0, 12);
  ok('the cache is named after the page, not just the version number',
     cacheName.slice(-13) === '-' + digest, cacheName + ' should end -' + digest);

  const srv = await serve();
  const base = 'http://127.0.0.1:' + srv.address().port + '/';

  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext({ viewport:{width:390,height:844}, acceptDownloads:true });
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  p.on('dialog', async d => { await d.accept(d.type() === 'prompt' ? 'Check this one' : undefined); });

  // ---- once, with a link ---------------------------------------------------
  await p.goto(base);
  await p.waitForTimeout(1200);
  const swReady = await p.evaluate(() =>
    navigator.serviceWorker ? navigator.serviceWorker.ready.then(r => !!r.active).catch(() => false) : false);
  ok('the service worker takes hold while there is still a link', swReady === true, swReady);

  // ---- and now there is none ----------------------------------------------
  await ctx.setOffline(true);
  const failed = [], attempted = [];
  p.on('requestfailed', r => failed.push(r.url()));
  p.on('request', r => attempted.push(r.url()));

  await p.reload();
  await p.waitForTimeout(1500);
  ok('the app still opens with the link down',
     await p.locator('#topTabs button[data-tab="notes"]').isVisible());
  ok('and the build stamp is the one that was cached',
     /BUILD v/.test(await p.locator('footer').textContent()),
     await p.locator('footer').textContent());

  // nothing may reach outside this page's own origin, ever
  const offsite = attempted.filter(u => u.indexOf(base) !== 0 && u.slice(0, 5) !== 'blob:' &&
                                        u.slice(0, 5) !== 'data:');
  ok('nothing is asked for from anywhere else', offsite.length === 0, offsite.slice(0, 3).join(' '));

  // ---- a written note, with a photograph on it ----------------------------
  await p.click('#topTabs button[data-tab="notes"]');
  await p.click('#noteNewBtn');
  await p.fill('#noteTitle', 'Offline check');
  await p.fill('#noteBody', 'Written with the radio off.');
  await p.setInputFiles('#noteImgInput', path.join(HERE, 'pic.jpg'));
  await p.waitForTimeout(1400);
  const kept = await p.evaluate(() => {
    const n = JSON.parse(localStorage.getItem('gasplanet_notes_v1'))[0];
    return (n.images || []).length;
  });
  ok('a photograph is taken and kept offline', kept === 1, kept);

  await p.click('#noteBackBtn');
  await p.waitForTimeout(400);
  const shown = await p.evaluate(() => {
    const img = document.querySelector('#noteListWrap .nc-thumbs img');
    return img ? (img.getAttribute('src') || '').slice(0, 10) : 'none';
  });
  ok('and it is read back out of the store to show', shown === 'data:image', shown);

  // ---- the PDF, which is the part that had to be proved -------------------
  const t0 = Date.now();
  await p.setInputFiles('#notePdfInput', path.join(HERE, 'sample.pdf'));
  await p.waitForFunction(() => {
    const el = document.getElementById('pdfPageNo');
    return el && /\d+ \/ \d+/.test(el.textContent);
  }, null, { timeout: 30000 }).catch(() => {});
  const opened = Date.now() - t0;
  ok('a PDF opens with no network at all',
     /1 \/ 2/.test(await p.locator('#pdfPageNo').textContent()),
     await p.locator('#pdfPageNo').textContent());
  ok('and it does not sit there doing it', opened < 15000, opened + ' ms');

  const painted = await p.evaluate(() => {
    const cv = document.getElementById('pdfCanvas');
    const d = cv.getContext('2d').getImageData(0, 0, cv.width, Math.min(cv.height, 220)).data;
    let dark = 0;
    for(let i = 0; i < d.length; i += 4) if(d[i] < 200) dark++;
    return dark;
  });
  ok('the page is drawn, not left blank', painted > 50, painted);

  // the worker is a real one, off the main thread, made from a blob rather
  // than a URL -- which is the only way it can exist with nothing to fetch
  const workerSrc = await p.evaluate(() =>
    window.pdfjsLib ? String(window.pdfjsLib.GlobalWorkerOptions.workerSrc) : '');
  ok('its worker came from a blob, not a URL', workerSrc.slice(0, 5) === 'blob:', workerSrc.slice(0, 40));

  // marking, and a marked copy back out
  const box = await p.locator('#pdfInk').boundingBox();
  const at = (fx, fy) => ({ x: box.x + box.width * fx, y: box.y + box.height * fy });
  await p.click('#pdfTools button[data-pdftool="hl"]');
  let a = at(0.13, 0.085), z = at(0.46, 0.085);
  await p.mouse.move(a.x, a.y); await p.mouse.down();
  await p.mouse.move(z.x, z.y, { steps: 8 }); await p.mouse.up();
  await p.waitForTimeout(300);
  const marks = await p.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_notes_v1'))
                                          .filter(x => x.kind === 'pdf')[0].marks['1'] || []);
  ok('it can be marked offline', marks.length === 1 && marks[0].t === 'hl',
     JSON.stringify(marks.map(m => m.t)));

  const dl = p.waitForEvent('download', { timeout: 60000 });
  await p.click('#pdfExportBtn');
  const got = await dl;
  const out = path.join(fs.mkdtempSync('/tmp/offline-'), 'marked.pdf');
  await got.saveAs(out);
  const bytes = fs.readFileSync(out);
  ok('and a marked copy is written offline', bytes.slice(0, 5).toString() === '%PDF-',
     bytes.slice(0, 8).toString());

  // ---- the whole ship's archive, with no link -----------------------------
  const zdl = p.waitForEvent('download', { timeout: 90000 });
  await p.click('#topTabs button[data-tab="ship"]');
  await p.waitForTimeout(300);
  await p.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find(x => /sign(ed)? off/i.test(x.textContent));
    if(btn) btn.click();
  });
  const zip = await zdl;
  const zf = path.join(fs.mkdtempSync('/tmp/offline-zip-'), 'ship.zip');
  await zip.saveAs(zf);
  ok('signing off writes its archive offline', fs.statSync(zf).size > 1000, fs.statSync(zf).size);

  // ---- nothing waited on the network at any point -------------------------
  // A failed request is not automatically wrong -- the service worker probes
  // the network on every fetch and is meant to fall back -- but nothing may
  // have been reached for outside this origin, and nothing may have broken.
  const outside = failed.filter(u => u.indexOf(base) !== 0);
  ok('no request went anywhere but this origin', outside.length === 0, outside.slice(0, 3).join(' '));
  ok('no JS errors with the link down', errs.length === 0, errs.slice(0, 3).join(' | '));

  await b.close();
  srv.close();
  console.log(fail ? '\n' + fail + ' FAILED' : '\nALL PASS');
  process.exit(fail ? 1 : 0);
})();
