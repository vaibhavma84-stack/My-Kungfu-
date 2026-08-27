const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
(async () => {
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync('process.env.APP_HTML'));}).listen(8746);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const ctx=await b.newContext({viewport:{width:420,height:900},acceptDownloads:true});
  const p=await ctx.newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  const dialogs=[]; p.on('dialog',async d=>{dialogs.push(d.message()); await d.accept();});
  await p.goto('http://localhost:8746/');

  // import the real 109 completed jobs
  await p.setInputFiles('#importInput', process.env.OUT + '/import_todo.csv');
  await p.waitForTimeout(1200);
  await p.selectOption('#filterStatus','done');
  await p.waitForTimeout(400);
  ok('109 completed jobs present', (await p.locator('.task').count())===109);
  ok('none ticked AD 19 yet',
     await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1')).every(t=>t.ad34Planner!=='yes')));

  // ---- edit sheet on a COMPLETED job ----
  await p.locator('.task').first().locator('[data-action="edit"]').click();
  ok('edit sheet opens on a completed job', await p.isVisible('.edit-card'));
  ok('fields prefilled', (await p.inputValue('#edJob')).length>0, await p.inputValue('#edJob'));
  ok('status shows Completed', (await p.inputValue('#edDone'))==='done');
  ok('completion date row visible', await p.isVisible('#edCompRow'));
  ok('permit row hidden while PTW off', !(await p.isVisible('#edPtwRow')));

  await p.click('[data-ed-tg="ad19"]');
  await p.click('[data-ed-tg="ra"]');
  await p.click('[data-ed-tg="ptw"]');
  ok('permit row appears when PTW ticked', await p.isVisible('#edPtwRow'));
  await p.selectOption('#edPtwType','ENCLOSED SPACE');
  await p.selectOption('#edPrio','urgent');
  await p.fill('#edRem','Added after completion');
  await p.fill('#edComp','2026-07-05');
  await p.click('[data-ed="save"]');
  await p.waitForTimeout(300);

  const j = await p.evaluate(()=>{
    const t=JSON.parse(localStorage.getItem('gasplanet_todo_v1'));
    return t.find(x=>x.ad34Planner==='yes');
  });
  ok('AD 19 ticked after completion', j && j.ad34Planner==='yes');
  ok('RA ticked', j.ra===true);
  ok('PTW + permit type saved', j.ptw===true && j.ptwType==='ENCLOSED SPACE', j.ptwType);
  ok('priority changed', j.priority==='urgent', j.priority);
  ok('remarks saved', j.remarks==='Added after completion', j.remarks);
  ok('completion date changed', j.dateCompleted==='2026-07-05', j.dateCompleted);
  ok('still completed', j.done===true);

  // cancel must not save
  await p.locator('.task').first().locator('[data-action="edit"]').click();
  await p.fill('#edJob','SHOULD NOT SAVE');
  await p.click('[data-ed="cancel"]');
  ok('cancel discards changes',
     await p.evaluate(()=>!JSON.parse(localStorage.getItem('gasplanet_todo_v1')).some(t=>t.job==='SHOULD NOT SAVE')));

  // reopening a completed job as pending
  await p.locator('.task').first().locator('[data-action="edit"]').click();
  await p.selectOption('#edDone','open');
  ok('completion row hides when set back to pending', !(await p.isVisible('#edCompRow')));
  await p.click('[data-ed="save"]');
  await p.waitForTimeout(200);
  ok('job reopened and completion date cleared',
     await p.evaluate(()=>{const t=JSON.parse(localStorage.getItem('gasplanet_todo_v1'));
       return t.filter(x=>!x.done).length===1 && t.find(x=>!x.done).dateCompleted==='';}));

  // ---- closing a job is now enough; no catch-up button ----
  ok('the bulk catch-up button is gone', (await p.locator('#bulkAd19Btn').count())===0);
  const st2 = await p.evaluate(()=>{
    const t=JSON.parse(localStorage.getItem('gasplanet_todo_v1'));
    return { done:t.filter(x=>x.done).length, pending:t.filter(x=>!x.done).length };
  });
  ok('108 completed, 1 reopened pending', st2.done===108 && st2.pending===1,
     JSON.stringify(st2));

  // export now carries them
  await p.click('#topTabs button[data-tab="jobs"]');
  const dl=p.waitForEvent('download'); await p.click('#exportAd19Btn');
  await (await dl).saveAs(process.env.OUT+'/bulk.csv');
  const csv=fs.readFileSync(process.env.OUT+'/bulk.csv','utf8');
  const jobRows=csv.split('\n').filter(l=>/^\d+,/.test(l)).length;
  // 108 completed reach the planner automatically, plus the 1 pending job
  // that was ticked AD 19 by hand in the edit sheet earlier
  ok('every completed job reaches the planner with no ticking', jobRows===109, jobRows);
  ok('no JS errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
