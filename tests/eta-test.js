// The ETA tool. Everything is UTC now -- the ship works in it, and a local
// time that has to be chosen from a list of zones is a place to make a mistake
// rather than a convenience. Departure UTC in, arrival UTC out, distance and
// speed. The speed band is the point of the thing, so its endpoints and its
// arithmetic are checked rather than eyeballed.
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
  ok('there are no time zones to get wrong', await p.locator('#etaZoneFrom').count()===0);
  ok('and no port pickers either', await p.locator('#etaFromPort').count()===0);
  ok('the departure date is labelled UTC',
     /UTC/.test(await p.textContent('label[for], .field:has(#etaDate) label').catch(()=>'')) ||
     (await p.locator('.field:has(#etaDate) label').textContent()).indexOf('UTC') >= 0,
     await p.locator('.field:has(#etaDate) label').textContent());

  // 1450 NM at 15.0 kn = 96.666.. h = 96h 40m
  await p.fill('#etaDate','2026-09-01');
  await p.fill('#etaTime','20:30');
  await p.fill('#etaDist','1450');
  await p.fill('#etaSpeed','15');
  await p.waitForTimeout(150);

  const dep = await p.textContent('#etaDep');
  ok('departure is echoed back as the UTC that was typed, unshifted',
     /Tue 01-Sep-2026 2030 UTC/.test(dep), dep);
  ok('and nothing anywhere claims a local time', !/LT/.test(dep), dep);

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

  // 1450 NM at 15.0 kn is 96h 40m. 01-Sep 2030 UTC plus that is 05-Sep 2110 UTC.
  const sel = await rowFor('15.0');
  ok('steaming time is right at the selected speed',
     /4d 0h 40m/.test(await sel.textContent()), await sel.textContent());
  ok('arrival UTC is right',
     /Sat 05-Sep-2026 2110 UTC/.test(await sel.textContent()), await sel.textContent());
  ok('and the row says UTC once, not a local time beside it',
     (await sel.textContent()).split('UTC').length === 2 && !/LT/.test(await sel.textContent()),
     await sel.textContent());

  // 1450 at 16.0 kn = 90.625 h = 3d 18h 38m (rounded up from 37.5m)
  const fast = await rowFor('16.0');
  ok('a faster speed shortens the passage correctly',
     /3d 18h 38m/.test(await fast.textContent()), await fast.textContent());
  // 1450 at 14.1 kn = 102.836 h = 4d 6h 50m
  const slow = await rowFor('14.1');
  ok('a slower speed lengthens it correctly',
     /4d 6h 50m/.test(await slow.textContent()), await slow.textContent());

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
  // The distance and the speed are remembered. The departure is not, and should
  // not be: a date left over from the last passage is exactly the sort of thing
  // that gets noticed only after the ETA has been passed to an agent.
  ok('but not a stale departure date -- it comes back as today',
     (await p.inputValue('#etaDate')) === new Date().toISOString().slice(0, 10),
     await p.inputValue('#etaDate'));

  await p.click('#etaBackBtn');
  ok('back returns to the launcher', await p.locator('#toolsHome').isVisible());

  ok('no page errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails? '\n'+fails+' FAILED' : '\nALL PASS');
  process.exit(fails?1:0);
})();
