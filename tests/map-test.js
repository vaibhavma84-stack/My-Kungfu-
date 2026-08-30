// The world map and the ETA port picker. The map is a canvas, so most of what
// matters cannot be read out of the DOM -- these check the data behind it, the
// geometry decoder against known coordinates, and the behaviour around it.
const { chromium } = require('playwright-core');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

(async () => {
  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext({ viewport:{width:900,height:1000} });
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  await p.goto('file://' + process.env.APP_HTML);
  await p.waitForTimeout(600);

  await p.click('#topTabs button[data-tab="tools"]');
  ok('the tile is there', await p.locator('[data-tool="map"]').count() === 1);
  ok('six live tools on the launcher',
     await p.locator('.tool-tile[data-tool]').count() === 6,
     await p.locator('.tool-tile[data-tool]').count());
  await p.click('[data-tool="map"]');
  await p.waitForTimeout(900);
  ok('it opens', await p.locator('#toolMap').isVisible());
  ok('titled World map', (await p.textContent('#pageTitle')).trim() === 'World map');

  // the canvas has actually been drawn on, not just sized
  const painted = await p.evaluate(() => {
    const c = document.getElementById('mapCanvas');
    const g = c.getContext('2d');
    const d = g.getImageData(0, 0, c.width, c.height).data;
    const seen = new Set();
    for(let i = 0; i < d.length; i += 4 * 997) seen.add(d[i] + ',' + d[i+1] + ',' + d[i+2]);
    return seen.size;
  });
  ok('the map is actually drawn, in more than one colour', painted > 3, painted + ' distinct colours');

  // geometry decodes to real places on Earth
  const geo = await p.evaluate(() => {
    const d = window.__mapProbe();
    function bbox(lines){
      let a = 1e9, b = -1e9, c = 1e9, e = -1e9;
      for(const L of lines) for(let i = 0; i < L.length; i += 2){
        a = Math.min(a, L[i]); b = Math.max(b, L[i]);
        c = Math.min(c, L[i+1]); e = Math.max(e, L[i+1]);
      }
      return [a, b, c, e];
    }
    return { coast: d.coast.all.length, rivers: d.rivers.all.length, borders: d.borders.all.length,
             places: d.places.length, box: bbox(d.coast.all),
             tiers: [d.coast.big.length, d.coast.mid.length, d.coast.all.length] };
  });
  ok('coastline decodes to tens of thousands of lines', geo.coast > 20000, geo.coast);
  ok('and is tiered by size, so a whole-globe pan does not walk all of it',
     geo.tiers[0] < geo.tiers[1] && geo.tiers[1] < geo.tiers[2] && geo.tiers[0] < 2000,
     geo.tiers.join(' < '));
  ok('borders and rivers decode too', geo.borders > 300 && geo.rivers > 3000,
     geo.borders + ' borders, ' + geo.rivers + ' rivers');
  ok('the coastline spans the whole Earth, not a corner of it',
     geo.box[0] < -179 && geo.box[1] > 179 && geo.box[2] < -60 && geo.box[3] > 70,
     geo.box.map(x => x.toFixed(1)).join(', '));

  // places: known coordinates, to catch a decoder that silently scales wrong
  const known = await p.evaluate(() => {
    const d = window.__mapProbe();
    const find = n => d.places.find(x => x.name === n);
    return { sing: find('Singapore'), rot: find('Rotterdam'), fuj: find('Fujairah') };
  });
  ok('Singapore is where Singapore is',
     known.sing && Math.abs(known.sing.lat - 1.29) < 0.1 && Math.abs(known.sing.lon - 103.85) < 0.1,
     known.sing && known.sing.lat + ', ' + known.sing.lon);
  ok('and it is marked as on the coast', known.sing && known.sing.coastal === true);
  ok('Rotterdam too, with its standard offset and a DST flag',
     known.rot && Math.abs(known.rot.lat - 51.92) < 0.15 && known.rot.off === 60 && known.rot.dst === true,
     known.rot && [known.rot.lat, known.rot.off, known.rot.dst].join(', '));

  // search
  await p.fill('#mapSearchIn', 'Rotter');
  await p.waitForTimeout(300);
  ok('search finds a port by the start of its name',
     await p.locator('#mapResults .mr-row').count() >= 1);
  await p.locator('#mapResults .mr-row').first().click();
  await p.waitForTimeout(400);
  const info = (await p.textContent('#mapInfo')).replace(/\s+/g, ' ');
  ok('picking it moves the map and names it', /Rotterdam/.test(info), info.slice(0, 120));
  ok('and gives its position in degrees and minutes', /\d+° \d+\.\d' [NS]/.test(info), info.slice(0, 90));
  ok('and its time zone', /UTC\+01:00/.test(info), info.slice(0, 160));

  await p.fill('#mapSearchIn', 'zzzzzz');
  await p.waitForTimeout(250);
  ok('a search with no hits says so', /Nothing by that name/.test(await p.textContent('#mapResults')));

  // layers really switch off
  const before = await p.evaluate(() => {
    const c = document.getElementById('mapCanvas'), g = c.getContext('2d');
    return g.getImageData(0, 0, c.width, c.height).data.reduce((a, v, i) => i % 4 ? a : a + v, 0);
  });
  await p.click('#mapLayerBtns [data-maplayer="rivers"]');
  await p.click('#mapLayerBtns [data-maplayer="places"]');
  await p.waitForTimeout(400);
  const after = await p.evaluate(() => {
    const c = document.getElementById('mapCanvas'), g = c.getContext('2d');
    return g.getImageData(0, 0, c.width, c.height).data.reduce((a, v, i) => i % 4 ? a : a + v, 0);
  });
  ok('turning layers off actually changes the picture', before !== after, before + ' vs ' + after);
  await p.click('#mapLayerBtns [data-maplayer="rivers"]');
  await p.click('#mapLayerBtns [data-maplayer="places"]');

  // the view is remembered
  await p.evaluate(() => { window.__mapSetView(35.6, 139.7, 20); });
  await p.waitForTimeout(300);
  await p.reload();
  await p.waitForTimeout(700);
  await p.click('#topTabs button[data-tab="tools"]');
  await p.click('[data-tool="map"]');
  await p.waitForTimeout(600);
  const view = await p.evaluate(() => window.__mapView());
  ok('the view is where it was left after a reload',
     Math.abs(view.lat - 35.6) < 0.5 && Math.abs(view.lon - 139.7) < 0.5,
     JSON.stringify(view));

  // --- the ETA port picker ------------------------------------------------
  await p.click('#mapBackBtn');
  await p.click('[data-tool="eta"]');
  await p.waitForTimeout(300);
  await p.focus('#etaFromPort');
  await p.waitForTimeout(800);
  ok('the ETA tool offers ports now',
     await p.locator('#etaPortList option').count() > 3000,
     await p.locator('#etaPortList option').count());
  await p.fill('#etaFromPort', 'Singapore');
  await p.waitForTimeout(400);
  ok('picking one sets the departure zone', await p.inputValue('#etaZoneFrom') === '480',
     await p.inputValue('#etaZoneFrom'));
  ok('and says it keeps that zone all year',
     /all year/.test(await p.textContent('#etaPortNote')));
  await p.fill('#etaToPort', 'Rotterdam');
  await p.waitForTimeout(400);
  ok('the arrival zone too', await p.inputValue('#etaZoneTo') === '60',
     await p.inputValue('#etaZoneTo'));
  ok('and a summer-time port is flagged rather than assumed',
     /observes summer time/.test(await p.textContent('#etaPortNote')),
     await p.textContent('#etaPortNote'));

  ok('no page errors', errs.length === 0, errs.join(' | '));
  await b.close();
  console.log('');
  console.log(fail ? fail + ' FAILED' : 'ALL PASS');
  process.exit(fail ? 1 : 0);
})();
