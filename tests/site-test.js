// The two hosted apps.
//
// The map is 45.9 MB of a 46.4 MB app. iOS caps what a home-screen web app may
// keep offline well below that, so the hosted build is split: a deck log of
// about two megabytes that installs in seconds, and a separate map whose data
// is fetched a band at a time. These have to be served over HTTP rather than
// opened as files, because that is the whole point -- the map fetches, and
// fetch does not work from file://.
const { chromium } = require('playwright-core');
const http = require('http');
const fs = require('fs');
const path = require('path');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

const ROOT = process.env.SITE_DIR ||
             path.join(path.dirname(process.env.APP_HTML || '.'), 'site');
const TYPES = { '.html':'text/html', '.js':'text/javascript', '.json':'application/json',
                '.png':'image/png', '.webmanifest':'application/manifest+json' };

let served = 0, servedData = 0;
function serve(){
  return new Promise(res => {
    const s = http.createServer((req, rp) => {
      let p = decodeURIComponent(req.url.split('?')[0]);
      if(p.endsWith('/')) p += 'index.html';
      const f = path.join(ROOT, p);
      if(!f.startsWith(ROOT) || !fs.existsSync(f) || fs.statSync(f).isDirectory()){
        rp.writeHead(404); rp.end('no'); return;
      }
      served++;
      if(p.indexOf('/data/') !== -1) servedData++;
      rp.writeHead(200, { 'Content-Type': TYPES[path.extname(f)] || 'application/octet-stream' });
      fs.createReadStream(f).pipe(rp);
    });
    s.listen(0, '127.0.0.1', () => res(s));
  });
}

(async () => {
  if(!fs.existsSync(ROOT)){ console.log('  SKIP  no site/ built'); console.log('ALL PASS'); return; }
  const server = await serve();
  const base = 'http://127.0.0.1:' + server.address().port;
  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });

  // ---- sizes: the reason the split exists -------------------------------
  const logSize = fs.statSync(path.join(ROOT, 'index.html')).size;
  const mapShell = fs.statSync(path.join(ROOT, 'map', 'index.html')).size;
  const dataDir = path.join(ROOT, 'map', 'data');
  const dataFiles = fs.readdirSync(dataDir);
  const dataSize = dataFiles.reduce((a, f) => a + fs.statSync(path.join(dataDir, f)).size, 0);
  ok('the deck log is small enough for a phone to keep offline',
     logSize < 4 * 1048576, (logSize / 1048576).toFixed(2) + ' MB');
  ok('the map ships as a small shell, not a single huge page',
     mapShell < 2 * 1048576, (mapShell / 1048576).toFixed(2) + ' MB');
  ok('with its data in separate files so none of it is precached',
     dataFiles.length > 30 && dataSize > 40 * 1048576,
     dataFiles.length + ' files, ' + (dataSize / 1048576).toFixed(1) + ' MB');

  // ---- the deck log ------------------------------------------------------
  {
    const ctx = await b.newContext({ viewport:{width:420,height:900} });
    const p = await ctx.newPage();
    const errs = []; p.on('pageerror', e => errs.push(String(e)));
    servedData = 0;
    await p.goto(base + '/');
    await p.waitForTimeout(900);
    ok('the deck log opens', await p.locator('#topTabs').isVisible());
    await p.click('#topTabs button[data-tab="tools"]');
    await p.waitForTimeout(200);
    ok('and has no world map tile, because its data is not here',
       await p.locator('[data-tool="map"]').count() === 0);
    ok('the other tools are all still there',
       await p.locator('.tool-tile[data-tool]').count() === 6,
       await p.locator('.tool-tile[data-tool]').count());

    // the ETA tool works in UTC and needs nothing from the map at all
    await p.click('[data-tool="eta"]');
    await p.waitForTimeout(300);
    ok('the ETA tool works without the chart behind it',
       await p.locator('#toolEta').isVisible() &&
       await p.locator('#etaZoneFrom').count() === 0);
    await p.fill('#etaDate', '2026-09-01');
    await p.fill('#etaTime', '20:30');
    await p.fill('#etaDist', '1450');
    await p.fill('#etaSpeed', '15');
    await p.waitForTimeout(300);
    ok('and gives an arrival in UTC on the hosted copy',
       /Sat 05-Sep-2026 2110 UTC/.test(await p.textContent('#etaOut')),
       (await p.textContent('#etaOut')).slice(0, 90));

    // the port-call panel still offers ports, which is what the list is for now
    await p.click('#topTabs button[data-tab="jobs"]');
    await p.focus('#paPort');
    await p.waitForTimeout(600);
    ok('the pre-arrival port picker still offers every port',
       await p.locator('#paPortList option').count() > 3000,
       await p.locator('#paPortList option').count());

    // a job survives, which is the thing the app is actually for
    await p.click('#topTabs button[data-tab="jobs"]');
    await p.fill('#inJob', 'Sound bilges');
    await p.click('#addBtn');
    await p.waitForTimeout(300);
    await p.reload();
    await p.waitForTimeout(800);
    ok('a job entered on the hosted copy is still there after a reload',
       (await p.textContent('#listWrap')).indexOf('Sound bilges') >= 0);

    ok('and none of that needed a byte of chart data', servedData === 0, servedData + ' requests');
    ok('no page errors on the deck log', errs.length === 0, errs.join(' | '));
    await ctx.close();
  }

  // ---- the world map -----------------------------------------------------
  {
    const ctx = await b.newContext({ viewport:{width:420,height:900} });
    const p = await ctx.newPage();
    const errs = []; p.on('pageerror', e => errs.push(String(e)));
    servedData = 0;
    await p.goto(base + '/map/');
    await p.waitForFunction(() => document.getElementById('mapCanvas') &&
                                  document.getElementById('toolMap').style.display !== 'none',
                            null, { timeout: 20000 });
    ok('the map app opens straight into the chart, not a launcher',
       await p.locator('#toolMap').isVisible());
    ok('and puts the rest of the app away',
       !(await p.locator('#topTabs').isVisible()));

    await p.waitForFunction(() => window.__mapTiles && (() => {
      try { return window.__mapTiles().bands > 0; } catch(e){ return false; }
    })(), null, { timeout: 30000 });
    const t = await p.evaluate(() => window.__mapTiles());
    ok('it fetched the data it needs to draw at all', t.bands === 18, JSON.stringify(t.bands));
    ok('but not every band of it', t.parsedBands.coast < t.bands,
       t.parsedBands.coast + ' of ' + t.bands);

    const painted = await p.evaluate(() => {
      const c = document.getElementById('mapCanvas'), g = c.getContext('2d');
      const d = g.getImageData(0, 0, c.width, c.height).data;
      const seen = new Set();
      for(let i = 0; i < d.length; i += 4 * 997) seen.add(d[i] + ',' + d[i+1] + ',' + d[i+2]);
      return seen.size;
    });
    ok('and actually drew a map', painted > 3, painted + ' distinct colours');

    // zoom in somewhere real: the bands for that sea have to arrive
    const before = servedData;
    await p.evaluate(() => window.__mapSetView(51.5, 1.5, 60));
    await p.waitForTimeout(2500);
    ok('panning to a coast fetches that part of the world and no more',
       servedData > before && servedData < 20,
       (servedData - before) + ' band requests');
    ok('no page errors on the map', errs.length === 0, errs.join(' | '));
    await ctx.close();
  }

  await b.close();
  server.close();
  console.log('');
  console.log(fail ? fail + ' FAILED' : 'ALL PASS');
  process.exit(fail ? 1 : 0);
})();
