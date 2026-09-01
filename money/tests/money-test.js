// The Ledger app, driven in a real browser.
//
// The point of this suite is the arithmetic. The app runs every instrument
// month by month through one loop; every figure checked here is re-derived
// from the published closed form instead, written out in this file against
// its own definition. Two routes to the same number is the only check worth
// having — a wrong EMI renders exactly as convincingly as a right one, and
// the screen cannot tell you which it is.
const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs'); const path = require('path');

let fails = 0;
const ok = (n,c,x) => { console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c) fails++; };
const near = (a,b,tol) => Math.abs(a-b) <= (tol === undefined ? 0.5 : tol);

/* ---- the closed forms, written here from their definitions ---- */
// EMI = P i (1+i)^n / ((1+i)^n - 1)
const emiClosed = (P, ratePa, n) => {
  const i = ratePa/12/100, g = Math.pow(1+i, n);
  return i === 0 ? P/n : P*i*g/(g-1);
};
// Balance after k payments = P(1+i)^k - EMI((1+i)^k - 1)/i
const balClosed = (P, ratePa, n, k) => {
  const i = ratePa/12/100, E = emiClosed(P, ratePa, n), g = Math.pow(1+i, k);
  return i === 0 ? P - E*k : P*g - E*(g-1)/i;
};
// FD, cumulative: A = P(1 + r/(100 m))^(m t)
const fdClosed = (P, r, m, months) => P * Math.pow(1 + r/(100*m), m*months/12);
// RD: M = SUM_k A (1 + r/(100 m))^((N-k+1)/(12/m))
const rdClosed = (A, r, m, N) => {
  let s = 0;
  for(let k = 1; k <= N; k++) s += A * Math.pow(1 + r/(100*m), (N-k+1)/(12/m));
  return s;
};
// Step-up SIP, grouped by year rather than by month: in year y the instalment
// is A(1+s)^y, and each of that year's twelve instalments grows for the months
// it has left. A different shape of sum from the app's running balance.
const stepUpClosed = (A, s, ratePa, m, years) => {
  const g = Math.pow(1 + ratePa/(100*m), m/12), N = years*12;
  let fv = 0;
  for(let y = 0; y < years; y++)
    for(let k = 1; k <= 12; k++){
      const month = y*12 + k;
      fv += A * Math.pow(1+s, y) * Math.pow(g, N - month + 1);
    }
  return fv;
};

