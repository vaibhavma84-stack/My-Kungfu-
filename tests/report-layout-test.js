// Builds a week with enough photographs to run over several pages, prints it to
// a real PDF, then hands it to report-layout-check.py. The layout rules — a
// repeating header, statements alone on page one, four photographs a page —
// only exist on paper, so nothing on screen can prove them.
const { chromium } = require('playwright-core');
const http=require('http'), fs=require('fs'), path=require('path');
const { execFileSync } = require('child_process');
const OUTDIR = process.env.OUT || fs.mkdtempSync('/tmp/replayout-');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+(x===undefined?'':x))); if(!c)fails++;};
(async()=>{
  const APP = process.env.APP_HTML || path.join(__dirname,'..','index.html');
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(APP));}).listen(8770);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const p=await (await b.newContext({viewport:{width:900,height:1000}})).newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  p.on('dialog',async d=>await d.accept());
  await p.goto('http://localhost:8770/');

  const jobs = ['Final coat paint application of main mast carried out.',
                'Derusting of main deck pipeline supports in progress.',
                'Mooring winches covered with canvas.',
                'Weekly bilge alarms tried out.'];
  for (const j of jobs){
    await p.fill('#inJob', j); await p.fill('#inDue','2026-08-19');
    await p.click('#inWeekly'); await p.click('#addBtn');
  }
  // Three photographs each, every one a different colour. Identical images are
  // stored once in a PDF, so reusing one picture would make four copies look
  // like a single image and hide a miscount.
  await p.evaluate(()=>{
    const shot = (hue, label) => {
      const c=document.createElement('canvas'); c.width=240; c.height=180;
      const g=c.getContext('2d');
      g.fillStyle='hsl('+hue+',65%,55%)'; g.fillRect(0,0,240,180);
      g.fillStyle='#fff'; g.font='bold 28px sans-serif'; g.fillText(label,16,100);
      return c.toDataURL('image/jpeg', 0.7);
    };
    const t=JSON.parse(localStorage.getItem('gasplanet_todo_v1'));
    // Deliberately uneven: 3, 3, 3 and 1 photographs give seven pairs, so the
    // last page holds a single pair. A page that is completely full looks the
    // same centred or not, so a run of full pages cannot test centring at all.
    const counts = [3, 3, 3, 1];
    t.forEach((j,n)=>{
      j.done=true; j.dateCompleted='2026-08-19';
      j.photos=[]; for(let k=0;k<counts[n];k++) j.photos.push(shot(n*70+k*20, (n+1)+'-'+(k+1)));
    });
    localStorage.setItem('gasplanet_todo_v1', JSON.stringify(t));
  });
  await p.reload(); await p.waitForTimeout(300);
  await p.evaluate(()=>{
    const Real=Date; const fixed=new Real(2026,7,23,12,0,0);
    class F extends Real{ constructor(...a){ return a.length?new Real(...a):new Real(fixed);} static now(){return fixed.getTime();} }
    window.Date=F;
  });
  await p.click('#weeklyReportBtn'); await p.waitForTimeout(400);

  // on-screen structure
  ok('the letterhead sits in a table head, which is what repeats when printing',
     await p.locator('table.rv-doc > thead .rv-letterhead').count()===1);
  const sheets = await p.locator('tr.rv-sheet').count();
  ok('the report is built as page sheets', sheets>=2, sheets);
  ok('the first sheet holds the statements', await p.locator('tr.rv-sheet').first().locator('.rv-list').count()===1);
  ok('the first sheet holds no photographs',
     await p.locator('tr.rv-sheet').first().locator('.rv-block').count()===0);
  // Two captioned pairs to a page, so at most four photographs. A job with an
  // odd number of photos leaves the second half of its last pair empty rather
  // than borrowing a photo from the next job, which would put a photograph
  // under someone else's caption.
  const perSheet = await p.locator('tr.rv-sheet').nth(1).locator('.rv-imgs img').count();
  ok('a photo sheet never exceeds four photographs', perSheet<=4, perSheet);
  ok('a photo sheet holds two captioned pairs',
     await p.locator('tr.rv-sheet').nth(1).locator('.rv-cap').count()===2);
  const everySheet = await p.locator('tr.rv-sheet').evaluateAll(rows =>
    rows.slice(1).map(r => r.querySelectorAll('.rv-imgs img').length));
  ok('no page anywhere exceeds four', everySheet.every(n=>n<=4), everySheet);

  // ---- arranging ----
  ok('arrange controls are hidden until asked for', await p.locator('.rv-ctl').count()===0);
  await p.click('[data-rv="arrange"]');
  await p.waitForTimeout(250);
  ok('arrange mode shows photo controls', await p.locator('.rv-ctl').count()>0);
  ok('and job controls', await p.locator('[data-rj]').count()>0);

  const firstCap = async () => (await p.locator('.rv-cap').first().textContent()).trim();
  const firstItem = async () => (await p.locator('.rv-list li').first().textContent()).trim();
  const capBefore = await firstCap();
  // move the leading job down, which is the one move that must change what
  // leads — moving a middle job up would not
  await p.locator('.rv-list li').first().locator('[data-rj="down"]').click();
  await p.waitForTimeout(250);
  ok('moving the leading job down changes which job leads the photos',
     (await firstCap()) !== capBefore, capBefore + ' -> ' + await firstCap());
  ok('the statement list is reordered to match',
     (await firstItem()).indexOf(await firstCap()) === 0,
     (await firstItem()) + ' vs ' + await firstCap());

  // photo order is stored on the job, so it must survive into localStorage
  const before = await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1'))
    .find(t=>/Derusting/.test(t.job)).photos.length);
  await p.locator('.rv-block', { hasText:'Derusting' }).first().locator('[data-rp="right"]').first().click();
  await p.waitForTimeout(250);
  ok('moving a photo keeps every photo', await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1'))
    .find(t=>/Derusting/.test(t.job)).photos.length)===before, before);

  await p.click('[data-rv="arrange"]');
  await p.waitForTimeout(250);
  ok('leaving arrange mode hides the controls again', await p.locator('.rv-ctl').count()===0);

  // the chosen order is remembered for that week
  await p.reload(); await p.waitForTimeout(300);
  await p.evaluate(()=>{
    const Real=Date; const fixed=new Real(2026,7,23,12,0,0);
    class F extends Real{ constructor(...a){ return a.length?new Real(...a):new Real(fixed);} static now(){return fixed.getTime();} }
    window.Date=F;
  });
  await p.click('#weeklyReportBtn'); await p.waitForTimeout(400);
  ok('the arranged order is remembered', (await firstCap()) !== capBefore, await firstCap());

  // ---- the real proof ----
  await p.emulateMedia({media:'print'});
  const pdfPath = OUTDIR+'/report-layout.pdf';
  await p.pdf({ path: pdfPath, format:'A4', printBackground:true,
                margin:{top:'12mm',bottom:'12mm',left:'12mm',right:'12mm'} });
  ok('no JS errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();

  console.log('\n  -- printed PDF --');
  try {
    console.log(execFileSync('python3', [__dirname+'/report-layout-check.py', pdfPath],
                             {encoding:'utf8'}));
  } catch (e) {
    console.log(e.stdout || '');
    fails++;
  }
  console.log(fails? fails+' FAILED' : 'ALL PASS');
  process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
