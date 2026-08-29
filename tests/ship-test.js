const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
(async () => {
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(process.env.APP_HTML));}).listen(8743);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const ctx=await b.newContext({viewport:{width:390,height:844},acceptDownloads:true});
  const p=await ctx.newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  const dialogs=[]; p.on('dialog',async d=>{dialogs.push(d.message()); await d.accept();});
  await p.goto('http://localhost:8743/');

  ok('six tabs now', (await p.locator('#topTabs button').count())===6);
  await p.click('#topTabs button[data-tab="ship"]');
  ok('ship section shows', await p.isVisible('#shipSection'));
  ok('other sections hidden', !(await p.isVisible('#jobsSection')) && !(await p.isVisible('#cargoSection')));
  ok('header title switches', (await p.textContent('#pageTitle')).indexOf('Particulars')>-1, await p.textContent('#pageTitle'));

  // prefilled from the PDF
  ok('vessel name prefilled', (await p.inputValue('input[data-ship="name"]')).indexOf('GAS PLANET')>-1,
     await p.inputValue('input[data-ship="name"]'));
  for (const [f,v] of [['imo','9889552'],['callSign','3EZC5'],['mmsi','355437000'],['flag','PANAMA'],
                       ['officialNo','51758-20'],['loa','229.90'],['breadth','37.20'],['grt','49231'],
                       ['dwt','54997'],['cargoTanks','4'],['minCargoTemp','-46.0']]) {
    ok(`${f} = ${v}`, (await p.inputValue(`input[data-ship="${f}"]`))===v, await p.inputValue(`input[data-ship="${f}"]`));
  }
  ok('hero shows IMO and call sign',
     (await p.textContent('.ship-hero')).indexOf('9889552')>-1 && (await p.textContent('.ship-hero')).indexOf('3EZC5')>-1);
  ok('all seven groups render', (await p.locator('.ship-group').count())===7, await p.locator('.ship-group').count());

  // editing persists
  await p.fill('input[data-ship="portReg"]','SINGAPORE');
  await p.locator('input[data-ship="imo"]').click();   // fire change
  ok('edits persist to storage',
     await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_ship_v1')).portReg)==='SINGAPORE');
  await p.reload();
  await p.click('#topTabs button[data-tab="ship"]');
  ok('edit survives reload', (await p.inputValue('input[data-ship="portReg"]'))==='SINGAPORE');

  // the paste parser, on text shaped like a real particulars sheet
  dialogs.length=0;
  await p.fill('#shipPaste', `VESSEL PARTICULARS
LPG 'GAS BREEZE'
CALL SIGN  3FAB7
FLAG  PANAMA
PORT OF REGISTRY  PANAMA
OFFICIAL NUMBER  51999-21
IMO / LLOYD'S NUMBER  9812345
MMSI  355999000
LOA  230.50
LBP  227.00
BREADTH (MOULDED)  37.40
GROSS  49500
NET  14900
DWT  55200
FWA (mm)  255
TPC  74.1
E-MAIL  gasbreeze@synergypacificship.com`);
  await p.click('#shipParseBtn');
  ok('parser reports what it filled', dialogs.some(d=>/Filled \d+ field/.test(d)), JSON.stringify(dialogs).slice(0,200));
  for (const [f,v] of [['name','GAS BREEZE'],['imo','9812345'],['callSign','3FAB7'],['mmsi','355999000'],
                       ['officialNo','51999-21'],['loa','230.50'],['lbp','227.00'],['breadth','37.40'],
                       ['grt','49500'],['dwt','55200'],['fwa','255'],['tpc','74.1'],
                       ['email','gasbreeze@synergypacificship.com']]) {
    ok(`parsed ${f} = ${v}`, (await p.inputValue(`input[data-ship="${f}"]`))===v, await p.inputValue(`input[data-ship="${f}"]`));
  }
  ok('hero updates to the new ship', (await p.textContent('.ship-hero')).indexOf('GAS BREEZE')>-1);

  // vessel name flows into the AD-19 export
  await p.click('#topTabs button[data-tab="jobs"]');
  await p.fill('#inJob','Test job'); await p.click('#inAd34'); await p.click('#addBtn');
  const dl=p.waitForEvent('download'); await p.click('#exportAd19Btn');
  const f=await dl; await f.saveAs(process.env.OUT+'/ship-export.csv');
  const csv=fs.readFileSync(process.env.OUT+'/ship-export.csv','utf8');
  ok('export header carries the ship from the Ship tab', csv.indexOf('VESSEL NAME,GAS BREEZE')>-1,
     csv.split('\n').find(l=>l.indexOf('VESSEL NAME')>-1));

  // new ship clears it
  await p.click('#topTabs button[data-tab="ship"]');
  await p.click('#shipResetBtn');
  ok('New ship clears the fields', (await p.inputValue('input[data-ship="imo"]'))==='');
  ok('jobs are untouched by New ship',
     await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_todo_v1')).length)===1);

  ok('no JS errors', errs.length===0, errs.join(' | '));
  await p.click('#topTabs button[data-tab="ship"]');
  await p.screenshot({path:process.env.OUT+'/shot-ship.png', fullPage:false});
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
