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
       // No rows: not one instalment can be written down honestly, because
       // none of it would reach the principal.
       return a.never === true && a.rows.length === 0 && a.payoff === null;
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

  /* ---- a floating rate ----
     The rate on the card is only the rate the loan started at. When the bank
     moves it, everything downstream is wrong from that date on, and wrong
     quietly, which is the whole reason this exists. */
  ok('with no rate changes the schedule is exactly what it always was',
     await p.evaluate(l => {
       const a = amortise(Object.assign({}, l, { emi: emiFor(l.principal, l.rate, l.months) }));
       return a.rows.length === 240 && a.payoff === '2045-12';
     }, L));
  ok('the rate in force is read per month, and the changes need not be in order',
     await p.evaluate(l => {
       const loan = Object.assign({}, l, { rates:[
         { id:'r1', from:'2030-04-01', rate:9.6 },
         { id:'r2', from:'2027-07-01', rate:7.9 } ]});
       return rateAt(loan, '2026-06') === 8.6 && rateAt(loan, '2027-06') === 8.6 &&
              rateAt(loan, '2027-07') === 7.9 && rateAt(loan, '2030-03') === 7.9 &&
              rateAt(loan, '2030-04') === 9.6 && rateAt(loan, '2044-01') === 9.6;
     }, L));

  const flat = await p.evaluate(l =>
    (a => ({ months:a.rows.length, int:a.totalInt }))(
      amortise(Object.assign({}, l, { emi: emiFor(l.principal, l.rate, l.months) }))), L);
  const risen = await p.evaluate(l =>
    (a => ({ months:a.rows.length, int:a.totalInt }))(
      amortise(Object.assign({}, l, { emi: emiFor(l.principal, l.rate, l.months),
        rates:[{ id:'r1', from:'2029-01-01', rate:10.6 }] }))), L);
  const cut = await p.evaluate(l =>
    (a => ({ months:a.rows.length, int:a.totalInt }))(
      amortise(Object.assign({}, l, { emi: emiFor(l.principal, l.rate, l.months),
        rates:[{ id:'r1', from:'2029-01-01', rate:6.6 }] }))), L);
  ok('a rate rise with the EMI left alone pushes the payoff out',
     risen.months > flat.months, risen.months + ' vs ' + flat.months);
  ok('and costs more interest',
     risen.int > flat.int, Math.round(risen.int) + ' vs ' + Math.round(flat.int));
  ok('a rate cut clears it sooner and costs less',
     cut.months < flat.months && cut.int < flat.int,
     cut.months + ' vs ' + flat.months);

  ok('a change dated before the loan starts simply is the rate',
     await p.evaluate(l => {
       const loan = Object.assign({}, l, { rates:[{ id:'r1', from:'2020-01-01', rate:7.0 }] });
       return Math.abs(rateAt(loan, ym(l.start)) - 7.0) < 1e-9;
     }, L));

  /* A rise can take the EMI below the interest part way through. The card
     would otherwise print a payoff a century out as though it meant something. */
  ok('a rise that leaves the EMI short of the interest is caught mid-loan',
     await p.evaluate(l => {
       const a = amortise(Object.assign({}, l, {
         emi: emiFor(l.principal, l.rate, l.months),
         rates:[{ id:'r1', from:'2028-01-01', rate:40 }] }));
       return a.never === true && a.payoff === null &&
              a.rows.length > 12 && a.rows.length < 240;
     }, L),
     await p.evaluate(l => amortise(Object.assign({}, l, {
       emi: emiFor(l.principal, l.rate, l.months),
       rates:[{ id:'r1', from:'2028-01-01', rate:40 }] })).rows.length, L));

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

  /* ---- the iPhone home-screen case ----
     navigator.standalone is true only in an iOS home-screen app, and there a
     click on <a download> saves nothing at all — no file, no error. Since the
     export IS the backup in this app, every one of these matters. */
  const ios = async (fn, setup) => p.evaluate(async ({ setup }) => {
    const realShare = navigator.share, realCanShare = navigator.canShare;
    const realAlert = window.alert;
    let alerted = null, shared = null, anchors = 0;
    const countAnchor = e => {
      const a = e.target.closest && e.target.closest('a[download]');
      if(a){ anchors++; e.preventDefault(); e.stopPropagation(); }
    };
    document.addEventListener('click', countAnchor, true);
    Object.defineProperty(navigator, 'standalone', { value:true, configurable:true });
    window.alert = m => { alerted = m; };
    if(setup === 'ok'){
      navigator.canShare = () => true;
      navigator.share = o => { shared = o.files[0].name; return Promise.resolve(); };
    } else if(setup === 'cancel'){
      navigator.canShare = () => true;
      navigator.share = () => Promise.reject(Object.assign(new Error('x'), { name:'AbortError' }));
    } else {
      navigator.canShare = () => false;
      navigator.share = undefined;
    }
    localStorage.removeItem('money_lastBackup_all');
    await download('ledger-test.json', '{"app":"ledger"}', 'application/json');
    const stamped = !!localStorage.getItem('money_lastBackup_all');

    document.removeEventListener('click', countAnchor, true);
    delete navigator.standalone;
    navigator.share = realShare; navigator.canShare = realCanShare;
    window.alert = realAlert;
    return { stamped, shared, alerted, anchors };
  }, { setup });

  const iosOk = await ios(null, 'ok');
  ok('on an iPhone home-screen app the backup goes through the share sheet',
     iosOk.shared === 'ledger-test.json' && iosOk.anchors === 0, JSON.stringify(iosOk));
  ok('a shared backup is recorded as a backup', iosOk.stamped === true);

  const iosCancel = await ios(null, 'cancel');
  ok('cancelling the share sheet is NOT recorded as a backup',
     iosCancel.stamped === false, JSON.stringify(iosCancel));

  const iosNone = await ios(null, 'none');
  ok('with no way to save, it says so rather than pretending',
     /Safari/.test(iosNone.alerted || ''), JSON.stringify(iosNone));
  ok('and nothing that saved nothing is recorded as a backup',
     iosNone.stamped === false, JSON.stringify(iosNone));

  /* <dialog> landed in Safari 15.4. An older iPad would otherwise reach a
     button that does nothing at all, with no clue why. */
  ok('the editor still opens where <dialog> is not supported',
     await p.evaluate(() => {
       const d = document.getElementById('dlg');
       const real = d.showModal;
       d.showModal = undefined;
       openDialog();
       const shown = d.hasAttribute('open') && d.classList.contains('fallback') &&
                     getComputedStyle(d).display !== 'none';
       closeDialog();
       const hidden = !d.hasAttribute('open') && getComputedStyle(d).display === 'none';
       d.showModal = real;
       return shown && hidden;
     }));

  /* ---- price history and the charts ---- */
  ok('a price written down is kept, dated, one point a day',
     await p.evaluate(() => {
       const h = { id:'h1', kind:'equity', units:10, buy:100, price:120, asof:'2026-09-01' };
       notePrice(h);
       notePrice(h);                                   // same day, same price
       h.price = 130; notePrice(h);                    // same day, changed
       h.asof = '2026-09-02'; h.price = 140; notePrice(h);
       return h.hist.length === 2 && h.hist[0].p === 130 && h.hist[1].p === 140 &&
              h.hist.every(x => x.id && x.upd && x.src === 'typed');
     }),
     await p.evaluate(() => {
       const h = { id:'h1', kind:'equity', price:120, asof:'2026-09-01' };
       notePrice(h); h.price = 130; notePrice(h);
       return JSON.stringify(h.hist);
     }));
  ok('a deposit gets no price history, having no price to write down',
     await p.evaluate(() => {
       const h = { id:'h2', kind:'fd', principal:100000, rate:7, months:12 };
       notePrice(h);
       return h.hist === undefined;
     }));
  ok('history points carry a source, so a fetched price can slot in later',
     await p.evaluate(() => {
       const h = { id:'h3', kind:'mf', price:50, asof:'2026-09-01' };
       notePrice(h);
       return h.hist[0].src === 'typed' && 'p' in h.hist[0] && 'd' in h.hist[0];
     }));

  ok('changing a holding writes one portfolio reading, and only one a day',
     await p.evaluate(() => {
       DB.invest = []; DB.snap = []; save('invest'); save('snap');
       upsert('invest', { id:'e1', kind:'equity', units:10, buy:100, price:120,
                          asof:today() });
       const one = DB.snap.length;
       upsert('invest', { id:'e1', kind:'equity', units:10, buy:100, price:150,
                          asof:today() });
       return one === 1 && DB.snap.length === 1 && DB.snap[0].value === 1500 &&
              DB.snap[0].cost === 1000;
     }),
     await p.evaluate(() => JSON.stringify(DB.snap)));

  ok('one reading draws no line, and says why rather than an empty box',
     /One reading so far/.test(await p.evaluate(() =>
       lineChart([{ d:'2026-09-01', value:100, cost:90 }]))));
  ok('the value line and the put-in line are drawn on one scale, not two',
     await p.evaluate(() => {
       const d = document.createElement('div');
       d.innerHTML = lineChart([
         { d:'2026-01-01', value:100000, cost:100000 },
         { d:'2026-06-01', value:140000, cost:120000 },
         { d:'2026-09-01', value:180000, cost:120000 }]);
       const paths = d.querySelectorAll('svg.line path');
       // Same value on both series must land on the same height, which is the
       // whole difference between one axis and the two-scale chart that lies.
       const d2 = document.createElement('div');
       d2.innerHTML = lineChart([
         { d:'2026-01-01', value:50000, cost:50000 },
         { d:'2026-09-01', value:50000, cost:50000 }]);
       const p2 = d2.querySelectorAll('svg.line path');
       const ys = s => (s.match(/[\d.]+ ([\d.]+)/g) || []).join();
       return paths.length === 2 &&
              ys(p2[0].getAttribute('d')) === ys(p2[1].getAttribute('d'));
     }));
  /* A zero baseline on a line chart of a portfolio that went from eight lakh
     to nine squeezes the whole story into the top of the box. The scale runs
     over the figures instead, and both ends of it are printed so the reader
     is not misled by the steepness. */
  ok('the scale runs over the figures rather than from zero',
     await p.evaluate(() => {
       const d = document.createElement('div');
       d.innerHTML = lineChart([
         { d:'2026-01-01', value:800000, cost:790000 },
         { d:'2026-05-01', value:860000, cost:800000 },
         { d:'2026-09-01', value:900000, cost:800000 }]);
       // Across both series — the cost line is deliberately the flat one, so
       // it is the pair that has to fill the box, not either alone.
       const ys = Array.from(d.querySelectorAll('svg.line path'))
         .reduce((a,pa) => a.concat((pa.getAttribute('d').match(/ ([\d.]+)/g) || [])
           .map(Number)), []);
       return (Math.max.apply(null, ys) - Math.min.apply(null, ys)) > 60;
     }),
     await p.evaluate(() => lineChart([
       { d:'2026-01-01', value:800000, cost:790000 },
       { d:'2026-09-01', value:900000, cost:800000 }]).slice(0,400)));
  ok('and both ends of that scale are printed, so it cannot mislead quietly',
     await p.evaluate(() => {
       const d = document.createElement('div');
       d.innerHTML = lineChart([
         { d:'2026-01-01', value:800000, cost:790000 },
         { d:'2026-09-01', value:900000, cost:800000 }]);
       return d.querySelectorAll('svg.line text.ax').length === 4;
     }));
  ok('a scale never runs below zero, whatever the figures',
     await p.evaluate(() => {
       const d = document.createElement('div');
       d.innerHTML = lineChart([{ d:'2026-01-01', value:10, cost:5 },
                                { d:'2026-09-01', value:40, cost:5 }]);
       const labels = Array.from(d.querySelectorAll('svg.line text.ax'))
                           .map(t => t.textContent);
       return !labels.some(l => l.indexOf('-') === 0);
     }));

  ok('both lines are labelled, so neither is told apart by colour alone',
     await p.evaluate(() => {
       const d = document.createElement('div');
       d.innerHTML = lineChart([
         { d:'2026-01-01', value:100000, cost:100000 },
         { d:'2026-09-01', value:180000, cost:120000 }]);
       return d.querySelectorAll('svg.line text.ll').length === 2 &&
              /Worth now/.test(d.textContent) && /Put in/.test(d.textContent);
     }));
  ok('a sparkline needs two prices, and draws one point per price',
     await p.evaluate(() => {
       const none = sparkline([{ d:'2026-09-01', p:10 }], 100, 30);
       const d = document.createElement('div');
       d.innerHTML = sparkline([{d:'2026-09-01',p:10},{d:'2026-09-02',p:12},
                                {d:'2026-09-03',p:11}], 100, 30);
       const pl = d.querySelector('polyline');
       return none === '' && pl.getAttribute('points').split(' ').length === 3;
     }));
  ok('a sparkline reads green when it ended up and red when it ended down',
     await p.evaluate(() => {
       const up = sparkline([{p:10},{p:14}], 100, 30);
       const dn = sparkline([{p:14},{p:10}], 100, 30);
       return /--in/.test(up) && /--out/.test(dn);
     }));

  ok('updating every price at once stamps them all with the one date',
     await p.evaluate(() => {
       DB.invest = [
         { id:'q1', kind:'equity', name:'A', units:10, buy:100, price:100 },
         { id:'q2', kind:'mf',     name:'B', units:20, buy:50,  price:50 },
         { id:'q3', kind:'fd',     name:'C', principal:1000, rate:7, months:12,
           start:'2026-01-01' }];
       DB.snap = []; save('invest'); save('snap');
       pricesEditor();
       const fields = Array.from(document.querySelectorAll('#dlgBody [data-k]'))
                           .map(n => n.dataset.k);
       document.querySelector('[data-k="asof"]').value = '2026-09-15';
       document.querySelector('[data-k="p_q1"]').value = '130';
       document.querySelector('[data-k="p_q2"]').value = '60';
       document.getElementById('dlgSave').click();
       const a = DB.invest.find(x => x.id === 'q1');
       const b = DB.invest.find(x => x.id === 'q2');
       return fields.indexOf('p_q3') === -1 &&          // the FD is not offered
              a.price === 130 && a.asof === '2026-09-15' &&
              b.price === 60  && a.hist[0].d === '2026-09-15' &&
              DB.snap.length === 1;
     }),
     await p.evaluate(() => JSON.stringify(DB.invest.map(h => [h.id, h.price, h.asof]))));
  await p.evaluate(() => { DB.invest = []; DB.snap = []; save('invest'); save('snap'); });

  /* ---- quick add, search and the month trend ---- */
  await p.evaluate(() => {
    DB.spend = []; DB.fixed = []; S.month = thisMonth();
    save('spend'); save('fixed'); save('set');
    SPEND_Q = ''; LAST_ADD = null;
    show('spend');
  });
  ok('an amount and one category tap files an expense for today',
     await p.evaluate(() => {
       document.getElementById('qAmt').value = '450';
       document.querySelector('[data-qcat]').click();
       return DB.spend.length === 1 && DB.spend[0].amount === 450 &&
              DB.spend[0].date === today();
     }),
     await p.evaluate(() => JSON.stringify(DB.spend)));
  ok('the amount box is cleared, so the next one cannot double up',
     await p.evaluate(() => el('qAmt').value === ''));
  ok('a tap with no amount files nothing rather than a zero',
     await p.evaluate(() => {
       const before = DB.spend.length;
       el('qAmt').value = '';
       document.querySelector('[data-qcat]').click();
       return DB.spend.length === before;
     }));
  ok('a mistapped category can be taken straight back',
     await p.evaluate(() => {
       const before = DB.spend.length;
       document.querySelector('[data-undoadd]').click();
       return DB.spend.length === before - 1;
     }));
  ok('and undoing leaves a tombstone, so it stays gone after a merge',
     await p.evaluate(() => DB.tomb.length > 0));
  ok('the quick categories are the ones actually used most, recently',
     await p.evaluate(() => {
       DB.spend = []; DB.tomb = [];
       const m = thisMonth();
       for(let i=0;i<5;i++) DB.spend.push({ id:'f'+i, date:m+'-0'+(i+1), amount:100, cat:'Fuel' });
       for(let i=0;i<3;i++) DB.spend.push({ id:'g'+i, date:m+'-1'+i, amount:100, cat:'Groceries' });
       save('spend'); save('tomb');
       const q = quickCats();
       return q[0] === 'Fuel' && q[1] === 'Groceries' && q.length === 6 &&
              new Set(q).size === 6;
     }),
     await p.evaluate(() => quickCats().join()));

  ok('search reaches expenses outside the month on screen',
     await p.evaluate(() => {
       DB.spend = [
         { id:'o1', date:'2025-03-14', amount:8900, cat:'Car', note:'Clutch job' },
         { id:'o2', date: thisMonth()+'-02', amount:120, cat:'Food', note:'Tea' }];
       save('spend');
       S.month = thisMonth();
       const hits = spendSearch('clutch');
       return hits.length === 1 && hits[0].id === 'o1';
     }));
  ok('search matches the category as well as the note',
     await p.evaluate(() => spendSearch('car').length === 1));
  ok('search finds an exact amount, which is how you look for one you remember',
     await p.evaluate(() => spendSearch('8900').length === 1 &&
                            spendSearch('8901').length === 0));
  ok('an empty search returns nothing rather than everything',
     await p.evaluate(() => spendSearch('').length === 0 &&
                            spendSearch('   ').length === 0));
  ok('results are newest first',
     await p.evaluate(() => {
       DB.spend.push({ id:'o3', date:'2026-01-01', amount:50, cat:'Car', note:'Wash' });
       save('spend');
       const h = spendSearch('car');
       return h.length === 2 && h[0].id === 'o3';
     }));

  ok('the month trend covers twelve months, oldest first, ending on this one',
     await p.evaluate(() => {
       const r = monthTotals(12);
       return r.length === 12 && r[11].m === thisMonth() &&
              r[0].m === monthAdd(thisMonth(), -11);
     }));
  ok('a month total is the fixed outgoings and the day-to-day together',
     await p.evaluate(() => {
       const m = thisMonth();
       DB.spend = [{ id:'t1', date:m+'-05', amount:4000, cat:'Food' }];
       DB.fixed = [{ id:'x1', name:'Rent', amount:38000, freq:'monthly',
                     start: monthAdd(m,-6)+'-01' }];
       save('spend'); save('fixed');
       const r = monthTotals(3);
       const now = r[r.length-1];
       return now.day === 4000 && now.fixed === 38000 && now.total === 42000;
     }),
     await p.evaluate(() => JSON.stringify(monthTotals(3))));
  ok('the bars scale to the biggest month and mark the current one',
     await p.evaluate(() => {
       const rows = [{ m:'2026-07', total:10000 }, { m:'2026-08', total:20000 },
                     { m: thisMonth(), total:5000 }];
       const d = document.createElement('div');
       d.innerHTML = monthBars(rows);
       const ws = Array.from(d.querySelectorAll('.mt > i'))
                       .map(i => parseFloat(i.style.width));
       return ws[1] === 100 && ws[0] === 50 &&
              d.querySelectorAll('.mbar.on').length === 1 &&
              d.querySelector('.mbar.on').dataset.goMonth === thisMonth();
     }));
  await p.evaluate(() => {
    DB.spend = []; DB.fixed = []; DB.tomb = []; SPEND_Q = ''; LAST_ADD = null;
    save('spend'); save('fixed'); save('tomb'); render();
  });

  /* ---- cash, and a net worth that includes it ---- */
  await p.evaluate(() => { DB.accts=[]; DB.invest=[]; DB.goals=[]; DB.loans=[];
    save('accts'); save('invest'); save('goals'); save('loans'); });
  ok('a balance is whatever was last written down on or before the day asked for',
     await p.evaluate(() => {
       const a = { id:'a1', kind:'bank', name:'SBI', bals:[
         { id:'b1', d:'2026-01-10', amount:50000 },
         { id:'b3', d:'2026-09-01', amount:120000 },
         { id:'b2', d:'2026-05-05', amount:80000 } ]};
       return balanceAt(a,'2026-01-09').amount === 0 &&
              balanceAt(a,'2026-01-10').amount === 50000 &&
              balanceAt(a,'2026-05-04').amount === 50000 &&
              balanceAt(a,'2026-12-31').amount === 120000 &&
              balanceAt(a,'2026-12-31').d === '2026-09-01';
     }));
  ok('a credit card balance counts against you, however it was typed',
     await p.evaluate(() => {
       const c = { id:'c1', kind:'card', bals:[{ id:'x', d:'2026-09-01', amount:18000 }] };
       const n = { id:'c2', kind:'card', bals:[{ id:'y', d:'2026-09-01', amount:-18000 }] };
       return acctSigned(c,'2026-09-02').amount === -18000 &&
              acctSigned(n,'2026-09-02').amount === -18000;
     }));
  ok('an account never counted is left out rather than treated as zero',
     await p.evaluate(() => {
       DB.accts = [{ id:'a1', kind:'bank', bals:[{id:'b',d:'2026-09-01',amount:100000}] },
                   { id:'a2', kind:'bank', bals:[] }];
       save('accts');
       const c = cashTotal('2026-09-05');
       return c.total === 100000 && c.any === true && c.oldest === '2026-09-01';
     }));
  ok('net worth now carries the cash, which it never used to',
     await p.evaluate(() => {
       DB.accts = [{ id:'a1', kind:'bank', bals:[{id:'b',d:today(),amount:250000}] },
                   { id:'a2', kind:'card', bals:[{id:'c',d:today(),amount:30000}] }];
       DB.invest = []; DB.goals = []; DB.loans = [];
       for(const k of ['accts','invest','goals','loans']) save(k);
       const nw = netWorth();
       return nw.cash === 220000 && nw.net === 220000;
     }),
     await p.evaluate(() => JSON.stringify(netWorth())));
  ok('a closed account drops out of the total',
     await p.evaluate(() => {
       DB.accts[1].closed = true; save('accts');
       return netWorth().cash === 250000;
     }));

  /* ---- what is held, from what was actually done ---- */
  ok('units and average cost come out of the purchases, charges included',
     await p.evaluate(() => {
       const h = { id:'h', kind:'equity', units:0, buy:0, txns:[
         { id:'t1', d:'2026-01-10', kind:'buy', units:100, price:1000, charges:200 },
         { id:'t2', d:'2026-06-10', kind:'buy', units:50,  price:1600, charges:100 } ]};
       const u = holdingUnits(h);
       // (100*1000+200 + 50*1600+100) / 150
       return u.units === 150 && Math.abs(u.cost - 180300) < 0.01 &&
              Math.abs(u.buy - 1202) < 0.01 && u.from === 'txns';
     }),
     await p.evaluate(() => JSON.stringify(holdingUnits({ kind:'equity', txns:[
       { id:'t1', d:'2026-01-10', kind:'buy', units:100, price:1000, charges:200 },
       { id:'t2', d:'2026-06-10', kind:'buy', units:50, price:1600, charges:100 }]}))));
  ok('a sale takes units out at the average and books the gain',
     await p.evaluate(() => {
       const h = { kind:'equity', txns:[
         { id:'t1', d:'2026-01-10', kind:'buy',  units:100, price:1000 },
         { id:'t2', d:'2026-06-10', kind:'sell', units:40,  price:1500, charges:50 } ]};
       const u = holdingUnits(h);
       return u.units === 60 && Math.abs(u.cost - 60000) < 0.01 &&
              Math.abs(u.buy - 1000) < 0.01 &&
              Math.abs(u.realised - (40*1500 - 50 - 40*1000)) < 0.01;
     }));
  ok('selling more than is held sells only what is held',
     await p.evaluate(() => {
       const u = holdingUnits({ kind:'equity', txns:[
         { id:'t1', d:'2026-01-10', kind:'buy',  units:10, price:100 },
         { id:'t2', d:'2026-02-10', kind:'sell', units:999, price:150 } ]});
       return u.units === 0 && u.cost === 0 && u.sold === 10;
     }));
  ok('transactions are applied in date order however they were entered',
     await p.evaluate(() => {
       const a = holdingUnits({ kind:'equity', txns:[
         { id:'t2', d:'2026-06-10', kind:'sell', units:40, price:1500 },
         { id:'t1', d:'2026-01-10', kind:'buy',  units:100, price:1000 } ]});
       return a.units === 60 && Math.abs(a.buy - 1000) < 0.01;
     }));
  ok('a holding with no transactions still uses the two typed figures',
     await p.evaluate(() => {
       const u = holdingUnits({ kind:'equity', units:120, buy:1420 });
       return u.units === 120 && u.buy === 1420 && u.from === 'typed' &&
              Math.abs(u.cost - 170400) < 0.01;
     }));
  ok('and the value on the card follows the transactions once there are any',
     await p.evaluate(() => {
       const v = unitValue({ kind:'equity', units:999, buy:99, price:1500, txns:[
         { id:'t1', d:'2026-01-10', kind:'buy', units:10, price:1000 } ]});
       return v.units === 10 && v.value === 15000 && v.cost === 10000;
     }));

  /* ---- a fixed outgoing that has been paid ---- */
  ok('marking rent paid files it as an expense and stops it counting twice',
     await p.evaluate(() => {
       const m = thisMonth();
       DB.spend = []; DB.fixed = [{ id:'f1', name:'House rent', amount:38000,
         freq:'monthly', cat:'Rent', start: monthAdd(m,-6)+'-01' }];
       save('spend'); save('fixed');
       S.month = m; show('spend');
       const before = monthFigures(m);
       document.querySelector('[data-paid]').click();
       const after = monthFigures(m);
       return before.fixed === 38000 && before.spend === 0 &&
              after.fixed === 0 && after.spend === 38000 &&
              after.out === before.out;              // the total cannot move
     }),
     await p.evaluate(() => JSON.stringify(monthFigures(thisMonth()))));
  ok('and undoing it takes the expense back out',
     await p.evaluate(() => {
       document.querySelector('[data-unpaid]').click();
       const f = monthFigures(thisMonth());
       return DB.spend.length === 0 && f.fixed === 38000 && f.spend === 0;
     }));

  /* ---- what is about to happen ---- */
  ok('an FD maturing soon is raised, and one far off is not',
     await p.evaluate(() => {
       DB.loans=[]; DB.goals=[]; DB.fixed=[]; DB.accts=[];
       const soon = new Date(Date.now() + 30*86400000);
       DB.invest = [
         { id:'fd1', kind:'fd', name:'SBI', principal:500000, rate:7, months:12,
           comp:'4', start: isoFromDate(new Date(soon.getFullYear()-1, soon.getMonth(), 1)) },
         { id:'fd2', kind:'fd', name:'Far', principal:100000, rate:7, months:120,
           comp:'4', start: today() }];
       for(const k of ['loans','goals','fixed','accts','invest']) save(k);
       const up = comingUp();
       return up.some(u => /SBI/.test(u.what) && /matures/.test(u.what)) &&
              !up.some(u => /Far/.test(u.what));
     }),
     await p.evaluate(() => JSON.stringify(comingUp().map(u => u.what))));
  ok('a goal past its date is raised first, as urgent',
     await p.evaluate(() => {
       DB.invest = [];
       DB.goals = [{ id:'g1', name:'Emergency fund', target:100000,
                     by:'2020-01-01', ledger:[] }];
       save('invest'); save('goals');
       const up = comingUp();
       return up.length > 0 && up[0].urgent === true &&
              /past its date/.test(up[0].what);
     }));
  ok('with nothing pending it says nothing rather than inventing something',
     await p.evaluate(() => {
       DB.goals=[]; DB.invest=[]; DB.loans=[]; DB.fixed=[]; DB.accts=[];
       for(const k of ['goals','invest','loans','fixed','accts']) save(k);
       return comingUp().length === 0;
     }));

  /* ---- pruning ---- */
  ok('a tombstone older than a year goes; a recent one stays',
     await p.evaluate(() => {
       const old = Date.now() - 400*86400000, recent = Date.now() - 10*86400000;
       DB.tomb = [{ id:'old', upd:old }, { id:'new', upd:recent }];
       save('tomb'); prune();
       return DB.tomb.length === 1 && DB.tomb[0].id === 'new';
     }));
  ok('history stays daily for six months and thins to weekly before that',
     await p.evaluate(() => {
       const hist = [];
       for(let k = 0; k < 500; k++)
         hist.push({ id:'p'+k, d: isoFromDate(new Date(Date.now() - k*86400000)),
                     p: 100 + k });
       DB.invest = [{ id:'h1', kind:'equity', hist }];
       save('invest'); prune();
       const kept = DB.invest[0].hist;
       const cut = isoFromDate(new Date(Date.now() - 180*86400000));
       const recent = kept.filter(x => x.d >= cut).length;
       const older  = kept.filter(x => x.d <  cut).length;
       return kept.length < 500 && recent >= 175 && older > 30 && older < 60;
     }),
     await p.evaluate(() => DB.invest[0] ? DB.invest[0].hist.length : 'gone'));
  ok('a short history is left alone entirely',
     await p.evaluate(() => {
       DB.invest = [{ id:'h2', kind:'equity', hist:[
         { id:'a', d:'2020-01-01', p:1 }, { id:'b', d:'2020-02-01', p:2 } ]}];
       save('invest'); prune();
       return DB.invest[0].hist.length === 2;
     }));
  await p.evaluate(() => {
    DB.invest=[]; DB.tomb=[]; DB.accts=[]; DB.spend=[]; DB.fixed=[];
    for(const k of ['invest','tomb','accts','spend','fixed']) save(k);
    render();
  });

  /* ---- spending categories ---- */
  ok('no category is listed in two groups, and none is repeated',
     await p.evaluate(() => {
       const flat = CAT_GROUPS.reduce((a,g) => a.concat(g.c), []);
       return new Set(flat).size === flat.length &&
              flat.length === DEFAULT_CATS.length;
     }),
     await p.evaluate(() => {
       const flat = CAT_GROUPS.reduce((a,g) => a.concat(g.c), []);
       return flat.filter((c,i) => flat.indexOf(c) !== i).join(',') || 'none';
     }));
  ok('the picker offers every category exactly once, inside its group',
     await p.evaluate(() => {
       const d = document.createElement('select');
       d.innerHTML = catOptions('Rent');
       const vals = Array.from(d.querySelectorAll('option')).map(o => o.value);
       return d.querySelectorAll('optgroup').length >= 6 &&
              vals.length === S.spendCats.length &&
              new Set(vals).size === vals.length &&
              d.value === 'Rent';
     }));
  /* Opening an old expense to change its amount must not quietly re-file it
     under whatever happens to be first in the list. */
  ok('a category dropped from the list is still selectable on records that use it',
     await p.evaluate(() => {
       const d = document.createElement('select');
       d.innerHTML = catOptions('Camel feed');
       return d.value === 'Camel feed' &&
              /no longer in your list/.test(d.textContent);
     }));
  ok('a category of your own appears under Yours',
     await p.evaluate(() => {
       const keep = S.spendCats.slice();
       S.spendCats = S.spendCats.concat(['Camel feed']);
       const d = document.createElement('select');
       d.innerHTML = catOptions('Camel feed');
       const grp = Array.from(d.querySelectorAll('optgroup'))
                        .find(g => g.label === 'Yours');
       const found = !!grp && grp.textContent.indexOf('Camel feed') >= 0;
       S.spendCats = keep;
       return found;
     }));
  ok('an older ledger keeps its categories and gains the new ones, once',
     await p.evaluate(() => {
       const real = localStorage.getItem('money_settings_v1');
       localStorage.setItem('money_settings_v1', JSON.stringify(
         { spendCats:['Rent','Car','Utilities'], theme:'light' }));
       // A real boot starts with no catsV in memory; load() only layers the
       // stored settings on top of it. Clear it so this is that, and not a
       // second load inside an app that has already run one.
       delete S.catsV;
       load();
       const got = S.spendCats.slice(), v = S.catsV;
       localStorage.setItem('money_settings_v1', real); load();
       return ['Rent','Car','Utilities'].every(c => got.includes(c)) &&
              got.includes('Festivals') && got.includes('Joining travel') &&
              v === 2;
     }));
  ok('and cutting the list down afterwards sticks',
     await p.evaluate(() => {
       const real = localStorage.getItem('money_settings_v1');
       localStorage.setItem('money_settings_v1', JSON.stringify(
         { spendCats:['Rent','Fuel'], catsV:2, theme:'light' }));
       load();
       const got = S.spendCats.slice();
       localStorage.setItem('money_settings_v1', real); load();
       return got.join() === 'Rent,Fuel';
     }),
     await p.evaluate(() => S.spendCats.length));

  ok('the page reports a build, which CI reads to name the APK',
     /^v\d/.test(await p.evaluate(() => APP_BUILD)),
     await p.evaluate(() => APP_BUILD));

  ok('no page errors anywhere in the run', errs.length === 0, errs.join(' | '));

  await b.close(); srv.close();
  console.log(fails ? fails + ' failed' : 'all passed');
  process.exit(fails ? 1 : 0);
})();
