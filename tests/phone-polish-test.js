// A second round of phone screenshots, six things pointed at directly.
//
// Two of them are the same bug in different rooms: a date or time input is a
// control, not a text box, and behaves differently before it has been focused
// once than after -- shorter, in the case that was actually reported, and (for
// time on a narrow row) wide enough to run off the edge of the screen in the
// case that was found while fixing it. Both get a fixed height and a fixed
// width rather than whatever the platform feels like giving an untouched
// field.
const { chromium } = require('playwright-core');
const http = require('http');
const fs = require('fs');
const path = require('path');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

(async () => {
  const srv = http.createServer((q, r) => {
    r.writeHead(200, { 'Content-Type':'text/html; charset=utf-8' });
    r.end(fs.readFileSync(process.env.APP_HTML));
  }).listen(8821);

  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext({ viewport:{ width:390, height:900 } });
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  await p.goto('http://localhost:8821/');
  await p.waitForTimeout(800);

  // ---- date/time: a fixed height, not one that depends on having been
  // touched -- the actual bug cannot be reproduced here (Chromium sizes an
  // empty date input the same before and after focus), so this checks the
  // guard: the height is set in CSS, not left to the control.
  {
    // Chromium happens to render an untouched date input at the same height it
    // would be given explicitly (41px, matching the select beside it), so a
    // pixel measurement here cannot tell a fix from its absence -- that gap is
    // exactly why the real bug only ever showed up on a phone. What can be
    // checked is the source itself: the rule is declared, and covers both
    // date and time.
    const rule = await p.evaluate(() => {
      for(const sheet of document.styleSheets){
        let rules;
        try{ rules = sheet.cssRules; }catch(e){ continue; }
        for(const r of rules){
          if(r.selectorText && r.selectorText.indexOf('.field input') !== -1 &&
             r.selectorText.indexOf('"date"') !== -1 && r.selectorText.indexOf('"time"') !== -1){
            return { selector: r.selectorText, height: r.style.height,
                      appearance: r.style.webkitAppearance || r.style.appearance };
          }
        }
      }
      return null;
    });
    ok('one rule covers both date and time inputs', !!rule, JSON.stringify(rule));
    ok('and it gives them an explicit height', rule && rule.height === '41px', rule && rule.height);
    ok('and turns off the native picker chrome that can outgrow it',
       rule && rule.appearance === 'none', rule && rule.appearance);

    // With that rule in place, the due-date box does at least render at the
    // height it names, and does not change when it is focused.
    const before = await p.evaluate(() =>
      getComputedStyle(document.getElementById('inDue')).height);
    await p.focus('#inDue');
    await p.waitForTimeout(100);
    const after = await p.evaluate(() =>
      getComputedStyle(document.getElementById('inDue')).height);
    ok('the due-date box renders at 41px', before === '41px', before);
    ok('and does not move when the field is touched', after === before, after);
  }

  // ---- the Cargo Log time field stays on screen ---------------------------
  {
    await p.click('#topTabs button[data-tab="cargo"]');
    await p.waitForTimeout(200);
    const box = await p.evaluate(() => {
      const r = document.getElementById('cargoTime').getBoundingClientRect();
      return { right: r.right, viewport: window.innerWidth };
    });
    ok('the Cargo Log time field stays inside the screen',
       box.right <= box.viewport + 1, box.right + ' vs ' + box.viewport + 'px wide');
    const scrollX = await p.evaluate(() =>
      document.documentElement.scrollWidth - window.innerWidth);
    ok('and nothing on the Cargo Log tab forces sideways scroll', scrollX <= 0, scrollX + 'px over');
  }

  // ---- Cargo Log: Port and Stbd as two columns, not two stacked blocks ----
  {
    const layout = await p.evaluate(() => {
      const wrap = document.getElementById('tankFieldsWrap');
      const heads = [...wrap.querySelectorAll('.tank-subhead')].map(h => h.textContent.trim());
      const keys = [...wrap.querySelectorAll('input[data-key]')].map(i => i.dataset.key);
      return { heads, keys };
    });
    ok('Port and Stbd are named as the two columns', 
       layout.heads.length === 2 && layout.heads[0] === 'Port' && layout.heads[1] === 'Stbd',
       JSON.stringify(layout.heads));
    const want = ['pressure','dome','portTop','stbdTop','portMid','stbdMid',
                  'portBot','stbdBot','portSump','stbdSump','portSound','stbdSound',
                  'holdTemp','holdPress'];
    ok('and the fields are interleaved Top/Mid/Bot/Sump/Sounding, Port then Stbd',
       JSON.stringify(layout.keys) === JSON.stringify(want), JSON.stringify(layout.keys));
  }

  // ---- the jobs toolbar: a clean two-column grid, not a ragged wrap -------
  {
    await p.click('#topTabs button[data-tab="jobs"]');
    await p.waitForTimeout(200);
    const items = await p.evaluate(() => {
      const bar = document.querySelector('.jobs-toolbar');
      return [...bar.children]
        .filter(el => getComputedStyle(el).display !== 'none' &&
                       el.className.indexOf('view-switch') === -1)
        .map(el => Math.round(el.getBoundingClientRect().width));
    });
    // grid-auto-flow keeps DOM order, so consecutive pairs are the rows --
    // no need to bucket by pixel position, which one rounding error away
    // splits a row of two into two rows of one.
    ok('an even number of items follow the view-switch, so nothing is left alone',
       items.length % 2 === 0, items.length);
    const pairs = [];
    for(let i = 0; i < items.length; i += 2) pairs.push([items[i], items[i + 1]]);
    const evenWidths = pairs.every(([a, b]) => Math.abs(a - b) <= 2);
    ok('and each pair is the same width', evenWidths, pairs.map(x => x.join('/')).join(', '));
  }

  // ---- a flags row: every button the same height, whichever one wraps ----
  {
    await p.click('#topTabs button[data-tab="forms"]');
    await p.waitForTimeout(200);
    const heights = await p.evaluate(() =>
      [...document.querySelectorAll('#extraSection .flags-row')][0]
        .querySelectorAll('.yn-toggle, .btn-primary')
      && [...document.querySelectorAll('#extraSection .flags-row > *')]
           .filter(el => getComputedStyle(el).display !== 'none')
           .map(el => Math.round(el.getBoundingClientRect().height)));
    const spread = Math.max(...heights) - Math.min(...heights);
    ok('RA SF-23, Photo and Add to AD-19 are all the same height',
       spread <= 1, JSON.stringify(heights));
  }

  // ---- the Calculator has a way back to Tools -----------------------------
  {
    await p.click('#topTabs button[data-tab="tools"]');
    await p.waitForTimeout(200);
    await p.click('[data-tool="calc"]');
    await p.waitForTimeout(200);
    const hasBack = await p.locator('#calcBackBtn').isVisible().catch(() => false);
    ok('the Calculator has a back button', hasBack);
    if(hasBack){
      await p.click('#calcBackBtn');
      await p.waitForTimeout(150);
      ok('and it actually goes back to the tool launcher',
         await p.locator('#toolsHome').isVisible() && await p.locator('.tool-tile').count() > 0);
    } else {
      ok('and it actually goes back to the tool launcher', false, 'no back button to click');
    }
  }

  // ---- the jobs tab reads Add Job, the list, Port Call, then the buttons ---
  // Day/Week/Month moved out to their own Calendar tab, so the To Do tab is
  // just Add Job, the plain list and Port Call -- the order is still worth
  // checking, not just that each piece still works wherever it ends up.
  {
    await p.click('#topTabs button[data-tab="jobs"]');
    await p.waitForTimeout(200);
    const order = await p.evaluate(() =>
      [...document.getElementById('jobsSection').children]
        .map(el => el.id).filter(Boolean));
    ok('Add Job, the list, Port Call, then the toolbar, in that order',
       JSON.stringify(order) ===
       JSON.stringify(['addWrap', 'listWrap', 'portCallWrap', 'fabAdd']),
       JSON.stringify(order));

    ok('Port call is collapsed on a first visit',
       await p.evaluate(() => document.getElementById('portCallWrap').classList.contains('collapsed')));
    ok('and reads "Checklists" now, not "Port call" -- it covers more than port calls',
       (await p.evaluate(() => document.getElementById('portCallToggle').textContent)).trim().indexOf('Checklists') === 0);
    ok('so its fields are not the thing taking up the screen',
       !(await p.locator('#paPort').isVisible()));

    await p.click('#portCallToggle');
    await p.waitForTimeout(150);
    ok('tapping the header opens it', await p.locator('#paPort').isVisible());
    // The glyph itself never changes -- CSS rotates the same triangle -- so
    // check the rotation, not the character.
    ok('and the chevron rotates to point down, open',
       (await p.evaluate(() => getComputedStyle(document.getElementById('portCallChevron')).transform))
         !== 'none');

    await p.reload();
    await p.waitForTimeout(700);
    ok('the choice to leave it open is remembered after a reload',
       !(await p.evaluate(() => document.getElementById('portCallWrap').classList.contains('collapsed'))));
  }

  // ---- Month view shows a dot per job, not the job's name -----------------
  {
    await p.evaluate(() => {
      const iso = d => d.toISOString().slice(0, 10);
      const t = new Date();
      const names = ['Sound all cargo tanks','Test emergency shutdown valves','Check mooring winch brakes',
        'Renew pilot ladder side ropes','Job five','Job six','Job seven','Job eight','Job nine','Job ten'];
      const prio = ['urgent','important','normal','normal','normal','normal','normal','normal','normal','normal'];
      localStorage.setItem('gasplanet_todo_v1', JSON.stringify(names.map((job, i) =>
        ({ id:'m'+i, serial:i+1, job, due:iso(t), priority:prio[i], done:false, photos:[], createdAt:iso(t) }))));
    });
    await p.reload();
    await p.waitForTimeout(700);
    await p.click('#topTabs button[data-tab="calendar"]');
    await p.click('[data-view="month"]');
    await p.waitForTimeout(300);
    const info = await p.evaluate(() => {
      const c = document.querySelector('.month-cell.is-today');
      if(!c) return null;
      const dots = [...c.querySelectorAll('.cell-dots .cell-dot')];
      const more = c.querySelector('.cell-more');
      return { count: dots.length, text: dots.map(d => d.textContent).join(''),
               classes: dots.map(d => d.className), more: more ? more.textContent : null };
    });
    ok('today\'s cell shows a dot per job, not the job\'s name',
       info && info.text === '', JSON.stringify(info));
    ok('capped at MONTH_CELL_DOT_CAP (8) rather than listing every one',
       info && info.count === 8, info && info.count);
    ok('dots coloured by priority',
       info && info.classes.some(c => /pri-urgent/.test(c)) && info.classes.some(c => /pri-important/.test(c)),
       JSON.stringify(info));
    ok('with the rest counted, not silently dropped',
       info && info.more === '+2', info && info.more);

    await p.click('.month-cell.is-today');
    await p.waitForTimeout(200);
    ok('tapping the day opens Day view, where the names are readable in full',
       await p.locator('#viewSwitch button[data-view="day"]').evaluate(b => b.classList.contains('active')));
    ok('and the day\'s jobs are named there',
       (await p.locator('#calWrap .task', { hasText: 'Sound all cargo tanks' }).count()) === 1);
  }

  // ---- Calendar is its own tab: nothing left to step aside for -------------
  // Day/Week/Month used to share the To Do tab with Add Job and Port Call and
  // had to shove them out of the way to go full screen. Now they are simply
  // not on that tab's markup at all, and visiting Calendar and coming back
  // must not touch whatever state Add Job/Port Call were left in.
  {
    await p.click('#topTabs button[data-tab="jobs"]');
    await p.waitForTimeout(200);
    const before = await p.evaluate(() => ({
      form: document.getElementById('addWrap').classList.contains('collapsed'),
      port: document.getElementById('portCallWrap').classList.contains('collapsed')
    }));
    const inCalendar = await p.evaluate(() => {
      const cal = document.getElementById('calendarSection');
      return { form: !!cal.querySelector('#addWrap'), port: !!cal.querySelector('#portCallWrap') };
    });
    ok('Calendar tab\'s markup has no Add Job form', inCalendar.form === false, JSON.stringify(inCalendar));
    ok('and no Port Call block', inCalendar.port === false, JSON.stringify(inCalendar));
    await p.click('#topTabs button[data-tab="calendar"]');
    await p.waitForTimeout(200);
    await p.click('#topTabs button[data-tab="jobs"]');
    await p.waitForTimeout(200);
    const after = await p.evaluate(() => ({
      form: document.getElementById('addWrap').classList.contains('collapsed'),
      port: document.getElementById('portCallWrap').classList.contains('collapsed')
    }));
    ok('and visiting Calendar and coming back leaves them exactly as they were',
       after.form === before.form && after.port === before.port, JSON.stringify({ before, after }));
  }

  ok('no page errors', errs.length === 0, errs.slice(0, 3).join(' | '));

  await b.close();
  srv.close();
  console.log(fail ? '\n' + fail + ' FAILED' : '\nALL PASS');
  process.exit(fail ? 1 : 0);
})();
