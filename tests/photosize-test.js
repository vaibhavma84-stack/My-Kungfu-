// How large a photograph ends up, and how clear.
//
// One fixed quality cannot hold a size range: the same setting gave 36 KB for a
// plain bulkhead and 128 KB for a busy one. So the quality is chosen per
// photograph. The floor matters more than the ceiling -- these are printed four
// to a page in a report someone has to read, and a detailed photograph below
// about 0.6 breaks into visible blocks.
const { chromium } = require('playwright-core');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

(async () => {
  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const p = await b.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  await p.goto('file://' + process.env.APP_HTML);
  await p.waitForTimeout(600);

  const out = await p.evaluate(() => new Promise(res => {
    function scene(detail){
      const W = 4000, H = 3000;
      const c = document.createElement('canvas'); c.width = W; c.height = H;
      const g = c.getContext('2d');
      const sky = g.createLinearGradient(0, 0, 0, H);
      sky.addColorStop(0, '#8fb6d8'); sky.addColorStop(1, '#d8d2c4');
      g.fillStyle = sky; g.fillRect(0, 0, W, H);
      let seed = 999;
      const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
      for(let i = 0; i < detail; i++){
        g.fillStyle = 'rgba(' + (40 + rnd()*160|0) + ',' + (40 + rnd()*150|0) + ',' + (40 + rnd()*140|0) + ',0.75)';
        const x = rnd()*W, y = rnd()*H, w = 6 + rnd()*90, h = 6 + rnd()*90;
        if(rnd() < 0.5) g.fillRect(x, y, w, h);
        else { g.beginPath(); g.ellipse(x, y, w/2, h/2, rnd()*3, 0, 6.283); g.fill(); }
      }
      return c;
    }
    const kinds = [['plain', 80], ['ordinary', 4000], ['busy', 30000]];
    const got = {}; let n = 0;
    (function step(){
      if(n >= kinds.length){ res(got); return; }
      const [label, detail] = kinds[n++];
      scene(detail).toBlob(function(blob){
        window.__compress(new File([blob], 'x.jpg', { type:'image/jpeg' }), function(url){
          const img = new Image();
          img.onload = function(){
            got[label] = { kb: Math.round((url.length - 23) * 3 / 4 / 1024),
                           w: img.width, h: img.height };
            step();
          };
          img.src = url;
        });
      }, 'image/jpeg', 0.99);
    })();
  }));

  ok('an ordinary photograph lands inside the band',
     out.ordinary.kb >= 90 && out.ordinary.kb <= 150, out.ordinary.kb + ' KB');
  ok('a plain one is smaller, because there is nothing there to encode',
     out.plain.kb < out.ordinary.kb, out.plain.kb + ' KB');
  ok('a busy one is never squeezed below the clarity floor, even past the ceiling',
     out.busy.kb >= 90, out.busy.kb + ' KB');

  // Nine hundred pixels across about 85 mm on the page is roughly 270 dots to
  // the inch. Printers do not resolve more, and less starts to show.
  ['plain','ordinary','busy'].forEach(k => {
    ok(k + ' is 900 px on the long edge, which prints at about 270 dpi',
       Math.max(out[k].w, out[k].h) === 900, out[k].w + 'x' + out[k].h);
  });

  // the quality floor itself, rather than only its effect
  const floor = await p.evaluate(() => window.__photoQualities ? window.__photoQualities() : null);
  ok('the quality never goes below 0.6', floor && Math.min.apply(null, floor) >= 0.6,
     JSON.stringify(floor));

  ok('no page errors', errs.length === 0, errs.join(' | '));
  await b.close();
  console.log('');
  console.log(fail ? fail + ' FAILED' : 'ALL PASS');
  process.exit(fail ? 1 : 0);
})();
