const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs');
(async () => {
  const srv = http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(process.env.APP_HTML));}).listen(8740);
  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext({ viewport:{width:900,height:900}, acceptDownloads:true });
  const p = await ctx.newPage();
  p.on('dialog', async d=>await d.accept());
  await p.goto('http://localhost:8740/');
  // three AD-19 jobs: two share a date, one on another date
  const add = async (job, due) => {
    await p.fill('#inJob', job); await p.fill('#inDue', due);
    await p.click('#inAd34'); await p.click('#addBtn');
  };
  await add('Air hoses weekly inspection','2026-07-04');
  await add('Scuppers weekly inspection','2026-07-04');
  await add('DWT ventilation','2026-07-05');
  const dl = p.waitForEvent('download');
  await p.click('#exportAd19Btn');
  await (await dl).saveAs(process.env.OUT + '/ad19-marsec.csv');
  await b.close(); srv.close(); console.log('exported');
})();
