const { chromium } = require('playwright-core');
const http=require('http'), fs=require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
(async()=>{
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync('process.env.APP_HTML'));}).listen(8752);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const ctx=await b.newContext({viewport:{width:900,height:900},acceptDownloads:true});
  const p=await ctx.newPage(); const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  const dl_msgs=[]; p.on('dialog',async d=>{dl_msgs.push(d.message()); await d.accept();});
  await p.goto('http://localhost:8752/');

  const dl=p.waitForEvent('download'); await p.click('#blankCsvBtn');
  const f=await dl; await f.saveAs(process.env.OUT+'/blank.csv');
  ok('named as a blank import sheet', /BLANK/.test(f.suggestedFilename()), f.suggestedFilename());
  const csv=fs.readFileSync(process.env.OUT+'/blank.csv','utf8').replace(/^﻿/,'');
  const lines=csv.trim().split(/\r?\n/);
  ok('header only, no rows', lines.length===1, lines.length);
  ok('three columns only', lines[0]==='Due Date,Job,Interval', lines[0]);
  ok('Job column present', lines[0].indexOf('Job')>-1);
  ok('Interval column present', lines[0].indexOf('Interval')>-1);
  ok('guidance shown', dl_msgs.some(m=>/three columns/.test(m)));

  // fill it like a PMS paste and import it back
  const filled = lines[0]+'\n'+
    '10-Sep-2026,Lifeboat engine weekly test,Weekly\n'+
    '15-Sep-2026,Emergency fire pump 3-monthly,3-monthly\n'+
    ',Job with no date at all,\n';
  fs.writeFileSync(process.env.OUT+'/filled.csv', filled);
  await p.setInputFiles('#importInput', process.env.OUT+'/filled.csv');
  await p.waitForTimeout(900);
  ok('three jobs imported', dl_msgs.some(m=>/Imported 3 job/.test(m)), JSON.stringify(dl_msgs).slice(-120));

  const t = await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1')));
  const a=t.find(x=>/Lifeboat/.test(x.job)), c=t.find(x=>/fire pump/i.test(x.job));
  ok('due date parsed', a.due==='2026-09-10', a.due);
  ok('weekly repeat parsed', a.repeat==='weekly', a.repeat);
  ok('flags default to off when the column is absent', a.weeklyReport!=='yes' && a.ad34Planner!=='yes');
  ok('3-monthly parsed', c.repeat==='quarterly', c.repeat);
  ok('priority defaults to normal', c.priority==='normal', c.priority);
  ok('imported jobs start pending', c.done===false);
  ok('a row with only a job still imports', t.some(x=>/no date at all/.test(x.job)));
  ok('weekly job shows yellow',
     (await p.locator('.task:not(.done)',{hasText:'Lifeboat'}).first().evaluate(e=>getComputedStyle(e).backgroundColor))==='rgb(255, 241, 118)');
  ok('no JS errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
