// The stores catalogue. The catalogue itself is NOT shipped with the app --
// IMPA's is copyrighted and a guessed code puts the wrong part on board -- so
// what is tested is the machinery: importing whatever a purchasing system can
// export, and searching it offline.
const { chromium } = require('playwright-core');
const path = require('path');
const PIC = path.join(__dirname, 'pic.jpg');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}
const CSV = path.join(__dirname, 'stores-sample.csv');

(async () => {
  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const p = await (await b.newContext()).newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  p.on('dialog', async d => await d.accept());
  await p.goto('file://' + process.env.APP_HTML);
  await p.waitForTimeout(600);

  await p.click('#topTabs button[data-tab="tools"]');
  ok('the tile is there', await p.locator('[data-tool="stores"]').count() === 1);
  ok('seven live tools on the launcher',
     await p.locator('.tool-tile[data-tool]').count() === 7,
     await p.locator('.tool-tile[data-tool]').count());
  await p.click('[data-tool="stores"]');
  await p.waitForTimeout(400);
  ok('it opens', await p.locator('#toolStores').isVisible());
  ok('and says plainly that no catalogue is supplied with the app',
     /not supplied with the app/i.test(await p.textContent('#storesOut')));
  ok('with nothing loaded there is nothing to remove',
     !(await p.locator('#storesClearBtn').isVisible()));

  // ---- import ----
  await p.setInputFiles('#storesImportInput', CSV);
  await p.waitForFunction(() => /items from/.test(document.getElementById('storesNote').textContent),
                          { timeout: 60000 });
  const note = (await p.textContent('#storesNote')).replace(/\s+/g,' ');
  ok('a CSV imports and the count is reported', /5,040 items/.test(note), note.slice(0,70));
  ok('and the catalogue can now be removed', await p.locator('#storesClearBtn').isVisible());

  // ---- searching ----
  await p.fill('#storesSearchIn','1701');
  await p.waitForTimeout(350);
  ok('a code finds its section', await p.locator('.store-row').count() > 0,
     await p.locator('.store-row').count());
  ok('and the code is shown in pairs, the way it is read',
     /17-01-\d\d/.test(await p.textContent('.store-row')),
     (await p.textContent('.store-row')).slice(0,40));

  // the trap: a query with digits in it is not a code lookup
  const words = await p.evaluate(() => window.__storesSearch('stainless bolt', 60));
  ok('words match anywhere, and every word has to appear',
     words.length > 0 && words.every(r => /STAINLESS/.test(r.d) && /BOLT/.test(r.d)),
     words.length + ' hits, first ' + (words[0] ? words[0].d : '-'));
  const mixed = await p.evaluate(() => window.__storesSearch('stainless bolt m12', 60));
  ok('a query with digits in it is still a word search, not a code search',
     mixed.length > 0 && mixed.every(r => /STAINLESS/.test(r.d) && /BOLT/.test(r.d)),
     mixed.length + ' hits, first ' + (mixed[0] ? mixed[0].c + ' ' + mixed[0].d : '-'));
  ok('an extra word narrows rather than widens', mixed.length <= words.length,
     words.length + ' -> ' + mixed.length);

  await p.fill('#storesSearchIn','zzzz nothing');
  await p.waitForTimeout(300);
  ok('nothing matching says so', /Nothing in the catalogue/.test(await p.textContent('#storesOut')));
  await p.fill('#storesSearchIn','a');
  await p.waitForTimeout(250);
  ok('one letter does not try to search the whole catalogue',
     await p.locator('.store-row').count() === 0);

  // ---- an item, and its photographs ----
  await p.fill('#storesSearchIn','1701');
  await p.waitForTimeout(350);
  await p.locator('.store-row').first().click();
  await p.waitForTimeout(400);
  ok('a row opens the item', await p.locator('#storesItem').isVisible());
  ok('with its code set out in full', /^\d\d-\d\d-\d\d$/.test((await p.textContent('.sh-c')).trim()),
     (await p.textContent('.sh-c')).trim());
  ok('and says there is no photograph of it yet',
     /No photograph of this item yet/.test(await p.textContent('#storesItem')));

  await p.setInputFiles('#storePhotoInput', PIC);
  await p.waitForTimeout(1600);
  ok('a photograph can be attached', await p.locator('#storesItem .thumb img').count() === 1,
     await p.locator('#storesItem .thumb img').count());
  ok('and it is a real image, not a broken reference',
     (await p.locator('#storesItem .thumb img').first().getAttribute('src') || '').startsWith('data:image'));

  await p.click('#storeItemBack');
  await p.waitForTimeout(400);
  ok('back at the results the row shows it has one',
     /\u{1F4F7}/u.test(await p.textContent('.store-row')),
     (await p.textContent('.store-row')).slice(0,60));

  // The collector deletes every photo reference it cannot see. A stores photo
  // that it does not know about would be gone on the next run -- which is
  // exactly what this checks, by forcing a full restart.
  await p.reload();
  await p.waitForTimeout(1200);
  await p.click('#topTabs button[data-tab="tools"]');
  await p.click('[data-tool="stores"]');
  await p.waitForFunction(() => /items from/.test(document.getElementById('storesNote').textContent),
                          { timeout: 60000 });
  await p.fill('#storesSearchIn','1701');
  await p.waitForTimeout(500);
  await p.locator('.store-row').first().click();
  await p.waitForTimeout(700);
  ok('the photograph survives a restart and the photo collector',
     await p.locator('#storesItem .thumb img').count() === 1 &&
     (await p.locator('#storesItem .thumb img').first().getAttribute('src') || '').startsWith('data:image'),
     await p.locator('#storesItem .thumb img').count() + ' images');
  await p.click('#storeItemBack');
  await p.waitForTimeout(300);

  // ---- it stays ----
  await p.reload();
  await p.waitForTimeout(800);
  await p.click('#topTabs button[data-tab="tools"]');
  await p.click('[data-tool="stores"]');
  await p.waitForFunction(() => /items from/.test(document.getElementById('storesNote').textContent),
                          { timeout: 60000 });
  await p.fill('#storesSearchIn','1701');
  await p.waitForTimeout(400);
  ok('the catalogue survives a restart', await p.locator('.store-row').count() > 0);

  // ---- and it does not live in localStorage, which could not hold it ----
  const ls = await p.evaluate(() => (localStorage.getItem('gasplanet_stores_v1') || '').length);
  ok('only a note about it is in localStorage, not the catalogue', ls < 400, ls + ' bytes');

  // ---- removing it ----
  await p.click('#storesClearBtn');
  await p.waitForTimeout(600);
  ok('removing it empties the catalogue',
     /not supplied with the app/i.test(await p.textContent('#storesOut')));

  ok('no page errors', errs.length === 0, errs.join(' | '));
  await b.close();
  console.log('');
  console.log(fail ? fail + ' FAILED' : 'ALL PASS');
  process.exit(fail ? 1 : 0);
})();
