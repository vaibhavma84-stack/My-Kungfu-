// The forms, at the width of a phone.
//
// Two columns divide five fields unevenly, and whichever one is left over sits
// beside a hole -- which is what "the boxes are not aligned" means when someone
// looks at the app rather than the CSS. And a date field is a control, not a
// text box: it sizes itself to its own content, so in a narrow column it grows
// past its cell and the overflow rule cuts its right-hand border off, leaving a
// box that does not close.
//
// So this measures. Every field is either the full width of the row or one of a
// complete pair; every input sits inside the field that holds it; nothing makes
// the page scroll sideways.
const { chromium } = require('playwright-core');
const http = require('http');
const fs = require('fs');
const path = require('path');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

const ROWS = [
  ['the add-job form', '#jobsSection .addForm .row-main, #addWrap .row-main'],
  ['the port call panel', '#portCallWrap .row-main']
];

(async () => {
  const srv = http.createServer((q, r) => {
    r.writeHead(200, { 'Content-Type':'text/html; charset=utf-8' });
    r.end(fs.readFileSync(process.env.APP_HTML));
  }).listen(8791);

  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });

  for(const width of [360, 390, 430]){
    const ctx = await b.newContext({ viewport:{ width, height:900 } });
    const p = await ctx.newPage();
    const errs = []; p.on('pageerror', e => errs.push(String(e)));
    await p.goto('http://localhost:8791/');
    await p.waitForTimeout(800);

    for(const theme of ['light', 'dark']){
      await p.evaluate(t => document.documentElement.setAttribute('data-theme', t), theme);
      await p.waitForTimeout(120);

      for(const [label, sel] of ROWS){
        const rows = await p.evaluate(sel => {
          const grid = document.querySelector(sel);
          if(!grid) return null;
          const gw = grid.getBoundingClientRect().width;
          const byRow = {};
          [...grid.children].forEach(el => {
            if(getComputedStyle(el).display === 'none') return;
            const r = el.getBoundingClientRect();
            const key = Math.round(r.y);
            (byRow[key] = byRow[key] || []).push({
              name: (el.querySelector('label') || {}).textContent || el.className,
              x: r.x, w: r.width,
              inner: [...el.querySelectorAll('input, select, button')]
                       .filter(i => i.type !== 'file')
                       .map(i => { const q = i.getBoundingClientRect();
                                   return { tag:i.tagName, right:q.right, w:q.width }; })
            });
          });
          return { gw, gx: grid.getBoundingClientRect().x, rows: Object.values(byRow) };
        }, sel);
        if(!rows){ ok(label + ' exists @' + width, false, 'not found'); continue; }

        // every row is filled: the fields on it span the grid, give or take the gap
        let ragged = [];
        rows.rows.forEach(fields => {
          const covered = fields.reduce((a, f) => a + f.w, 0) + 10 * (fields.length - 1);
          if(covered < rows.gw - 2) ragged.push(fields.map(f => f.name.trim()).join('+'));
        });
        ok(label + ': no field left beside a hole @' + width + 'px ' + theme,
           ragged.length === 0, ragged.join(', '));

        // nothing inside a field sticks out of it
        let burst = [];
        rows.rows.forEach(fields => fields.forEach(f => f.inner.forEach(i => {
          if(i.right > f.x + f.w + 1) burst.push(f.name.trim() + ' ' + i.tag);
        })));
        ok(label + ': every control fits its box @' + width + 'px ' + theme,
           burst.length === 0, burst.join(', '));
      }
    }

    const sideways = await p.evaluate(() =>
      document.documentElement.scrollWidth - window.innerWidth);
    ok('the page does not scroll sideways @' + width + 'px', sideways <= 0, sideways + 'px over');
    ok('no page errors @' + width + 'px', errs.length === 0, errs.slice(0, 2).join(' | '));
    await ctx.close();
  }

  // ---- the strip the clock sits in ---------------------------------------
  // Added to the Home Screen, iOS hands the app the whole screen. env() is 0 in
  // a desktop browser, so the variable is set by hand to prove the rule is
  // wired to it rather than merely present.
  {
    const ctx = await b.newContext({ viewport:{ width:390, height:844 } });
    const p = await ctx.newPage();
    await p.goto('http://localhost:8791/');
    await p.waitForTimeout(700);
    const before = await p.evaluate(() =>
      document.querySelector('#topTabs').getBoundingClientRect().top);
    await p.evaluate(() => document.documentElement.style.setProperty('--safe-top', '44px'));
    await p.waitForTimeout(120);
    const after = await p.evaluate(() => ({
      border: getComputedStyle(document.body).borderTopWidth,
      tabs: document.querySelector('#topTabs').getBoundingClientRect().top
    }));
    ok('the page keeps clear of the status bar', after.border === '44px', after.border);
    ok('and everything moves down with it', Math.round(after.tabs - before) === 44,
       Math.round(after.tabs - before));
    await ctx.close();
  }

  await b.close();
  srv.close();
  console.log(fail ? '\n' + fail + ' FAILED' : '\nALL PASS');
  process.exit(fail ? 1 : 0);
})();
