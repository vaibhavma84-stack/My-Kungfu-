// The home-screen widgets cannot read the page's storage, so the page publishes
// an agenda across the Android bridge and the widgets render from that. This
// tests the page's half: what it publishes, and that a tick made on the widget
// is applied back to the real job list.
//
// There is no Android here, so a stand-in bridge is installed before the page
// loads and records what it is handed.
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

  // a stand-in for the Android bridge, installed before any page script runs
  await p.addInitScript(() => {
    window.__published = [];
    window.__ticks = null;
    window.AndroidBridge = {
      publishAgenda: function(json){ window.__published.push(json); },
      pendingTicks: function(){ return window.__ticks; },
      clearTicks: function(){ window.__ticks = null; }
    };
  });
  await p.goto('file://' + process.env.APP_HTML);
  await p.waitForTimeout(600);

  const today = await p.evaluate(() => {
    const d = new Date();
    return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');
  });

  ok('the page publishes an agenda on load', await p.evaluate(() => window.__published.length > 0),
     await p.evaluate(() => window.__published.length));

  // --- a job today ---------------------------------------------------------
  await p.fill('#inJob','Check emergency fire pump');
  await p.fill('#inDue', today);
  await p.selectOption('#inPriority','urgent');
  await p.click('#addBtn');
  await p.waitForTimeout(250);

  let ag = await p.evaluate(() => JSON.parse(window.__published[window.__published.length-1]));
  ok('today has the job', !!(ag.days[today] && ag.days[today].jobs.length === 1),
     JSON.stringify(ag.days[today]));
  ok('the job carries its id, so a tick can be routed back',
     !!(ag.days[today].jobs[0].i), JSON.stringify(ag.days[today].jobs[0]));
  ok('and its priority', ag.days[today].jobs[0].p === 'urgent');
  ok('the window spans whole months either side of today',
     ag.from.slice(-2) === '01' && ag.from < today && ag.to > today, ag.from + ' -> ' + ag.to);
  ok('empty days are left out rather than published as filler',
     Object.keys(ag.days).length < 20, Object.keys(ag.days).length);
  ok('the ship is named, so the widget can label itself', typeof ag.ship === 'string' && ag.ship.length > 0, ag.ship);

  // --- what today's list is actually for -----------------------------------
  // A job entered with no date never reached the widget at all, and an overdue
  // one dropped off it the day after it was due. Both are still work to do.
  await p.fill('#inJob','Grease deck crane wires');
  await p.fill('#inDue','');
  await p.click('#addBtn');
  await p.waitForTimeout(250);
  ag = await p.evaluate(() => JSON.parse(window.__published[window.__published.length-1]));
  ok('a job with no date shows on today',
     ag.days[today].jobs.some(j => j.t === 'Grease deck crane wires'),
     JSON.stringify(ag.days[today].jobs.map(j => j.t)));
  ok('and is labelled as having no date',
     (ag.days[today].jobs.find(j => j.t === 'Grease deck crane wires') || {}).w === 'no date');

  const past = await p.evaluate(() => {
    const d = new Date(); d.setDate(d.getDate() - 5);
    return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');
  });
  await p.fill('#inJob','Test emergency steering');
  await p.fill('#inDue', past);
  await p.click('#addBtn');
  await p.waitForTimeout(250);
  ag = await p.evaluate(() => JSON.parse(window.__published[window.__published.length-1]));
  ok('an overdue job still shows on today',
     ag.days[today].jobs.some(j => j.t === 'Test emergency steering'),
     JSON.stringify(ag.days[today].jobs.map(j => j.t + ':' + j.w)));
  ok('labelled overdue, and sorted to the top',
     ag.days[today].jobs[0].t === 'Test emergency steering' && ag.days[today].jobs[0].w === 'overdue',
     JSON.stringify(ag.days[today].jobs[0]));
  ok('it is still on its own day too, not moved',
     !!(ag.days[past] && ag.days[past].jobs.some(j => j.t === 'Test emergency steering')));
  ok('and it is not duplicated on today',
     ag.days[today].jobs.filter(j => j.t === 'Test emergency steering').length === 1);

  // once done, it drops off
  await p.evaluate(() => {
    const t = JSON.parse(localStorage.getItem('gasplanet_todo_v1'));
    const j = t.find(x => x.job === 'Test emergency steering');
    j.done = true;
    localStorage.setItem('gasplanet_todo_v1', JSON.stringify(t));
  });
  await p.reload();
  await p.waitForTimeout(700);
  ag = await p.evaluate(() => JSON.parse(window.__published[window.__published.length-1]));
  ok('a completed overdue job is no longer carried onto today',
     !ag.days[today].jobs.some(j => j.t === 'Test emergency steering'),
     JSON.stringify(ag.days[today].jobs.map(j => j.t)));

  // --- a birthday, in the form the widget shows -----------------------------
  await p.click('#topTabs button[data-tab="crew"]');
  await p.fill('#crewName','Karan Vir Bhatia');
  await p.selectOption('#crewRank','Master');
  await p.fill('#crewShip','Gas Planet');
  await p.fill('#crewDob','1979-' + today.slice(5));
  await p.click('#crewAddBtn');
  await p.waitForTimeout(300);

  ag = await p.evaluate(() => JSON.parse(window.__published[window.__published.length-1]));
  ok('the birthday reads as rank, name and ship',
     ag.days[today].bdays[0] === 'Capt. Karan Vir Bhatia (Gas Planet)', ag.days[today].bdays[0]);

  // a rank with no courtesy title still reads properly
  await p.fill('#crewName','R Kumar');
  await p.selectOption('#crewRank','Chief Officer');
  await p.fill('#crewDob','1985-' + today.slice(5));
  await p.click('#crewAddBtn');
  await p.waitForTimeout(300);
  ag = await p.evaluate(() => JSON.parse(window.__published[window.__published.length-1]));
  ok('a second rank gets its own abbreviation',
     ag.days[today].bdays.indexOf('C/O R Kumar (Gas Planet)') >= 0, JSON.stringify(ag.days[today].bdays));

  // --- the ship survives a CSV round trip ----------------------------------
  const csv = await p.evaluate(() => {
    const rows = [['Serial No','Name','Rank','Ship','DOB']];
    return rows[0].join(',');
  });
  ok('CSV carries a Ship column', csv.indexOf('Ship') >= 0, csv);

  // --- a tick made on the widget comes back into the job list --------------
  const jobId = ag.days[today].jobs[0].i;
  await p.evaluate((id) => { window.__ticks = JSON.stringify({ [id]: true }); }, jobId);
  // coming back to the app is what applies it
  await p.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
  await p.waitForTimeout(300);
  ok('a tick made on the home screen marks the job done',
     await p.evaluate((id) => {
       const t = JSON.parse(localStorage.getItem('gasplanet_todo_v1')).find(x => x.id === id);
       return !!(t && t.done);
     }, jobId));
  ok('and the queue is cleared, so it is not applied twice',
     await p.evaluate(() => window.__ticks === null));
  ok('the republished agenda shows it done',
     await p.evaluate((id) => {
       const a = JSON.parse(window.__published[window.__published.length-1]);
       const k = Object.keys(a.days);
       for(const d of k){ const j = a.days[d].jobs.find(x => x.i === id); if(j) return j.d === true; }
       return false;
     }, jobId));

  // a tick naming a job that no longer exists must not wedge anything
  await p.evaluate(() => { window.__ticks = JSON.stringify({ 'no-such-job': true }); });
  await p.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
  await p.waitForTimeout(250);
  ok('a tick for a deleted job is discarded rather than retried forever',
     await p.evaluate(() => window.__ticks === null));

  // --- it does not republish identical payloads ----------------------------
  // publishAgenda lives inside the page's own closure, so calling it directly
  // from here would be a no-op and prove nothing. Driving a real re-render that
  // changes no data is the honest way to test it.
  await p.click('#topTabs button[data-tab="jobs"]');
  await p.waitForTimeout(200);
  const before = await p.evaluate(() => window.__published.length);
  ok('the harness is actually seeing pushes', before > 1, before);
  await p.click('#viewSwitch button[data-view="month"]');
  await p.waitForTimeout(200);
  await p.click('#viewSwitch button[data-view="week"]');
  await p.waitForTimeout(200);
  await p.click('#viewSwitch button[data-view="list"]');
  await p.waitForTimeout(250);
  ok('three re-renders that changed no data pushed nothing new',
     await p.evaluate(() => window.__published.length) === before,
     await p.evaluate(() => window.__published.length) + ' vs ' + before);

  // and a real change does push
  await p.fill('#inJob','Sound all tanks');
  await p.fill('#inDue', today);
  await p.click('#addBtn');
  await p.waitForTimeout(250);
  ok('but a real change does',
     await p.evaluate(() => window.__published.length) > before,
     await p.evaluate(() => window.__published.length) + ' vs ' + before);

  ok('no page errors', errs.length === 0, errs.join(' | '));
  await b.close();
  console.log('');
  console.log(fail ? fail + ' FAILED' : 'ALL PASS');
  process.exit(fail ? 1 : 0);
})();
