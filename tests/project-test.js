const { chromium } = require('playwright-core');
const http=require('http'), fs=require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
(async()=>{
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(process.env.APP_HTML));}).listen(8761);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const p=await (await b.newContext({viewport:{width:420,height:900}})).newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  p.on('dialog',async d=>await d.accept());
  await p.goto('http://localhost:8761/');

  // weekly job due Tue 01-Sep-2026
  await p.fill('#inJob','Air hoses weekly inspection'); await p.fill('#inDue','2026-09-01');
  await p.selectOption('#inRepeat','weekly'); await p.click('#addBtn');
  // a monthly one, and one that does not repeat
  await p.fill('#inJob','Provision cranes monthly'); await p.fill('#inDue','2026-09-03');
  await p.selectOption('#inRepeat','monthly'); await p.click('#addBtn');
  await p.fill('#inJob','One-off derusting'); await p.fill('#inDue','2026-09-04');
  await p.click('#addBtn');

  ok('the pending list still shows one line per job', (await p.locator('.task').count())===3,
     await p.locator('.task').count());
  ok('no projections in the list view', (await p.locator('.task.ghost').count())===0);

  // month view, September
  await p.click('#viewSwitch button[data-view="month"]');
  await p.evaluate(()=>{ document.querySelector('#navToday').click(); });
  await p.waitForTimeout(150);
  // Stepping a month must not skip one. It used to: setMonth keeps the day of
  // the month, so from the 31st it rolled into the month after next, and on the
  // 31st of August the calendar went straight from August to October. This walks
  // the months and checks each one follows the last, whatever today happens to be.
  const MONTHS = ['January','February','March','April','May','June','July',
                  'August','September','October','November','December'];
  const label2num = (t) => { const b = t.trim().split(' '); return +b[1] * 12 + MONTHS.indexOf(b[0]); };
  let prev = label2num(await p.textContent('.nav-label'));
  let skipped = null;
  for(let i=0;i<14;i++){
    await p.click('#navNext'); await p.waitForTimeout(60);
    const now = label2num(await p.textContent('.nav-label'));
    if(now !== prev + 1 && skipped === null) skipped = (await p.textContent('.nav-label')).trim();
    prev = now;
  }
  ok('stepping forward advances exactly one month, every time', skipped === null,
     'jumped to ' + skipped);
  for(let i=0;i<14;i++){
    await p.click('#navPrev'); await p.waitForTimeout(60);
    const now = label2num(await p.textContent('.nav-label'));
    if(now !== prev - 1 && skipped === null) skipped = (await p.textContent('.nav-label')).trim();
    prev = now;
  }
  ok('and stepping back goes back exactly one', skipped === null, 'jumped to ' + skipped);

  const goTo = async (label) => {
    for(let i=0;i<36;i++){
      if((await p.textContent('.nav-label')).trim()===label) return true;
      await p.click('#navNext'); await p.waitForTimeout(60);
    }
    return false;
  };
  // start from today, so the walk to a fixed month is always forwards
  await p.evaluate(()=>{ document.querySelector('#navToday').click(); });
  await p.waitForTimeout(120);
  ok('reached September 2026', await goTo('September 2026'), await p.textContent('.nav-label'));
  const sept = await p.evaluate(()=>{
    const out={};
    document.querySelectorAll('.month-cell[data-date]').forEach(c=>{
      const n=c.querySelector('.cell-count');
      if(n) out[c.dataset.date]=n.textContent;
    });
    return out;
  });
  ok('weekly shows on 01, 08, 15, 22 and 29 Sep',
     ['2026-09-01','2026-09-08','2026-09-15','2026-09-22','2026-09-29'].every(d=>sept[d]),
     JSON.stringify(sept));
  ok('the one-off shows only on its own date',
     sept['2026-09-04'] && !sept['2026-09-11'], JSON.stringify(sept));

  // October — this is what was missing
  ok('reached October 2026', await goTo('October 2026'), await p.textContent('.nav-label'));
  const oct = await p.evaluate(()=>{
    const out={};
    document.querySelectorAll('.month-cell[data-date]').forEach(c=>{
      const n=c.querySelector('.cell-count'); if(n) out[c.dataset.date]=n.textContent;
    });
    return out;
  });
  ok('weekly carries into October (06, 13, 20, 27)',
     ['2026-10-06','2026-10-13','2026-10-20','2026-10-27'].every(d=>oct[d]), JSON.stringify(oct));
  ok('monthly lands 30 days on, 03-Oct', !!oct['2026-10-03'], JSON.stringify(oct));
  ok('the one-off does not appear in October', !oct['2026-10-04'], JSON.stringify(oct));

  // day view
  await p.click('#viewSwitch button[data-view="day"]');
  await p.evaluate(()=>{ document.querySelector('#navToday').click(); });
  await p.waitForTimeout(100);
  await p.evaluate(()=>{
    // jump straight to 06-Oct via the month cell route
    document.querySelector('#viewSwitch button[data-view="month"]').click();
  });
  await p.waitForTimeout(100);
  await goTo('October 2026');
  await p.click('.month-cell[data-date="2026-10-06"]');
  await p.waitForTimeout(150);
  ok('day view shows the projected occurrence', (await p.locator('.task.ghost').count())===1,
     await p.locator('.task.ghost').count());
  ok('it is marked as a forecast, not tickable',
     (await p.locator('.task.ghost .tick').count())===0);
  ok('it names the job', (await p.textContent('.task.ghost')).indexOf('Air hoses')>-1);

  // closing the real job moves the whole forecast
  await p.click('#viewSwitch button[data-view="list"]');
  await p.locator('.task:not(.done)',{hasText:'Air hoses'}).first().locator('.tick').click();
  await p.waitForSelector('.date-card');
  await p.fill('.dc-input','2026-09-03'); await p.click('[data-dc="ok"]');   // done 2 days late
  await p.waitForTimeout(300);
  await p.click('#viewSwitch button[data-view="month"]');
  await goTo('October 2026');
  const oct2 = await p.evaluate(()=>{
    const out={};
    document.querySelectorAll('.month-cell[data-date]').forEach(c=>{
      const n=c.querySelector('.cell-count'); if(n) out[c.dataset.date]=n.textContent;
    });
    return out;
  });
  ok('forecast re-based on the new due date (10-Sep -> 08-Oct, 15-Oct...)',
     !!oct2['2026-10-08'] && !!oct2['2026-10-15'] && !oct2['2026-10-06'], JSON.stringify(oct2));
  ok('no JS errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
