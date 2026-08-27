const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
(async () => {
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync('process.env.APP_HTML'));}).listen(8749);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const p=await (await b.newContext({viewport:{width:900,height:1000}})).newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  p.on('dialog',async d=>await d.accept());
  await p.goto('http://localhost:8749/');
  const PIC = process.env.OUT + '/pic.jpg';

  // pin "today" into the 17-23 Aug 2026 week so the default lands there
  await p.evaluate(()=>{
    const Real = Date;
    const fixed = new Real(2026, 7, 23, 18, 0, 0);   // Sun 23 Aug 2026, evening
    class FakeDate extends Real {
      constructor(...a){ return a.length ? new Real(...a) : new Real(fixed); }
      static now(){ return fixed.getTime(); }
    }
    window.Date = FakeDate;
  });
  await p.reload();
  await p.evaluate(()=>{
    const Real = Date;
    const fixed = new Real(2026, 7, 23, 18, 0, 0);
    class FakeDate extends Real {
      constructor(...a){ return a.length ? new Real(...a) : new Real(fixed); }
      static now(){ return fixed.getTime(); }
    }
    window.Date = FakeDate;
  });

  const add = async (job, due) => { await p.fill('#inJob', job); await p.fill('#inDue', due);
                                    await p.click('#inWeekly'); await p.click('#addBtn'); };
  await add('Final coat Paint Application of Main Mast carried out.','2026-08-18');
  await add('Mooring Winches covered with canvas.','2026-08-20');
  await add('Weekly Bilge Alarms tried out.','2026-08-23');
  await add('Job from the week before','2026-08-14');
  await p.fill('#inJob','Not ticked WR'); await p.fill('#inDue','2026-08-19'); await p.click('#addBtn');

  await p.selectOption('#filterStatus','all');
  for (const [job,iso] of [['Final coat Paint Application of Main Mast carried out.','2026-08-18'],
                           ['Mooring Winches covered with canvas.','2026-08-20'],
                           ['Weekly Bilge Alarms tried out.','2026-08-23'],
                           ['Job from the week before','2026-08-14']]) {
    await p.locator('.task',{hasText:job}).locator('.tick').click();
    await p.waitForSelector('.date-card'); await p.fill('.dc-input',iso); await p.click('[data-dc="ok"]');
  }
  // three photos on the first job -> 2 rows of images, caption repeated.
  // Written straight to storage: the attach paths are covered by other suites,
  // and this keeps the report test about the report.
  await p.evaluate(()=>{
    const t=JSON.parse(localStorage.getItem('gasplanet_todo_v1'));
    const j=t.find(x=>/Main Mast/.test(x.job));
    const px='data:image/gif;base64,R0lGODlhAQABAIAAAP///wAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw==';
    j.photos=[px,px,px];
    localStorage.setItem('gasplanet_todo_v1', JSON.stringify(t));
  });
  await p.reload();
  await p.evaluate(()=>{
    const Real=Date; const fixed=new Real(2026,7,23,18,0,0);
    class F extends Real { constructor(...a){ return a.length?new Real(...a):new Real(fixed);} static now(){return fixed.getTime();} }
    window.Date=F;
  });
  ok('three photos attached',
     await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1')).find(t=>/Main Mast/.test(t.job)).photos.length)===3);

  await p.click('#weeklyReportBtn');
  await p.waitForTimeout(400);
  ok('report opens', await p.isVisible('#reportView'));
  ok('title matches the document', (await p.textContent('.rv-title'))==='WEEKLY WORK DONE REPORT');
  ok('ship line reads LPG/C GAS PLANET', (await p.textContent('.rv-ship')).trim()==='LPG/C GAS PLANET',
     await p.textContent('.rv-ship'));
  ok('week range in the ship\'s wording',
     (await p.textContent('.rv-range')).trim()==='17th AUG 2026 to 23rd AUG 2026', await p.textContent('.rv-range'));

  const items = await p.evaluate(()=>[...document.querySelectorAll('.rv-list li')].map(l=>l.textContent));
  ok('three statements listed for this week', items.length===3, JSON.stringify(items));
  ok('previous week excluded', !items.some(i=>/week before/.test(i)));
  ok('un-ticked job excluded', !items.some(i=>/Not ticked/.test(i)));
  ok('statements in date order', items[0].indexOf('Main Mast')>-1 && items[2].indexOf('Bilge')>-1, JSON.stringify(items));

  ok('two photo blocks for three photos', (await p.locator('.rv-block').count())===2,
     await p.locator('.rv-block').count());
  ok('first block has two images', (await p.locator('.rv-block').first().locator('img').count())===2);
  ok('second block has one image', (await p.locator('.rv-block').nth(1).locator('img').count())===1);
  const caps = await p.evaluate(()=>[...document.querySelectorAll('.rv-cap')].map(c=>c.textContent));
  ok('caption repeated under each row', caps.length===2 && caps[0]===caps[1] && /Main Mast/.test(caps[0]),
     JSON.stringify(caps));

  // week navigation
  await p.click('[data-rv="prev"]');
  await p.waitForTimeout(250);
  ok('prev week shows 10th to 16th AUG', (await p.textContent('.rv-range')).trim()==='10th AUG 2026 to 16th AUG 2026',
     await p.textContent('.rv-range'));
  const prevItems = await p.evaluate(()=>[...document.querySelectorAll('.rv-list li')].map(l=>l.textContent));
  ok('previous week shows its own job', prevItems.length===1 && /week before/.test(prevItems[0]), JSON.stringify(prevItems));
  await p.click('[data-rv="thisweek"]');
  await p.waitForTimeout(250);
  ok('This week returns to 17-23 AUG', (await p.textContent('.rv-range')).trim()==='17th AUG 2026 to 23rd AUG 2026');

  await p.screenshot({path:process.env.OUT+'/shot-report.png', fullPage:true});
  await p.click('[data-rv="close"]');
  ok('close returns to the app', !(await p.isVisible('#reportView')));
  ok('no JS errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
