// Cargo quantity. The tool ships with no density figures at all — the point of
// these checks is that it works from the ship's own rows and refuses to invent
// anything outside them.
const { chromium } = require('playwright-core');
const http=require('http'), fs=require('fs'), path=require('path');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+(x===undefined?'':x))); if(!c)fails++;};
(async()=>{
  const APP = process.env.APP_HTML || path.join(__dirname,'..','index.html');
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(APP));}).listen(8773);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const p=await (await b.newContext({viewport:{width:412,height:900}})).newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  p.on('dialog',async d=>await d.accept());
  await p.goto('http://localhost:8773/');

  await p.click('#topTabs button[data-tab="tools"]');
  ok('the tile is there', await p.locator('[data-tool="lpg"]').count()===1);
  await p.click('[data-tool="lpg"]');
  ok('it opens', await p.locator('#toolLpg').isVisible());
  ok('it says the tables are not built in',
     /copyrighted/.test(await p.textContent('#lpgIntro')));
  ok('propane and butane are offered, empty',
     (await p.locator('#lpgProduct option').allTextContents()).join(',')==='Propane,Butane');
  ok('and it says so rather than pretending to know a density',
     /No density table for Propane/.test(await p.textContent('#lpgDensity')));

  // enter rows the way the ship would, from its own copy
  await p.click('#lpgEditRows');
  await p.waitForTimeout(200);
  ok('the table editor opens', await p.locator('#lpgPaste').count()===1);
  await p.fill('#lpgPaste', '-45, 0.5860\n-40, 0.5800\n-30, 0.5690\n15, 0.5077');
  await p.click('[data-lp="add"]');
  await p.waitForTimeout(200);
  ok('four rows accepted', (await p.locator('#lpgRowList .conv-row').count())===4);
  await p.click('[data-lp="save"]');
  await p.waitForTimeout(250);

  // a temperature that sits exactly on a row
  await p.fill('#lpgTemp','15');
  await p.waitForTimeout(200);
  const d15 = await p.textContent('#lpgDensity');
  ok('an exact row is read straight off, not interpolated',
     /0\.5077 t\/m/.test(d15) && /straight off the table/.test(d15), d15.replace(/\s+/g,' ').slice(0,110));
  // 6.28981077 / 0.5077 = 12.388833...
  ok('barrels per tonne computed from that density', /12\.38883/.test(d15), d15.replace(/\s+/g,' ').slice(0,160));

  // one that has to be interpolated: halfway between -40 and -30
  await p.fill('#lpgTemp','-35');
  await p.waitForTimeout(200);
  const d35 = await p.textContent('#lpgDensity');
  ok('a value between rows is interpolated', /interpolated between -40 and -30/.test(d35), d35.replace(/\s+/g,' ').slice(0,120));
  ok('and the midpoint is right', /0\.5745 t\/m/.test(d35), d35.replace(/\s+/g,' ').slice(0,120));

  // outside the entered rows it must refuse
  await p.fill('#lpgTemp','-60');
  await p.waitForTimeout(200);
  const dOut = await p.textContent('#lpgDensity');
  ok('below the table it refuses rather than extrapolating', /Outside the table/.test(dOut));
  ok('and names the range it does have', /-45 to 15/.test(dOut), dOut.replace(/\s+/g,' ').slice(0,140));
  await p.fill('#lpgTemp','40');
  await p.waitForTimeout(200);
  ok('above the table too', /Outside the table/.test(await p.textContent('#lpgDensity')));
  ok('and nothing is calculated while outside',
     (await p.textContent('#lpgOut')).trim()==='');

  // the conversion itself
  await p.fill('#lpgTemp','15');
  await p.fill('#lpgMT','1000');
  await p.waitForTimeout(250);
  const m3 = parseFloat(await p.inputValue('#lpgM3'));
  const bbl = parseFloat(await p.inputValue('#lpgBbl'));
  const gal = parseFloat(await p.inputValue('#lpgGal'));
  ok('1000 MT gives 1969.67 m3', Math.abs(m3-1969.667)<0.05, m3);
  ok('and 12388.7 barrels', Math.abs(bbl-12388.72)<0.5, bbl);
  ok('and 42 US gallons to the barrel', Math.abs(gal-bbl*42)<1, gal+' vs '+bbl*42);

  // driving it from the other end
  await p.fill('#lpgMT','');
  await p.fill('#lpgBbl','12388.72');
  await p.waitForTimeout(250);
  ok('entering barrels gives the tonnes back',
     Math.abs(parseFloat(await p.inputValue('#lpgMT'))-1000)<0.5, await p.inputValue('#lpgMT'));

  // a second product keeps its own table
  await p.selectOption('#lpgProduct', { label: 'Butane' });
  await p.waitForTimeout(200);
  ok('butane has its own, still empty, table',
     /No density table for Butane/.test(await p.textContent('#lpgDensity')));
  await p.selectOption('#lpgProduct', { label: 'Propane' });
  await p.waitForTimeout(200);
  await p.fill('#lpgTemp','15');
  await p.waitForTimeout(200);
  ok('propane still has its rows', /0\.5077/.test(await p.textContent('#lpgDensity')));

  // and it survives a restart
  await p.reload(); await p.waitForTimeout(400);
  await p.click('#topTabs button[data-tab="tools"]');
  await p.click('[data-tool="lpg"]');
  await p.fill('#lpgTemp','-30');
  await p.waitForTimeout(200);
  ok('the table survives a reload', /0\.569 t\/m/.test(await p.textContent('#lpgDensity')),
     (await p.textContent('#lpgDensity')).replace(/\s+/g,' ').slice(0,110));

  ok('no page errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails? '\n'+fails+' FAILED' : '\nALL PASS');
  process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
