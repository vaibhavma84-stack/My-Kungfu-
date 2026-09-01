// A port call brings the same paperwork every time. Entering the port and the
// date should raise the whole set as jobs due on that date -- and, because an
// ETA moves, re-dating the call should move them with it.
const { chromium } = require('playwright-core');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

const WANT = [
  'Pre-Arrival Checklist', 'Load / Disch. Comparison Sheet', 'Cargo Plan', 'Stowage Plan',
  'Risk Assessment', 'Load / Discharge Orders', 'BWRF', 'BWRB', 'GRB', 'OLB',
  'Rodent log', 'Sounding log', 'PTW - WAH', 'NOR', 'MSDS', 'VEF', 'DLB', 'MARVS ENTRY'
];
const WANT_DEP = [
  'Hourly calculations sheet', 'Pumping log', 'Manifold log', 'Comparison sheets',
  'Cargo plan', 'Stowage plan', 'Condition entries each stage', 'Risk Assessment',
  'Departure condition', 'Booster / heater log', 'Taking over checklist', 'Cargo receipt',
  'Cargo documents in envelope', 'Port log', 'BWRF', 'BWRB', 'OLB',
  'Vessel Search Checklist', 'Load / Disch. Orders', 'PTW - WAH', 'MSDS', 'MARVS ENTRY',
  'Post Cargo form'
];

