const { chromium } = require('playwright-core');
const http=require('http'), fs=require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
const dayOf=(csv,job)=>{let cur=null;
  for(const l of csv.replace(/^﻿/,'').split(/\r?\n/)){
    const m=l.match(/^WORK PLANNER FOR DATE,(.+)$/); if(m){cur=m[1];continue;}
    if(l.indexOf(job)>-1) return cur;} return null;};
(async()=>{
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync('process.env.APP_HTML'));}).listen(8753);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const ctx=await b.newContext({viewport:{width:900,height:900},acceptDownloads:true});
  const p=await ctx.newPage(); const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  p.on('dialog',async d=>await d.accept());
  await p.goto('http://localhost:8753/');

  ok('the bulk catch-up button is gone', (await p.locator('#bulkAd19Btn').count())===0);

  // NOT ticked AD 19 at any point — just closed out
  await p.fill('#inJob','Scuppers weekly inspection'); await p.fill('#inDue','2026-07-08');
  await p.click('#addBtn');
  // ticked AD 19 but left open — planned ahead
  await p.fill('#inJob','Planned for Friday'); await p.fill('#inDue','2026-07-10');
  await p.click('#inAd34'); await p.click('#addBtn');
  // neither ticked nor done — must stay out
  await p.fill('#inJob','Just sitting on the list'); await p.fill('#inDue','2026-07-09');
  await p.click('#addBtn');

  await p.selectOption('#filterStatus','all');
  await p.locator('.task:not(.done)',{hasText:'Scuppers'}).first().locator('.tick').click();
  await p.waitForSelector('.date-card'); await p.fill('.dc-input','2026-07-08'); await p.click('[data-dc="ok"]');
  await p.waitForTimeout(250);

  const dl=p.waitForEvent('download'); await p.click('#exportAd19Btn');
  await (await dl).saveAs(process.env.OUT+'/auto19.csv');
  const csv=fs.readFileSync(process.env.OUT+'/auto19.csv','utf8');

  ok('a closed job reaches the planner with no AD 19 tick',
     dayOf(csv,'Scuppers weekly inspection')==='08-Jul-2026', dayOf(csv,'Scuppers weekly inspection'));
  ok('an open job ticked AD 19 still plans ahead on its due date',
     dayOf(csv,'Planned for Friday')==='10-Jul-2026', dayOf(csv,'Planned for Friday'));
  ok('an open job with no tick stays out', csv.indexOf('Just sitting on the list')===-1);
  ok('two day blocks', (csv.match(/WORK PLANNER FOR DATE/g)||[]).length===2);

  // the imported back-catalogue lands too, with nothing to remember
  await p.setInputFiles('#importInput', process.env.OUT+'/import_todo.csv');
  await p.waitForTimeout(1500);
  const dl2=p.waitForEvent('download'); await p.click('#exportAd19Btn');
  await (await dl2).saveAs(process.env.OUT+'/auto19b.csv');
  const csv2=fs.readFileSync(process.env.OUT+'/auto19b.csv','utf8');
  const rows=(csv2.match(/^\d+,/gm)||[]).length;
  ok('all 109 imported completed jobs appear without any tick', rows===111, rows);
  ok('grouped into their own days', (csv2.match(/WORK PLANNER FOR DATE/g)||[]).length>=45,
     (csv2.match(/WORK PLANNER FOR DATE/g)||[]).length);
  ok('no JS errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
