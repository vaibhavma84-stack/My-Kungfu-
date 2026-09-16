// A job set to repeat daily is done every day, not tied to one date -- it
// used to be mixed into the List and the Day view (and skipped, on purpose,
// from Week/Month, which would otherwise show it in every single cell). It
// now has one home instead: a dedicated Daily Jobs list on the Calendar tab,
// kept out of the List and every calendar view above it.
const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
(async () => {
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(process.env.APP_HTML));}).listen(8749);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const ctx=await b.newContext({viewport:{width:390,height:900}});
  const p=await ctx.newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  await p.goto('http://localhost:8749/');

  const today = new Date().toISOString().slice(0,10);

  await p.fill('#inJob','Check emergency fire pump');
  await p.fill('#inDue', today);
  await p.selectOption('#inRepeat','daily');
  await p.click('#addBtn');
  await p.waitForTimeout(150);

  await p.fill('#inJob','Sound cargo tanks');
  await p.fill('#inDue', today);
  await p.click('#addBtn');
  await p.waitForTimeout(200);

  // ---- To Do tab: the daily job is not in the plain list -------------------
  ok('a dated job shows in the plain list', (await p.locator('#listWrap .task', {hasText:'Sound cargo tanks'}).count())===1);
  ok('the daily job does not', (await p.locator('#listWrap .task', {hasText:'Check emergency fire pump'}).count())===0);

  // ---- Calendar tab ----------------------------------------------------
  await p.click('#topTabs button[data-tab="calendar"]');
  await p.waitForTimeout(300);

  ok('Day view shows the dated job', (await p.locator('#calWrap .task', {hasText:'Sound cargo tanks'}).count())===1);
  ok('Day view does not show the daily job', (await p.locator('#calWrap .task', {hasText:'Check emergency fire pump'}).count())===0);

  ok('the Daily Jobs list shows it instead', (await p.locator('#dailyJobsListWrap .task', {hasText:'Check emergency fire pump'}).count())===1);
  ok('and not the dated job', (await p.locator('#dailyJobsListWrap .task', {hasText:'Sound cargo tanks'}).count())===0);
  ok('it is marked as repeating daily',
     /Daily/.test(await p.locator('#dailyJobsListWrap .task', {hasText:'Check emergency fire pump'}).textContent()));

  // Week and Month must not show it either -- a daily job on every cell would
  // bury everything else, which is the one thing those views exist to show.
  await p.click('[data-view="week"]');
  await p.waitForTimeout(200);
  ok('Week view does not show the daily job', (await p.locator('#calWrap .task', {hasText:'Check emergency fire pump'}).count())===0);
  ok('but the Daily Jobs list still does, underneath',
     (await p.locator('#dailyJobsListWrap .task', {hasText:'Check emergency fire pump'}).count())===1);

  await p.click('[data-view="month"]');
  await p.waitForTimeout(200);
  const monthDots = await p.evaluate(() => {
    const c = document.querySelector('.month-cell.is-today');
    return c ? [...c.querySelectorAll('.cell-dots .cell-dot')].map(d => d.title) : null;
  });
  ok('the month grid carries no dot for the daily job',
     monthDots && !monthDots.includes('Check emergency fire pump'), JSON.stringify(monthDots));
  ok('the Daily Jobs list still shows it under the month grid',
     (await p.locator('#dailyJobsListWrap .task', {hasText:'Check emergency fire pump'}).count())===1);

  // ---- ticking it there raises tomorrow's, the same as any repeat ----------
  const tomorrow = await p.evaluate(() => {
    const d = new Date(); d.setDate(d.getDate()+1);
    return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');
  });
  await p.locator('#dailyJobsListWrap .task', {hasText:'Check emergency fire pump'}).locator('.tick').click();
  await p.waitForSelector('.date-card');
  await p.fill('.dc-input', today); await p.click('[data-dc="ok"]');
  await p.waitForTimeout(250);
  ok('ticking it in the Daily Jobs list still shows one -- tomorrow\'s freshly raised',
     (await p.locator('#dailyJobsListWrap .task', {hasText:'Check emergency fire pump'}).count())===1);
  const raised = await p.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_todo_v1'))
    .filter(t => t.job === 'Check emergency fire pump'));
  ok('two records now: the completed one and tomorrow\'s', raised.length===2, JSON.stringify(raised.map(t=>[t.done,t.due])));
  ok('the new one is due tomorrow and still pending',
     raised.some(t => !t.done && t.due===tomorrow), JSON.stringify(raised.map(t=>[t.done,t.due])));

  // leaving the tab and coming back does not leave a stale extra copy behind
  await p.click('#topTabs button[data-tab="jobs"]');
  await p.waitForTimeout(150);
  await p.click('#topTabs button[data-tab="calendar"]');
  await p.waitForTimeout(200);
  ok('back on Calendar, the raised occurrence shows once, not twice',
     (await p.locator('#dailyJobsListWrap .task', {hasText:'Check emergency fire pump'}).count())===1);

  // ---- a missed daily job is not carried forward as a debt -----------------
  // It just repeats the next day -- no "3 days overdue", no backlog. A
  // daily job due days ago and never ticked should read as today's job,
  // unticked, the moment the app is open to see it.
  const threeDaysAgo = await p.evaluate(() => {
    const d = new Date(); d.setDate(d.getDate()-3);
    return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');
  });
  const fiveDaysAhead = await p.evaluate(() => {
    const d = new Date(); d.setDate(d.getDate()+5);
    return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');
  });
  await p.evaluate(({threeDaysAgo, fiveDaysAhead}) => {
    localStorage.setItem('gasplanet_todo_v1', JSON.stringify([
      { id:'stale', serial:1, job:'Stale daily job', due:threeDaysAgo, priority:'normal', repeat:'daily',
        done:false, photos:[], createdAt:threeDaysAgo },
      { id:'future', serial:2, job:'Future daily job', due:fiveDaysAhead, priority:'normal', repeat:'daily',
        done:false, photos:[], createdAt:threeDaysAgo }
    ]));
  }, {threeDaysAgo, fiveDaysAhead});
  await p.reload();
  await p.waitForTimeout(600);

  const rolled = await p.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_todo_v1')));
  const stale = rolled.find(t => t.id === 'stale');
  const future = rolled.find(t => t.id === 'future');
  ok('a stale, unticked daily job is bumped to today, not left overdue',
     stale.due === today, JSON.stringify(stale));
  ok('one not due yet is left alone', future.due === fiveDaysAhead, JSON.stringify(future));

  await p.click('#topTabs button[data-tab="calendar"]');
  await p.waitForTimeout(300);
  ok('it shows in the Daily Jobs list as today\'s, not flagged overdue',
     (await p.locator('#dailyJobsListWrap .task:not(.overdue)', {hasText:'Stale daily job'}).count())===1);

  ok('no JS errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
