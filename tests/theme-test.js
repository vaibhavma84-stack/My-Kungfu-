const { chromium } = require(process.env.SP + '/node_modules/playwright-core');
const http=require('http'), fs=require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
const lum = c => { const m=c.match(/\d+/g); return m ? (0.299*m[0]+0.587*m[1]+0.114*m[2]) : null; };
(async()=>{
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync('/home/user/expenses/GasPlanet_ToDoList.html'));}).listen(8762);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});

  // --- light phone ---
  let ctx=await b.newContext({viewport:{width:420,height:900}, colorScheme:'light'});
  let p=await ctx.newPage(); const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  p.on('dialog',async d=>await d.accept());
  await p.goto('http://localhost:8762/');
  ok('auto follows a light phone', await p.evaluate(()=>document.documentElement.dataset.theme)==='light',
     await p.evaluate(()=>document.documentElement.dataset.theme));
  const lightBody = await p.evaluate(()=>getComputedStyle(document.body).backgroundColor);
  ok('page is light', lum(lightBody)>200, lightBody);

  await p.click('#settingsBtn');
  ok('settings opens', await p.isVisible('.edit-card'));
  ok('three choices', (await p.locator('[data-theme-set]').count())===3);
  ok('auto is selected', (await p.locator('[data-theme-set].on').getAttribute('data-theme-set'))==='auto');
  ok('storage summary shown', (await p.textContent('#setStorage')).indexOf('Export CSV is the backup')>-1);

  await p.click('[data-theme-set="dark"]');
  await p.waitForTimeout(150);
  ok('switches to dark immediately', await p.evaluate(()=>document.documentElement.dataset.theme)==='dark');
  const darkBody = await p.evaluate(()=>getComputedStyle(document.body).backgroundColor);
  ok('page went dark', lum(darkBody)<60, darkBody);
  await p.click('[data-set="close"]');

  // readable text on the dark ground
  await p.fill('#inJob','Air hoses weekly inspection'); await p.fill('#inDue','2026-09-01');
  await p.selectOption('#inRepeat','weekly'); await p.click('#addBtn');
  const card = await p.evaluate(()=>{
    const t=document.querySelector('.task');
    const cs=getComputedStyle(t), ts=getComputedStyle(t.querySelector('.job-text'));
    return {bg:cs.backgroundColor, fg:ts.color};
  });
  ok('a weekly card is dark, not the light yellow', lum(card.bg)<90, JSON.stringify(card));
  ok('its text is light on that ground', lum(card.fg)>150, JSON.stringify(card));
  const toolbar = await p.evaluate(()=>getComputedStyle(document.querySelector('.toolbar')).backgroundColor);
  ok('toolbar is dark too', lum(toolbar)<70, toolbar);
  const hdr = await p.evaluate(()=>{
    const h=document.querySelector('header');
    return {bg:getComputedStyle(h).backgroundColor,
            fg:getComputedStyle(document.getElementById('pageTitle')).color};
  });
  ok('header title stays light on the navy chrome', lum(hdr.fg)>200, JSON.stringify(hdr));

  // the report must stay light — it is a printed document
  await p.locator('.task:not(.done)').first().locator('.tick').click();
  await p.waitForSelector('.date-card'); await p.click('[data-dc="today"]'); await p.click('[data-dc="ok"]');
  await p.waitForTimeout(200);
  await p.click('#weeklyReportBtn'); await p.waitForTimeout(300);
  const rep = await p.evaluate(()=>getComputedStyle(document.getElementById('reportView')).backgroundColor);
  ok('weekly report stays light in dark mode', lum(rep)>240, rep);
  await p.screenshot({path:process.env.SP+'/shot-dark-report.png'});
  await p.click('[data-rv="close"]');
  await p.screenshot({path:process.env.SP+'/shot-dark.png'});

  // choice persists
  await p.reload(); await p.waitForTimeout(200);
  ok('dark survives a reload', await p.evaluate(()=>document.documentElement.dataset.theme)==='dark');
  ok('no JS errors', errs.length===0, errs.join(' | '));
  await ctx.close();

  // --- dark phone, fresh profile, auto ---
  ctx=await b.newContext({viewport:{width:420,height:900}, colorScheme:'dark'});
  p=await ctx.newPage();
  await p.goto('http://localhost:8762/');
  ok('auto follows a dark phone', await p.evaluate(()=>document.documentElement.dataset.theme)==='dark');
  await p.click('#settingsBtn'); await p.click('[data-theme-set="light"]');
  await p.waitForTimeout(150);
  ok('an explicit light choice beats a dark phone',
     await p.evaluate(()=>document.documentElement.dataset.theme)==='light');
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
