// WWR tab: the ticked-WR jobs and the ones added straight into the tab are one
// and the same record — added here they must show on the to do list as done,
// reach the work done report, and honour the AD 19 tick.
const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs'); const path = require('path');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+(x||''))); if(!c)fails++;};
(async () => {
  const APP = process.env.APP_HTML || path.join(__dirname,'..','index.html');
  const OUT = process.env.OUT || fs.mkdtempSync('/tmp/wwr-');
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(APP));}).listen(8760);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const ctx=await b.newContext({viewport:{width:900,height:1000},acceptDownloads:true});
  const p=await ctx.newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  const dialogs=[]; p.on('dialog',async d=>{dialogs.push(d.message()); await d.accept();});
  await p.goto('http://localhost:8760/');

  // ---- the tab exists and switches ----
  ok('WWR tab button is present', await p.locator('#topTabs button[data-tab="wwr"]').count()===1);
  await p.click('#topTabs button[data-tab="wwr"]');
  ok('WWR section shows', await p.locator('#wwrSection').isVisible());
  ok('other sections hide', !(await p.locator('#jobsSection').isVisible()) && !(await p.locator('#extraSection').isVisible()));
  ok('title reads Weekly Work Done', (await p.textContent('#pageTitle')).trim()==='Weekly Work Done');
  ok('starts empty', /Nothing for the work done report/.test(await p.textContent('#wwrListWrap')));

  // ---- add work done straight into the tab ----
  await p.fill('#wrJob','Renewed pilot ladder side ropes');
  await p.fill('#wrDate','2026-08-26');            // Wed of the 24-Aug week
  await p.fill('#wrRemarks','Both ropes renewed, old ones landed ashore');
  await p.selectOption('#wrPtw','WORK AT HEIGHT/OVERSIDE');
  await p.click('#wrRA');
  await p.click('#wrAd19');
  await p.setInputFiles('#wrPhotoInput', path.join(OUT,'pic.jpg'));
  await p.waitForTimeout(500);
  ok('photo thumb queued', await p.locator('#wrPendingThumbs .thumb').count()===1);
  await p.click('#wrAddBtn');
  await p.waitForTimeout(300);

  const row = p.locator('#wwrListWrap .extra-row', { hasText:'pilot ladder' });
  ok('entry lands in the WWR list', await row.count()===1);
  ok('carries the AD 19 tag', /AD 19/.test(await row.textContent()));
  ok('carries the RA tag', /RA SF-23/.test(await row.textContent()));
  ok('carries the permit type', /WORK AT HEIGHT/.test(await row.textContent()));
  ok('carries the comments', /landed ashore/.test(await row.textContent()));
  ok('carries the photo', await row.locator('.thumb img').count()===1);
  ok('week band shown', /25-Aug-2026|24-Aug-2026/.test(await p.textContent('#wwrListWrap')));
  ok('form cleared', (await p.inputValue('#wrJob'))==='' && (await p.inputValue('#wrRemarks'))==='');
  ok('pending thumbs cleared', await p.locator('#wrPendingThumbs .thumb').count()===0);
  ok('count badge reads 1', /1 entry/.test(await p.textContent('#wrCount')));

  // ---- it is a real, completed job on the to do list ----
  await p.click('#topTabs button[data-tab="jobs"]');
  await p.selectOption('#filterStatus','done');
  const card = p.locator('.task', { hasText:'pilot ladder' });
  ok('shows on the to do list', await card.count()===1);
  ok('shows there as completed', await card.first().evaluate(el=>el.classList.contains('done')));
  ok('photo came with it', await card.locator('.thumbstrip .thumb').count()===1);
  ok('WR flag set on the card', /WR/.test(await card.textContent()));
  ok('AD 19 flag set on the card', /AD 19/.test(await card.textContent()));

  // ---- a job ticked WR on the to do list appears in WWR ----
  await p.selectOption('#filterStatus','open');
  await p.fill('#inJob','Weekly test of emergency steering');
  await p.fill('#inDue','2026-08-27');
  await p.click('#inWeekly');
  await p.click('#addBtn');
  await p.waitForTimeout(200);
  await p.click('#topTabs button[data-tab="wwr"]');
  const pend = p.locator('#wwrListWrap .extra-row', { hasText:'emergency steering' });
  ok('a WR-ticked job appears here too', await pend.count()===1);
  ok('and is marked as not yet done', /NOT YET COMPLETED/.test(await pend.textContent()));

  // ---- exports pick both up ----
  const grab = async (sel) => {
    const [dl] = await Promise.all([p.waitForEvent('download'), p.click(sel)]);
    const f = path.join(OUT, dl.suggestedFilename()); await dl.saveAs(f);
    return fs.readFileSync(f,'utf8').replace(/^﻿/,'');
  };
  const wr = await grab('#wrExportBtn');
  ok('WR export has the added job', /pilot ladder side ropes/.test(wr));
  ok('WR export has the ticked job', /emergency steering/.test(wr));
  ok('WR export keeps the comments', /landed ashore/.test(wr));

  await p.click('#topTabs button[data-tab="jobs"]');
  const ad = await grab('#exportAd19Btn');
  ok('AD-19 export has the WWR job (AD 19 ticked)', /pilot ladder side ropes/.test(ad));
  ok('AD-19 export leaves out the un-ticked one', !/emergency steering/.test(ad));

  // ---- the weekly report ----
  await p.click('#topTabs button[data-tab="wwr"]');
  await p.click('#wrReportBtn');
  await p.waitForTimeout(400);
  ok('report view opens from the WWR tab', await p.locator('#reportView.on').count()===1);

  // ---- edit and delete are the job's own ----
  await p.keyboard.press('Escape');
  await p.evaluate(()=>{ const b=document.querySelector('#reportView .rep-close'); if(b) b.click(); });
  await p.waitForTimeout(200);
  await p.evaluate(()=>{ document.getElementById('reportView').classList.remove('on'); document.body.style.overflow=''; });
  await p.click('#wwrListWrap .extra-row [data-wwr-action="edit"]');
  await p.waitForTimeout(200);
  ok('edit opens the job editor', await p.locator('.edit-card #edJob').count()===1);
  await p.click('[data-ed="cancel"]').catch(()=>{});
  await p.evaluate(()=>{ const m=document.querySelector('.modal-bg'); if(m) m.remove(); });

  const before = await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1')).length);
  await p.locator('#wwrListWrap .extra-row', { hasText:'pilot ladder' }).locator('[data-wwr-action="delete"]').click();
  await p.waitForTimeout(300);
  const after = await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1')).length);
  ok('delete removes the underlying job', after===before-1, before+' -> '+after);

  ok('no page errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails? fails+' FAILED' : 'wwr-test: all passed');
  process.exit(fails?1:0);
})();
