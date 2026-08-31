const { chromium } = require('playwright-core');
const http = require('http');
const fs = require('fs');
const path = process.env.APP_HTML;

let fails = 0;
const ok = (name, cond, extra) => {
  if (cond) console.log('  PASS  ' + name);
  else { console.log('  FAIL  ' + name + (extra !== undefined ? '  -> ' + extra : '')); fails++; }
};

(async () => {
  const server = http.createServer((req, res) => {
    // the hosted copy registers a service worker; serve it with a script MIME
    // type or the browser rejects it and the page-error check trips on the
    // harness rather than on the app
    if (/sw\.js$/.test(req.url)) {
      res.writeHead(200, {'Content-Type': 'application/javascript'});
      res.end('');
      return;
    }
    res.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'});
    res.end(fs.readFileSync(path));
  }).listen(8731);

  const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 } }); // iPhone-ish
  const page = await ctx.newPage();

  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });

  const external = [];
  page.on('request', r => { if (!r.url().startsWith('http://localhost:8731')) external.push(r.url()); });

  const dialogs = [];
  page.on('dialog', async d => { dialogs.push({ type: d.type(), msg: d.message() }); await d.accept(); });

  await page.goto('http://localhost:8731/', { waitUntil: 'networkidle' });

  ok('page loads with no JS errors', errors.length === 0, errors.join(' | '));
  ok('makes no external network requests (offline-safe)', external.length === 0, external.join(', '));

  // ---- FIX 3: no capture attribute on either file input ----
  ok('FIX 3  #photoInput has no capture attribute',
     await page.getAttribute('#photoInput', 'capture') === null);
  ok('FIX 3  dynamic add-photo input has no capture attribute',
     await page.evaluate(() => {
       const inputs = [...document.querySelectorAll('input[type=file][accept="image/*"]')];
       return inputs.every(i => !i.hasAttribute('capture') && !i.capture);
     }));

  // ---- FIX 5: entry form is not clipped on a narrow phone ----
  const clip = await page.evaluate(() => {
    const w = document.getElementById('addWrap');
    const btn = document.getElementById('addBtn');
    return {
      scrollH: w.scrollHeight, clientH: w.clientHeight,
      btnBottom: btn.getBoundingClientRect().bottom,
      wrapBottom: w.getBoundingClientRect().bottom
    };
  });
  ok('FIX 5  #addWrap is not clipping its content',
     clip.scrollH <= clip.clientH + 1, `scrollHeight=${clip.scrollH} clientHeight=${clip.clientH}`);
  ok('FIX 5  "+ Add Job" button is inside the visible form area',
     clip.btnBottom <= clip.wrapBottom + 1, `btn=${clip.btnBottom} wrap=${clip.wrapBottom}`);

  // ---- add a job, check it renders and persists ----
  await page.fill('#inJob', 'Check cargo tank relief valves');
  await page.fill('#inDue', '2026-08-26');
  await page.selectOption('#inPriority', 'urgent');
  await page.click('#inRA');
  await page.click('#addBtn');
  ok('job appears in the list', (await page.locator('.task').count()) === 1);
  ok('urgent badge rendered', (await page.locator('.pri-badge.pri-urgent').first().textContent()).trim() === 'U');
  ok('form cleared after successful save', (await page.inputValue('#inJob')) === '');

  // ---- FIX 1: completion stamps the LOCAL date ----
  await page.click('.tick');
  await page.waitForSelector('.date-card');
  await page.click('[data-dc="today"]');
  await page.click('[data-dc="ok"]');
  const stamp = await page.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_todo_v1'))[0].dateCompleted);
  const localToday = await page.evaluate(() => {
    const d = new Date();
    return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');
  });
  ok('FIX 1  completion date is the local calendar day', stamp === localToday, `${stamp} vs ${localToday}`);
  // the Pending filter hides it once done — that is intended behaviour
  ok('completed job leaves the Pending filter', (await page.locator('.task').count()) === 0);
  await page.selectOption('#filterStatus', 'all');
  ok('completed job visible under the All filter', (await page.locator('.task').count()) === 1);
  await page.click('.tick'); // un-tick needs no picker
  await page.selectOption('#filterStatus', 'open');

  // ---- FIX 4: a negative temperature can actually be entered ----
  // This used to assert the opposite -- that no tank input carried
  // inputmode="decimal" -- because dropping it was the only lever available
  // against a keypad with no minus key, at the cost of the numeric keypad.
  // The fields now keep the numeric keypad AND get a +/- button, so the
  // assertion is reversed on purpose. What matters is the last check: a
  // negative goes in and stays in.
  await page.click('#topTabs button[data-tab="cargo"]');
  ok('FIX 4  tank inputs keep the numeric keypad',
     await page.evaluate(() => [...document.querySelectorAll('#tankFieldsWrap input[data-key]')]
       .every(i => i.getAttribute('inputmode') === 'decimal')));
  ok('FIX 4  and each has a sign button, since that keypad has no minus key',
     await page.evaluate(() => {
       const n = document.querySelectorAll('#tankFieldsWrap input[data-key]').length;
       return n > 10 && document.querySelectorAll('#tankFieldsWrap .signbtn').length === n;
     }));
  ok('FIX 4  negative temperature is accepted',
     await page.evaluate(() => {
       const i = document.querySelector('#tankFieldsWrap input[data-key="portTop"]');
       i.value = '-162.4';
       return i.value === '-162.4';
     }));

  // ---- FIX 6: tank draft really clears after saving ----
  await page.fill('#cargoDate', '2026-08-26');
  await page.fill('#cargoTime', '06:00');
  await page.click('#tankTabs button[data-tank="3"]');
  await page.fill('#tankFieldsWrap input[data-key="pressure"]', '118');
  await page.fill('#tankFieldsWrap input[data-key="portTop"]', '-42.7');
  await page.click('#cargoAddBtn');
  ok('cargo entry saved', (await page.locator('table.cargo-table tbody tr').count()) === 1);
  ok('FIX 6  form returns to Tank 1 after saving',
     await page.getAttribute('#tankTabs button[data-tank="1"]', 'class') === 'active');
  await page.click('#tankTabs button[data-tank="3"]');
  const stale = await page.evaluate(() => ({
    press: document.querySelector('#tankFieldsWrap input[data-key="pressure"]').value,
    top: document.querySelector('#tankFieldsWrap input[data-key="portTop"]').value
  }));
  ok('FIX 6  Tank 3 fields are empty again, not refilled with the saved entry',
     stale.press === '' && stale.top === '', JSON.stringify(stale));

  // ---- FIX 7: duplicate date+time prompts to overwrite ----
  dialogs.length = 0;
  await page.fill('#cargoTime', '06:00');
  await page.click('#tankTabs button[data-tank="1"]');
  await page.fill('#tankFieldsWrap input[data-key="pressure"]', '125');
  await page.click('#cargoAddBtn');
  ok('FIX 7  a confirm dialog is shown for a duplicate slot',
     dialogs.length === 1 && dialogs[0].type === 'confirm', JSON.stringify(dialogs));
  ok('FIX 7  dialog names the slot',
     dialogs.length === 1 && /26-Aug-2026 at 06:00/.test(dialogs[0].msg), dialogs[0] && dialogs[0].msg);
  ok('FIX 7  accepting overwrites in place — still one row',
     (await page.locator('table.cargo-table tbody tr').count()) === 1);
  ok('FIX 7  the row holds the NEW reading',
     await page.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_cargo_v1'))[0].tanks['1'].pressure) === '125');

  // declining the prompt leaves the row untouched
  page.removeAllListeners('dialog');
  page.on('dialog', async d => { dialogs.push({type:d.type(), msg:d.message()}); await d.dismiss(); });
  await page.fill('#tankFieldsWrap input[data-key="pressure"]', '999');
  await page.click('#cargoAddBtn');
  ok('FIX 7  declining leaves the existing row unchanged',
     await page.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_cargo_v1'))[0].tanks['1'].pressure) === '125');

  // ---- FIX 2: quota failure alerts AND rolls back ----
  page.removeAllListeners('dialog');
  const alerts = [];
  page.on('dialog', async d => { alerts.push(d.message()); await d.accept(); });
  await page.click('#topTabs button[data-tab="jobs"]');
  const before = await page.locator('.task').count();
  await page.evaluate(() => {
    const real = localStorage.setItem.bind(localStorage);
    localStorage.setItem = function(k, v){
      if (k === 'gasplanet_todo_v1') { const e = new Error('quota'); e.name = 'QuotaExceededError'; throw e; }
      return real(k, v);
    };
  });
  await page.fill('#inJob', 'This job should be rolled back');
  await page.click('#addBtn');
  ok('FIX 2  storage failure raises a visible alert',
     alerts.some(a => /STORAGE FULL/.test(a)), JSON.stringify(alerts));
  ok('FIX 2  alert tells the user to export a backup',
     alerts.some(a => /Export a CSV backup/.test(a)));
  ok('FIX 2  the unsaved job is rolled back out of the list',
     (await page.locator('.task').count()) === before, `before=${before} after=${await page.locator('.task').count()}`);
  ok('FIX 2  typed text is preserved so nothing is lost',
     (await page.inputValue('#inJob')) === 'This job should be rolled back');
  ok('FIX 2  header pill warns that saving failed',
     /NOT SAVING/.test(await page.textContent('#storageText')));
  ok('FIX 2  on-disk data still matches the last good state',
     await page.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_todo_v1')).length) === before);

  // ---- birthdays reach the calendar (FIX 8 path, live) ----
  await page.evaluate(() => localStorage.setItem = Object.getPrototypeOf(localStorage).setItem.bind(localStorage));
  await page.click('#topTabs button[data-tab="crew"]');
  await page.fill('#crewName', 'J Leapling');
  await page.selectOption('#crewRank', 'Bosun');
  await page.fill('#crewDob', '2000-02-29');
  await page.click('#crewAddBtn');
  ok('crew member added', (await page.locator('.crew-row').count()) === 1);
  await page.click('#topTabs button[data-tab="jobs"]');
  await page.click('#viewSwitch button[data-view="day"]');
  await page.evaluate(() => {
    document.querySelector('#navToday').click();
  });
  ok('day view renders', (await page.locator('#dateNav').isVisible()) === true);

  await browser.close();
  server.close();
  console.log(fails === 0 ? '\nALL BROWSER TESTS PASS' : '\n' + fails + ' FAILED');
  process.exit(fails === 0 ? 0 : 1);
})().catch(e => { console.error(e); process.exit(1); });
