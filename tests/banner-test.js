const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
const visible = p => p.evaluate(()=>getComputedStyle(document.getElementById('backupBanner')).display !== 'none');
const text    = p => p.textContent('#backupText');
const daysAgo = n => { const d=new Date(); d.setDate(d.getDate()-n);
  return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0'); };

(async () => {
  const srv = http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(process.env.APP_HTML));}).listen(8735);
  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext({ viewport:{width:390,height:844}, acceptDownloads:true });
  const p = await ctx.newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  await p.goto('http://localhost:8735/');

  // empty app: nothing to lose, so no nagging
  ok('no banner on a brand-new empty app', !(await visible(p)));

  // first job created -> never backed up
  await p.fill('#inJob','Sound all cargo tanks'); await p.click('#addBtn');
  ok('banner appears once there is data to lose', await visible(p));
  ok('message says there is no backup yet', /No backup yet of To Do List/.test(await text(p)), await text(p));
  ok('message warns this device is the only copy', /only copy/.test(await text(p)));
  ok('empty Crew and Cargo lists are not nagged about', !/Crew List|Cargo Log/.test(await text(p)));

  // exporting clears it, and stamps only that list
  const dl = p.waitForEvent('download');
  await p.click('#exportBtn');
  const file = await dl;
  ok('Export CSV produces a download', (await file.path()) !== null);
  ok('banner clears after exporting', !(await visible(p)));
  ok('backup date recorded for jobs only',
     await p.evaluate(()=>!!localStorage.getItem('gasplanet_lastBackup_jobs') && !localStorage.getItem('gasplanet_lastBackup_crew')));

  // adding crew re-raises it for that list alone
  await p.click('#topTabs button[data-tab="crew"]');
  await p.fill('#crewName','R Mahajan'); await p.fill('#crewDob','1984-11-30'); await p.click('#crewAddBtn');
  ok('banner returns for the newly-populated Crew List', await visible(p));
  ok('names only the Crew List, not the freshly-backed-up To Do List',
     /Crew List/.test(await text(p)) && !/To Do List/.test(await text(p)), await text(p));

  // "Later" snoozes for 7 days
  await p.click('#backupLater');
  ok('Later dismisses the banner', !(await visible(p)));
  await p.reload(); await p.waitForTimeout(200);
  ok('stays dismissed across a reload', !(await visible(p)));
  await p.evaluate(d=>localStorage.setItem('gasplanet_backupSnooze', d), daysAgo(8));
  await p.reload(); await p.waitForTimeout(200);
  ok('comes back after the 7-day snooze lapses', await visible(p));

  // a stale backup (>14 days) re-raises it with a day count
  await p.evaluate(d=>{ localStorage.setItem('gasplanet_backupSnooze','');
                        localStorage.setItem('gasplanet_lastBackup_jobs', d);
                        localStorage.setItem('gasplanet_lastBackup_crew', d); }, daysAgo(21));
  await p.reload(); await p.waitForTimeout(200);
  ok('stale backup raises the banner', await visible(p));
  ok('message reports how many days stale', /Not backed up for 21 days/.test(await text(p)), await text(p));
  ok('lists both stale lists', /To Do List, Crew List/.test(await text(p)), await text(p));

  // a 13-day-old backup is still inside the window
  await p.evaluate(d=>{ localStorage.setItem('gasplanet_lastBackup_jobs', d);
                        localStorage.setItem('gasplanet_lastBackup_crew', d); }, daysAgo(13));
  await p.reload(); await p.waitForTimeout(200);
  ok('13-day-old backup is not yet nagged about', !(await visible(p)));

  // "Back up now" exports every stale list, one file each
  await p.evaluate(d=>{ localStorage.setItem('gasplanet_lastBackup_jobs', d);
                        localStorage.setItem('gasplanet_lastBackup_crew', d); }, daysAgo(30));
  await p.reload(); await p.waitForTimeout(200);
  const files=[]; p.on('download', d => files.push(d.suggestedFilename()));
  await p.click('#backupNow');
  await p.waitForTimeout(2600);
  ok('Back up now saves one CSV per stale list', files.length === 2, JSON.stringify(files));
  ok('files are named per list',
     files.some(f=>/ToDoList/.test(f)) && files.some(f=>/CrewList/.test(f)), JSON.stringify(files));
  ok('banner clears once every stale list is saved', !(await visible(p)));

  ok('no JS errors throughout', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
