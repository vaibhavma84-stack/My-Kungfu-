const { chromium } = require('playwright-core');
const http=require('http'), fs=require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
(async()=>{
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(process.env.APP_HTML));}).listen(8765);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const p=await (await b.newContext({viewport:{width:900,height:1000}})).newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  p.on('dialog',async d=>await d.accept());
  await p.goto('http://localhost:8765/');

  // a week of work with photos, so the report runs to more than one page
  const px = 'data:image/jpeg;base64,' + fs.readFileSync(__dirname+'/pic.jpg').toString('base64');
  const jobs = ['Final coat Paint Application of Main Mast carried out.',
                'Derusting of Main Deck Pipeline Support in progress.',
                'Mooring Winches covered with canvas.',
                'Weekly Bilge Alarms tried out.'];
  for (const j of jobs){
    await p.fill('#inJob', j); await p.fill('#inDue','2026-08-19');
    await p.click('#inWeekly'); await p.click('#addBtn');
  }
  await p.selectOption('#filterStatus','all');
  await p.evaluate((photo)=>{
    const t=JSON.parse(localStorage.getItem('gasplanet_todo_v1'));
    t.forEach(j=>{ j.done=true; j.dateCompleted='2026-08-19'; j.photos=[photo,photo,photo]; });
    localStorage.setItem('gasplanet_todo_v1', JSON.stringify(t));
  }, px);
  await p.reload(); await p.waitForTimeout(300);

  await p.evaluate(()=>{
    const Real=Date; const fixed=new Real(2026,7,23,12,0,0);
    class F extends Real{ constructor(...a){ return a.length?new Real(...a):new Real(fixed);} static now(){return fixed.getTime();} }
    window.Date=F;
  });
  await p.click('#weeklyReportBtn'); await p.waitForTimeout(400);

  ok('letterhead rendered', (await p.locator('.rv-letterhead').count())===1);
  const logo = await p.evaluate(()=>{
    const i=document.querySelector('.lh-logo img');
    return i ? {w:i.naturalWidth, h:i.naturalHeight, shown:i.getBoundingClientRect().width} : null;
  });
  ok('logo actually decoded (168x162 from the template)',
     logo && logo.w===168 && logo.h===162, JSON.stringify(logo));
  ok('logo drawn at a sensible size', logo && logo.shown>50 && logo.shown<110, logo && logo.shown);
  ok('title still centred beside it', (await p.textContent('.rv-title'))==='WEEKLY WORK DONE REPORT');

  // the real proof: print it
  await p.emulateMedia({media:'print'});
  const pdf = await p.pdf({ path: process.env.SP+'/report.pdf', format:'A4',
                            printBackground:true, margin:{top:'12mm',bottom:'12mm',left:'12mm',right:'12mm'} });
  const bytes = fs.statSync(process.env.SP+'/report.pdf').size;
  const raw = fs.readFileSync(process.env.SP+'/report.pdf');
  const pages = (raw.toString('latin1').match(/\/Type\s*\/Page[^s]/g)||[]).length;
  ok('a PDF was produced', bytes>5000, bytes+' bytes');
  ok('it runs to more than one page', pages>1, pages+' pages');
  console.log(`        PDF: ${Math.round(bytes/1024)} KB, ${pages} pages`);
  ok('no JS errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
