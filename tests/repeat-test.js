const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
(async () => {
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(process.env.APP_HTML));}).listen(8751);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const ctx=await b.newContext({viewport:{width:900,height:900},acceptDownloads:true});
  const p=await ctx.newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  const dialogs=[]; p.on('dialog',async d=>{dialogs.push(d.message()); await d.accept();});
  await p.goto('http://localhost:8751/');

  const opts = await p.evaluate(()=>[...document.querySelectorAll('#inRepeat option')].map(o=>o.value+'|'+o.textContent));
  ok('repeat list offers the PMS intervals', opts.length===7, JSON.stringify(opts));
  ok('labels show the day counts',
     opts.some(o=>/3-monthly \(90 days\)/.test(o)) && opts.some(o=>/Weekly \(7 days\)/.test(o)), JSON.stringify(opts));

  const add = async (job, due, rep) => {
    await p.fill('#inJob',job); await p.fill('#inDue',due);
    await p.selectOption('#inRepeat',rep); await p.click('#inAd34'); await p.click('#inRA');
    await p.fill('#inRemarks','carry me over'); await p.click('#addBtn');
  };
  await add('Air hoses weekly inspection','2026-07-06','weekly');
  await add('Lifeboat 3-monthly service','2026-07-06','quarterly');
  await add('One-off job','2026-07-06','');

  ok('repeat chip on the card', (await p.locator('.rep-chip').count())===2, await p.locator('.rep-chip').count());
  ok('chip omits the day count', (await p.locator('.rep-chip').first().textContent()).indexOf('(')===-1,
     await p.locator('.rep-chip').first().textContent());

  await p.selectOption('#filterStatus','all');
  const tick = async (job, iso) => {
    await p.locator('.task:not(.done)',{hasText:job}).first().locator('.tick').click();
    await p.waitForSelector('.date-card'); await p.fill('.dc-input',iso); await p.click('[data-dc="ok"]');
    await p.waitForTimeout(200);
  };

  // done LATE on 09-Jul: weekly -> 16-Jul (7 days after DONE, not after due)
  dialogs.length=0;
  await tick('Air hoses weekly inspection','2026-07-09');
  ok('message quotes days after the done date',
     dialogs.some(d=>/7 days after 09-Jul-2026/.test(d)), JSON.stringify(dialogs));
  let st = await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1')));
  let nxt = st.filter(t=>/Air hoses/.test(t.job) && !t.done);
  ok('one new occurrence raised', nxt.length===1, nxt.length);
  ok('weekly = 7 days after the done date (16-Jul, not 13-Jul)', nxt[0].due==='2026-07-16', nxt[0].due);

  // 3-monthly done on 09-Jul -> 07-Oct (90 days)
  await tick('Lifeboat 3-monthly service','2026-07-09');
  st = await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1')));
  nxt = st.filter(t=>/Lifeboat/.test(t.job) && !t.done);
  ok('3-monthly = 90 days after the done date', nxt[0].due==='2026-10-07', nxt[0].due);

  // flags and settings carry, photos and completion do not
  const c = nxt[0];
  ok('AD 19 carried over', c.ad34Planner==='yes');
  ok('RA carried over', c.ra===true);
  ok('remarks carried over', c.remarks==='carry me over');
  ok('repeat carried over', c.repeat==='quarterly');
  ok('new one starts pending with no completion date', c.done===false && c.dateCompleted==='');
  ok('new one carries no photos', (c.photos||[]).length===0);

  // non-repeating job raises nothing
  const before = (await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1')).length));
  await tick('One-off job','2026-07-09');
  ok('a job with no repeat raises nothing',
     await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1')).length)===before);

  // un-tick then re-tick must not raise a duplicate
  // the COMPLETED card, not .first() — pending sorts above done
  await p.locator('.task.done',{hasText:'Air hoses'}).locator('.tick').click();  // un-tick
  await p.waitForTimeout(200);
  await tick('Air hoses weekly inspection','2026-07-09');
  const airs = await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1')).filter(t=>/Air hoses/.test(t.job)).length);
  ok('re-ticking does not raise a duplicate', airs===2, airs);

  // completing from the edit sheet also raises the next one
  // the PENDING occurrence (due 16-Jul) — the completed one has already spawned
  await p.locator('.task:not(.done)',{hasText:'Air hoses'}).first().locator('[data-action="edit"]').click();
  ok('edit sheet shows the repeat', (await p.inputValue('#edRepeat'))==='weekly', await p.inputValue('#edRepeat'));
  await p.selectOption('#edDone','done'); await p.fill('#edComp','2026-07-20');
  await p.click('[data-ed="save"]'); await p.waitForTimeout(300);
  const airs2 = await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1')).filter(t=>/Air hoses/.test(t.job)));
  ok('completing via the edit sheet raises the next', airs2.length===3, airs2.length);
  ok('and it is 7 days on from 20-Jul', airs2.some(t=>t.due==='2026-07-27'), airs2.map(t=>t.due).join(','));

  // CSV round trip
  const dl=p.waitForEvent('download'); await p.click('#exportBtn');
  await (await dl).saveAs(process.env.OUT+'/rep.csv');
  const csv=fs.readFileSync(process.env.OUT+'/rep.csv','utf8');
  ok('CSV carries an Interval column', csv.split('\n')[0].indexOf('Interval')>-1);
  ok('CSV shows the interval', /3-monthly \(90 days\)/.test(csv));
  // interval colours
  const bg = async (job) => p.locator('.task:not(.done)',{hasText:job}).first()
                             .evaluate(el=>getComputedStyle(el).backgroundColor);
  ok('weekly card is dull yellow', (await bg('Air hoses'))==='rgb(239, 231, 194)', await bg('Air hoses'));
  ok('3-monthly card is dull red', (await bg('Lifeboat'))==='rgb(239, 217, 212)', await bg('Lifeboat'));
  await p.fill('#inJob','Monthly greasing'); await p.fill('#inDue','2026-08-01');
  await p.selectOption('#inRepeat','monthly'); await p.click('#addBtn');
  ok('monthly card is dull blue', (await bg('Monthly greasing'))==='rgb(214, 226, 240)', await bg('Monthly greasing'));
  await p.fill('#inJob','Plain job no repeat'); await p.click('#addBtn');
  ok('a job with no repeat keeps the plain card',
     (await bg('Plain job no repeat'))==='rgb(255, 255, 255)', await bg('Plain job no repeat'));

  ok('no JS errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
