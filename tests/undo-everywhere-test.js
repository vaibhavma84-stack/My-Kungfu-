// The same undo-toast treatment as jobs, extended to the other same-shape
// deletes: a note, a crew member, an AD-19 extra job, an instrument's
// procedure and its spares. Each used to interrupt with a "cannot be
// undone" confirm dialog; each now deletes immediately and offers Undo.
//
// The one real risk here is the note: deleting it used to collect its now-
// orphaned photographs and PDF straight away. Made undoable without more,
// that collection would still run immediately -- so an undone delete would
// bring the note back with its pictures already gone. showUndoToast's
// onExpire defers that collection until the undo window has actually
// closed; this file is what proves it actually does.
const { chromium } = require('playwright-core');
const path = require('path');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

(async () => {
  const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 } });
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  const dialogs = []; p.on('dialog', async d => { dialogs.push(d.message()); await d.accept('Step 1'); });
  await p.goto('file://' + process.env.APP_HTML);
  await p.waitForTimeout(500);

  const photoStats = () => p.evaluate(() => window.__photoStats());
  const undoVisible = () => p.isVisible('#undoToast');
  const undoText = () => p.textContent('#undoToastText');

  // ============================================================ Notes =====
  await p.click('#topTabs button[data-tab="notes"]');
  await p.click('#noteNewBtn');
  await p.fill('#noteTitle', 'Winch brake test');
  await p.fill('#noteBody', 'Port aft winch brake slipped at 45% MBL.');
  await p.setInputFiles('#noteImgInput', path.join(process.env.OUT, 'pic.jpg'));
  await p.waitForTimeout(900);
  const before = await photoStats();
  ok('the photo attached to the note is actually stored', before.stored >= 1, before);
  await p.click('#noteBackBtn');

  await p.locator('.note-card', { hasText: 'Winch brake test' }).click();
  await p.click('#noteDelBtn');
  await p.waitForTimeout(200);
  ok('no confirm dialog on note delete any more', dialogs.length === 0, dialogs);
  ok('the note is gone from the list', (await p.locator('#noteListWrap .note-card').count()) === 0);
  ok('undo toast reads "Note deleted."', (await undoText()) === 'Note deleted.', await undoText());

  const duringUndo = await photoStats();
  ok('the photo is NOT collected while the undo window is still open',
     duringUndo.stored === before.stored, { before, duringUndo });

  await p.click('#undoToastBtn');
  await p.waitForTimeout(200);
  ok('undo brings the note back', (await p.locator('#noteListWrap .note-card').count()) === 1);
  await p.locator('.note-card', { hasText: 'Winch brake test' }).click();
  const restoredThumbs = await p.locator('#noteThumbs .thumb').count();
  ok('with its photo still attached, not silently dropped', restoredThumbs === 1, restoredThumbs);
  await p.click('#noteBackBtn');

  // delete it again, this time let the toast actually expire
  await p.locator('.note-card', { hasText: 'Winch brake test' }).click();
  await p.click('#noteDelBtn');
  await p.waitForTimeout(6500);
  const afterExpiry = await photoStats();
  ok('once the window actually closes, the orphaned photo is collected',
     afterExpiry.stored < before.stored, { before, afterExpiry });

  // ============================================================ Crew ======
  await p.click('#topTabs button[data-tab="ship"]');
  await p.click('#shipTabs button[data-ship="crew"]');
  await p.fill('#crewName', 'R Kumar');
  await p.selectOption('#crewRank', 'Chief Officer');
  await p.fill('#crewDob', '1990-03-04');
  await p.click('#crewAddBtn');
  await p.waitForTimeout(200);

  await p.locator('[data-crew-action="delete"]').first().click();
  await p.waitForTimeout(200);
  ok('no confirm dialog on crew removal any more', dialogs.length === 0, dialogs);
  ok('the crew member is gone from the list', (await p.locator('#crewListWrap').textContent()).indexOf('R Kumar') === -1);
  ok('undo toast reads "Crew member removed."', (await undoText()) === 'Crew member removed.', await undoText());
  await p.click('#undoToastBtn');
  await p.waitForTimeout(200);
  ok('undo brings the crew member back', (await p.locator('#crewListWrap').textContent()).indexOf('R Kumar') > -1);

  // ========================================================= AD-19 extras =
  await p.click('#topTabs button[data-tab="forms"]');
  await p.click('#formTabs button[data-form="extra"]');
  await p.fill('#exJob', 'Renew galley exhaust gasket');
  await p.fill('#exDate', '2026-07-01');
  await p.click('#exAddBtn');
  await p.waitForTimeout(200);

  await p.locator('[data-extra-action="delete"]').first().click();
  await p.waitForTimeout(200);
  ok('no confirm dialog on AD-19 extra removal any more', dialogs.length === 0, dialogs);
  ok('the extra job is gone from the list',
     (await p.locator('#extraListWrap').textContent()).indexOf('Renew galley exhaust gasket') === -1);
  ok('undo toast reads "Job removed from the AD-19 list."',
     (await undoText()) === 'Job removed from the AD-19 list.', await undoText());
  await p.click('#undoToastBtn');
  await p.waitForTimeout(200);
  ok('undo brings the extra job back',
     (await p.locator('#extraListWrap').textContent()).indexOf('Renew galley exhaust gasket') > -1);

  // ===================================================== Instrument procs =
  await p.click('#topTabs button[data-tab="tools"]');
  await p.click('[data-tool="instr"]');
  await p.fill('#inInstrModel', 'Riken Keiki GX-8000');
  await p.click('#instrAddBtn');
  await p.waitForTimeout(200);
  await p.locator('.instr-card', { hasText: 'Riken Keiki GX-8000' }).click();
  await p.waitForTimeout(200);

  // add-proc prompts for a title; the dialog handler above answers every
  // prompt with "Step 1", which does double duty as this procedure's name.
  await p.click('[data-ia="add-proc"]');
  await p.waitForTimeout(200);
  ok('the procedure was added', (await p.locator('#instrDetail').textContent()).indexOf('Step 1') > -1);

  var dialogsBeforeProcDelete = dialogs.length;
  await p.click('[data-ia="del-proc"]');
  await p.waitForTimeout(200);
  ok('no confirm dialog on procedure delete any more',
     dialogs.length === dialogsBeforeProcDelete, dialogs.slice(dialogsBeforeProcDelete));
  ok('the procedure is gone', (await p.locator('#instrDetail').textContent()).indexOf('Step 1') === -1);
  ok('undo toast reads "Procedure deleted."', (await undoText()) === 'Procedure deleted.', await undoText());
  await p.click('#undoToastBtn');
  await p.waitForTimeout(200);
  ok('undo brings the procedure back', (await p.locator('#instrDetail').textContent()).indexOf('Step 1') > -1);

  // ===================================================== Instrument spares =
  await p.click('[data-ia="add-part"]');
  await p.waitForTimeout(200);
  ok('the spare was added', (await p.locator('#instrDetail').textContent()).indexOf('Step 1') > -1);

  var dialogsBeforePartDelete = dialogs.length;
  await p.click('[data-ia="del-part"]');
  await p.waitForTimeout(200);
  ok('no confirm dialog on spare removal any more',
     dialogs.length === dialogsBeforePartDelete, dialogs.slice(dialogsBeforePartDelete));
  ok('undo toast reads "Spare removed."', (await undoText()) === 'Spare removed.', await undoText());
  await p.click('#undoToastBtn');
  await p.waitForTimeout(200);
  ok('undo brings the spare back', (await p.locator('#instrDetail').textContent()).indexOf('Step 1') > -1);

  ok('no page errors', errs.length === 0, errs.join(' | '));
  await b.close();
  console.log('');
  console.log(fail ? fail + ' FAILED' : 'ALL PASS');
  process.exit(fail ? 1 : 0);
})();
