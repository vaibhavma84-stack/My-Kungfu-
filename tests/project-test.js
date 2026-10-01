const { chromium } = require('playwright-core');
const http=require('http'), fs=require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};

// Every fixture date below is computed from today, never typed -- this file
// used to hardcode "September 2026" / "October 2026" and specific dates
// inside them, and the moment the real calendar caught up to October 2026
// the forward-only month walk could no longer reach a target already in the
// past, failing six checks for a reason that had nothing to do with the app.
// "Month A" is today's own calendar month, "Month B" the one after.
const MONTHS = ['January','February','March','April','May','June','July',
                'August','September','October','November','December'];
const iso = d => d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');
const fromIso = s => { const b = s.split('-').map(Number); return new Date(b[0], b[1]-1, b[2]); };
const addDays = (d,n) => new Date(d.getFullYear(), d.getMonth(), d.getDate()+n);
const monthEnd = d => new Date(d.getFullYear(), d.getMonth()+1, 0);
const label = d => MONTHS[d.getMonth()] + ' ' + d.getFullYear();
// every 7-day occurrence of a weekly job from `start`, landing from
// `from` (inclusive) to the end of `from`'s month (inclusive)
function weeklyIn(start, from){
  const end = monthEnd(from);
  const out = []; let d = start;
  while(d <= end){ if(d >= from) out.push(iso(d)); d = addDays(d, 7); }
  return out;
}

