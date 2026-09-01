// Birthdays are kept in a register of their own, permanently. The crew list
// changes constantly -- people sign off, a new list is imported over the old
// one, the ship changes -- and none of that is a reason for a birthday to
// disappear off the calendar.
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
  await p.goto('file://' + process.env.APP_HTML);
  await p.waitForTimeout(500);

  const reg = () => p.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_bdays_v1') || '[]'));
  const crew = () => p.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_crew_v1') || '[]'));
  // what the calendar shows on a day -- any day, not just the few months the
  // widget agenda covers
  const onDay = iso => p.evaluate(d => window.__bdaysOn(d), iso);
  // and what actually reached the widgets, which is a narrower window
  const inAgenda = iso => p.evaluate(d => {
    const ag = JSON.parse(localStorage.getItem('gasplanet_agenda_v1') || '{}');
    return (ag.days && ag.days[d] && ag.days[d].bdays) || [];
  }, iso);

  await p.click('#topTabs button[data-tab="crew"]');
  await p.waitForTimeout(200);

  // --- someone entered on the crew list is recorded automatically ----------
  await p.fill('#crewName', 'Karan Vir Bhatia');
  await p.selectOption('#crewRank', 'Master');
  await p.fill('#crewShip', 'Gas Planet');
  await p.fill('#crewDob', '1984-08-12');
  await p.click('#crewAddBtn');
  await p.waitForTimeout(300);

  let r = await reg();
  ok('a crew member with a date of birth is recorded', r.length === 1, JSON.stringify(r));
  ok('and is filed as ship', r[0].kind === 'ship', r[0].kind);
  ok('reading the way the ship asked for it',
     (await onDay('2026-08-12')).indexOf('Capt. Karan Vir Bhatia (Gas Planet)') >= 0,
     JSON.stringify(await onDay('2026-08-12')));

  // --- the whole point: the crew list going does not take the birthday ------
  await p.evaluate(() => {
    // sign everyone off, exactly as clearing or replacing the list would
    localStorage.setItem('gasplanet_crew_v1', '[]');
    location.reload();
  });
  await p.waitForTimeout(700);
  ok('the crew list is empty', (await crew()).length === 0);
  r = await reg();
  ok('but the birthday is still in the register', r.length === 1, JSON.stringify(r));
  ok('and still reaches the calendar',
     (await onDay('2026-08-12')).indexOf('Capt. Karan Vir Bhatia (Gas Planet)') >= 0,
     JSON.stringify(await onDay('2026-08-12')));

  // --- a new crew list imported over the top adds, never removes -----------
  await p.click('#topTabs button[data-tab="crew"]');
  await p.fill('#crewName', 'R Kumar');
  await p.selectOption('#crewRank', 'Chief Officer');
  await p.fill('#crewShip', 'Gas Comet');
  await p.fill('#crewDob', '1990-03-04');
  await p.click('#crewAddBtn');
  await p.waitForTimeout(300);
  r = await reg();
  ok('a second ship does not displace the first', r.length === 2, JSON.stringify(r.map(x => x.name)));
  ok('the man off the old ship keeps his old ship on his line',
     r.find(x => x.name === 'Karan Vir Bhatia').ship === 'Gas Planet');

  // --- family and friends, who are never on a crew list --------------------
  await p.fill('#bdName', 'Priya');
  await p.selectOption('#bdKind', 'family');
  await p.fill('#bdDob', '1988-11-02');
  await p.click('#bdAddBtn');
  await p.waitForTimeout(300);
  await p.fill('#bdName', 'Sanjay');
  await p.selectOption('#bdKind', 'friend');
  await p.fill('#bdDob', '1985-01-19');
  await p.click('#bdAddBtn');
  await p.waitForTimeout(300);

  r = await reg();
  ok('family and friends can be added without a crew list', r.length === 4,
     JSON.stringify(r.map(x => x.name + ':' + x.kind)));
  ok('a family birthday reads as family, with no rank or ship',
     (await onDay('2026-11-02')).indexOf('Priya (Family)') >= 0,
     JSON.stringify(await onDay('2026-11-02')));
  ok('and it reaches the home-screen widgets too',
     (await inAgenda('2026-11-02')).indexOf('Priya (Family)') >= 0,
     JSON.stringify(await inAgenda('2026-11-02')));
  ok('and a friend as a friend',
     (await onDay('2026-01-19')).indexOf('Sanjay (Friend)') >= 0,
     JSON.stringify(await onDay('2026-01-19')));
  ok('none of them carries a rank or a ship',
     r.filter(x => x.kind !== 'ship').every(x => !x.rank && !x.ship),
     JSON.stringify(r.filter(x => x.kind !== 'ship')));

  // --- filing someone differently later ------------------------------------
  const kumarId = (await reg()).find(x => x.name === 'R Kumar').id;
  await p.evaluate(id => {
    const sel = document.querySelector('select[data-bd-kind="' + id + '"]');
    sel.value = 'friend';
    sel.dispatchEvent(new Event('change', { bubbles: true }));
  }, kumarId);
  await p.waitForTimeout(300);
  r = await reg();
  const kumar = r.find(x => x.name === 'R Kumar');
  ok('a shipmate can be re-filed as a friend', kumar.kind === 'friend', kumar.kind);
  ok('and his rank and ship are dropped with the change', !kumar.rank && !kumar.ship,
     JSON.stringify({ rank: kumar.rank, ship: kumar.ship }));
  ok('which changes how he reads on the calendar',
     (await onDay('2026-03-04')).indexOf('R Kumar (Friend)') >= 0,
     JSON.stringify(await onDay('2026-03-04')));

  // --- the filter ----------------------------------------------------------
  await p.click('#bdFilters [data-bdfilter="family"]');
  await p.waitForTimeout(200);
  ok('the register can be shown one group at a time',
     await p.locator('#bdListWrap .bd-row').count() === 1,
     await p.locator('#bdListWrap .bd-row').count());
  await p.click('#bdFilters [data-bdfilter="all"]');
  await p.waitForTimeout(200);
  ok('and all together again', await p.locator('#bdListWrap .bd-row').count() === 4);

  // --- the same person entered twice ---------------------------------------
  await p.fill('#bdName', 'Priya');
  await p.selectOption('#bdKind', 'family');
  await p.fill('#bdDob', '1988-11-02');
  await p.click('#bdAddBtn');
  await p.waitForTimeout(250);
  ok('the same person twice is refused', (await reg()).length === 4);
  ok('and says why', /already in the register/.test(await p.textContent('#bdNote')),
     await p.textContent('#bdNote'));

  // --- removal is deliberate, and the only way ------------------------------
  p.on('dialog', d => d.accept());
  await p.evaluate(() => {
    const rows = [...document.querySelectorAll('#bdListWrap button[data-bd-del]')];
    rows[0].click();
  });
  await p.waitForTimeout(400);
  ok('Remove takes one out', (await reg()).length === 3);

  // --- and it all survives a restart ---------------------------------------
  await p.reload();
  await p.waitForTimeout(700);
  ok('the register survives a restart', (await reg()).length === 3);
  ok('and is still on the calendar',
     (await onDay('2026-08-12')).indexOf('Capt. Karan Vir Bhatia (Gas Planet)') >= 0,
     JSON.stringify(await onDay('2026-08-12')));

  ok('no page errors', errs.length === 0, errs.join(' | '));
  await b.close();
  console.log('');
  console.log(fail ? fail + ' FAILED' : 'ALL PASS');
  process.exit(fail ? 1 : 0);
})();