(async () => {
  const APP = process.env.APP_HTML || path.join(__dirname, '..', 'index.html');
  const srv = http.createServer((q,r) => {
    r.writeHead(200, {'Content-Type':'text/html; charset=utf-8'});
    r.end(fs.readFileSync(APP));
  }).listen(8752);
  const b = await chromium.launch({
    executablePath: process.env.CHROME || '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext({ viewport:{width:390,height:844}, acceptDownloads:true });
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  await p.goto('http://localhost:8752/');

  /* ================= dates ================= */
  // toISOString() is UTC; east of Greenwich in the morning it names yesterday.
  ok('today() is the local calendar day, not the UTC one',
     await p.evaluate(() => {
       const d = new Date();
       const p2 = n => String(n).padStart(2,'0');
       return today() === d.getFullYear()+'-'+p2(d.getMonth()+1)+'-'+p2(d.getDate());
     }));
  ok('monthAdd crosses the year end',
     await p.evaluate(() => monthAdd('2026-11', 3)) === '2027-02');
  ok('monthAdd goes backwards over the year end',
     await p.evaluate(() => monthAdd('2026-02', -3)) === '2025-11');
  ok('monthDiff counts months, both ways',
     await p.evaluate(() => monthDiff('2025-11','2027-02')) === 15 &&
     await p.evaluate(() => monthDiff('2027-02','2025-11')) === -15);
  ok('a day-31 anchor clamps to a short month',
     await p.evaluate(() => dayOfMonth('2027-02', 31)) === '2027-02-28');

  /* ================= loans ================= */
  const L = { principal: 3500000, rate: 8.6, months: 240, start: '2026-01-01' };
  const emiApp = await p.evaluate(l => emiFor(l.principal, l.rate, l.months), L);
  ok('EMI matches the closed form',
     near(emiApp, emiClosed(L.principal, L.rate, L.months), 0.01),
     emiApp + ' vs ' + emiClosed(L.principal, L.rate, L.months));

  const am = await p.evaluate(l => {
    const a = amortise(Object.assign({}, l, { emi: emiFor(l.principal, l.rate, l.months) }));
    return { months: a.rows.length, totalInt: a.totalInt, payoff: a.payoff,
             b12: a.rows[11].close, b120: a.rows[119].close,
             lastClose: a.rows[a.rows.length-1].close };
  }, L);
  ok('the schedule runs exactly the tenure', am.months === 240, am.months);
  ok('balance after 12 instalments matches the closed form',
     near(am.b12, balClosed(L.principal, L.rate, L.months, 12), 1),
     am.b12 + ' vs ' + balClosed(L.principal, L.rate, L.months, 12));
  ok('balance after 120 instalments matches the closed form',
     near(am.b120, balClosed(L.principal, L.rate, L.months, 120), 1),
     am.b120 + ' vs ' + balClosed(L.principal, L.rate, L.months, 120));
  ok('the loan finishes at zero, not at a rounding crumb', near(am.lastClose, 0, 0.01), am.lastClose);
  ok('payoff month is the tenure from the first instalment', am.payoff === '2045-12', am.payoff);
  ok('total interest matches EMI x n - principal',
     near(am.totalInt, emiApp*240 - L.principal, 5),
     am.totalInt + ' vs ' + (emiApp*240 - L.principal));

  // A prepayment is the reason the schedule is simulated rather than solved.
  const pre = await p.evaluate(l => {
    const loan = Object.assign({}, l, { emi: emiFor(l.principal, l.rate, l.months),
      extras: [{ date:'2028-01-15', amount: 500000 }] });
    const a = amortise(loan);
    return { months: a.rows.length, totalInt: a.totalInt, payoff: a.payoff };
  }, L);
  ok('a prepayment shortens the loan', pre.months < 240, pre.months);
  ok('a prepayment cuts the interest', pre.totalInt < am.totalInt,
     pre.totalInt + ' vs ' + am.totalInt);
  ok('the prepaid loan still lands on zero',
     await p.evaluate(l => {
       const loan = Object.assign({}, l, { emi: emiFor(l.principal, l.rate, l.months),
         extras: [{ date:'2028-01-15', amount: 500000 }] });
       const a = amortise(loan);
       return a.rows[a.rows.length-1].close <= 0.01;
     }, L));

  // A prepayment larger than the balance must not overpay into a credit.
  ok('an oversized prepayment is trimmed to what is owed',
     await p.evaluate(l => {
       const loan = Object.assign({}, l, { emi: emiFor(l.principal, l.rate, l.months),
         extras: [{ date:'2026-03-15', amount: 99999999 }] });
       const a = amortise(loan);
       const last = a.rows[a.rows.length-1];
       return last.close === 0 && last.extra < 99999999 && a.rows.length === 3;
     }, L));

  // An EMI below the first month's interest never repays anything.
  ok('an EMI that cannot cover the interest is flagged, not looped forever',
     await p.evaluate(() => {
       const a = amortise({ principal:1000000, rate:12, months:240, emi:5000,
                            start:'2026-01-01' });
       return a.never === true && a.rows.length === 1 && a.payoff === null;
     }));
  // P i (1+i)^n / ((1+i)^n - 1) is 0/0 at zero interest; it has to be P/n.
  ok('a zero-interest loan is simply the principal over the tenure',
     near(await p.evaluate(() => emiFor(120000, 0, 12)), 10000, 0.01),
     await p.evaluate(() => emiFor(120000, 0, 12)));
  ok('a zero-interest loan clears in exactly its tenure with no interest',
     await p.evaluate(() => {
       const a = amortise({ principal:120000, rate:0, months:12, emi:10000,
                            start:'2026-01-01' });
       return a.rows.length === 12 && Math.abs(a.totalInt) < 1e-9 &&
              Math.abs(a.rows[11].close) < 1e-9;
     }));

  /* ================= deposits ================= */
  const FD = { principal: 500000, rate: 7.1, months: 60, comp: 4,
               start: '2026-01-01', payout: 'cumulative' };
  const fdApp = await p.evaluate(f => fdValue(f, '2031-01-01').maturity, FD);
  ok('FD maturity matches the closed form, compounded quarterly',
     near(fdApp, fdClosed(FD.principal, FD.rate, 4, 60), 0.5),
     fdApp + ' vs ' + fdClosed(FD.principal, FD.rate, 4, 60));
  ok('quarterly compounding beats yearly on the same rate',
     fdClosed(FD.principal, FD.rate, 4, 60) > fdClosed(FD.principal, FD.rate, 1, 60));
  ok('an FD paying interest out does not compound the principal',
     await p.evaluate(f => {
       const v = fdValue(Object.assign({}, f, { payout:'periodic' }), '2031-01-01');
       return v.maturity === f.principal && Math.abs(v.periodic - f.principal*f.rate/400) < 0.01;
     }, FD));
  ok('an FD is not worth its maturity value before it matures',
     await p.evaluate(f => {
       const v = fdValue(f, '2027-01-01');
       return v.value > f.principal && v.value < v.maturity;
     }, FD));
  ok('an FD is worth exactly its maturity value on the maturity date',
     await p.evaluate(f => Math.abs(fdValue(f, '2031-01-01').value -
                                    fdValue(f, '2031-01-01').maturity) < 0.01, FD),
     await p.evaluate(f => fdValue(f,'2031-01-01').value + ' vs ' +
                           fdValue(f,'2031-01-01').maturity, FD));
  ok('an FD read after maturity does not keep growing',
     await p.evaluate(f => {
       const a = fdValue(f, '2031-01-01').value, b = fdValue(f, '2040-01-01').value;
       return Math.abs(a-b) < 0.01;
     }, FD));

  const RD = { monthly: 15000, rate: 6.8, months: 60, comp: 4, start: '2026-01-01' };
  const rdApp = await p.evaluate(r => rdValue(r, '2031-01-01').maturity, RD);
  ok('RD maturity matches the per-instalment sum',
     near(rdApp, rdClosed(RD.monthly, RD.rate, 4, 60), 0.5),
     rdApp + ' vs ' + rdClosed(RD.monthly, RD.rate, 4, 60));
  ok('RD maturity is more than the instalments paid in',
     rdApp > RD.monthly * 60, rdApp);
  // The single-rate shortcut people reach for is wrong, always in the bank's
  // favour. If the app ever drifts onto it, this catches it.
  const naive = RD.monthly * 60 * Math.pow(1 + RD.rate/100, 60/12);
  ok('RD is not the naive "everything compounds for the whole term" figure',
     Math.abs(rdApp - naive) > 1000, rdApp + ' vs naive ' + naive);
  ok('a part-run RD counts only the instalments actually paid',
     await p.evaluate(r => {
       const v = rdValue(r, '2027-01-01');
       return v.done === 13 && Math.abs(v.paid - r.monthly*13) < 0.01 && v.value > v.paid;
     }, RD));

  /* ================= the calculator ================= */
  ok('a flat SIP matches the step-up sum with the step at zero',
     near(await p.evaluate(() => calcRun({ kind:'sip', monthly:10000, lump:0,
            rate:12, years:10, step:0, comp:12 }).value),
          stepUpClosed(10000, 0, 12, 12, 10), 1));
  ok('a 10%-a-year step-up SIP matches the closed form',
     near(await p.evaluate(() => calcRun({ kind:'sip', monthly:10000, lump:0,
            rate:12, years:10, step:10, comp:12 }).value),
          stepUpClosed(10000, 0.10, 12, 12, 10), 1),
     await p.evaluate(() => calcRun({ kind:'sip', monthly:10000, lump:0, rate:12,
       years:10, step:10, comp:12 }).value) + ' vs ' + stepUpClosed(10000,0.10,12,12,10));
  ok('stepping up beats not stepping up',
     await p.evaluate(() => calcRun({kind:'sip',monthly:10000,lump:0,rate:12,years:10,step:10,comp:12}).value) >
     await p.evaluate(() => calcRun({kind:'sip',monthly:10000,lump:0,rate:12,years:10,step:0,comp:12}).value));
  ok('the instalment on the last month is the first stepped up nine times',
     near(await p.evaluate(() => calcRun({kind:'sip',monthly:10000,lump:0,rate:12,
            years:10,step:10,comp:12}).instLast), 10000*Math.pow(1.1,9), 0.01));
  ok('the calculator RD agrees with the portfolio RD on the same figures',
     near(await p.evaluate(() => calcRun({ kind:'rd', monthly:15000, lump:0,
            rate:6.8, years:5, step:0, comp:4 }).value),
          rdClosed(15000, 6.8, 4, 60), 1));
  ok('the calculator FD agrees with the portfolio FD on the same figures',
     near(await p.evaluate(() => calcRun({ kind:'lump', monthly:0, lump:500000,
            rate:7.1, years:5, step:0, comp:4 }).value),
          fdClosed(500000, 7.1, 4, 60), 1));

  // Solving backwards must land on the target it was given.
  ok('the monthly amount solved for actually reaches the target',
     await p.evaluate(() => {
       const c = { kind:'sip', monthly:0, lump:0, rate:12, years:15, step:10,
                   comp:12, target:10000000, solve:'monthly' };
       const need = calcSolveMonthly(c);
       const got = calcRun(Object.assign({}, c, { monthly:need })).value;
       return Math.abs(got - 10000000) < 1;
     }));
  ok('a lump sum already large enough asks for nothing more',
     await p.evaluate(() => calcSolveMonthly({ kind:'sip', monthly:0, lump:9000000,
       rate:12, years:15, step:10, comp:12, target:1000000, solve:'monthly' })) === 0);
  ok('the lump sum solved for actually reaches the target',
     await p.evaluate(() => {
       const c = { kind:'lump', monthly:0, lump:0, rate:9, years:8, step:0,
                   comp:4, target:2500000, solve:'lump' };
       const need = calcSolveLump(c);
       return Math.abs(calcRun(Object.assign({}, c, { lump:need })).value - 2500000) < 1;
     }));

  /* The return quoted must be the return actually earned on money that went
     in month by month. At a flat rate every period must come back at that
     same rate — a figure that drifts period to period on a constant rate is
     measuring the size of the pot, not its return. */
  ok('the headline return comes back as the rate that was put in',
     near(await p.evaluate(() => calcRun({ kind:'sip', monthly:10000, lump:0,
            rate:12, years:10, step:10, comp:12 }).annual),
          (Math.pow(1 + 0.12/12, 12) - 1) * 100, 0.001),
     await p.evaluate(() => calcRun({kind:'sip',monthly:10000,lump:0,rate:12,
       years:10,step:10,comp:12}).annual));
  ok('the pot-over-paid-in shortcut would have been far lower, and is not used',
     await p.evaluate(() => {
       const r = calcRun({kind:'sip',monthly:10000,lump:0,rate:12,years:10,step:10,comp:12});
       return (Math.pow(r.value/r.paid, 1/10) - 1) * 100 < r.annual - 5;
     }));
  ok('every year of a flat run returns the same rate, first year included',
     await p.evaluate(() => {
       const run = calcRun({ kind:'sip', monthly:10000, lump:0, rate:12, years:5,
                             step:0, comp:12 });
       const yrs = calcPeriods(run, 12);
       const want = (Math.pow(1.01, 12) - 1) * 100;
       return yrs.length === 5 && yrs.every(y => Math.abs(y.ret - want) < 0.001);
     }),
     JSON.stringify(await p.evaluate(() => calcPeriods(calcRun({kind:'sip',
       monthly:10000,lump:0,rate:12,years:5,step:0,comp:12}), 12).map(y => y.ret))));
  ok('every quarter of a flat run returns a quarter of that, compounded',
     await p.evaluate(() => {
       const run = calcRun({ kind:'rd', monthly:15000, lump:0, rate:6.8, years:3,
                             step:0, comp:4 });
       const qs = calcPeriods(run, 3);
       const want = (Math.pow(1 + 0.068/4, 1) - 1) * 100;
       return qs.length === 12 && qs.every(q => Math.abs(q.ret - want) < 0.001);
     }));
  ok('money paid in during a period is reported apart from the return',
     await p.evaluate(() => {
       const run = calcRun({ kind:'sip', monthly:10000, lump:0, rate:12, years:3,
                             step:0, comp:12 });
       const yrs = calcPeriods(run, 12);
       return Math.abs(yrs[1].inFlow - 120000) < 1;
     }));
  ok('IRR refuses a flow with no sign change rather than inventing a rate',
     await p.evaluate(() => irr([-100,-100,-100]) === null && irr([100,100]) === null));
  ok('quarters and years describe the same run',
     await p.evaluate(() => {
       const run = calcRun({ kind:'sip', monthly:10000, lump:0, rate:12, years:5,
                             step:5, comp:12 });
       const q = calcPeriods(run, 3), y = calcPeriods(run, 12);
       return q.length === 20 && y.length === 5 &&
              Math.abs(q[19].value - y[4].value) < 0.01;
     }));

  /* ================= the pie ================= */
  ok('slices are drawn for every category and add up to the whole',
     await p.evaluate(() => {
       const parts = [{label:'Rent',value:40000},{label:'Car',value:12000},
                      {label:'Food',value:18000}];
       const div = document.createElement('div');
       div.innerHTML = donut(parts, 'out', '70000');
       const arcs = div.querySelectorAll('circle');
       const total = Array.from(arcs).reduce((s,c) =>
         s + parseFloat(c.getAttribute('stroke-dasharray').split(' ')[0]) + 2, 0);
       const C = 2*Math.PI*62;
       return arcs.length === 3 && Math.abs(total - C) < 1;
     }));
  ok('past six categories the rest fold into one Other slice',
     await p.evaluate(() => {
       const parts = [];
       for(let i=0;i<11;i++) parts.push({label:'C'+i, value:1000-i*10});
       const div = document.createElement('div');
       div.innerHTML = donut(parts, 'out', 'x');
       return div.querySelectorAll('circle').length === 7 &&
              /Other \(5\)/.test(div.textContent);
     }));
  ok('a category keeps its colour when a smaller one is removed',
     await p.evaluate(() => {
       const mk = parts => { const d = document.createElement('div');
         d.innerHTML = donut(parts, 'x', 'y');
         return Array.from(d.querySelectorAll('circle')).map(c => c.getAttribute('stroke')); };
       const a = mk([{label:'A',value:100},{label:'B',value:50},{label:'C',value:10}]);
       const b = mk([{label:'A',value:100},{label:'B',value:50}]);
       return a[0] === b[0] && a[1] === b[1];
     }));
  ok('nothing to chart says so rather than drawing an empty ring',
     /Nothing to chart/.test(await p.evaluate(() => donut([], 'x', 'y'))));

  /* ================= the app, driven ================= */
  await p.click('#nav button[data-tab="income"]');
  await p.click('[data-add="income"]');
  await p.fill('[data-k="name"]', 'Salary');
  await p.fill('[data-k="amount"]', '250000');
  await p.fill('[data-k="start"]', '2026-01-01');
  await p.click('#dlgSave');
  await p.click('#nav button[data-tab="income"]');
  ok('an income source is saved and listed',
     /Salary/.test(await p.textContent('#tab-income')));
  ok('it survives a reload', await (async () => {
     await p.reload(); await p.waitForTimeout(150);
     await p.click('#nav button[data-tab="income"]');
     return /Salary/.test(await p.textContent('#tab-income'));
  })());

  await p.click('#nav button[data-tab="loans"]');
  await p.click('[data-add="loan"]');
  await p.fill('[data-k="name"]', 'Home loan');
  await p.fill('[data-k="principal"]', '3500000');
  await p.fill('[data-k="rate"]', '8.6');
  await p.fill('[data-k="months"]', '240');
  await p.fill('[data-k="start"]', '2026-01-01');
  await p.click('#dlgSave');
  const loanTxt = await p.textContent('#tab-loans');
  ok('a loan with no EMI entered works one out', /EMI/.test(loanTxt) && !/NaN/.test(loanTxt));
  ok('the loan card names the month it clears', /clear by/.test(loanTxt), loanTxt.slice(0,200));

  await p.click('#nav button[data-tab="spend"]');
  await p.click('[data-add="spend"]');
  await p.fill('[data-k="amount"]', '4200');
  await p.fill('[data-k="note"]', 'Diesel');
  await p.click('#dlgSave');
  ok('an expense lands in the current month',
     /Diesel/.test(await p.textContent('#tab-spend')));

  /* A rate a day needs enough days behind it. Dividing one expense by one
     elapsed day and multiplying by thirty produced a month-end figure larger
     than the year's income. */
  ok('no rate a day is quoted at the very start of a month',
     await p.evaluate(() => {
       DB.spend = [{ id:'r1', date: thisMonth()+'-01', amount:4000, cat:'Food' }];
       S.month = thisMonth(); renderSpend();
       const t = document.getElementById('tab-spend').textContent;
       const early = new Date().getDate() < 5;
       return early ? /Too little of the month/.test(t) : /a day over/.test(t);
     }));
  ok('the rate a day covers the span the spending actually falls in',
     await p.evaluate(() => {
       DB.spend = [{ id:'r1', date: thisMonth()+'-20', amount:20000, cat:'Food' }];
       S.month = thisMonth(); renderSpend();
       const t = document.getElementById('tab-spend').textContent;
       const m = t.match(/a day over (\d+) days/);
       return !!m && Number(m[1]) >= 20;
     }),
     await p.evaluate(() => document.getElementById('tab-spend').textContent.slice(0,300)));
  ok('a month-end projection never exceeds the month it is projecting',
     await p.evaluate(() => {
       DB.spend = [{ id:'r1', date: thisMonth()+'-28', amount:30000, cat:'Food' }];
       S.month = thisMonth(); renderSpend();
       const days = new Date(Number(thisMonth().slice(0,4)),
                             Number(thisMonth().slice(5,7)), 0).getDate();
       const t = document.getElementById('tab-spend').textContent;
       const m = t.match(/a day over (\d+) days/);
       return !m || Number(m[1]) <= days;
     }));
  ok('a long value in the middle of the pie is set smaller so it clears the ring',
     await p.evaluate(() => {
       const d = document.createElement('div');
       d.innerHTML = donut([{label:'A',value:12989200}], 'invested', '\u20B91,29,89,200');
       return /font-size:12px/.test(d.querySelector('.pc1').getAttribute('style'));
     }));
  await p.evaluate(() => { DB.spend = []; save('spend'); });

  await p.click('#nav button[data-tab="spend"]');
  await p.click('[data-add="spend"]');
  await p.fill('[data-k="amount"]', '4200');
  await p.fill('[data-k="note"]', 'Diesel');
  await p.click('#dlgSave');

  await p.click('#nav button[data-tab="month"]');
  const monthTxt = await p.textContent('#tab-month');
  ok('the month view carries income, the EMI and the spending',
     /Income due/.test(monthTxt) && /Loan instalments/.test(monthTxt) &&
     /Day-to-day spending/.test(monthTxt));
  ok('no figure on the month view reads NaN', !/NaN/.test(monthTxt),
     (monthTxt.match(/.{0,40}NaN.{0,40}/)||[''])[0]);
  ok('the month view draws its pie', await p.locator('#tab-month svg.pie').count() > 0);

  /* ---- storage failures roll back rather than being swallowed ---- */
  ok('a failed write leaves the store and the screen agreeing',
     await p.evaluate(() => {
       const before = localStorage.getItem('money_spend_v1');
       const real = localStorage.setItem.bind(localStorage);
       const alertReal = window.alert; window.alert = () => {};
       localStorage.setItem = (k,v) => { if(k === 'money_spend_v1') throw new Error('quota'); real(k,v); };
       DB.spend.push({ id:'x', date:today(), amount:1, cat:'Other' });
       const okSave = save('spend');
       localStorage.setItem = real; window.alert = alertReal;
       return okSave === false &&
              localStorage.getItem('money_spend_v1') === before &&
              !DB.spend.some(s => s.id === 'x');
     }));

  /* ---- the backup nag ---- */
  ok('the backup banner appears once there is something to lose',
     await p.evaluate(() => { localStorage.removeItem('money_lastBackup_all');
       localStorage.removeItem('money_backupSnooze'); renderBanner();
       return /export it/i.test(document.getElementById('backup').textContent); }));
  ok('exporting stamps the backup date',
     await (async () => {
       const dl = p.waitForEvent('download');
       await p.click('#nav button[data-tab="data"]');
       await p.click('[data-export="csv"]');
       await dl;
       return await p.evaluate(() => !!localStorage.getItem('money_lastBackup_all'));
     })());
  ok('the money app never writes a deck-log key',
     await p.evaluate(() => Object.keys(localStorage).every(k => !k.startsWith('gasplanet_'))),
     await p.evaluate(() => Object.keys(localStorage).join(',')));

  /* ---- the contract with the Android shell ----
     A WebView ignores a click on <a download> completely: no error, no file,
     nothing. The shell catches that click in the capture phase, reads the blob
     back and writes it to Downloads. That only works while the page keeps
     saving files exactly this way, so the contract is pinned from this side
     too — if the export is ever rewritten to use something else, the APK
     silently stops being able to back anything up, and this fails instead. */
  const caught = await p.evaluate(() => new Promise(resolve => {
    const seen = [];
    document.addEventListener('click', function(e){
      const a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
      if(!a || !a.href) return;
      e.preventDefault(); e.stopPropagation();
      seen.push({ name: a.getAttribute('download'), href: a.href });
      fetch(a.href).then(r => r.text()).then(text => {
        resolve({ name: seen[0].name, scheme: seen[0].href.split(':')[0],
                  head: text.slice(0, 60), bytes: text.length });
      }).catch(err => resolve({ error: String(err) }));
    }, true);
    exportCsv();
  }));
  ok('the CSV export is a click on an <a download>, which the shell can catch',
     !caught.error && caught.scheme === 'blob', JSON.stringify(caught));
  ok('the file it hands over is named and non-empty',
     /^ledger-\d{4}-\d{2}-\d{2}\.csv$/.test(caught.name || '') && caught.bytes > 50,
     JSON.stringify(caught));
  ok('the blob is still readable when the shell gets to it',
     /^"list","id","name"/.test(caught.head || ''), caught.head);

  const caughtJson = await p.evaluate(() => new Promise(resolve => {
    document.addEventListener('click', function(e){
      const a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
      if(!a || !a.href) return;
      e.preventDefault(); e.stopPropagation();
      const name = a.getAttribute('download');
      fetch(a.href).then(r => r.text())
        .then(t => resolve({ name, ok: JSON.parse(t).app === 'ledger' }))
        .catch(err => resolve({ error: String(err) }));
    }, true);
    exportJson();
  }));
  ok('the JSON backup goes the same way and is a Ledger backup',
     caughtJson.ok === true && /\.json$/.test(caughtJson.name || ''),
     JSON.stringify(caughtJson));

  ok('the page reports a build, which CI reads to name the APK',
     /^v\d/.test(await p.evaluate(() => APP_BUILD)),
     await p.evaluate(() => APP_BUILD));

  ok('no page errors anywhere in the run', errs.length === 0, errs.join(' | '));

  await b.close(); srv.close();
  console.log(fails ? fails + ' failed' : 'all passed');
  process.exit(fails ? 1 : 0);
})();
