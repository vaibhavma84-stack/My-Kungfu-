const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
(async () => {
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(process.env.APP_HTML));}).listen(8747);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const p=await (await b.newContext({viewport:{width:420,height:900}})).newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  p.on('dialog',async d=>await d.accept());
  await p.goto('http://localhost:8747/');
  const PIC = process.env.OUT + '/pic.jpg';

  await p.click('#topTabs button[data-tab="extra"]');

  // photo attached at creation
  await p.fill('#exJob','Renew galley exhaust gasket');
  await p.fill('#exDate','2026-07-09');
  await p.selectOption('#exPtw','HOT WORK');
  await p.setInputFiles('#exPhotoInput', PIC);
  await p.waitForTimeout(600);
  ok('pending thumbnail shows before adding', (await p.locator('#exPendingThumbs .thumb').count())===1);
  await p.click('#exAddBtn');
  await p.waitForTimeout(400);
  ok('job added with its photo',
     await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_extra_v1'))[0].photos.length)===1);
  ok('pending strip cleared', (await p.locator('#exPendingThumbs .thumb').count())===0);
  ok('thumbnail shows on the row', (await p.locator('.extra-row .thumb').count())===1);
  ok('photo count badge on the row', (await p.textContent('.extra-row .photo-count'))==='1');

  // add a second photo straight from the row. The row button opens an input
  // that lives on document.body, so drive it by id — picking the last file
  // input in the document broke the moment a fourth one was added.
  await p.locator('[data-extra-action="addphoto"]').click();
  await p.waitForTimeout(150);
  await p.setInputFiles('#exRowPhotoInput', PIC);
  await p.waitForTimeout(700);
  ok('second photo added from the row',
     await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_extra_v1'))[0].photos.length)===2);

  // tapping a thumbnail opens the viewer
  await p.locator('.extra-row .thumb').first().click();
  ok('thumbnail opens the photo viewer', await p.isVisible('.modal-bg img'));
  await p.click('.modal-close');

  // edit sheet
  await p.locator('[data-extra-action="edit"]').click();
  ok('edit sheet opens', await p.isVisible('.edit-card'));
  ok('activity prefilled', (await p.inputValue('#exeJob'))==='Renew galley exhaust gasket');
  ok('date prefilled', (await p.inputValue('#exeDate'))==='2026-07-09');
  ok('permit prefilled', (await p.inputValue('#exePtw'))==='HOT WORK');
  ok('both photos listed in the sheet', (await p.locator('#exeThumbs .thumb').count())===2);

  await p.fill('#exeJob','Renew galley exhaust gasket and clips');
  await p.fill('#exeDate','2026-07-11');
  await p.selectOption('#exePtw','ENCLOSED SPACE');
  await p.fill('#exeRem','Permit closed out same day');
  await p.click('[data-exe-tg="ra"]');
  await p.locator('#exeThumbs [data-rm]').first().click();
  ok('removing a photo updates the sheet', (await p.locator('#exeThumbs .thumb').count())===1);
  await p.click('[data-exe="save"]');
  await p.waitForTimeout(300);

  const x = await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_extra_v1'))[0]);
  ok('activity saved', x.job==='Renew galley exhaust gasket and clips', x.job);
  ok('date saved', x.date==='2026-07-11', x.date);
  ok('permit saved', x.ptwType==='ENCLOSED SPACE', x.ptwType);
  ok('remarks saved', x.remarks==='Permit closed out same day', x.remarks);
  ok('RA toggled on', x.ra===true);
  ok('photo removal saved', x.photos.length===1, x.photos.length);

  // cancel does nothing
  await p.locator('[data-extra-action="edit"]').click();
  await p.fill('#exeJob','SHOULD NOT SAVE');
  await p.click('[data-exe="cancel"]');
  ok('cancel discards',
     await p.evaluate(()=>JSON.parse(localStorage.getItem('gasplanet_extra_v1'))[0].job!=='SHOULD NOT SAVE'));

  // edits reach the export
  await p.click('#topTabs button[data-tab="jobs"]');
  const dl=p.waitForEvent('download'); await p.click('#exportAd19Btn');
  await (await dl).saveAs(process.env.OUT+'/exedit.csv');
  const csv=fs.readFileSync(process.env.OUT+'/exedit.csv','utf8');
  ok('export carries the edited activity and date',
     csv.indexOf('Renew galley exhaust gasket and clips')>-1 && csv.indexOf('11-Jul-2026')>-1);
  ok('export carries the edited permit', csv.indexOf('ENCLOSED SPACE')>-1);
  ok('no JS errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
