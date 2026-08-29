// The Instruments tool. It ships empty on purpose — the content comes from the
// maker's manual — so what is tested is that the structure holds what is typed
// into it, and that nothing is pre-filled.
const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs'); const path = require('path');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+(x===undefined?'':x))); if(!c)fails++;};
(async () => {
  const APP = process.env.APP_HTML || path.join(__dirname,'..','index.html');
  const OUT = process.env.OUT || fs.mkdtempSync('/tmp/instr-');
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(APP));}).listen(8768);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const ctx=await b.newContext({viewport:{width:412,height:900},acceptDownloads:true});
  const p=await ctx.newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));

  // prompts and confirms are answered from a queue
  let answers = [];
  p.on('dialog', async d => {
    if(d.type()==='prompt') await d.accept(answers.length ? answers.shift() : '');
    else await d.accept();
  });
  await p.goto('http://localhost:8768/');

  await p.click('#topTabs button[data-tab="tools"]');
  ok('Instruments tile present', await p.locator('[data-tool="instr"]').count()===1);
  await p.click('[data-tool="instr"]');
  ok('opens', await p.locator('#toolInstr').isVisible());
  ok('starts empty — nothing is shipped pre-filled',
     /No instruments yet/.test(await p.textContent('#instrList')));

  await p.fill('#inInstrModel','Riken Keiki GX-8000');
  await p.fill('#inInstrSerial','A123456');
  await p.fill('#inInstrLoc','CCR');
  await p.click('#instrAddBtn');
  await p.waitForTimeout(150);
  const card = p.locator('.instr-card');
  ok('the instrument is listed', await card.count()===1);
  ok('serial shown', /A123456/.test(await card.textContent()));
  ok('no interval set reads as such', /no interval set/.test(await card.textContent()));

  await card.click();
  await p.waitForTimeout(150);
  ok('detail opens', await p.locator('#instrDetailView').isVisible());
  ok('title becomes the instrument', (await p.textContent('#instrTitle')).trim()==='Riken Keiki GX-8000');
  ok('bump vs calibration is explained', /bump test only confirms/i.test(await p.textContent('#instrDetail')));
  ok('it says the numbers come from the manual', /maker's manual/i.test(await p.textContent('#instrDetail')));

  // ---- intervals drive the due state ----
  await p.fill('#fInstrSensors','LEL / O2 / H2S / CO');
  await p.fill('#fCalDays','180');
  await p.waitForTimeout(120);
  await p.fill('#fLastCal','2020-01-01');
  await p.waitForTimeout(150);
  ok('a long-past calibration reads overdue',
     await p.locator('#instrDetail .due-over').count()>=1);
  const today = new Date();
  const iso = d => d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');
  await p.fill('#fBumpDays','30');
  await p.waitForTimeout(120);
  await p.fill('#fLastBump', iso(new Date(today.getTime()-27*864e5)));
  await p.waitForTimeout(150);
  ok('a test due within the week reads as due soon',
     await p.locator('#instrDetail .due-soon').count()>=1,
     await p.textContent('#instrDetail .isec-h'));
  await p.fill('#fLastBump', iso(today));
  await p.waitForTimeout(150);
  ok('a test done today reads as in date',
     await p.locator('#instrDetail .due-ok').count()>=1);

  // ---- a procedure with steps ----
  answers = ['Changing the H2S alarm setpoint'];
  await p.click('[data-ia="add-proc"]');
  await p.waitForTimeout(150);
  ok('procedure added', /Changing the H2S alarm setpoint/.test(await p.textContent('#instrDetail')));
  ok('a new procedure has no steps', /No steps yet/.test(await p.textContent('#instrDetail')));

  answers = ['Hold the power key until the display lights.'];
  await p.click('[data-ia="add-step"]');
  await p.waitForTimeout(150);
  answers = ['Enter the maintenance menu as the manual describes.'];
  await p.click('[data-ia="add-step"]');
  await p.waitForTimeout(150);
  ok('two steps recorded', await p.locator('#instrDetail .step-n').count()===2);
  ok('steps are numbered from one',
     (await p.locator('#instrDetail .step-n').first().textContent()).trim()==='1');
  ok('the step text is kept', /Hold the power key/.test(await p.textContent('#instrDetail')));

  // a photograph on a step
  await p.locator('[data-ia="step-photo"]').first().click();
  await p.setInputFiles('#instrPhotoInput', path.join(OUT,'pic.jpg'));
  await p.waitForTimeout(600);
  ok('a photo attaches to the step', await p.locator('#instrDetail .thumb img').count()>=1,
     await p.locator('#instrDetail .thumb img').count());

  // ---- spares ----
  answers = ['H2S sensor','ESH-A1DP','2'];
  await p.click('[data-ia="add-part"]');
  await p.waitForTimeout(150);
  ok('spare part recorded', /H2S sensor/.test(await p.textContent('#instrDetail')));
  ok('part number kept exactly', /ESH-A1DP/.test(await p.textContent('#instrDetail')));
  ok('quantity held shown', /2 held/.test(await p.textContent('#instrDetail')));

  // ---- log, and logging a test moves its due date ----
  answers = ['Calibration','2026-08-20','Pass','CH4 50%LEL / H2S 25ppm','Span gas from cylinder 4471'];
  await p.click('[data-ia="add-log"]');
  await p.waitForTimeout(200);
  ok('log entry recorded', /Span gas from cylinder 4471/.test(await p.textContent('#instrDetail')));
  ok('logging a calibration moves the last-calibration date',
     (await p.inputValue('#fLastCal'))==='2026-08-20', await p.inputValue('#fLastCal'));

  // ---- it survives a reload ----
  await p.reload();
  await p.waitForTimeout(250);
  await p.click('#topTabs button[data-tab="tools"]');
  await p.click('[data-tool="instr"]');
  await p.waitForTimeout(150);
  ok('the instrument survives a reload', await p.locator('.instr-card').count()===1);
  ok('and its counts are right',
     /1 procedure/.test(await p.textContent('.instr-card')) && /1 spare/.test(await p.textContent('.instr-card')),
     await p.textContent('.instr-card'));
  await p.locator('.instr-card').click();
  await p.waitForTimeout(150);
  ok('the steps survive too', await p.locator('#instrDetail .step-n').count()===2);
  ok('the photo survives too', await p.locator('#instrDetail .thumb img').count()>=1);

  await p.click('#instrListBtn');
  ok('back to the list', await p.locator('#instrListView').isVisible());
  await p.click('#instrBackBtn');
  ok('back to the launcher', await p.locator('#toolsHome').isVisible());

  ok('no page errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails? '\n'+fails+' FAILED' : '\nALL PASS');
  process.exit(fails?1:0);
})();
