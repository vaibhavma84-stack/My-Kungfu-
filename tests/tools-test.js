// The Tools tab and its converter. Conversion factors are checked against
// independent references by check_factors.py; this suite checks that the page
// applies them, formats them, and remembers where you were.
const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs'); const path = require('path');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+(x===undefined?'':x))); if(!c)fails++;};
(async () => {
  const APP = process.env.APP_HTML || path.join(__dirname,'..','index.html');
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(APP));}).listen(8763);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const ctx=await b.newContext({viewport:{width:412,height:900},permissions:['clipboard-read','clipboard-write']});
  const p=await ctx.newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  p.on('dialog',async d=>await d.accept());
  await p.goto('http://localhost:8763/');

  // ---- the tab and its launcher ----
  ok('Tools tab present', await p.locator('#topTabs button[data-tab="tools"]').count()===1);
  await p.click('#topTabs button[data-tab="tools"]');
  ok('Tools section shows', await p.locator('#toolsSection').isVisible());
  ok('launcher shows, converter does not',
     await p.locator('#toolsHome').isVisible() && !(await p.locator('#toolConvert').isVisible()));
  ok('converter tile has a drawn icon, not an emoji',
     await p.locator('[data-tool="convert"] svg').count()===1);
  ok('four live tools on the launcher',
     await p.locator('.tool-tile[data-tool]').count()===4,
     await p.locator('.tool-tile[data-tool]').count());
  ok('no placeholder left, every tile does something',
     await p.locator('.tool-tile.soon').count()===0);

  await p.click('[data-tool="convert"]');
  ok('converter opens', await p.locator('#toolConvert').isVisible());
  ok('title changes to Converter', (await p.textContent('#pageTitle')).trim()==='Converter');
  ok('empty until a value is entered', /Enter a value/.test(await p.textContent('#convOut')));

  // ---- reading one unit's row out of the list ----
  const val = async (label) => {
    const row = p.locator('.conv-row', { hasText: label }).first();
    return (await row.locator('.cv').textContent()).trim();
  };
  const setUp = async (cat, unit, v) => {
    await p.click(`#convCats button[data-cat="${cat}"]`);
    await p.selectOption('#convUnit', unit);
    await p.fill('#convValue', String(v));
    await p.waitForTimeout(80);
  };

  // ---- pressure ----
  await setUp('pressure','bar',1);
  ok('1 bar = 14.50377 psi',        (await val('psi (lbf/in²)'))==='14.50377', await val('psi (lbf/in²)'));
  ok('1 bar = 0.1 MPa',             (await val('megapascal'))==='0.1', await val('megapascal'));
  ok('1 bar = 1000 mbar',           (await val('mbar'))==='1 000', await val('mbar'));
  ok('1 bar = 750.0616 mmHg',       (await val('mmHg'))==='750.0616', await val('mmHg'));
  ok('1 bar = 1.019716 kg/cm²',     (await val('kg/cm²'))==='1.019716', await val('kg/cm²'));
  ok('1 bar = 10.19716 mWC',        (await val('metres water'))==='10.19716', await val('metres water'));
  ok('the input unit is marked',    await p.locator('.conv-row.self', {hasText:'bar'}).count()>=1);
  ok('pressure warns about gauge vs absolute', /gauge/i.test(await p.textContent('#convNote')));

  await setUp('pressure','psi',14.6959488);   // one standard atmosphere
  ok('1 atm in psi comes back to 1 atm', (await val('atmosphere'))==='1', await val('atmosphere'));

  // ---- temperature, the one with offsets ----
  await setUp('temperature','C',100);
  ok('100 °C = 212 °F',    (await val('Fahrenheit'))==='212', await val('Fahrenheit'));
  ok('100 °C = 373.15 K',  (await val('Kelvin'))==='373.15', await val('Kelvin'));
  await setUp('temperature','C',-40);
  ok('-40 °C = -40 °F',    (await val('Fahrenheit'))==='-40', await val('Fahrenheit'));
  ok('a negative value can be entered at all', (await p.inputValue('#convValue'))==='-40');
  await setUp('temperature','C',0);
  ok('0 °C = 273.15 K',    (await val('Kelvin'))==='273.15', await val('Kelvin'));
  ok('0 °C = 491.67 °R',   (await val('Rankine'))==='491.67', await val('Rankine'));
  await setUp('temperature','K',0);
  ok('0 K = -273.15 °C',   (await val('Celsius'))==='-273.15', await val('Celsius'));
  await setUp('temperature','F',-42.7);        // a propane cargo temperature
  ok('-42.7 °F = -41.5 °C', (await val('Celsius'))==='-41.5', await val('Celsius'));

  // ---- energy ----
  await setUp('energy','kWh',1);
  ok('1 kWh = 3600 kJ',      (await val('kilojoule'))==='3 600', await val('kilojoule'));
  ok('1 kWh = 3412.142 BTU', (await val('BTU')).startsWith('3 412.14'), await val('BTU'));
  await setUp('energy','MMBTU',1);
  ok('1 MMBTU = 1.055056 GJ', (await val('gigajoule'))==='1.055056', await val('gigajoule'));
  await setUp('energy','therm',10);
  ok('10 therm = 1 MMBTU',    (await val('MMBTU'))==='1', await val('MMBTU'));

  // ---- volume ----
  await setUp('volume','m3',1);
  ok('1 m³ = 1000 L',            (await val('litre'))==='1 000', await val('litre'));
  ok('1 m³ = 1 000 000 mL',      (await val('millilitre'))==='1 000 000', await val('millilitre'));
  ok('1 m³ = 264.1721 US gal',   (await val('US gallon'))==='264.1721', await val('US gallon'));
  ok('1 m³ = 219.9692 imp gal',  (await val('Imperial gallon'))==='219.9692', await val('Imperial gallon'));
  ok('1 m³ = 35.31467 ft³',      (await val('cubic foot'))==='35.31467', await val('cubic foot'));
  await setUp('volume','bbl',1);
  ok('1 barrel = 158.9873 L',    (await val('litre'))==='158.9873', await val('litre'));
  ok('1 barrel = 42 US gal',     (await val('US gallon'))==='42', await val('US gallon'));

  // ---- the rest ----
  await setUp('mass','t',1);
  ok('1 tonne = 0.9842065 long ton', (await val('long ton'))==='0.9842065', await val('long ton'));
  ok('1 tonne = 2204.623 lb',        (await val('pound'))==='2 204.623', await val('pound'));
  await setUp('length','nm',1);
  ok('1 nautical mile = 1852 m', (await val('metre (m)'))==='1 852', await val('metre (m)'));
  await setUp('length','fath',1);
  ok('1 fathom = 6 ft', (await val('foot'))==='6', await val('foot'));
  await setUp('density','tm3',1);
  ok('1 t/m³ = 62.42796 lb/ft³', (await val('lb/ft³'))==='62.42796', await val('lb/ft³'));
  await setUp('speed','kn',1);
  ok('1 knot = 1.852 km/h', (await val('km/h'))==='1.852', await val('km/h'));
  await setUp('flow','m3h',100);
  ok('100 m³/h = 1.666667 m³/min', (await val('m³/min'))==='1.666667', await val('m³/min'));

  // ---- copy, and remembering where you were ----
  await setUp('pressure','bar',1);
  await p.locator('.conv-row', {hasText:'psi'}).first().click();
  await p.waitForTimeout(150);
  let clip = '';
  try { clip = await p.evaluate(()=>navigator.clipboard.readText()); } catch(e) { clip = 'n/a'; }
  ok('tapping a line copies it', clip==='14.50377' || clip==='n/a', clip);

  await setUp('energy','BTU',500);
  await p.reload();
  await p.waitForTimeout(200);
  await p.click('#topTabs button[data-tab="tools"]');
  await p.click('[data-tool="convert"]');
  ok('reopens on the last category', await p.locator('#convCats button.active').textContent()==='Energy');
  ok('reopens on the last unit',  (await p.inputValue('#convUnit'))==='BTU');
  ok('reopens with the last value', (await p.inputValue('#convValue'))==='500');

  // ---- leaving and coming back lands on the launcher ----
  await p.click('#topTabs button[data-tab="jobs"]');
  await p.click('#topTabs button[data-tab="tools"]');
  ok('returns to the launcher, not the last tool',
     await p.locator('#toolsHome').isVisible() && !(await p.locator('#toolConvert').isVisible()));
  ok('title back to Tools', (await p.textContent('#pageTitle')).trim()==='Tools');
  await p.click('[data-tool="convert"]');
  await p.click('#toolBackBtn');
  ok('back button returns to the launcher', await p.locator('#toolsHome').isVisible());

  // ---- garbage in ----
  await p.click('[data-tool="convert"]');
  await p.fill('#convValue','');
  await p.waitForTimeout(80);
  ok('empty input does not print NaN', !/NaN|—/.test(await p.textContent('#convOut')));

  // ---- the working is shown, in plain arithmetic ----
  const formulaFor = async (label) =>
    (await p.locator('.conv-row', { hasText: label }).first().locator('.cf').textContent()).trim();

  await setUp('pressure','bar',1);
  ok('pressure shows the factor',   (await formulaFor('psi (lbf/in²)'))==='psi = bar × 14.50377', await formulaFor('psi (lbf/in²)'));
  ok('a factor under 1 is shown as a division',
     (await formulaFor('megapascal'))==='MPa = bar ÷ 10', await formulaFor('megapascal'));
  ok('the row for the input unit says so', (await formulaFor('bar'))==='same unit', await formulaFor('bar'));
  ok('no exponent notation in any formula',
     !/e[+-]\d/.test(await p.textContent('#convOut')));
  // unit names legitimately contain a slash (kg/cm², m³/h) — what must not
  // appear is a programmer's operator standing in for × or ÷
  const allFormulas = async () =>
    (await p.locator('#convOut .cf').allTextContents()).join(' | ');
  ok('multiplication is written ×, never *', !/\*/.test(await allFormulas()));
  ok('no caret exponents',                   !/\^/.test(await allFormulas()));
  ok('division is written ÷, never a bare slash', !/ \/ /.test(await allFormulas()));

  await setUp('volume','mL',1);
  ok('a tiny factor stays readable',
     (await formulaFor('barrel'))==='bbl = mL ÷ 158 987.3', await formulaFor('barrel'));

  await setUp('temperature','C',0);
  ok('temperature is written out, not factored',
     (await formulaFor('Fahrenheit'))==='°F = (°C × 9 ÷ 5) + 32', await formulaFor('Fahrenheit'));
  ok('Kelvin formula shown', (await formulaFor('Kelvin'))==='K = °C + 273.15', await formulaFor('Kelvin'));
  await setUp('temperature','F',32);
  ok('the reverse direction is shown correctly',
     (await formulaFor('Celsius'))==='°C = (°F − 32) × 5 ÷ 9', await formulaFor('Celsius'));

  await setUp('energy','GJ',1);
  ok('a big factor is grouped, not exponential',
     (await formulaFor('joule (J)'))==='J = GJ × 1 000 000 000', await formulaFor('joule (J)'));

  // ---- the information guide ----
  // leave the tab and come back, since re-clicking the tab you are already on
  // is not what resets the launcher
  await p.click('#topTabs button[data-tab="jobs"]');
  await p.click('#topTabs button[data-tab="tools"]');
  ok('a second tile is live now', await p.locator('[data-tool="guide"]').count()===1);
  await p.click('[data-tool="guide"]');
  ok('guide opens', await p.locator('#toolGuide').isVisible());
  ok('title reads Information', (await p.textContent('#pageTitle')).trim()==='Information');
  ok('opens on a category with its intro', (await p.textContent('#guideIntro')).length>40);
  ok('lists that category\'s units',
     await p.locator('#guideOut .ug-item').count()===14, await p.locator('#guideOut .ug-item').count());
  ok('entries start closed', await p.locator('#guideOut .ug-item.open').count()===0);

  const bar = p.locator('.ug-item', { hasText:'bar' }).first();
  await bar.locator('.ug-head').click();
  await p.waitForTimeout(120);
  ok('tapping opens one', await p.locator('#guideOut .ug-item.open').count()===1);
  ok('and it says where the unit came from', /Bjerknes/.test(await bar.textContent()));
  await bar.locator('.ug-head').click();
  await p.waitForTimeout(120);
  ok('tapping again closes it', await p.locator('#guideOut .ug-item.open').count()===0);

  await p.click('#guideCats button[data-gcat="length"]');
  await p.waitForTimeout(100);
  ok('switching category switches the list',
     /fathom/i.test(await p.textContent('#guideOut')));
  await p.locator('.ug-item', { hasText:'fathom' }).first().locator('.ug-head').click();
  await p.waitForTimeout(120);
  ok('the fathom entry explains the lead line',
     /arms/.test(await p.locator('.ug-item', { hasText:'fathom' }).first().textContent()));

  // search reaches across every category
  await p.fill('#guideSearch','torricelli');
  await p.waitForTimeout(120);
  ok('search finds a unit by its history, not just its name',
     await p.locator('#guideOut .ug-item').count()>=1, await p.locator('#guideOut .ug-item').count());
  ok('search hides the category chips', !(await p.locator('#guideCats').isVisible()));
  ok('search labels which category a hit is in', await p.locator('#guideOut .ug-cat').count()>=1);
  await p.fill('#guideSearch','MMBTU');
  await p.waitForTimeout(120);
  await p.locator('#guideOut .ug-item').first().locator('.ug-head').click();
  await p.waitForTimeout(120);
  ok('the MMBTU trap is spelled out',
     /million million|not a million million/.test(await p.textContent('#guideOut')));
  await p.fill('#guideSearch','zzzz');
  await p.waitForTimeout(120);
  ok('a search with no hits says so', /Nothing matches/.test(await p.textContent('#guideOut')));
  await p.fill('#guideSearch','');
  await p.waitForTimeout(120);
  ok('clearing the search brings the chips back', await p.locator('#guideCats').isVisible());

  // ---- GX-8000 sensing principles, a category that is not units ----
  // The chip list is CONV_CATS plus the guide's own topics, and the chip click
  // used to look the key up in CONV_CATS only -- which silently fell back to
  // Pressure rather than failing, so this asserts the content, not just that
  // something rendered.
  const gxChip = p.locator('#guideCats button', { hasText:'GX-8000' });
  ok('the GX-8000 category is offered', await gxChip.count()===1);
  await gxChip.click();
  await p.waitForTimeout(120);
  ok('it does not fall back to another category',
     /five separate cells/.test(await p.textContent('#guideIntro')),
     (await p.textContent('#guideIntro')).slice(0,60));
  ok('every sensor and topic is listed',
     await p.locator('#guideOut .ug-item').count()===11,
     await p.locator('#guideOut .ug-item').count());

  const lelItem = p.locator('.ug-item', { hasText:'%LEL range' }).first();
  await lelItem.locator('.ug-head').click();
  await p.waitForTimeout(120);
  const lelText = await lelItem.textContent();
  ok('the %LEL sensor names its principle', /catalytic/i.test(lelText));
  ok('and says it needs oxygen to work', /oxygen/i.test(lelText));

  const volItem = p.locator('.ug-item', { hasText:'vol% range' }).first();
  await volItem.locator('.ug-head').click();
  await p.waitForTimeout(120);
  const volText = await volItem.textContent();
  ok('the vol% sensor is thermal conductivity, a different cell',
     /thermal conductivity/i.test(volText));
  ok('and is the one that works with no oxygen', /needs no oxygen/i.test(volText));

  // the calculation, which is the part worth getting right
  await p.fill('#guideSearch','%LEL actually means');
  await p.waitForTimeout(120);
  await p.locator('#guideOut .ug-item').first().locator('.ug-head').click();
  await p.waitForTimeout(120);
  const calc = await p.textContent('#guideOut');
  ok('the LEL formula is spelled out both ways',
     /reading in %LEL\s*=\s*\(gas present in vol%\)/.test(calc) &&
     /gas present in vol%\s*=\s*reading in %LEL/.test(calc));
  ok("and is anchored to the manual's own methane example",
     /100 %LEL is 5 vol% methane/.test(calc) && /60 %LEL is 3 vol%/.test(calc));
  ok('the published limits are marked as not being from the manual',
     /not from the RKI manual/.test(calc));

  // galvanic / electrochemical, and the documented CO behaviour
  await p.fill('#guideSearch','galvanic');
  await p.waitForTimeout(120);
  await p.locator('#guideOut .ug-item').first().locator('.ug-head').click();
  await p.waitForTimeout(120);
  ok('oxygen is a galvanic cell measuring partial pressure',
     /partial pressure/i.test(await p.textContent('#guideOut')));

  await p.fill('#guideSearch','100 %LEL, the CO reading rises');
  await p.waitForTimeout(120);
  ok('the CO cross-reading over 100 %LEL is documented',
     await p.locator('#guideOut .ug-item').count()>=1);

  await p.fill('#guideSearch','');
  await p.waitForTimeout(120);
  await p.locator('#guideCats button').first().click();
  await p.waitForTimeout(120);

  await p.click('#guideBackBtn');
  ok('back returns to the launcher', await p.locator('#toolsHome').isVisible());

  ok('no page errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails? '\n'+fails+' FAILED' : '\nALL PASS');
  process.exit(fails?1:0);
})();
