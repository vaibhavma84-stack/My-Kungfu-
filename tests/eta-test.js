// The ETA tool. Departure is entered local and must show UTC; arrival is shown
// both ways; and the speed band is the point of the thing, so its endpoints and
// its arithmetic are checked rather than eyeballed.
const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs'); const path = require('path');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+(x===undefined?'':x))); if(!c)fails++;};
(async () => {
  const APP = process.env.APP_HTML || path.join(__dirname,'..','index.html');
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(APP));}).listen(8766);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const ctx=await b.newContext({viewport:{width:412,height:900}});
  const p=await ctx.newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  p.on('dialog',async d=>await d.accept());
  await p.goto('http://localhost:8766/');

  await p.click('#topTabs button[data-tab="tools"]');
  ok('ETA tile present', await p.locator('[data-tool="eta"]').count()===1);
  await p.click('[data-tool="eta"]');
  ok('ETA opens', await p.locator('#toolEta').isVisible());
  ok('title reads ETA', (await p.textContent('#pageTitle')).trim()==='ETA');
  ok('defaults to the ship on China time', (await p.inputValue('#etaZoneFrom'))==='480');

  // 1450 NM at 15.0 kn = 96.666.. h = 96h 40m
  await p.fill('#etaDate','2026-09-01');
  await p.fill('#etaTime','20:30');
  await p.fill('#etaDist','1450');
  await p.fill('#etaSpeed','15');
  await p.waitForTimeout(150);

  const dep = await p.textContent('#etaDep');
  ok('departure echoed in local time', /Tue 01-Sep-2026 2030 LT/.test(dep), dep);
  ok('and converted to UTC automatically', /Tue 01-Sep-2026 1230 UTC/.test(dep), dep);

  const rows = p.locator('#etaOut .eta-row');
  ok('twenty speeds are listed', await rows.count()===20, await rows.count());
  ok('band starts nine tenths below',
     (await rows.first().locator('.es').textContent()).trim()==='14.1 kn',
     await rows.first().locator('.es').textContent());
  ok('band ends one knot above',
     (await rows.last().locator('.es').textContent()).trim()==='16.0 kn',
     await rows.last().locator('.es').textContent());
  ok('the selected speed is the highlighted one',
     (await p.locator('#etaOut .eta-row.self .es').textContent()).trim()==='15.0 kn');
  ok('exactly one row is highlighted', await p.locator('#etaOut .eta-row.self').count()===1);

  const rowFor = async (kn) => p.locator('.eta-row', { hasText: kn+' kn' }).first();

  // 96h 40m (4d 0h 40m) after 01-Sep 1230 UTC is 05-Sep 1310 UTC; +8 makes it 05-Sep 2110 LT
  const sel = await rowFor('15.0');
  ok('steaming time is right at the selected speed',
     /4d 0h 40m/.test(await sel.textContent()), await sel.textContent());
  ok('arrival UTC is right',
     /05-Sep-2026 1310 UTC/.test(await sel.textContent()), await sel.textContent());
  ok('arrival LT is right',
     /Sat 05-Sep-2026 2110/.test(await sel.textContent()), await sel.textContent());

  // 1450 at 16.0 kn = 90.625 h = 3d 18h 38m (rounded up from 37.5m)
  const fast = await rowFor('16.0');
  ok('a faster speed shortens the passage correctly',
     /3d 18h 38m/.test(await fast.textContent()), await fast.textContent());
  // 1450 at 14.1 kn = 102.836 h = 4d 6h 50m
  const slow = await rowFor('14.1');
  ok('a slower speed lengthens it correctly',
     /4d 6h 50m/.test(await slow.textContent()), await slow.textContent());

  // ---- a different arrival zone ----
  await p.selectOption('#etaZoneTo','0');
  await p.waitForTimeout(150);
  const sel2 = await rowFor('15.0');
  ok('arrival zone is applied separately from departure',
     /Sat 05-Sep-2026 1310/.test(await sel2.textContent()), await sel2.textContent());
  ok('departure line still shows the departure zone', /2030 LT/.test(await p.textContent('#etaDep')));
  await p.selectOption('#etaZoneTo','480');

  // ---- half-hour zones exist ----
  await p.selectOption('#etaZoneFrom','330');       // India
  await p.waitForTimeout(150);
  ok('half-hour zones are offered', (await p.inputValue('#etaZoneFrom'))==='330');
  ok('a half-hour zone converts correctly',
     /Tue 01-Sep-2026 1500 UTC/.test(await p.textContent('#etaDep')), await p.textContent('#etaDep'));
  await p.selectOption('#etaZoneFrom','480');

  // ---- a date-line crossing zone ----
  await p.selectOption('#etaZoneFrom','-660');
  await p.waitForTimeout(150);
  ok('a negative zone rolls the UTC date forward',
     /Wed 02-Sep-2026 0730 UTC/.test(await p.textContent('#etaDep')), await p.textContent('#etaDep'));
  await p.selectOption('#etaZoneFrom','480');

  // ---- guards ----
  await p.fill('#etaSpeed','');
  await p.waitForTimeout(120);
  ok('no speed means no table', /Enter a distance and a speed/.test(await p.textContent('#etaOut')));
  await p.fill('#etaSpeed','0.4');
  await p.waitForTimeout(120);
  ok('a very low speed drops the rows that would be zero or negative',
     await rows.count() < 20 && await rows.count() > 0, await rows.count());
  ok('and never prints a nonsense speed',
     !/(^|\s)(0\.0|-)\d* kn/.test(await p.textContent('#etaOut')));
  await p.fill('#etaSpeed','15');
  await p.fill('#etaDist','');
  await p.waitForTimeout(120);
  ok('no distance means no table', /Enter a distance and a speed/.test(await p.textContent('#etaOut')));

  // ---- what it remembers ----
  await p.fill('#etaDist','830');
  await p.fill('#etaSpeed','12.5');
  await p.waitForTimeout(150);
  await p.reload();
  await p.waitForTimeout(200);
  await p.click('#topTabs button[data-tab="tools"]');
  await p.click('[data-tool="eta"]');
  ok('remembers the distance', (await p.inputValue('#etaDist'))==='830');
  ok('remembers the speed',    (await p.inputValue('#etaSpeed'))==='12.5');
  ok('remembers the zones',    (await p.inputValue('#etaZoneFrom'))==='480');

  await p.click('#etaBackBtn');
  ok('back returns to the launcher', await p.locator('#toolsHome').isVisible());

  ok('no page errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails? '\n'+fails+' FAILED' : '\nALL PASS');
  process.exit(fails?1:0);
})();
