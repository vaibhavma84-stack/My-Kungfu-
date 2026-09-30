// Two additions to the To Do tab: a text search over the job list (the notes
// tab, the map and the unit converter all already had one; the job list did
// not), and an undo toast in place of the old "this cannot be undone" confirm
// dialogs on Delete and Clear completed.
const { chromium } = require('playwright-core');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

(async () => {
  const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const p = await b.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  const dialogs = []; p.on('dialog', async d => { dialogs.push(d.message()); await d.dismiss(); });
  await p.goto('file://' + process.env.APP_HTML);
  await p.waitForTimeout(500);

  const add = async (job, due, rem) => {
    await p.fill('#inJob', job);
    await p.fill('#inDue', due);
    await p.click('#addBtn');
    await p.waitForTimeout(150);
    if(rem){
      await p.locator('.task', { hasText: job }).locator('[data-action="edit"]').click();
      await p.fill('#edRem', rem);
      await p.click('[data-ed="save"]');
      await p.waitForTimeout(150);
    }
  };

  await add('Sound all tanks', '2026-06-01');
  await add('Check mooring lines', '2026-06-02');
  await add('Paint deck', '2026-06-03', 'Alongside Singapore, weather permitting');
  await add('Inspect fire extinguishers', '2026-06-04');
  await p.selectOption('#filterStatus', 'all');

  const visibleJobs = () => p.locator('#listWrap .task').allTextContents();

  // ---- search: title -------------------------------------------------------
  await p.fill('#jobSearch', 'moor');
  await p.waitForTimeout(150);
  let shown = await visibleJobs();
  ok('search on the job title narrows the list', shown.length === 1 && /mooring/.test(shown[0]), shown);

  // ---- search: remarks, not just the title ---------------------------------
  await p.fill('#jobSearch', 'Singapore');
  await p.waitForTimeout(150);
  shown = await visibleJobs();
  ok('search also matches remarks, not only the job title',
     shown.length === 1 && /Paint deck/.test(shown[0]), shown);

  // ---- case-insensitive ------------------------------------------------------
  await p.fill('#jobSearch', 'PAINT');
  await p.waitForTimeout(150);
  shown = await visibleJobs();
  ok('search is case-insensitive', shown.length === 1 && /Paint deck/.test(shown[0]), shown);

  // ---- clearing the box restores the full list -------------------------------
  await p.fill('#jobSearch', '');
  await p.waitForTimeout(150);
  shown = await visibleJobs();
  ok('an empty search shows everything again', shown.length === 4, shown.length);

  // ---- search combines with the existing status/priority filters ------------
  await p.locator('.task', { hasText: 'Sound all tanks' }).locator('.tick').click();
  await p.waitForSelector('.date-card');
  await p.fill('.dc-input', '2026-06-01');
  await p.click('[data-dc="ok"]');
  await p.waitForTimeout(200);
  await p.fill('#jobSearch', 'tanks');
  await p.selectOption('#filterStatus', 'open');
  await p.waitForTimeout(150);
  shown = await visibleJobs();
  ok('search narrows within the Pending filter, not around it', shown.length === 0, shown);
  await p.selectOption('#filterStatus', 'all');
  await p.fill('#jobSearch', '');
  await p.waitForTimeout(150);

  // ---- delete: no interrupting confirm dialog any more -----------------------
  await p.locator('.task', { hasText: 'Inspect fire extinguishers' })
    .locator('[data-action="delete"]').click();
  await p.waitForTimeout(200);
  ok('no confirm dialog on delete any more', dialogs.length === 0, dialogs);
  shown = await visibleJobs();
  ok('the job is gone from the list immediately', !shown.some(t => /Inspect fire/.test(t)), shown);
  ok('the undo toast appears', await p.isVisible('#undoToast'));
  ok('and says what happened', /Job deleted/.test(await p.textContent('#undoToastText')),
     await p.textContent('#undoToastText'));

  // ---- undo brings it back exactly -------------------------------------------
  await p.click('#undoToastBtn');
  await p.waitForTimeout(200);
  shown = await visibleJobs();
  ok('undo restores the deleted job', shown.some(t => /Inspect fire extinguishers/.test(t)), shown);
  ok('and the toast is gone', !(await p.isVisible('#undoToast')));

  // ---- a second delete while the toast is up replaces the first, Gmail-style -
  await p.locator('.task', { hasText: 'Check mooring lines' }).locator('[data-action="delete"]').click();
  await p.waitForTimeout(150);
  await p.locator('.task', { hasText: 'Paint deck' }).locator('[data-action="delete"]').click();
  await p.waitForTimeout(150);
  await p.click('#undoToastBtn');
  await p.waitForTimeout(200);
  shown = await visibleJobs();
  ok('undo after a second delete restores the second job',
     shown.some(t => /Paint deck/.test(t)), shown);
  ok('but not the first -- its undo window had already closed',
     !shown.some(t => /Check mooring lines/.test(t)), shown);

  // ---- clear completed: same toast-and-undo treatment ------------------------
  // "Sound all tanks" was already ticked done earlier in the test (the
  // status/priority filter-combination check above), so it is the one
  // completed job Clear completed has to work with here.
  await p.click('#clearDoneBtn');
  await p.waitForTimeout(200);
  ok('no confirm dialog on Clear completed either', dialogs.length === 0, dialogs);
  shown = await visibleJobs();
  ok('completed jobs are gone from the list', !shown.some(t => /Sound all tanks/.test(t)), shown);
  ok('the toast counts what was cleared',
     /^1 job cleared\.$/.test(await p.textContent('#undoToastText')),
     await p.textContent('#undoToastText'));

  await p.click('#undoToastBtn');
  await p.waitForTimeout(200);
  shown = await visibleJobs();
  const restored = await p.locator('.task.done', { hasText: 'Sound all tanks' }).count();
  ok('undo brings the cleared job back', shown.some(t => /Sound all tanks/.test(t)), shown);
  ok('still marked done, not reset to pending', restored === 1, restored);

  // ---- Clear completed with nothing done is a silent no-op -------------------
  await p.locator('.task.done', { hasText: 'Sound all tanks' }).locator('.tick').click();
  await p.waitForTimeout(200);
  await p.click('#clearDoneBtn');
  await p.waitForTimeout(200);
  ok('clicking Clear completed with nothing done shows no toast',
     !(await p.isVisible('#undoToast')));

  // ---- the toast dismisses itself if nobody touches it ------------------------
  await p.locator('.task', { hasText: 'Paint deck' }).locator('[data-action="delete"]').click();
  await p.waitForTimeout(200);
  ok('toast is up right after a delete', await p.isVisible('#undoToast'));
  await p.waitForTimeout(6500);
  ok('and gone on its own a few seconds later', !(await p.isVisible('#undoToast')));

  ok('no page errors', errs.length === 0, errs.join(' | '));
  await b.close();
  console.log('');
  console.log(fail ? fail + ' FAILED' : 'ALL PASS');
  process.exit(fail ? 1 : 0);
})();
