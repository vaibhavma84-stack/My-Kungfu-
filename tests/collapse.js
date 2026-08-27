const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
(async () => {
  const srv = http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync('process.env.APP_HTML'));}).listen(8733);
  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const p = await (await b.newContext({viewport:{width:390,height:844}})).newPage();
  await p.goto('http://localhost:8733/');

  const expandedH = await p.evaluate(()=>document.getElementById('addWrap').getBoundingClientRect().height);
  ok('form starts expanded', expandedH > 200, expandedH);

  await p.click('#toggleFormBtn');
  await p.waitForTimeout(500); // let the 0.2s transition finish
  const collapsedH = await p.evaluate(()=>document.getElementById('addWrap').getBoundingClientRect().height);
  ok('FIX 5  Hide still fully collapses the form', collapsedH === 0, collapsedH);
  ok('FIX 5  toggle button flips to Show', (await p.textContent('#toggleFormBtn')).indexOf('Show') === 0);
  ok('FIX 5  floating + appears when hidden', await p.isVisible('#fabAdd'));
  ok('FIX 5  preference persisted', await p.evaluate(()=>localStorage.getItem('gasplanet_todo_formHidden')) === '1');

  await p.reload(); await p.waitForTimeout(400);
  ok('FIX 5  stays hidden across reload',
     await p.evaluate(()=>document.getElementById('addWrap').getBoundingClientRect().height) === 0);

  await p.click('#fabAdd'); await p.waitForTimeout(500);
  const reH = await p.evaluate(()=>document.getElementById('addWrap').getBoundingClientRect().height);
  ok('FIX 5  + button restores the full form', reH > 200, reH);
  const fits = await p.evaluate(()=>{const w=document.getElementById('addWrap');return w.scrollHeight<=w.clientHeight+1;});
  ok('FIX 5  restored form is not clipped', fits);

  // narrowest realistic phone
  await p.setViewportSize({width:320,height:568});
  await p.waitForTimeout(300);
  const fits320 = await p.evaluate(()=>{const w=document.getElementById('addWrap');
    return {fits:w.scrollHeight<=w.clientHeight+1, h:w.scrollHeight,
            btnIn: document.getElementById('addBtn').getBoundingClientRect().bottom <= w.getBoundingClientRect().bottom+1};});
  ok('FIX 5  not clipped at 320px wide (iPhone SE)', fits320.fits && fits320.btnIn, JSON.stringify(fits320));
  console.log('        (form height at 320px: '+fits320.h+'px — old 600px cap would have clipped it)');

  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})();
