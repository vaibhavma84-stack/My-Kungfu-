const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
(async () => {
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync('process.env.APP_HTML'));}).listen(8748);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const ctx=await b.newContext({viewport:{width:900,height:900},acceptDownloads:true});
  const p=await ctx.newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  const dialogs=[]; p.on('dialog',async d=>{dialogs.push(d.message()); await d.accept();});
  await p.goto('http://localhost:8748/');

  await p.click('#exportWrBtn');
  await p.waitForTimeout(200);
  ok('warns when nothing is ticked WR', dialogs.some(d=>/No jobs are ticked WR/.test(d)));

  // 06-Jul is a Monday; 12-Jul Sunday. 13-Jul starts the next week.
  const add = async (job, due, wr, opts={}) => {
    await p.fill('#inJob', job); await p.fill('#inDue', due);
    if(wr) await p.click('#inWeekly');
    if(opts.ra) await p.click('#inRA');
    if(opts.ptw){ await p.click('#inPTW'); await p.selectOption('#inPtwType', opts.ptw); }
    if(opts.rem) await p.fill('#inRemarks', opts.rem);
    await p.click('#addBtn');
  };
  await add('Air hoses weekly inspection','2026-07-06', true, {rem:'All hoses in date'});
  await add('Scuppers weekly inspection','2026-07-10', true, {ra:true});
  await add('Bilge alarms weekly test','2026-07-12', true, {ptw:'COLD WORK'});
  await add('Draeger monthly routines','2026-07-13', true, {});      // next week
  await add('Not in the report','2026-07-08', false, {});            // no WR tick
  await add('Still outstanding','2026-07-09', true, {});             // stays pending

  await p.selectOption('#filterStatus','all');
  for (const [job, iso] of [['Air hoses weekly inspection','2026-07-06'],
                            ['Scuppers weekly inspection','2026-07-10'],
                            ['Bilge alarms weekly test','2026-07-12'],
                            ['Draeger monthly routines','2026-07-13']]) {
    await p.locator('.task', { hasText: job }).locator('.tick').click();
    await p.waitForSelector('.date-card');
    await p.fill('.dc-input', iso); await p.click('[data-dc="ok"]');
  }

  const dl=p.waitForEvent('download'); await p.click('#exportWrBtn');
  await (await dl).saveAs(process.env.OUT+'/wr.csv');
  const csv=fs.readFileSync(process.env.OUT+'/wr.csv','utf8');
  fs.writeFileSync(process.env.OUT+'/wr-view.txt', csv);
  const lines=csv.split(/\r?\n/);

  ok('two week blocks', (csv.match(/WORK DONE REPORT/g)||[]).length===2, (csv.match(/WORK DONE REPORT/g)||[]).length);
  ok('first week commences Mon 06-Jul', csv.indexOf('WEEK COMMENCING,06-Jul-2026')>-1);
  ok('first week ends Sun 12-Jul', csv.indexOf('WEEK ENDING,12-Jul-2026')>-1);
  ok('second week commences Mon 13-Jul', csv.indexOf('WEEK COMMENCING,13-Jul-2026')>-1);
  ok('vessel name from the Ship tab', csv.indexOf('VESSEL NAME,GAS PLANET')>-1,
     lines.find(l=>l.indexOf('VESSEL NAME')>-1));
  ok('un-ticked job excluded', csv.indexOf('Not in the report')===-1);
  ok('pending WR job still listed and marked',
     csv.indexOf('Still outstanding')>-1 && /Still outstanding,NOT YET COMPLETED/.test(csv));
  ok('RA carried', /Scuppers weekly inspection,,YES/.test(csv), lines.find(l=>l.indexOf('Scuppers')>-1));
  ok('permit type carried', /Bilge alarms weekly test,,NO,COLD WORK/.test(csv), lines.find(l=>l.indexOf('Bilge')>-1));
  ok('remarks carried', csv.indexOf('All hoses in date')>-1);
  const week1 = csv.split('WORK DONE REPORT')[1];
  const nums = (week1.match(/^\d+,/gm)||[]).map(x=>parseInt(x));
  ok('numbering restarts each week at 1', nums[0]===1, JSON.stringify(nums));
  ok('four rows in the first week (incl. the pending one)', nums.length===4, nums.length);
  ok('no JS errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
