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
  await p.fill('#paDate', '2026-09-18');      // the day the paperwork is done
  await p.fill('#paArrDate', '2026-09-20');   // the day the ship gets there
  await p.click('#paAddBtn');
  await p.waitForTimeout(300);

  let t = await jobs();
  ok('the whole set is raised', t.length === WANT.length, t.length + ' jobs');
  const missing = WANT.filter(w => !t.some(j => j.job.indexOf(w + ' —') === 0));
  ok('every item on the ship\'s list is there, spelled as given',
     missing.length === 0, missing.join(' | '));
  ok('and each one names the port', t.every(j => / — Singapore$/.test(j.job)),
     t.slice(0, 2).map(j => j.job).join(' | '));
  ok('all of them due on the PRE-ARRIVAL date, not the arrival date',
     t.every(j => j.due === '2026-09-18'),
     [...new Set(t.map(j => j.due))].join(', '));
  ok('and none of them landed on the arrival date',
     !t.some(j => j.due === '2026-09-20'));
  ok('the remarks carry the arrival, so a job says what it is for',
     t.every(j => /arrives 20-Sep-2026/.test(j.remarks)), t[0].remarks);
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
  ok('the call remembers both dates',
     c.length === 1 && c[0].port === 'Singapore' && c[0].date === '2026-09-18'
       && c[0].arrival === '2026-09-20',
     JSON.stringify(c));
  ok('and the list shows when the ship arrives',
     /arrives 20-Sep-2026/.test(await p.textContent('#paList')),
     await p.textContent('#paList'));
  ok('and it is listed', await p.locator('#paList .pa-row').count() === 1);
  ok('showing how much of it is done', /0 of 18 done/.test(await p.textContent('#paList')),
     await p.textContent('#paList'));

  // the same port on the same day twice is a slip, not two calls
  await p.fill('#paPort', 'singapore');
  await p.fill('#paDate', '2026-09-18');
  await p.click('#paAddBtn');
  await p.waitForTimeout(250);
  t = await jobs();
  ok('entering it twice does not raise thirty-six jobs', t.length === WANT.length, t.length);
  ok('and says why', /already entered/.test(await p.textContent('#paNote')),
     await p.textContent('#paNote'));

  // a second, different call stands on its own
  await p.fill('#paPort', 'Rotterdam');
  await p.fill('#paDate', '2026-10-05');
  await p.fill('#paArrDate', '2026-10-07');
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
  ok('a departure has one date, so the arrival field is out of the way',
     !(await p.locator('#paArrField').isVisible()));
  await p.selectOption('#paKind', 'arrival');
  await p.waitForTimeout(150);
  ok('a pre-arrival has two, and says which one the jobs go on',
     await p.locator('#paArrField').isVisible() &&
     (await p.textContent('#paDateLabel')).indexOf('Pre-arrival') === 0 &&
     /on the pre-arrival date/.test(await p.textContent('#paNote')),
     await p.textContent('#paNote'));
  await p.selectOption('#paKind', 'departure');
  await p.waitForTimeout(150);

  await p.fill('#paPort', 'Dongying');
  await p.fill('#paDate', '2026-09-28');
  await p.click('#paAddBtn');
  await p.waitForTimeout(300);
  t = await jobs();
  const dep = t.filter(j => / — Dongying$/.test(j.job));
  ok('a departure call stores no arrival date',
     !(await calls()).find(x => x.port === 'Dongying' && x.kind === 'departure').arrival);
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

  // --- last minute checks, dated off the ETA -------------------------------
  // These are done the day before the ship berths, so the date follows the
  // arrival rather than being typed twice.
  const WANT_LM = [
    'Stores arranged', 'Locks and security seals', 'Vents', 'Paint store PPE',
    'Paint store MSDS', 'Chemical store PPE', 'Chemical store MSDS',
    'All securing by wires', 'Reducer installed', 'Visitors list at gangway',
    'MSDS at gangway', 'Tide table at gangway', 'Briefing card at gangway',
    'Cargo meeting', 'Pre-arrivals entry in DLB and port log',
    'Accommodation at positive pressure', 'Vents close and pressure arrange',
    'Anemometer alarm', 'MARVS setting boards',
    'Drip trays', 'Scuppers', 'SOPEP equipment'
  ];
  await p.selectOption('#paKind', 'lastmin');
  await p.waitForTimeout(200);
  ok('the last minute set is in the drop-down and says when it lands',
     /the day before the ship arrives/.test(await p.textContent('#paNote')),
     await p.textContent('#paNote'));
  await p.fill('#paPort', 'Fujairah');
  await p.fill('#paArrDate', '2026-11-10');
  await p.waitForTimeout(250);
  ok('entering the arrival date fills in the day before, without being asked',
     await p.inputValue('#paDate') === '2026-11-09', await p.inputValue('#paDate'));
  await p.click('#paAddBtn');
  await p.waitForTimeout(300);
  t = await jobs();
  const lm = t.filter(j => / — Fujairah$/.test(j.job));
  ok('the whole last minute set is raised', lm.length === WANT_LM.length, lm.length);
  const missLm = WANT_LM.filter(w => !lm.some(j => j.job.indexOf(w + ' —') === 0));
  ok('every last minute item is there', missLm.length === 0, missLm.join(' | '));
  ok('all of them the day BEFORE the ship arrives',
     lm.every(j => j.due === '2026-11-09'), [...new Set(lm.map(j => j.due))].join(', '));
  ok('and the call records the arrival it was worked back from',
     (await calls()).find(x => x.port === 'Fujairah').arrival === '2026-11-10');

  // a month boundary is where naive date arithmetic goes wrong
  await p.fill('#paPort', 'Yanbu');
  await p.fill('#paArrDate', '2026-12-01');
  await p.waitForTimeout(250);
  ok('the day before the first of a month is the last of the one before',
     await p.inputValue('#paDate') === '2026-11-30', await p.inputValue('#paDate'));
  await p.fill('#paArrDate', '2027-01-01');
  await p.waitForTimeout(250);
  ok('and the day before New Year is the last day of the year before',
     await p.inputValue('#paDate') === '2026-12-31', await p.inputValue('#paDate'));
  await p.fill('#paArrDate', '2028-03-01');
  await p.waitForTimeout(250);
  ok('a leap day is not skipped either',
     await p.inputValue('#paDate') === '2028-02-29', await p.inputValue('#paDate'));
  await p.fill('#paPort', '');
  await p.fill('#paArrDate', '');
  await p.fill('#paDate', '');
  await p.selectOption('#paKind', 'arrival');
  await p.waitForTimeout(150);

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
  ok('and the arrival date is left where it was — the two are independent',
     (await calls()).find(x => x.port === 'Singapore').arrival === '2026-09-20');

  // an ETA slips. That must not drag the paperwork with it.
  await p.evaluate(() => {
    const el = document.querySelector('#paList input[data-pa-arr]');
    el.value = '2026-09-26';
    el.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await p.waitForTimeout(300);
  const after = await jobs();
  ok('moving the arrival date leaves the jobs where they are',
     after.filter(j => / — Singapore$/.test(j.job)).every(j => j.due === '2026-09-23'),
     [...new Set(after.filter(j => / — Singapore$/.test(j.job)).map(j => j.due))].join(', '));
  ok('but the jobs now say the new arrival',
     after.filter(j => / — Singapore$/.test(j.job)).every(j => /arrives 26-Sep-2026/.test(j.remarks)),
     after.find(j => / — Singapore$/.test(j.job)).remarks);
  ok('and leaves the other call alone',
     t.filter(j => / — Rotterdam$/.test(j.job)).every(j => j.due === '2026-10-05'));

  await p.evaluate(() => {
    const el = document.querySelector('#paList input[data-pa-arr]');
    el.value = '2026-09-20';
    el.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await p.waitForTimeout(250);

  // one done, then re-date again: what is finished stays where it was finished
  await p.evaluate(() => {
    const all = JSON.parse(localStorage.getItem('gasplanet_todo_v1'));
    const one = all.find(j => j.job.indexOf('Cargo Plan — Singapore') === 0);
    one.done = true; one.dateCompleted = '2026-09-23';
    localStorage.setItem('gasplanet_todo_v1', JSON.stringify(all));
    location.reload();
  });
  await p.waitForFunction(() => document.querySelectorAll('#paList .pa-row').length > 0);
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
     !c.some(x => x.port === 'Singapore') && c.length === 4,
     JSON.stringify(c.map(x => x.port + '/' + x.kind)));
  ok('the other call is untouched',
     t.filter(j => / — Rotterdam$/.test(j.job)).length === WANT.length);

  // it survives a restart
  await p.reload();
  await p.waitForFunction(() => document.querySelectorAll('#paList .pa-row').length > 0);
  ok('the calls are still listed after a restart',
     await p.locator('#paList .pa-row').count() === 4,
     await p.locator('#paList .pa-row').count());

  ok('no page errors', errs.length === 0, errs.join(' | '));
  await b.close();
  console.log('');
  console.log(fail ? fail + ' FAILED' : 'ALL PASS');
  process.exit(fail ? 1 : 0);
})();
