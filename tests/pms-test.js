const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
const dayOf=(csv,job)=>{ // which WORK PLANNER FOR DATE block a job sits under
  let cur=null;
  for(const l of csv.replace(/^\uFEFF/,'').split(/\r?\n/)){
    const m=l.match(/^WORK PLANNER FOR DATE,(.+)$/); if(m){cur=m[1];continue;}
    if(l.indexOf(job)>-1) return cur;
  }
  return null;
};
(async () => {
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(process.env.APP_HTML));}).listen(8750);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const ctx=await b.newContext({viewport:{width:900,height:900},acceptDownloads:true});
  const p=await ctx.newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  p.on('dialog',async d=>await d.accept());
  await p.goto('http://localhost:8750/');

  // three PMS jobs all due 10-Jul, all ticked AD 19
  const add = async (job) => { await p.fill('#inJob',job); await p.fill('#inDue','2026-07-10');
                               await p.click('#inAd34'); await p.click('#addBtn'); };
  await add('PMS - Lifeboat engine monthly');    // left open  -> plans on due date
  await add('PMS - Fire pump monthly');          // closed early 07-Jul
  await add('PMS - Emergency generator monthly');// closed late  14-Jul

  await p.selectOption('#filterStatus','all');
  const tick = async (job, iso) => {
    await p.locator('.task',{hasText:job}).locator('.tick').click();
    await p.waitForSelector('.date-card'); await p.fill('.dc-input',iso); await p.click('[data-dc="ok"]');
  };
  await tick('PMS - Fire pump monthly','2026-07-07');
  await tick('PMS - Emergency generator monthly','2026-07-14');

  let dl=p.waitForEvent('download'); await p.click('#exportAd19Btn');
  await (await dl).saveAs(process.env.OUT+'/pms.csv');
  let csv=fs.readFileSync(process.env.OUT+'/pms.csv','utf8');

  ok('open job plans under its DUE date',
     dayOf(csv,'Lifeboat engine monthly')==='10-Jul-2026', dayOf(csv,'Lifeboat engine monthly'));
  ok('job closed EARLY moves to the completed date',
     dayOf(csv,'Fire pump monthly')==='07-Jul-2026', dayOf(csv,'Fire pump monthly'));
  ok('job closed LATE moves to the completed date',
     dayOf(csv,'Emergency generator monthly')==='14-Jul-2026', dayOf(csv,'Emergency generator monthly'));
  ok('three separate day blocks', (csv.match(/WORK PLANNER FOR DATE/g)||[]).length===3);
  ok('planned entry is NOT tagged not-yet-completed', csv.indexOf('NOT YET COMPLETED')===-1);

  // now close the open one on a different day again — it must move
  await tick('PMS - Lifeboat engine monthly','2026-07-12');
  dl=p.waitForEvent('download'); await p.click('#exportAd19Btn');
  await (await dl).saveAs(process.env.OUT+'/pms2.csv');
  csv=fs.readFileSync(process.env.OUT+'/pms2.csv','utf8');
  ok('once closed, it leaves the due-date planner',
     dayOf(csv,'Lifeboat engine monthly')==='12-Jul-2026', dayOf(csv,'Lifeboat engine monthly'));
  ok('the 10-Jul block is gone entirely', csv.indexOf('WORK PLANNER FOR DATE,10-Jul-2026')===-1);

  // the WR report still marks unfinished work, where it does matter
  await p.fill('#inJob','WR job not finished'); await p.fill('#inDue','2026-07-10');
  await p.click('#inWeekly'); await p.click('#addBtn');
  dl=p.waitForEvent('download'); await p.click('#exportWrBtn');
  await (await dl).saveAs(process.env.OUT+'/wr2.csv');
  const wr=fs.readFileSync(process.env.OUT+'/wr2.csv','utf8');
  ok('WR report still marks work not yet done', /WR job not finished,NOT YET COMPLETED/.test(wr));
  ok('no JS errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
