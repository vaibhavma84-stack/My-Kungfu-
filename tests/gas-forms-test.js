// The Tools tab's GAS Forms catalog: the ISGOTT and cargo-ops checklists and
// letters, vendored once (the same technique as pdf.js) and seeded into the
// existing PDF file store (gasplanet_files) the first time the app runs.
// Download hands back the stored bytes; Replace swaps them for a newer
// version without touching anything else in the catalog.
//
// The point of this suite, more than any one row: collectFiles() frees
// whatever the file store holds that nothing points at any more, and until
// it was taught about gasForms[].fileRef it had no idea these 20 files were
// spoken for -- the very next note deleted would have swept the lot. This
// is checked directly: delete a note, let its undo toast expire (collectFiles
// runs on commit, the same point photographs are freed), and prove every
// GAS form is still there afterwards.
const { chromium } = require('playwright-core');
const http = require('http');
const fs = require('fs');
const path = require('path');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

const HERE = __dirname;

(async () => {
  const srv = http.createServer((q, r) => {
    r.writeHead(200, { 'Content-Type':'text/html; charset=utf-8' });
    r.end(fs.readFileSync(process.env.APP_HTML));
  }).listen(8891);

  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext({ viewport:{ width:390, height:900 }, acceptDownloads:true });
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  p.on('dialog', async d => { await d.accept(); });

  await p.goto('http://localhost:8891/');
  await p.waitForTimeout(800);   // openFileDB + whenArchiveReady settle

  // ---- the tile and the catalog ----------------------------------------
  await p.click('#topTabs button[data-tab="tools"]');
  ok('the tile is there', await p.locator('[data-tool="forms"]').count() === 1);
  await p.click('[data-tool="forms"]');
  await p.waitForTimeout(2000);  // first-run seeding: 33 filePut round-trips
  ok('it opens', await p.locator('#toolForms').isVisible());
  ok('titled GAS Forms', (await p.textContent('#pageTitle')).trim() === 'GAS Forms');

  const FORM_COUNT = 33;
  const rows = await p.locator('.gf-row').count();
  ok('every form seeded and listed', rows === FORM_COUNT, rows);

  const first = await p.evaluate(() => {
    const row = document.querySelector('.gf-row');
    return { num: row.querySelector('.gf-num').textContent.trim(),
             date: row.querySelector('.gf-date').textContent.trim() };
  });
  ok('a row shows its number', first.num === 'GAS-01', first.num);
  ok('and its version/update date, from the form\'s own footer', /Updated \S/.test(first.date), first.date);

  await p.click('#formsBackBtn');
  ok('back button returns to the launcher', await p.locator('#toolsHome').isVisible());
  await p.click('[data-tool="forms"]');
  await p.waitForTimeout(300);

  // ---- download ----------------------------------------------------------
  const firstRowId = await p.evaluate(() => document.querySelector('.gf-row').dataset.id);
  const [dl1] = await Promise.all([
    p.waitForEvent('download'),
    p.click('.gf-row[data-id="' + firstRowId + '"] [data-gf-action="download"]'),
  ]);
  ok('download suggests the form\'s own filename',
     dl1.suggestedFilename() === 'GAS-01 Ship-Shore Safety Checklist.xlsx', dl1.suggestedFilename());
  const dl1Path = await dl1.path();
  ok('and it is a real, non-empty file', fs.statSync(dl1Path).size > 1000, fs.statSync(dl1Path).size);

  // ---- replace -------------------------------------------------------------
  const replaceRowId = firstRowId;
  const beforeDate = await p.evaluate(id =>
    document.querySelector('.gf-row[data-id="' + id + '"] .gf-date').textContent, replaceRowId);

  // the Replace button opens a hidden file input; the prompt afterwards asks
  // for the new version's date. Swapped for a plain function rather than
  // answered through the 'dialog' event, so the chosen text is unambiguous.
  await p.evaluate(() => {
    window.__gfPromptAnswer = '15-Oct-2026';
    const real = window.prompt;
    window.prompt = function(msg, def){ return window.__gfPromptAnswer; };
  });
  const [fc] = await Promise.all([
    p.waitForEvent('filechooser'),
    p.click('.gf-row[data-id="' + replaceRowId + '"] [data-gf-action="replace"]'),
  ]);
  await fc.setFiles({
    name: 'GAS-01 replacement.xlsx',
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: Buffer.from('this is a stand-in for a real replacement workbook'),
  });
  await p.waitForTimeout(500);

  const afterDate = await p.evaluate(id =>
    document.querySelector('.gf-row[data-id="' + id + '"] .gf-date').textContent, replaceRowId);
  ok('replace updates the shown version/date', afterDate !== beforeDate && /15-Oct-2026/.test(afterDate), afterDate);

  const [dl2] = await Promise.all([
    p.waitForEvent('download'),
    p.click('.gf-row[data-id="' + replaceRowId + '"] [data-gf-action="download"]'),
  ]);
  ok('replace swaps the file itself, not just the label',
     dl2.suggestedFilename() === 'GAS-01 replacement.xlsx', dl2.suggestedFilename());
  const dl2Text = fs.readFileSync(await dl2.path(), 'utf8');
  ok('and the new bytes are what was actually uploaded',
     dl2Text === 'this is a stand-in for a real replacement workbook', dl2Text);

  const otherRowsStillThere = await p.locator('.gf-row').count();
  ok('replacing one form leaves every other one untouched', otherRowsStillThere === FORM_COUNT, otherRowsStillThere);

  // ---- the collectFiles() regression --------------------------------------
  // Everything above happened with the catalog fully seeded, i.e. with 20
  // file-store entries that nothing but gasForms[].fileRef points at. A note
  // delete's undo-toast commit runs collectFiles(); until it was taught to
  // treat gasForms as a use, this is exactly the sweep that deleted them.
  await p.click('#topTabs button[data-tab="notes"]');
  await p.click('#noteNewBtn');
  await p.click('#noteDelBtn');
  ok('the note\'s own undo toast appears', await p.isVisible('#undoToast'));
  await p.waitForTimeout(6500);   // let it expire and commit -> collectFiles()
  ok('and is gone, the delete committed', !(await p.isVisible('#undoToast')));

  await p.click('#topTabs button[data-tab="tools"]');
  await p.click('[data-tool="forms"]');
  await p.waitForTimeout(300);
  const rowsAfterSweep = await p.locator('.gf-row').count();
  ok('every GAS form survives the housekeeping sweep', rowsAfterSweep === FORM_COUNT, rowsAfterSweep);

  const untouchedRowId = await p.evaluate(() =>
    Array.from(document.querySelectorAll('.gf-row')).find(r => !r.querySelector('.gf-num').textContent.includes('GAS-01')).dataset.id);
  let dl3 = null, dl3Err = null;
  try{
    [dl3] = await Promise.all([
      p.waitForEvent('download', { timeout: 5000 }),
      p.click('.gf-row[data-id="' + untouchedRowId + '"] [data-gf-action="download"]'),
    ]);
  }catch(e){ dl3Err = e; }
  ok('a form never replaced still downloads its real seeded bytes after the sweep',
     !dl3Err && fs.statSync(await dl3.path()).size > 1000,
     dl3Err ? 'collectFiles() swept it -- no download fired' : fs.statSync(await dl3.path()).size);

  ok('no page errors', errs.length === 0, errs[0]);

  console.log((fail === 0 ? 'ALL PASS' : (fail + ' FAILED')));
  await b.close();
  srv.close();
  process.exit(fail === 0 ? 0 : 1);
})();
