// Signing off. Everything for the ship being left is written to one file, kept
// in the app so it can be looked at again, and then cleared for the next ship.
//
// The order matters more than anything else here: nothing is cleared until the
// archive has actually been recorded. An archive that half happened is worse
// than one that did not.
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

(async () => {
  const OUT = fs.mkdtempSync('/tmp/signoff-');
  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext({ viewport:{width:420,height:900}, acceptDownloads:true });
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  p.on('dialog', d => d.accept());
  await p.goto('file://' + process.env.APP_HTML);
  await p.waitForTimeout(900);

  // a ship's worth of work
  await p.evaluate(async () => {
    const db = await new Promise(res => {
      const r = indexedDB.open('gasplanet_media', 1);
      r.onsuccess = () => res(r.result); r.onerror = () => res(null);
    });
    function shot(i){
      const c = document.createElement('canvas'); c.width = 300; c.height = 200;
      const g = c.getContext('2d');
      g.fillStyle = 'hsl(' + (i * 40 % 360) + ',60%,45%)'; g.fillRect(0, 0, 300, 200);
      return c.toDataURL('image/jpeg', 0.65);
    }
    const refs = [];
    for(let i = 0; i < 6; i++){
      const id = 'p:so' + i;
      await new Promise(res => {
        const tx = db.transaction('photos', 'readwrite');
        tx.objectStore('photos').put(shot(i), id);
        tx.oncomplete = res; tx.onerror = res;
      });
      refs.push(id);
    }
    localStorage.setItem('gasplanet_todo_v1', JSON.stringify([
      { id:'j1', serial:1, job:'Sound bilges, forward', due:'2026-09-01', priority:'normal',
        remarks:'', weeklyReport:'yes', ad34Planner:'no', ra:false, ptw:false, ptwType:'',
        repeat:'', photos:refs.slice(0,3), done:true, dateCompleted:'2026-09-02', createdAt:'2026-09-01' },
      { id:'j2', serial:2, job:'Grease crane wires', due:'2026-09-03', priority:'urgent',
        remarks:'port crane', weeklyReport:'yes', ad34Planner:'yes', ra:false, ptw:false,
        ptwType:'', repeat:'', photos:refs.slice(3), done:false, dateCompleted:'', createdAt:'2026-09-01' }
    ]));
    localStorage.setItem('gasplanet_crew_v1', JSON.stringify([
      { id:'c1', serial:1, name:'Karan Vir Bhatia', rank:'Master', ship:'Gas Planet', dob:'1984-08-12' }
    ]));
    localStorage.setItem('gasplanet_ship_v1', JSON.stringify({ name:'GAS PLANET', type:'LPG TANKER', imo:'9123456' }));
    location.reload();
  });
  await p.waitForTimeout(1600);

  await p.click('#topTabs button[data-tab="ship"]');
  await p.waitForTimeout(300);
  ok('the Signed off button is on the Ship tab', await p.locator('#signOffBtn').isVisible());
  ok('and nothing has been signed off yet',
     /No ship has been signed off/.test(await p.textContent('#archiveList')));

  const dl = p.waitForEvent('download', { timeout: 30000 });
  await p.click('#signOffBtn');
  const file = await dl;
  const saved = path.join(OUT, file.suggestedFilename());
  await file.saveAs(saved);
  ok('it writes one file, named for the ship and the day',
     /^GAS PLANET signed off \d{4}-\d{2}-\d{2}\.zip$/.test(file.suggestedFilename()),
     file.suggestedFilename());

  await p.waitForTimeout(1500);

  // the file has to be a real zip that a real unzipper opens
  fs.writeFileSync(path.join(OUT, 'names.txt'), '');
  const { execSync } = require('child_process');
  const listing = execSync('python3 -c "' +
    'import zipfile,sys;z=zipfile.ZipFile(sys.argv[1]);' +
    'print(\'BAD\' if z.testzip() else \'OK\');' +
    '[print(i.filename) for i in z.infolist()]" ' + JSON.stringify(saved)).toString();
  const lines = listing.trim().split('\n');
  ok('the archive is a valid zip', lines[0] === 'OK', lines[0]);
  const names = lines.slice(1);
  ['jobs.csv','crew.csv','cargo-log.csv','ship-particulars.csv','port-calls.csv',
   'birthdays.csv','ad19-work-planner.csv','README.txt'].forEach(f => {
    ok('it carries ' + f, names.some(n => n.endsWith('/' + f)), names.slice(0,3).join(' | '));
  });
  ok('every photograph is in it, named after its job',
     names.filter(n => /\/photos\/\d{4} .+\.jpg$/.test(n)).length === 6,
     names.filter(n => n.indexOf('/photos/') !== -1).join(' | '));
  ok('and they are all inside one folder named for the ship',
     names.every(n => n.indexOf('GAS PLANET signed off ') === 0), names[0]);

  const jobsCsv = execSync('python3 -c "' +
    'import zipfile,sys;z=zipfile.ZipFile(sys.argv[1]);' +
    'print([z.read(i).decode(\'utf-8-sig\') for i in z.namelist() if i.endswith(\'jobs.csv\')][0])" ' +
    JSON.stringify(saved)).toString();
  ok('the jobs csv holds the real jobs',
     /Sound bilges, forward/.test(jobsCsv) && /Grease crane wires/.test(jobsCsv),
     jobsCsv.slice(0, 120));

  // and the app is ready for the next ship
  const after = await p.evaluate(() => ({
    jobs: JSON.parse(localStorage.getItem('gasplanet_todo_v1') || '[]').length,
    crew: JSON.parse(localStorage.getItem('gasplanet_crew_v1') || '[]').length,
    ship: JSON.parse(localStorage.getItem('gasplanet_ship_v1') || '{}').name || '',
    bdays: JSON.parse(localStorage.getItem('gasplanet_bdays_v1') || '[]').length
  }));
  ok('the jobs are cleared', after.jobs === 0, after.jobs);
  ok('the crew list is cleared', after.crew === 0, after.crew);
  ok('the ship particulars are cleared', after.ship === '', after.ship);
  ok('but the birthdays are kept — they are a register of people, not of a ship',
     after.bdays >= 1, after.bdays);

  ok('the ship now appears in the list of ships signed off',
     /GAS PLANET/.test(await p.textContent('#archiveList')),
     await p.textContent('#archiveList'));

  // the photographs of a signed-off ship must survive the tidy-up
  await p.reload();
  await p.waitForTimeout(2000);
  const kept = await p.evaluate(() => window.__photoStats());
  ok('its photographs are still stored after a restart and a tidy-up',
     kept.stored >= 6, kept.stored + ' stored');

  await p.click('#topTabs button[data-tab="ship"]');
  await p.waitForTimeout(300);
  const dl2 = p.waitForEvent('download', { timeout: 30000 });
  await p.click('#archiveList [data-arch-save]');
  const file2 = await dl2;
  ok('a signed-off ship can be written out again later',
     /GAS PLANET signed off/.test(file2.suggestedFilename()), file2.suggestedFilename());

  const live = await p.evaluate(() => ({
    jobs: JSON.parse(localStorage.getItem('gasplanet_todo_v1') || '[]').length
  }));
  ok('and writing it out again does not put the old ship back',
     live.jobs === 0, live.jobs);

  ok('no page errors', errs.length === 0, errs.join(' | '));
  await b.close();
  console.log('');
  console.log(fail ? fail + ' FAILED' : 'ALL PASS');
  process.exit(fail ? 1 : 0);
})();
