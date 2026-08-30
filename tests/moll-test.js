// The Mollier tool. The numbers it shows are computed from tables baked into the
// page, so what matters is not that a number appears but that it is the RIGHT
// number -- every expected value below was taken from the published reference
// equations of state for that fluid, independently of the app's own tables.
const { chromium } = require('playwright-core');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}
// a number in the output, near enough
function near(text, re, want, tol, name){
  const m = text.match(re);
  if(!m){ ok(name, false, 'no match for ' + re); return; }
  const got = parseFloat(m[1].replace(/,/g,''));
  ok(name + ' (' + want + ' ± ' + tol + ')', Math.abs(got - want) <= tol, got);
}

(async () => {
  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const p = await b.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  await p.goto('file://' + process.env.APP_HTML);
  await p.waitForTimeout(600);

  await p.click('#topTabs button[data-tab="tools"]');
  ok('the tile is there', await p.locator('[data-tool="moll"]').count() === 1);
  await p.click('[data-tool="moll"]');
  ok('it opens', await p.locator('#toolMoll').isVisible());
  ok('titled Mollier', (await p.textContent('#pageTitle')).trim() === 'Mollier');
  ok('five products offered', await p.locator('#mollProds button').count() === 5,
     await p.locator('#mollProds button').count());
  ok('both mixtures are offered, and say which is which',
     await p.locator('#mollProds button', { hasText:'by mass' }).count() === 1 &&
     await p.locator('#mollProds button', { hasText:'by mole' }).count() === 1);
  ok('the chart draws', await p.locator('.moll-chart svg').count() === 1);
  ok('the dome is drawn from the tables, both branches',
     await p.locator('.moll-chart .md').count() === 2);

  // ---- saturation, against the reference equation of state ----
  await p.fill('#mollTankT','-42');
  await p.waitForTimeout(200);
  let t = (await p.textContent('#mollOut')).replace(/\s+/g,' ');
  near(t, /([\d.]+) bar absolute/, 1.0184, 0.002, 'propane at -42 C: saturation pressure');
  near(t, /Liquid\s*([\d,.]+) kg\/m/, 580.9, 0.5, 'liquid density');
  near(t, /Latent heat\s*([\d,.]+) kJ\/kg/, 425.5, 0.6, 'latent heat');
  ok('the gauge/absolute difference is spelled out, not left to be assumed',
     /bar g/.test(t) && /bar absolute/.test(t));

  // pressure drives temperature as well as the other way round
  await p.fill('#mollTankT','');
  await p.fill('#mollTankP','0');
  await p.waitForTimeout(200);
  t = (await p.textContent('#mollOut')).replace(/\s+/g,' ');
  near(t, /Liquid temperature\s*(-?[\d.]+)\s*°C/, -42.11, 0.3, 'entering 0 bar g gives back the boiling point');

  // ---- boil-off ----
  await p.click('#mollModes button[data-mmode="boil"]');
  await p.fill('#mollBoilT','-42');
  await p.fill('#mollBoilQ','100');
  await p.waitForTimeout(250);
  t = (await p.textContent('#mollOut')).replace(/\s+/g,' ');
  near(t, /Boil-off\s*([\d.]+) t\/h/, 0.8461, 0.004, '100 kW into propane at -42 C');
  // and it runs backwards
  await p.fill('#mollBoilQ','');
  await p.fill('#mollBoilM','0.8461');
  await p.waitForTimeout(250);
  t = (await p.textContent('#mollOut')).replace(/\s+/g,' ');
  near(t, /Heat into the tank\s*([\d,.]+) kW/, 100, 0.6, 'and backwards from the rate to the heat');

  // ---- flash on loading ----
  await p.click('#mollModes button[data-mmode="flash"]');
  await p.fill('#mollFlashT','10');
  await p.fill('#mollFlashP','0');
  await p.fill('#mollFlashQty','1000');
  await p.waitForTimeout(250);
  t = (await p.textContent('#mollOut')).replace(/\s+/g,' ');
  near(t, /Flashes to vapour\s*([\d.]+) %/, 29.4, 0.3, 'propane at +10 C into an atmospheric tank');
  near(t, /tonnes\s*Vapour\s*([\d,.]+) t/, 294, 3, 'and on 1000 tonnes');
  ok('it says throttling adds no energy', /isenthalpic|no energy/i.test(t));
  // refuses the case that is not a flash at all
  await p.fill('#mollFlashT','-45');
  await p.waitForTimeout(200);
  ok('refuses when the tank is not below the liquid’s own vapour pressure',
     /nothing flashes/i.test(await p.textContent('#mollOut')));

  // ---- the cycle ----
  await p.click('#mollModes button[data-mmode="cycle"]');
  await p.fill('#mollCycTe','-42'); await p.fill('#mollCycTc','40');
  await p.fill('#mollCycSh','5');  await p.fill('#mollCycEta','0.75');
  await p.fill('#mollCycQ','300');
  await p.waitForTimeout(350);
  t = (await p.textContent('#mollOut')).replace(/\s+/g,' ');
  near(t, /Compressor work\s*([\d,.]+) kJ\/kg/, 168.15, 0.4, 'propane -42/+40 compressor work');
  near(t, /Refrigerating effect\s*([\d,.]+) kJ\/kg/, 226.2, 0.5, 'refrigerating effect');
  near(t, /COP\s*([\d.]+)\s*refrigerating/, 1.345, 0.005, 'COP');
  near(t, /Compression ratio\s*([\d.]+)\s*:/, 13.45, 0.05, 'compression ratio');
  near(t, /Flash at the valve\s*([\d.]+) %/, 48.5, 0.4, 'flash at the expansion valve');
  near(t, /Mass flow\s*([\d.]+) kg\/s/, 1.326, 0.01, '300 kW needs this mass flow');
  near(t, /Suction volume\s*([\d,.]+) m/, 2015, 12, 'and this much suction volume');
  near(t, /Shaft power\s*([\d,.]+) kW/, 223, 1.5, 'and this much shaft power');
  ok('condenser duty is flagged as cargo heat PLUS the work',
     /cargo heat PLUS the compressor work/i.test(t));
  ok('the high compression ratio is called out with the two-stage figure',
     /Two stages of 3\.6/.test(t), t.slice(-260));
  ok('the cycle is drawn on the chart', await p.locator('.moll-chart .mc').count() >= 1);
  ok('all four points are marked', await p.locator('.moll-chart .mpt').count() === 4);

  // wet compression: butane with almost no suction superheat is the real trap
  await p.click('#mollProds button[data-mprod="nbutane"]');
  await p.fill('#mollCycTe','-3'); await p.fill('#mollCycTc','49');
  await p.fill('#mollCycSh','0.3');
  await p.waitForTimeout(350);
  t = (await p.textContent('#mollOut')).replace(/\s+/g,' ');
  near(t, /Compressor work\s*([\d,.]+) kJ\/kg/, 85.67, 0.4,
       'n-butane at 0.3 K superheat, where the ideal compression ends inside the dome');
  ok('and it warns that there is no margin against wet compression',
     /inside the dome/i.test(t), t.slice(-240));
  // with proper superheat the warning goes away
  await p.fill('#mollCycSh','12');
  await p.waitForTimeout(300);
  ok('carrying real superheat clears the warning',
     !/inside the dome/i.test(await p.textContent('#mollOut')));

  // ---- mixtures behave as mixtures ----
  await p.click('#mollProds button[data-mprod="mix50m"]');
  await p.click('#mollModes button[data-mmode="tank"]');
  await p.fill('#mollTankP',''); await p.fill('#mollTankT','-20');
  await p.waitForTimeout(300);
  t = (await p.textContent('#mollOut')).replace(/\s+/g,' ');
  near(t, /([\d.]+) bar absolute/, 1.5631, 0.005, '50/50 by mass at -20 C: bubble pressure');
  ok('a mixture shows its dew point, not just one saturation temperature',
     /dew point/i.test(t) && /glide/i.test(t), t.slice(0,220));
  near(t, /glide\s*([\d.]+) K/, 15.4, 0.4, 'and the glide is real, not a rounding');

  // the two 50/50 readings are genuinely different mixtures
  const massP = (await p.textContent('#mollOut')).match(/([\d.]+) bar absolute/)[1];
  await p.click('#mollProds button[data-mprod="mix50n"]');
  await p.waitForTimeout(300);
  t = (await p.textContent('#mollOut')).replace(/\s+/g,' ');
  near(t, /([\d.]+) bar absolute/, 1.4258, 0.005, '50/50 by mole at -20 C is a different pressure');
  ok('by mass and by mole are not the same mixture',
     Math.abs(parseFloat(massP) - parseFloat(t.match(/([\d.]+) bar absolute/)[1])) > 0.05);

  // ---- out of range refuses rather than extrapolating ----
  await p.click('#mollProds button[data-mprod="propane"]');
  await p.fill('#mollTankT','-200');
  await p.waitForTimeout(250);
  ok('outside the table it says so instead of inventing a figure',
     /Outside the table/i.test(await p.textContent('#mollOut')));

  // ---- it remembers ----
  await p.fill('#mollTankT','');
  await p.click('#mollProds button[data-mprod="isobutane"]');
  await p.click('#mollModes button[data-mmode="flash"]');
  await p.reload();
  await p.waitForTimeout(700);
  await p.click('#topTabs button[data-tab="tools"]');
  await p.click('[data-tool="moll"]');
  await p.waitForTimeout(250);
  ok('the product is remembered across a reload',
     await p.locator('#mollProds button.active', { hasText:'Isobutane' }).count() === 1);
  ok('and so is the mode',
     await p.locator('#mollModes button.active', { hasText:'Flash' }).count() === 1);

  ok('no page errors', errs.length === 0, errs.join(' | '));
  await b.close();
  console.log('');
  console.log(fail ? fail + ' FAILED' : 'ALL PASS');
  process.exit(fail ? 1 : 0);
})();
