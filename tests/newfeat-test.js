const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
(async () => {
  const srv = http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(process.env.APP_HTML));}).listen(8741);
  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext({ viewport:{width:420,height:900}, acceptDownloads:true });
  const p = await ctx.newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  p.on('dialog', async d=>await d.accept());
  await p.goto('http://localhost:8741/');

  // --- permit type ---
  ok('permit type hidden until PTW is ticked', !(await p.isVisible('#ptwTypeRow')));
  await p.click('#inPTW');
  ok('permit type appears when PTW ticked', await p.isVisible('#ptwTypeRow'));
  const opts = await p.evaluate(()=>[...document.querySelectorAll('#inPtwType option')].map(o=>o.value));
  ok('11 permit types from the workbook, plus a placeholder', opts.length===12, opts.length);
  ok('list matches the sheet exactly',
     opts[5]==='ENCLOSED SPACE' && opts[1]==='N/A' && opts[11]==='WORKING ON DECK IN HEAVY WEATHER', opts.join('|'));
  await p.selectOption('#inPtwType','ENCLOSED SPACE');
  await p.click('#inPTW');
  ok('un-ticking PTW hides and clears the type',
     !(await p.isVisible('#ptwTypeRow')) && (await p.inputValue('#inPtwType'))==='');
  await p.click('#inPTW'); await p.selectOption('#inPtwType','ENCLOSED SPACE');

  await p.fill('#inJob','ESE WAH - No.2 Stbd WBT Inspection');
  await p.fill('#inDue','2026-07-10'); await p.click('#inAd34'); await p.click('#inRA');
  await p.click('#addBtn');
  ok('permit type stored on the job',
     await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1'))[0].ptwType) === 'ENCLOSED SPACE');
  ok('form reset hides the permit row again', !(await p.isVisible('#ptwTypeRow')));

  // --- completion date picker ---
  await p.click('.tick');
  ok('ticking opens the date picker instead of stamping today', await p.isVisible('.date-card'));
  ok('picker defaults to the due date when it is not in the future',
     (await p.inputValue('.dc-input')) === '2026-07-10', await p.inputValue('.dc-input'));
  await p.click('[data-dc="cancel"]');
  ok('cancelling leaves the job pending',
     await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1'))[0].done) === false);

  await p.click('.tick');
  await p.fill('.dc-input','2026-07-08');
  await p.click('[data-dc="ok"]');
  ok('confirming stores the chosen date, not today',
     await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1'))[0].dateCompleted) === '2026-07-08');

  await p.selectOption('#filterStatus','all');
  ok('completion date shown on the card', (await p.textContent('.done-chip')).indexOf('08-Jul-2026') > -1,
     await p.textContent('.done-chip'));

  await p.click('.done-chip');
  ok('tapping the chip reopens the picker', await p.isVisible('.date-card'));
  await p.fill('.dc-input','2026-07-09'); await p.click('[data-dc="ok"]');
  ok('date can be corrected afterwards',
     await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1'))[0].dateCompleted) === '2026-07-09');
  ok('card chip updates', (await p.textContent('.done-chip')).indexOf('09-Jul-2026') > -1);

  await p.click('.tick');
  ok('un-ticking needs no picker and clears the date',
     !(await p.isVisible('.date-card')) &&
     await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1'))[0].dateCompleted) === '');

  // --- export carries the permit type ---
  await p.click('.tick'); await p.fill('.dc-input','2026-07-09'); await p.click('[data-dc="ok"]');
  const dl = p.waitForEvent('download'); await p.click('#exportAd19Btn');
  await (await dl).saveAs(process.env.OUT + '/ad19-permit.csv');
  ok('no JS errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