(async()=>{
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(process.env.APP_HTML));}).listen(8761);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const p=await (await b.newContext({viewport:{width:420,height:900}})).newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  p.on('dialog',async d=>await d.accept());
  await p.goto('http://localhost:8761/');

  const monthA = new Date(); monthA.setDate(1);
  const monthB = new Date(monthA.getFullYear(), monthA.getMonth()+1, 1);
  const weeklyDue  = addDays(monthA, 0);  // the 1st of month A
  const monthlyDue = addDays(monthA, 2);  // the 3rd -- never the 31st, so a month
                                           // of any length lands the next
                                           // occurrence safely one month on
  const oneOffDue  = addDays(monthA, 3);  // the 4th

  // weekly job due on the 1st of month A
  await p.fill('#inJob','Air hoses weekly inspection'); await p.fill('#inDue',iso(weeklyDue));
  await p.selectOption('#inRepeat','weekly'); await p.click('#addBtn');
  // a monthly one, and one that does not repeat
  await p.fill('#inJob','Provision cranes monthly'); await p.fill('#inDue',iso(monthlyDue));
  await p.selectOption('#inRepeat','monthly'); await p.click('#addBtn');
  await p.fill('#inJob','One-off derusting'); await p.fill('#inDue',iso(oneOffDue));
  await p.click('#addBtn');

  ok('the pending list still shows one line per job', (await p.locator('.task').count())===3,
     await p.locator('.task').count());
  ok('no projections in the list view', (await p.locator('.task.ghost').count())===0);

  // month view, Month A -- Day/Week/Month live on their own tab now
  await p.click('#topTabs button[data-tab="calendar"]');
  await p.click('#viewSwitch button[data-view="month"]');
  await p.evaluate(()=>{ document.querySelector('#navToday').click(); });
  await p.waitForTimeout(150);
  // Stepping a month must not skip one. It used to: setMonth keeps the day of
  // the month, so from the 31st it rolled into the month after next, and on the
  // 31st of August the calendar went straight from August to October. This walks
  // the months and checks each one follows the last, whatever today happens to be.
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

  const goTo = async (lbl) => {
    for(let i=0;i<36;i++){
      if((await p.textContent('.nav-label')).trim()===lbl) return true;
      await p.click('#navNext'); await p.waitForTimeout(60);
    }
    return false;
  };
  const cellDots = () => p.evaluate(()=>{
    const out={};
    document.querySelectorAll('.month-cell[data-date]').forEach(c=>{
      const jobs=c.querySelectorAll('.cell-dots .cell-dot');
      if(jobs.length) out[c.dataset.date]=[...jobs].map(j=>j.title).join(', ');
    });
    return out;
  });

  // start from today, so the walk to month A is always forwards (zero steps)
  await p.evaluate(()=>{ document.querySelector('#navToday').click(); });
  await p.waitForTimeout(120);
  ok('reached ' + label(monthA), await goTo(label(monthA)), await p.textContent('.nav-label'));
  const monA = await cellDots();
  const weekInA = weeklyIn(weeklyDue, monthA);
  ok('weekly shows on all ' + weekInA.length + ' occurrences in month A (' + weekInA.join(', ') + ')',
     weekInA.every(d=>monA[d]), JSON.stringify(monA));
  ok('the one-off shows only on its own date',
     monA[iso(oneOffDue)] && !monA[iso(addDays(oneOffDue,7))], JSON.stringify(monA));

  // Month B — this is what was missing
  ok('reached ' + label(monthB), await goTo(label(monthB)), await p.textContent('.nav-label'));
  const monB = await cellDots();
  const weekInB = weeklyIn(addDays(fromIso(weekInA[weekInA.length-1]), 7), monthB);
  ok('weekly carries into month B (' + weekInB.join(', ') + ')',
     weekInB.every(d=>monB[d]), JSON.stringify(monB));
  // "Monthly" is a fixed 30-day interval in this app (REPEATS: ['monthly',
  // 'Monthly (30 days)', 30]), not calendar-month arithmetic -- the 3rd of
  // month A plus 30 days, not "the 3rd of month B".
  const monthlyNext = iso(addDays(monthlyDue, 30));
  ok('monthly lands 30 days on, ' + monthlyNext, !!monB[monthlyNext], JSON.stringify(monB));
  ok('the one-off does not appear in month B',
     !(monB[iso(new Date(monthB.getFullYear(), monthB.getMonth(), oneOffDue.getDate()))] || '').includes('One-off'),
     JSON.stringify(monB));

  // day view
  await p.click('#viewSwitch button[data-view="day"]');
  await p.evaluate(()=>{ document.querySelector('#navToday').click(); });
  await p.waitForTimeout(100);
  await p.evaluate(()=>{
    // jump straight to month B's first weekly occurrence via the month cell route
    document.querySelector('#viewSwitch button[data-view="month"]').click();
  });
  await p.waitForTimeout(100);
  await goTo(label(monthB));
  await p.click('.month-cell[data-date="' + weekInB[0] + '"]');
  await p.waitForTimeout(150);
  ok('day view shows the projected occurrence', (await p.locator('.task.ghost').count())===1,
     await p.locator('.task.ghost').count());
  ok('it is marked as a forecast, not tickable',
     (await p.locator('.task.ghost .tick').count())===0);
  ok('it names the job', (await p.textContent('.task.ghost')).indexOf('Air hoses')>-1);

  // closing the real job moves the whole forecast -- the plain list is the
  // To Do tab now, not a view-switch option
  await p.click('#topTabs button[data-tab="jobs"]');
  await p.locator('.task:not(.done)',{hasText:'Air hoses'}).first().locator('.tick').click();
  await p.waitForSelector('.date-card');
  const completedLate = addDays(weeklyDue, 2);           // done 2 days late
  await p.fill('.dc-input', iso(completedLate)); await p.click('[data-dc="ok"]');
  await p.waitForTimeout(300);
  await p.click('#topTabs button[data-tab="calendar"]');
  await p.click('#viewSwitch button[data-view="month"]');
  await goTo(label(monthB));
  const monB2 = await cellDots();
  const rebased = weeklyIn(addDays(completedLate, 7), monthB);
  ok('forecast re-based on the new due date (' + rebased.slice(0,2).join(', ') + '...)',
     rebased.length >= 2 && rebased.slice(0,2).every(d=>monB2[d]), JSON.stringify(monB2));
  ok('and the old, un-shifted slot no longer carries it',
     !(monB2[weekInB[0]] || '').includes('Air hoses'), JSON.stringify(monB2));
  ok('no JS errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})();
