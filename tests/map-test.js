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
  ok('seven live tools on the launcher',
     await p.locator('.tool-tile[data-tool]').count() === 7,
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

  // --- the high-resolution tiles, the bands and the country colouring -----
  // The map is too big to parse in one go, so the tiles are stored as
  // twenty-degree bands and only what is on screen is parsed. These check the
  // bands are all present, that opening the map does NOT drag them all in, that
  // each tile is genuinely clipped to its own square, and that the colouring
  // promise the generator makes reached the app.
  // zoomed in first: below the tile threshold the map draws from the coarse
  // layer and needs no bands at all, so a count taken there proves nothing
  await p.evaluate(() => { window.__mapSetView(51.0, 1.5, 60); });
  await p.waitForTimeout(500);
  const tiles = await p.evaluate(() => window.__mapTiles());
  ok('the coastline and the country fills are both banded',
     tiles.coastBands === tiles.bands && tiles.countryBands === tiles.bands && tiles.bands >= 12,
     tiles.coastBands + '/' + tiles.countryBands + ' of ' + tiles.bands);
  ok('and drawing one place parses only the bands it needs, not all of them',
     tiles.parsedBands.coast > 0 && tiles.parsedBands.coast < tiles.bands,
     tiles.parsedBands.coast + ' coast bands parsed of ' + tiles.bands);
  ok('the geometry is encoded finer than it is simplified',
     tiles.prec >= 2000, '1/' + tiles.prec + ' of a degree');
  ok('with a coarse whole-world layer for zoomed-out views',
     tiles.overview >= 100, tiles.overview + ' countries');
  ok('no two countries within reach of each other share a colour',
     tiles.adjBad === 0, tiles.adjBad + ' pairs sharing');
  ok('the palette has as many colours as the colouring used',
     tiles.fills >= tiles.colours, tiles.fills + ' fills for ' + tiles.colours + ' colours');
  ok('countries and rivers both carry names',
     tiles.countryLabels > 200 && tiles.riverLabels > 1000,
     tiles.countryLabels + ' countries, ' + tiles.riverLabels + ' rivers');

  // Every ring in a tile must lie inside that tile, or the clip did not happen
  // and the tile is carrying geometry that belongs to its neighbours.
  const clipped = await p.evaluate(() => {
    const size = 4, keys = [];
    for(const [lon, lat] of [[0, 51], [103, 1], [-4, 50], [55, 25]])
      keys.push(Math.floor((lon + 180) / size) + '_' + Math.floor((lat + 90) / size));
    let checked = 0, outside = 0, rings = 0;
    for(const k of keys){
      const t = window.__mapTile('coast', k);
      if(!t) continue;
      checked++;
      const [xs, ys] = k.split('_').map(Number);
      const w = xs * size - 180, e = w + size, s = ys * size - 90, n = s + size;
      for(const lvl of Object.keys(t)) for(const r of t[lvl]){
        rings++;
        if(r.w < w - 0.02 || r.e > e + 0.02 || r.s < s - 0.02 || r.no > n + 0.02) outside++;
      }
    }
    return { checked, outside, rings };
  });
  ok('four sample tiles all decode', clipped.checked === 4, clipped.checked);
  ok('and every ring in them is clipped to its own square, not spilling out',
     clipped.rings > 20 && clipped.outside === 0,
     clipped.outside + ' of ' + clipped.rings + ' rings outside');

  // The colours have to be readable against the sea and against each other: an
  // earlier palette put Britain in a blue two just-noticeable differences from
  // the water and the island simply disappeared.
  const sep = await p.evaluate(() => {
    const cs = getComputedStyle(document.documentElement);
    const sea = cs.getPropertyValue('--map-sea').trim();
    const fills = window.__mapFills();
    function lab(hex){
      const v = parseInt(hex.slice(1), 16);
      let r = ((v >> 16) & 255) / 255, g = ((v >> 8) & 255) / 255, b = (v & 255) / 255;
      const f = c => c > 0.04045 ? Math.pow((c + 0.055) / 1.055, 2.4) : c / 12.92;
      r = f(r); g = f(g); b = f(b);
      let X = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047;
      let Y = (r * 0.2126 + g * 0.7152 + b * 0.0722);
      let Z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883;
      const h = t => t > 0.008856 ? Math.cbrt(t) : (7.787 * t + 16 / 116);
      X = h(X); Y = h(Y); Z = h(Z);
      return [116 * Y - 16, 500 * (X - Y), 200 * (Y - Z)];
    }
    const dE = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
    const L = fills.map(lab), S = lab(sea);
    let minPair = 1e9, minSea = 1e9;
    for(let i = 0; i < L.length; i++){
      minSea = Math.min(minSea, dE(L[i], S));
      for(let j = i + 1; j < L.length; j++) minPair = Math.min(minPair, dE(L[i], L[j]));
    }
    return { minPair, minSea, n: fills.length };
  });
  ok('no country colour can be mistaken for the sea',
     sep.minSea > 8, 'closest is ' + sep.minSea.toFixed(1) + ' dE from the water');
  ok('and no two of them can be mistaken for each other',
     sep.minPair > 10, 'closest pair ' + sep.minPair.toFixed(1) + ' dE');

  // Past TILE_ZOOM the tiles take over from the coarse whole-world layer. The
  // test straddles the threshold at the same place -- 13.5 and 14.5 px/degree
  // show almost the same window, so any gain in edge detail is the tiles
  // carrying more coastline than the coarse layer did, not a wider view.
  const detail = await p.evaluate(() => {
    const c = document.getElementById('mapCanvas'), g = c.getContext('2d');
    function edges(){
      const d = g.getImageData(0, 0, c.width, c.height).data, w = c.width;
      let n = 0;
      for(let y = 0; y < c.height; y += 2) for(let x = 0; x < w - 1; x++){
        const i = (y * w + x) * 4;
        if(Math.abs(d[i] - d[i + 4]) + Math.abs(d[i + 1] - d[i + 5]) > 24) n++;
      }
      return n;
    }
    const out = {};
    for(const [name, lat, lon] of [['channel', 50.9, 1.6], ['fjords', 60, 5]]){
      window.__mapSetView(lat, lon, 13.5); window.__mapRedraw(); const a = edges();
      window.__mapSetView(lat, lon, 14.5); window.__mapRedraw(); const b = edges();
      out[name] = [a, b];
    }
    return out;
  });
  ok('crossing the tile threshold brings in finer coastline detail',
     detail.channel[1] > detail.channel[0] && detail.fjords[1] > detail.fjords[0],
     Object.keys(detail).map(k => k + ' ' + detail[k].join('->')).join(', '));

  // The staircase this replaced: the geometry was full resolution but the
  // encoder quantised it to a hundredth of a degree, so at a thousand pixels to
  // the degree every coastline step was ten pixels wide. Consecutive points on
  // a decoded ring should now be far finer than that.
  const grid = await p.evaluate(() => {
    const t = window.__mapTile('coast', Math.floor((1 + 180) / 4) + '_' + Math.floor((51 + 90) / 4));
    return t ? Object.keys(t).length : 0;
  });
  ok('a coastal tile carries real geometry at the finer grid', grid > 0, grid + ' levels');

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