(async () => {
  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const p = await b.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  await p.goto('file://' + process.env.APP_HTML);
  await p.waitForTimeout(500);

  const jobs = () => p.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_todo_v1') || '[]'));
  const calls = () => p.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_portcalls_v1') || '[]'));

  // The panel used to sit inside the job-entry form, so "Hide" took it down
  // with it and the feature simply was not there to be found.
  await p.click('#toggleFormBtn');
  await p.waitForTimeout(250);
  ok('hiding the job entry form does not hide the pre-arrival panel',
     await p.locator('#portCallWrap').isVisible());
  ok('and the job entry really is collapsed, so that is what was tested',
     await p.evaluate(() => {
       const w = document.getElementById('addWrap');
       return w.classList.contains('collapsed') && w.getBoundingClientRect().height < 4;
     }));
  await p.click('#toggleFormBtn');
  await p.waitForTimeout(250);

  await p.fill('#paPort', 'Singapore');
  await p.fill('#paDate', '2026-09-20');
  await p.click('#paAddBtn');
  await p.waitForTimeout(300);

  let t = await jobs();
  ok('the whole set is raised', t.length === WANT.length, t.length + ' jobs');
  const missing = WANT.filter(w => !t.some(j => j.job.indexOf(w + ' —') === 0));
  ok('every item on the ship\'s list is there, spelled as given',
     missing.length === 0, missing.join(' | '));
  ok('and each one names the port', t.every(j => / — Singapore$/.test(j.job)),
     t.slice(0, 2).map(j => j.job).join(' | '));
  ok('all of them due on the arrival date', t.every(j => j.due === '2026-09-20'),
     [...new Set(t.map(j => j.due))].join(', '));
  ok('none of them repeats', t.every(j => !j.repeat));
  ok('none arrives already done', t.every(j => !j.done));

  // the two the app already understands
  const ra = t.find(j => j.job.indexOf('Risk Assessment') === 0);
  ok('the risk assessment carries the RA flag, so it reaches the AD 19 export',
     !!ra && ra.ra === true, JSON.stringify(ra && { ra: ra.ra }));
  const ptw = t.find(j => j.job.indexOf('PTW - WAH') === 0);
  ok('and the permit carries PTW with its type filled in',
     !!ptw && ptw.ptw === true && /WORK AT HEIGHT/.test(ptw.ptwType),
     JSON.stringify(ptw && { ptw: ptw.ptw, type: ptw.ptwType }));
  ok('nothing else is flagged as a permit or an assessment',
     t.filter(j => j.ra).length === 1 && t.filter(j => j.ptw).length === 1);

  let c = await calls();
  ok('the call itself is remembered', c.length === 1 && c[0].port === 'Singapore' && c[0].date === '2026-09-20',
     JSON.stringify(c));
  ok('and it is listed', await p.locator('#paList .pa-row').count() === 1);
  ok('showing how much of it is done', /0 of 18 done/.test(await p.textContent('#paList')),
     await p.textContent('#paList'));

  // the same port on the same day twice is a slip, not two calls
  await p.fill('#paPort', 'singapore');
  await p.fill('#paDate', '2026-09-20');
  await p.click('#paAddBtn');
  await p.waitForTimeout(250);
  t = await jobs();
  ok('entering it twice does not raise thirty-six jobs', t.length === WANT.length, t.length);
  ok('and says why', /already entered/.test(await p.textContent('#paNote')),
     await p.textContent('#paNote'));

  // a second, different call stands on its own
  await p.fill('#paPort', 'Rotterdam');
  await p.fill('#paDate', '2026-10-05');
  await p.click('#paAddBtn');
  await p.waitForTimeout(300);
  t = await jobs();
  ok('a different port call raises its own set', t.length === WANT.length * 2, t.length);
  ok('and the two sets do not share a call id',
     new Set(t.map(j => j.call)).size === 2);

  // --- the departure set ---------------------------------------------------
  // Leaving brings its own paperwork, and it is a different list.
  await p.selectOption('#paKind', 'departure');
  await p.waitForTimeout(150);
  ok('choosing departure says how many jobs that will be',
     /23 jobs will be raised/.test(await p.textContent('#paNote')),
     await p.textContent('#paNote'));
  ok('and the date is labelled for leaving, not arriving',
     (await p.textContent('#paDateLabel')).indexOf('Departure') === 0,
     await p.textContent('#paDateLabel'));

  await p.fill('#paPort', 'Dongying');
  await p.fill('#paDate', '2026-09-28');
  await p.click('#paAddBtn');
  await p.waitForTimeout(300);
  t = await jobs();
  const dep = t.filter(j => / — Dongying$/.test(j.job));
  ok('the departure set is raised in full', dep.length === WANT_DEP.length, dep.length + ' jobs');
  const missDep = WANT_DEP.filter(w => !dep.some(j => j.job.indexOf(w + ' —') === 0));
  ok('every item on the departure list is there, spelled as given',
     missDep.length === 0, missDep.join(' | '));
  ok('all of them due on the departure date', dep.every(j => j.due === '2026-09-28'),
     [...new Set(dep.map(j => j.due))].join(', '));
  ok('its risk assessment and permit are flagged too',
     dep.filter(j => j.ra).length === 1 && dep.filter(j => j.ptw).length === 1);
  ok('and the remarks say which kind of call it is',
     dep.every(j => j.remarks === 'Departure Dongying'),
     [...new Set(dep.map(j => j.remarks))].join(' | '));

  // an arrival and a departure at the same port on one date is a real thing
  await p.selectOption('#paKind', 'arrival');
  await p.fill('#paPort', 'Dongying');
  await p.fill('#paDate', '2026-09-28');
  await p.click('#paAddBtn');
  await p.waitForTimeout(300);
  t = await jobs();
  ok('an arrival on the same day at the same port is allowed alongside it',
     t.filter(j => / — Dongying$/.test(j.job)).length === WANT_DEP.length + WANT.length,
     t.filter(j => / — Dongying$/.test(j.job)).length);
  // but the same one twice is still refused
  await p.selectOption('#paKind', 'departure');
  await p.fill('#paPort', 'dongying');
  await p.fill('#paDate', '2026-09-28');
  await p.click('#paAddBtn');
  await p.waitForTimeout(250);
  ok('while the same departure twice is still refused',
     (await jobs()).filter(j => / — Dongying$/.test(j.job)).length === WANT_DEP.length + WANT.length);
  ok('and says which kind it means',
     /Departure for dongying/i.test(await p.textContent('#paNote')),
     await p.textContent('#paNote'));
  ok('the list marks each call with its kind',
     (await p.textContent('#paList')).indexOf('Departure') >= 0);

  await p.selectOption('#paKind', 'arrival');

  // an ETA moves
  await p.evaluate(() => {
    const row = document.querySelector('#paList .pa-row input[data-pa-date]');
    row.value = '2026-09-23';
    row.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await p.waitForTimeout(300);
  t = await jobs();
  const sing = t.filter(j => / — Singapore$/.test(j.job));
  ok('re-dating the call moves its jobs with it',
     sing.length === WANT.length && sing.every(j => j.due === '2026-09-23'),
     [...new Set(sing.map(j => j.due))].join(', '));
  ok('and leaves the other call alone',
     t.filter(j => / — Rotterdam$/.test(j.job)).every(j => j.due === '2026-10-05'));

  // one done, then re-date again: what is finished stays where it was finished
  await p.evaluate(() => {
    const all = JSON.parse(localStorage.getItem('gasplanet_todo_v1'));
    const one = all.find(j => j.job.indexOf('Cargo Plan — Singapore') === 0);
    one.done = true; one.dateCompleted = '2026-09-23';
    localStorage.setItem('gasplanet_todo_v1', JSON.stringify(all));
    location.reload();
  });
  await p.waitForTimeout(700);
  await p.evaluate(() => {
    const row = document.querySelector('#paList .pa-row input[data-pa-date]');
    row.value = '2026-09-25';
    row.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await p.waitForTimeout(300);
  t = await jobs();
  const doneOne = t.find(j => j.job.indexOf('Cargo Plan — Singapore') === 0);
  ok('a job already done is not dragged to the new date',
     doneOne.due === '2026-09-23', doneOne.due);
  ok('but the outstanding ones move',
     t.filter(j => / — Singapore$/.test(j.job) && !j.done).every(j => j.due === '2026-09-25'));

  // removing a call
  p.on('dialog', d => d.accept());
  await p.evaluate(() => {
    document.querySelector('#paList .pa-row button[data-pa-del]').click();
  });
  await p.waitForTimeout(400);
  t = await jobs();
  c = await calls();
  ok('removing a call takes its outstanding jobs with it',
     t.filter(j => / — Singapore$/.test(j.job) && !j.done).length === 0,
     t.filter(j => / — Singapore$/.test(j.job)).map(j => j.job + ':' + j.done).join(' | '));
  ok('but keeps what was already completed — that is a record, not a plan',
     t.some(j => j.job.indexOf('Cargo Plan — Singapore') === 0 && j.done));
  ok('and the call is gone from the list',
     !c.some(x => x.port === 'Singapore') && c.length === 3,
     JSON.stringify(c.map(x => x.port + '/' + x.kind)));
  ok('the other call is untouched',
     t.filter(j => / — Rotterdam$/.test(j.job)).length === WANT.length);

  // it survives a restart
  await p.reload();
  await p.waitForTimeout(600);
  ok('the calls are still listed after a restart',
     await p.locator('#paList .pa-row').count() === 3,
     await p.locator('#paList .pa-row').count());

  ok('no page errors', errs.length === 0, errs.join(' | '));
  await b.close();
  console.log('');
  console.log(fail ? fail + ' FAILED' : 'ALL PASS');
  process.exit(fail ? 1 : 0);
})();
