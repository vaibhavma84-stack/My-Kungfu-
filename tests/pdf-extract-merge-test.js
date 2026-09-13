// Extract pages from a PDF note into a new one; merge two or more into one.
//
// Both are built entirely from machinery already in the app -- pdf.js to
// read, drawOneMark to bake marks in, buildFlatPdf to write -- rather than a
// second vendored library, which was asked about and declined. The trade is
// the one Save marked already makes: the result is flattened, so a page
// stops being selectable text.
//
// This also guards a bug the feature turned up rather than caused: two phone-
// width grids (.note-bar, .pdfv .pv-actions) were declared in a media query
// positioned BEFORE their own unconditional base rule further down the
// stylesheet -- so the base rule always won, at every width, and the grid
// never applied regardless of what the CSS said. Checking that a rule with
// the right name exists is not the same as checking it wins the cascade.
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
    let u = q.url.split('?')[0];
    if(u === '/'){ r.writeHead(200, { 'Content-Type':'text/html; charset=utf-8' });
      r.end(fs.readFileSync(process.env.APP_HTML)); return; }
    const f = path.join(HERE, path.basename(u));
    if(fs.existsSync(f)){ r.writeHead(200, { 'Content-Type':'application/pdf' }); r.end(fs.readFileSync(f)); return; }
    r.writeHead(404); r.end('no');
  }).listen(8861);

  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext({ viewport:{ width:390, height:900 }, acceptDownloads:true });
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  const dialogs = [];
  p.on('dialog', async d => { dialogs.push(d.message()); await d.accept(); });

  await p.goto('http://localhost:8861/');
  await p.waitForTimeout(800);

  // ---- the cascade-order regression, checked directly ----------------------
  const grids = await p.evaluate(() => {
    document.querySelector('#topTabs button[data-tab="notes"]').click();
    return { noteBar: getComputedStyle(document.querySelector('.note-bar')).display };
  });
  ok('the note-bar grid actually wins the cascade at phone width',
     grids.noteBar === 'grid', grids.noteBar);

  // ---- extract --------------------------------------------------------------
  await p.setInputFiles('#notePdfInput', path.join(HERE, 'sample.pdf'));
  await p.waitForTimeout(3000);
  ok('the PDF viewer\'s own grid wins the cascade too',
     (await p.evaluate(() => getComputedStyle(document.querySelector('.pdfv .pv-actions')).display)) === 'grid');

  // mark page 1, so the extracted copy has something to prove it kept it --
  // a pure colour (green, which nothing in the sample PDF's own black text or
  // white background can produce) so the check below cannot be satisfied by
  // the page's own content, only by an actual baked-in mark.
  const box = await p.locator('#pdfInk').boundingBox();
  await p.click('#pdfTools button[data-pdftool="pen"]');
  await p.click('#pdfSwatches [data-pdfcolour="#1E8A3C"]');
  const inkAt = { x0: 0.2, y0: 0.62, x1: 0.6, y1: 0.66 };
  await p.mouse.move(box.x + box.width * inkAt.x0, box.y + box.height * inkAt.y0);
  await p.mouse.down();
  await p.mouse.move(box.x + box.width * inkAt.x1, box.y + box.height * inkAt.y1, { steps: 6 });
  await p.mouse.up();
  await p.waitForTimeout(300);

  await p.click('#pdfExtractBtn');
  await p.waitForTimeout(300);
  ok('the extract dialog offers one row per page', (await p.locator('.pdf-pagepick-row').count()) === 2);
  ok('and Extract starts disabled with nothing picked',
     await p.locator('#ppGoBtn').isDisabled());

  await p.click('[data-pp-page="1"] input');
  await p.waitForTimeout(100);
  ok('picking page 1 enables Extract', !(await p.locator('#ppGoBtn').isDisabled()));
  await p.click('#ppGoBtn');
  await p.waitForTimeout(2500);

  const afterExtract = await p.evaluate(() =>
    JSON.parse(localStorage.getItem('gasplanet_notes_v1'))
      .filter(n => n.kind === 'pdf').map(n => ({ name: n.pdfName, pages: n.pages })));
  ok('a new one-page note exists alongside the original',
     afterExtract.length === 2 && afterExtract[0].pages === 2 && afterExtract[1].pages === 1,
     JSON.stringify(afterExtract));
  ok('and it is named after the page it came from', /p1/.test(afterExtract[1].name), afterExtract[1].name);

  await p.click('#pdfBackBtn');
  await p.waitForTimeout(300);

  // open the extracted copy and check the mark actually travelled -- sampled
  // in the band it was drawn in, for the exact hue drawn, not "any dark pixel"
  // (the page's own black text would satisfy that regardless of marks, which
  // is exactly the false pass this replaced).
  await p.click('.note-card:has-text("p1")');
  await p.waitForTimeout(2500);
  const greenPixels = await p.evaluate((at) => {
    const cv = document.getElementById('pdfCanvas');
    const y0 = Math.floor(cv.height * at.y0), y1 = Math.ceil(cv.height * at.y1);
    const d = cv.getContext('2d').getImageData(0, y0, cv.width, y1 - y0).data;
    let green = 0;
    for(let i = 0; i < d.length; i += 4){
      if(d[i] < 100 && d[i + 1] > 100 && d[i + 1] < 190 && d[i + 2] < 100) green++;
    }
    return green;
  }, inkAt);
  ok('the mark on page 1 was baked into the extracted copy', greenPixels > 30, greenPixels);
  await p.click('#pdfBackBtn');
  await p.waitForTimeout(300);

  // ---- merge, with the two PDF notes now on hand ---------------------------
  await p.click('#noteMergeBtn');
  await p.waitForTimeout(300);
  const mergeRows = await p.locator('#mgList .pdf-pagepick-row').count();
  ok('the merge dialog lists every PDF note', mergeRows === 2, mergeRows);
  ok('and Merge starts disabled', await p.locator('#mgGoBtn').isDisabled());

  await p.locator('#mgList .pdf-pagepick-row').nth(0).locator('input').click();
  await p.waitForTimeout(100);
  ok('one picked is still not enough', await p.locator('#mgGoBtn').isDisabled());
  await p.locator('#mgList .pdf-pagepick-row').nth(1).locator('input').click();
  await p.waitForTimeout(100);
  ok('two picked enables Merge', !(await p.locator('#mgGoBtn').isDisabled()));

  const orders = await p.locator('#mgList .pp-order').allTextContents();
  ok('the pick order is numbered 1 and 2', orders.join(',') === '1,2', orders.join(','));

  await p.click('#mgGoBtn');
  await p.waitForTimeout(4000);

  const afterMerge = await p.evaluate(() =>
    JSON.parse(localStorage.getItem('gasplanet_notes_v1'))
      .filter(n => n.kind === 'pdf').map(n => ({ name: n.pdfName, pages: n.pages })));
  ok('a merged note carries every page from both sources',
     afterMerge.length === 3 && afterMerge.some(n => n.pages === 3 && /Merged/.test(n.name)),
     JSON.stringify(afterMerge));

  ok('no page errors', errs.length === 0, errs.slice(0, 3).join(' | '));

  await b.close();
  srv.close();
  console.log(fail ? '\n' + fail + ' FAILED' : '\nALL PASS');
  process.exit(fail ? 1 : 0);
})();
