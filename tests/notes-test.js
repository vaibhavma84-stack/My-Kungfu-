// The Notes tab. Written notes with photographs on them, and PDFs that get
// marked up and handed back.
//
// Two things here are worth more than the rest. The first is that a mark is
// stored as a fraction of the page, so it has to land in the same place after a
// reload at a different zoom. The second is the tidy-up race: the collector
// must not run before the archive index has loaded, or a signed-off ship's
// photographs are deleted by the app's own housekeeping.
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
const TYPES = { '.pdf':'application/pdf', '.jpg':'image/jpeg', '.html':'text/html; charset=utf-8' };

(async () => {
  const srv = http.createServer((q, r) => {
    if(q.url === '/' || q.url.startsWith('/index')){
      r.writeHead(200, { 'Content-Type':'text/html; charset=utf-8' });
      r.end(fs.readFileSync(process.env.APP_HTML));
      return;
    }
    const f = path.join(HERE, path.basename(q.url));
    if(fs.existsSync(f)){
      r.writeHead(200, { 'Content-Type': TYPES[path.extname(f)] || 'application/octet-stream' });
      r.end(fs.readFileSync(f));
      return;
    }
    r.writeHead(404); r.end('no');
  }).listen(8751);

  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext({ viewport:{width:390,height:844}, acceptDownloads:true });
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  p.on('dialog', async d => { await d.accept(d.type() === 'prompt' ? 'Brake test overdue' : undefined); });

  await p.goto('http://localhost:8751/');
  await p.waitForTimeout(900);

  // ---- a written note -----------------------------------------------------
  await p.click('#topTabs button[data-tab="notes"]');
  ok('the Notes tab opens', await p.locator('#notesSection').isVisible());
  ok('and starts empty', (await p.locator('#noteListWrap .empty').count()) === 1);

  await p.click('#noteNewBtn');
  await p.fill('#noteTitle', 'Winch brake test');
  await p.fill('#noteBody', 'Port aft winch brake slipped at 45% MBL.\nHolding capacity to be re-measured.');
  await p.waitForTimeout(700);
  await p.click('#noteBackBtn');
  ok('the note is in the list', (await p.locator('#noteListWrap .note-card').count()) === 1);
  ok('with its title on the card',
     (await p.locator('#noteListWrap .nc-title').first().textContent()).trim() === 'Winch brake test');

  // a photograph on a note is a photograph like any other
  await p.click('#noteListWrap .note-card');
  await p.setInputFiles('#noteImgInput', path.join(HERE, 'pic.jpg'));
  await p.waitForTimeout(1200);
  const imgs = await p.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_notes_v1'))[0].images);
  ok('the photograph is kept by reference, not inline', imgs.length === 1 && imgs[0].slice(0,2) === 'p:', JSON.stringify(imgs));

  await p.reload();
  await p.waitForTimeout(1400);
  await p.click('#topTabs button[data-tab="notes"]');
  ok('the note survives a reload', (await p.locator('#noteListWrap .note-card').count()) === 1);

  // The collector walks every array of photographs in the app. If it does not
  // know about notes, the photograph above is deleted on the next start-up.
  const stillThere = await p.evaluate(async () => {
    const ref = JSON.parse(localStorage.getItem('gasplanet_notes_v1'))[0].images[0];
    const db = await new Promise(res => { const r = indexedDB.open('gasplanet_media', 1);
      r.onsuccess = () => res(r.result); r.onerror = () => res(null); });
    return await new Promise(res => {
      const rq = db.transaction('photos','readonly').objectStore('photos').get(ref);
      rq.onsuccess = () => res(typeof rq.result === 'string');
      rq.onerror = () => res(false);
    });
  });
  ok('and its photograph is not collected away', stillThere);

  // ---- a PDF --------------------------------------------------------------
  await p.setInputFiles('#notePdfInput', path.join(HERE, 'sample.pdf'));
  await p.waitForTimeout(3500);
  ok('the PDF opens in the viewer', await p.locator('#notePdfView').isVisible());
  ok('and reports both of its pages',
     (await p.locator('#pdfPageNo').textContent()).trim() === '1 / 2',
     await p.locator('#pdfPageNo').textContent());

  const painted = await p.evaluate(() => {
    const cv = document.getElementById('pdfCanvas');
    const d = cv.getContext('2d').getImageData(0, 0, cv.width, Math.min(cv.height, 200)).data;
    let dark = 0;
    for(let i = 0; i < d.length; i += 4) if(d[i] < 200) dark++;
    return dark;
  });
  ok('the page is actually drawn, not blank', painted > 50, painted);

  const box = await p.locator('#pdfInk').boundingBox();
  function at(fx, fy){ return { x: box.x + box.width * fx, y: box.y + box.height * fy }; }

  // highlight: dragged across the heading, it should follow the words rather
  // than leave a smear the width of the drag
  await p.click('#pdfTools button[data-pdftool="hl"]');
  let a = at(0.13, 0.085), z = at(0.46, 0.085);
  await p.mouse.move(a.x, a.y); await p.mouse.down();
  await p.mouse.move(z.x, z.y, { steps: 8 }); await p.mouse.up();
  await p.waitForTimeout(400);
  let marks = await p.evaluate(() => {
    const n = JSON.parse(localStorage.getItem('gasplanet_notes_v1')).filter(x => x.kind === 'pdf')[0];
    return n.marks['1'] || [];
  });
  ok('the highlight is recorded', marks.length === 1 && marks[0].t === 'hl', JSON.stringify(marks.map(m=>m.t)));
  // The drag started at 0.13 across and had no height at all. A highlight that
  // followed the finger would be exactly that; one that followed the text
  // starts at the left margin -- 72pt of 595, or 0.121 -- and is as tall as the
  // 22pt heading. That difference is the whole point of the tool.
  const hl = (marks[0] && marks[0].r || [])[0] || [];
  ok('and it snapped onto the text rather than the drag',
     hl.length === 4 && hl[0] < 0.128 && hl[3] > 0.02, JSON.stringify(hl));

  // every rectangle is a fraction of the page, never a pixel count
  const fractional = (marks[0].r || []).every(r => r.every(v => v >= -0.01 && v <= 1.01));
  ok('marks are stored as a fraction of the page', fractional, JSON.stringify(marks[0].r));

  // pen
  await p.click('#pdfTools button[data-pdftool="pen"]');
  a = at(0.2, 0.4); z = at(0.7, 0.5);
  await p.mouse.move(a.x, a.y); await p.mouse.down();
  await p.mouse.move(z.x, z.y, { steps: 10 }); await p.mouse.up();
  await p.waitForTimeout(300);
  marks = await p.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_notes_v1'))
                                   .filter(x => x.kind === 'pdf')[0].marks['1']);
  ok('a pen stroke is recorded', marks.length === 2 && marks[1].t === 'ink',
     JSON.stringify(marks.map(m => m.t)));
  ok('with the path it was drawn along', (marks[1].p || []).length >= 3, (marks[1].p || []).length);

  // a typed note on the page
  await p.click('#pdfTools button[data-pdftool="text"]');
  a = at(0.3, 0.6);
  await p.mouse.move(a.x, a.y); await p.mouse.down(); await p.mouse.up();
  await p.waitForTimeout(300);
  marks = await p.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_notes_v1'))
                                   .filter(x => x.kind === 'pdf')[0].marks['1']);
  ok('a typed note lands on the page',
     marks.length === 3 && marks[2].t === 'text' && marks[2].v === 'Brake test overdue',
     JSON.stringify(marks.map(m => m.t + ':' + (m.v || ''))));

  await p.click('#pdfUndoBtn');
  await p.waitForTimeout(250);
  marks = await p.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_notes_v1'))
                                   .filter(x => x.kind === 'pdf')[0].marks['1']);
  ok('undo takes the last one back off', marks.length === 2, marks.length);

  // page 2 keeps its own marks
  await p.click('#pdfNext');
  await p.waitForTimeout(1200);
  ok('page two opens', (await p.locator('#pdfPageNo').textContent()).trim() === '2 / 2',
     await p.locator('#pdfPageNo').textContent());
  await p.click('#pdfTools button[data-pdftool="rect"]');
  a = at(0.15, 0.1); z = at(0.75, 0.2);
  await p.mouse.move(a.x, a.y); await p.mouse.down();
  await p.mouse.move(z.x, z.y, { steps: 6 }); await p.mouse.up();
  await p.waitForTimeout(300);
  let all = await p.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_notes_v1'))
                                     .filter(x => x.kind === 'pdf')[0].marks);
  ok('the box went on page two only',
     (all['1'] || []).length === 2 && (all['2'] || []).length === 1,
     JSON.stringify({ one:(all['1']||[]).length, two:(all['2']||[]).length }));

  // ---- the marks survive a reload -----------------------------------------
  const before = JSON.stringify(all);
  await p.reload();
  await p.waitForTimeout(1500);
  all = await p.evaluate(() => JSON.parse(localStorage.getItem('gasplanet_notes_v1'))
                                 .filter(x => x.kind === 'pdf')[0].marks);
  ok('every mark survives a reload', JSON.stringify(all) === before);

  // ---- a marked copy, as a PDF --------------------------------------------
  await p.click('#topTabs button[data-tab="notes"]');
  await p.click('#noteListWrap .note-card:has(.nc-chip.pdf)');
  await p.waitForTimeout(3500);
  const dl = p.waitForEvent('download', { timeout: 60000 });
  await p.click('#pdfExportBtn');
  const got = await dl;
  const outFile = path.join(fs.mkdtempSync('/tmp/notes-'), 'marked.pdf');
  await got.saveAs(outFile);
  const bytes = fs.readFileSync(outFile);
  ok('Save marked writes a PDF', bytes.slice(0, 5).toString() === '%PDF-', bytes.slice(0, 8).toString());
  ok('with both pages in it', /\/Count 2\b/.test(bytes.toString('latin1')));
  ok('and an image for each of them',
     (bytes.toString('latin1').match(/\/Subtype \/Image/g) || []).length === 2);
  // the cross-reference table has to point at real objects or no reader opens it
  const txt = bytes.toString('latin1');
  const startxref = parseInt((txt.match(/startxref\s+(\d+)/) || [])[1], 10);
  ok('its cross-reference table is where it says it is',
     txt.slice(startxref, startxref + 4) === 'xref', txt.slice(startxref, startxref + 12));
  const firstOff = parseInt(txt.slice(startxref).match(/\n(\d{10}) 00000 n/)[1], 10);
  ok('and its first object is where the table says',
     /^1 0 obj/.test(txt.slice(firstOff, firstOff + 8)), txt.slice(firstOff, firstOff + 10));

  // ---- signing off carries the notes and the PDF --------------------------
  const zdl = p.waitForEvent('download', { timeout: 90000 });
  await p.evaluate(() => {
    document.querySelector('#topTabs button[data-tab="ship"]').click();
  });
  await p.waitForTimeout(300);
  await p.evaluate(() => {
    const b = [...document.querySelectorAll('button')].find(x => /sign(ed)? off/i.test(x.textContent));
    if(b) b.click();
  });
  const zip = await zdl;
  const zfile = path.join(fs.mkdtempSync('/tmp/notes-zip-'), 'ship.zip');
  await zip.saveAs(zfile);
  const zbytes = fs.readFileSync(zfile).toString('latin1');
  ok('the archive carries notes.csv', zbytes.indexOf('notes.csv') !== -1);
  ok('and the PDF itself', /pdf\/0001 sample\.pdf/.test(zbytes), zbytes.slice(0, 0));
  ok('and the note text is in the CSV', zbytes.indexOf('Port aft winch brake slipped') !== -1);

  // ---- the tidy-up must wait for the archive index -----------------------
  // Both collectors delete anything nothing points at. The list of what a
  // signed-off ship still points at arrives from IndexedDB, which is not
  // instant -- so with the archive database made slow on purpose, the tidy-up
  // gets its chance to run first. Nothing of the signed-off ship may go.
  const archived = await p.evaluate(async () => {
    const db = await new Promise(res => { const r = indexedDB.open('gasplanet_archive', 1);
      r.onsuccess = () => res(r.result); r.onerror = () => res(null); });
    if(!db) return [];
    return await new Promise(res => {
      const out = [];
      const rq = db.transaction('ships','readonly').objectStore('ships').openCursor();
      rq.onsuccess = () => { const c = rq.result;
        if(!c){ res(out); return; }
        (c.value.photos || []).forEach(v => out.push(v));
        c.continue(); };
      rq.onerror = () => res(out);
    });
  });
  ok('the signed-off ship left photographs behind to protect', archived.length > 0, archived.length);

  await p.addInitScript(() => {
    const real = indexedDB.open.bind(indexedDB);
    indexedDB.open = function(name, ver){
      const req = real(name, ver);
      if(name !== 'gasplanet_archive') return req;
      const shim = { result:null, onsuccess:null, onerror:null, onupgradeneeded:null, onblocked:null };
      req.onupgradeneeded = e => { shim.result = req.result; if(shim.onupgradeneeded) shim.onupgradeneeded(e); };
      req.onsuccess = e => { shim.result = req.result;
        setTimeout(() => { if(shim.onsuccess) shim.onsuccess(e); }, 1500); };
      req.onerror = e => { if(shim.onerror) shim.onerror(e); };
      return shim;
    };
  });
  await p.reload();
  await p.waitForTimeout(3200);
  const survived = await p.evaluate(async refs => {
    const db = await new Promise(res => { const r = indexedDB.open('gasplanet_media', 1);
      r.onsuccess = () => res(r.result); r.onerror = () => res(null); });
    if(!db) return -1;
    let n = 0;
    for(const ref of refs){
      const hit = await new Promise(res => {
        const rq = db.transaction('photos','readonly').objectStore('photos').get(ref);
        rq.onsuccess = () => res(typeof rq.result === 'string');
        rq.onerror = () => res(false);
      });
      if(hit) n++;
    }
    return n;
  }, archived);
  ok('the archived photographs all survive a slow archive index',
     survived === archived.length, survived + ' of ' + archived.length);

  ok('no JS errors anywhere', errs.length === 0, errs.slice(0, 3).join(' | '));

  await b.close();
  srv.close();
  console.log(fail ? '\n' + fail + ' FAILED' : '\nALL PASS');
  process.exit(fail ? 1 : 0);
})();
