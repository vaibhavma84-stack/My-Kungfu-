// Negative numbers on a phone. The numeric keypad Android and iOS put up for a
// number field has no minus key, and type="number" will not even hold a lone
// "-", so a cargo temperature of -42 could be read back but never entered.
// Every field that can legitimately go negative is checked here.
const { chromium } = require('playwright-core');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

(async () => {
  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext();
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  p.on('dialog', async d => { await d.accept(); });
  await p.goto('file://' + process.env.APP_HTML);
  await p.waitForTimeout(600);

  // ---- the cargo log, which is where this was noticed ----
  await p.click('#topTabs button[data-tab="cargo"]');
  await p.waitForTimeout(400);
  const fields = await p.locator('#tankFieldsWrap input[data-key]').count();
  ok('every cargo field has a sign button',
     await p.locator('#tankFieldsWrap .signbtn').count() === fields && fields > 10,
     fields + ' fields');
  ok('and none is a number input, which cannot hold a bare minus',
     await p.locator('#tankFieldsWrap input[data-key][type="number"]').count() === 0);
  ok('but they still bring up a numeric keypad',
     await p.locator('#tankFieldsWrap input[data-key][inputmode="decimal"]').count() === fields);

  const dome = p.locator('#tankFieldsWrap input[data-key="dome"]');
  const domeBtn = p.locator('#tankFieldsWrap .field', { has: p.locator('input[data-key="dome"]') }).locator('.signbtn');
  await dome.fill('42.5');
  await domeBtn.click(); await p.waitForTimeout(120);
  ok('the button makes a positive negative', await dome.inputValue() === '-42.5', await dome.inputValue());
  await domeBtn.click(); await p.waitForTimeout(120);
  ok('and back again', await dome.inputValue() === '42.5', await dome.inputValue());
  await dome.fill('');
  await domeBtn.click(); await p.waitForTimeout(120);
  ok('on an empty field it leaves a minus ready to type into',
     await dome.inputValue() === '-', JSON.stringify(await dome.inputValue()));
  await dome.fill('');
  await dome.type('-163.2');
  ok('a minus typed straight in is kept', await dome.inputValue() === '-163.2', await dome.inputValue());
  await dome.fill(''); await dome.type('ab-1.2.3x');
  ok('and anything that is not a number is dropped as you type',
     await dome.inputValue() === '-1.23', await dome.inputValue());

  // it has to survive the round trip, not just look right
  for(const [k, v] of [['dome','-41.8'],['portTop','-42.1'],['holdTemp','-12.4'],['pressure','-3.5']]){
    await p.locator('#tankFieldsWrap input[data-key="' + k + '"]').fill(v);
  }
  await p.click('#tankTabs button[data-tank="2"]'); await p.waitForTimeout(200);
  await p.click('#tankTabs button[data-tank="1"]'); await p.waitForTimeout(300);
  ok('negatives survive switching tanks and coming back',
     await p.locator('#tankFieldsWrap input[data-key="portTop"]').inputValue() === '-42.1',
     await p.locator('#tankFieldsWrap input[data-key="portTop"]').inputValue());

  await p.locator('#cargoSaveBtn, button:has-text("Save")').first().click();
  await p.waitForTimeout(500);
  const stored = await p.evaluate(() => localStorage.getItem('gasplanet_cargo_v1') || '');
  ok('and are stored with the minus intact', /-42\.1/.test(stored) && /-12\.4/.test(stored));
  ok('including a vacuum on the pressure field', /-3\.5/.test(stored));

  // ---- the other places a negative is real ----
  await p.click('#topTabs button[data-tab="tools"]');
  await p.click('[data-tool="convert"]');
  await p.waitForTimeout(300);
  ok('the converter takes one too', await p.locator('#convValue').getAttribute('inputmode') === 'decimal');
  await p.selectOption('#convCats button ~ *', {}).catch(()=>{});
  await p.locator('#convCats button', { hasText:'Temperature' }).click();
  await p.waitForTimeout(200);
  await p.fill('#convValue', '-42');
  await p.waitForTimeout(300);
  const conv = await p.textContent('#convOut');
  ok('and converts a negative temperature correctly',
     /-43\.6/.test(conv.replace(/\s+/g,'')) || /-43\.60/.test(conv),
     conv.replace(/\s+/g,' ').slice(0, 120));

  await p.click('#toolBackBtn');
  await p.click('[data-tool="moll"]');
  await p.waitForTimeout(400);
  ok('the Mollier temperature fields take one', 
     await p.locator('#mollTankT').getAttribute('inputmode') === 'decimal');
  await p.fill('#mollTankT', '-42');
  await p.waitForTimeout(300);
  ok('and a cargo at -42 still reads right',
     /1\.018/.test((await p.textContent('#mollOut')).replace(/\s+/g,' ')),
     (await p.textContent('#mollOut')).replace(/\s+/g,' ').slice(0, 110));

  ok('no page errors', errs.length === 0, errs.join(' | '));
  await b.close();
  console.log('');
  console.log(fail ? fail + ' FAILED' : 'ALL PASS');
  process.exit(fail ? 1 : 0);
})();
