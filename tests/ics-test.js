// Calendar export (.ics) -- the app cannot sync live with Apple Calendar,
// Google Calendar or Fantastical (no network call on any path that matters),
// so this is a one-way iCalendar export instead: every pending job with a
// due date becomes an all-day VEVENT, done jobs and undated jobs stay out.
const { chromium } = require('playwright-core');
const fs = require('fs');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

// RFC 5545 line unfolding: a continuation line starts with a single space.
function unfold(text){
  return text.replace(/\r\n /g, '').split('\r\n').filter(Boolean);
}

(async () => {
  const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext({ viewport: { width: 900, height: 900 }, acceptDownloads: true });
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  await p.goto('file://' + process.env.APP_HTML);
  await p.waitForTimeout(500);

  const add = async (job, due, opts = {}) => {
    await p.fill('#inJob', job);
    await p.fill('#inDue', due);
    if(opts.priority) await p.selectOption('#inPriority', opts.priority);
    await p.click('#addBtn');
    await p.waitForTimeout(150);
    if(opts.rem){
      await p.locator('.task', { hasText: job }).locator('[data-action="edit"]').click();
      await p.fill('#edRem', opts.rem);
      await p.click('[data-ed="save"]');
      await p.waitForTimeout(150);
    }
  };

  const longRemark = 'Checked against the manufacturer’s manual, section 4, ' +
    'all readings within tolerance, photographs attached for the file, ' +
    'nothing further required before the next survey.';

  await add('Sound all tanks', '2026-05-10', { priority: 'urgent', rem: 'Ballast and cargo, both sides' });
  await add('Routine, with a comma; and a semicolon', '2026-05-11');
  await add('Long remark job', '2026-05-12', { rem: longRemark });
  await add('No date job', '', {});
  await add('Already done job', '2026-05-09');

  await p.selectOption('#filterStatus', 'all');
  await p.locator('.task', { hasText: 'Already done job' }).locator('.tick').click();
  await p.waitForSelector('.date-card');
  await p.fill('.dc-input', '2026-05-09');
  await p.click('[data-dc="ok"]');
  await p.waitForTimeout(200);

  const dl = p.waitForEvent('download');
  await p.click('#exportIcsBtn');
  const download = await dl;
  const outPath = process.env.OUT + '/export.ics';
  await download.saveAs(outPath);
  const raw = fs.readFileSync(outPath, 'utf8');
  const lines = unfold(raw);

  ok('filename ends .ics', /\.ics$/.test(download.suggestedFilename()), download.suggestedFilename());
  ok('opens with VCALENDAR', lines[0] === 'BEGIN:VCALENDAR', lines[0]);
  ok('closes with VCALENDAR', lines[lines.length - 1] === 'END:VCALENDAR', lines[lines.length - 1]);
  ok('declares VERSION 2.0', lines.indexOf('VERSION:2.0') > -1);
  ok('lines use CRLF', raw.indexOf('\r\n') > -1);

  const events = raw.split('BEGIN:VEVENT').slice(1).map(function(chunk){
    return unfold('BEGIN:VEVENT' + chunk.split('END:VEVENT')[0] + 'END:VEVENT');
  });
  ok('one event per pending, dated job', events.length === 3, events.length);

  const find = title => events.find(ev => ev.some(l => l === 'SUMMARY:' + title));
  ok('undated job excluded', !find('No date job'));
  ok('completed job excluded', !events.some(ev => ev.some(l => /Already done job/.test(l))));

  const sound = find('Sound all tanks');
  ok('found the sound-tanks event', !!sound, JSON.stringify(events));
  if(sound){
    ok('all-day DTSTART with no dashes', sound.indexOf('DTSTART;VALUE=DATE:20260510') > -1, sound);
    ok('DTSTAMP present', sound.some(l => /^DTSTAMP:\d{8}T\d{6}Z$/.test(l)), sound);
    ok('UID present and namespaced', sound.some(l => /^UID:.+@gasplanet-decklog$/.test(l)), sound);
    ok('priority carried in the description', sound.indexOf('DESCRIPTION:Priority: Urgent\\nRemarks: Ballast and cargo\\, both sides') > -1, sound);
  }

  const comma = find('Routine\\, with a comma\\; and a semicolon');
  ok('comma and semicolon escaped in SUMMARY', !!comma, JSON.stringify(events.map(function(ev){ return ev.find(function(l){ return l.indexOf('SUMMARY') === 0; }); })));

  const longEv = events.find(ev => ev.some(l => l === 'SUMMARY:Long remark job'));
  ok('found the long-remark event', !!longEv);
  if(longEv){
    const desc = longEv.find(l => l.indexOf('DESCRIPTION:') === 0);
    ok('folded description unfolds back to the full remark',
       desc === 'DESCRIPTION:Priority: Normal\\nRemarks: ' + longRemark.replace(/,/g, '\\,'),
       desc);
    // and prove the raw (folded) file actually needed more than one physical
    // line for it -- otherwise the fold path was never exercised at all.
    const foldedBlock = raw.split('SUMMARY:Long remark job')[1].split('END:VEVENT')[0];
    ok('the raw file actually folded this line', (foldedBlock.match(/\r\n /g) || []).length > 0,
       foldedBlock.length);
  }

  ok('no page errors', errs.length === 0, errs.join(' | '));
  await b.close();
  console.log('');
  console.log(fail ? fail + ' FAILED' : 'ALL PASS');
  process.exit(fail ? 1 : 0);
})();
