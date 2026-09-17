// Every word capitalised as it's typed -- Job names, ports, crew names and
// the like are short field-style entries, not prose, so the platform's own
// per-word auto-capitalisation is the right default. It is set once, on
// <body>, and every field inherits it unless it overrides its own -- which
// only the Notes writing area does, since a written passage wants normal
// sentence capitalisation, not every single word capitalised.
//
// This is a virtual-keyboard/IME behaviour: it cannot be observed by typing
// into a headless desktop browser (there is no on-screen keyboard to hint),
// and the autocapitalize IDL property only reflects an element's own local
// attribute, never an inherited one -- so what is actually checkable is the
// wiring: the hint is set where it should be, overridden where it should be,
// and not fought by a stray local attribute on the fields that should just
// inherit it.
const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c)fails++;};
(async () => {
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(process.env.APP_HTML));}).listen(8785);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const ctx=await b.newContext({viewport:{width:390,height:900}});
  const p=await ctx.newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  await p.goto('http://localhost:8785/');
  await p.waitForTimeout(500);

  ok('the page-wide hint is set on <body>',
     await p.evaluate(() => document.body.getAttribute('autocapitalize')) === 'words');

  // fields across several tabs -- none of them should carry their own
  // attribute fighting the inherited one
  const plain = ['inJob', 'paPort'];
  for(const id of plain){
    ok(id + ' inherits the page-wide hint, no local override',
       await p.evaluate((i) => document.getElementById(i).getAttribute('autocapitalize'), id) === null);
  }

  await p.click('#topTabs button[data-tab="ship"]');
  await p.click('#shipTabs button[data-ship="crew"]');
  await p.waitForTimeout(200);
  ok('crewName inherits the page-wide hint, no local override',
     await p.evaluate(() => document.getElementById('crewName').getAttribute('autocapitalize')) === null);

  // the one deliberate exception: a written note is prose, not field entries
  await p.click('#topTabs button[data-tab="notes"]');
  await p.waitForTimeout(300);
  const noteBody = await p.evaluate(() => {
    const el = document.getElementById('noteBody');
    return el ? el.getAttribute('autocapitalize') : null;
  });
  ok('the Notes writing area overrides to sentence capitalisation, not per-word',
     noteBody === 'sentences', noteBody);

  ok('no JS errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails===0?'\nALL PASS':'\n'+fails+' FAILED'); process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
