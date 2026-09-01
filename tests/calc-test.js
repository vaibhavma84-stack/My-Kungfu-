// The calculator. Its expression parser is written out rather than handed to
// eval, so the arithmetic has to be checked case by case -- a calculator that
// is quietly wrong is worse than no calculator at all.
const { chromium } = require('playwright-core');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

// expression, expected, angle mode
const CASES = [
  // precedence and associativity -- where calculators disagree with each other
  ['2+3*4',            14],
  ['(2+3)*4',          20],
  ['2^3^2',            512],        // right to left, not 64
  ['-2^2',             -4],         // the minus after the square, as written
  ['(-2)^2',           4],
  ['2*-3',             -6],
  ['--5',              5],
  ['10-2-3',           5],          // left to right
  ['100/5/2',          10],
  ['2^-1',             0.5],

  // percent as a suffix
  ['50%',              0.5],
  ['200*5%',           10],
  ['1+10%',            1.1],

  // factorial
  ['5!',               120],
  ['0!',               1],
  ['3!+2',             8],
  ['(2+2)!',           24],

  // functions and constants
  ['sqrt(16)',         4],
  ['cbrt(27)',         3],
  ['abs(-7)',          7],
  ['ln(e)',            1],
  ['log(1000)',        3],
  ['exp(0)',           1],
  ['pi',               Math.PI],
  ['sin(30)',          0.5,               'deg'],
  ['cos(60)',          0.5,               'deg'],
  ['tan(45)',          1,                 'deg'],
  ['asin(0.5)',        30,                'deg'],
  ['acos(0.5)',        60,                'deg'],
  ['atan(1)',          45,                'deg'],
  ['sin(pi/2)',        1,                 'rad'],
  ['atan(1)',          Math.PI/4,         'rad'],
  ['sinh(0)',          0],
  ['tanh(0)',          0],

  // the sort of thing actually worked out on a ship
  ['1000*0.5077',      1000 * 0.5077],
  ['6.28981077/0.5077', 6.28981077 / 0.5077],
  ['(273.15+15)/273.15', (273.15 + 15) / 273.15],
  ['sqrt(2*9.81*3)',   Math.sqrt(2 * 9.81 * 3)]
];

const ERRORS = [
  ['1/0',        /divides by zero/],
  ['sqrt(-1)',   /not negative/],
  ['ln(0)',      /above zero/],
  ['log(-5)',    /above zero/],
  ['asin(2)',    /between/],
  ['(-3)!',      /whole number/],
  ['2.5!',       /whole number/],
  ['tan(90)',    /no value/,   'deg'],
  ['2+',         /stops early/],
  ['(2+3',       /not closed/],
  ['2 3',        /left over/],
  ['sin 30',     /bracket after it/],
  ['1.2.3',      /two decimal points/],
  ['#',          /cannot read/]
];

(async () => {
  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const p = await b.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  await p.goto('file://' + process.env.APP_HTML);
  await p.waitForTimeout(500);

  let wrong = [];
  for(const [src, want, angle] of CASES){
    const got = await p.evaluate(([s, a]) => {
      try { return { v: window.__calc(s, a) }; } catch(e){ return { e: e.message }; }
    }, [src, angle || 'deg']);
    const good = got.v !== undefined && Math.abs(got.v - want) < Math.max(1e-9, Math.abs(want) * 1e-9);
    if(!good) wrong.push(src + ' = ' + JSON.stringify(got) + ', wanted ' + want);
  }
  ok(CASES.length + ' arithmetic cases all correct', wrong.length === 0, wrong.join(' | '));

  let notCaught = [];
  for(const [src, re, angle] of ERRORS){
    const got = await p.evaluate(([s, a]) => {
      try { return { v: window.__calc(s, a) }; } catch(e){ return { e: e.message }; }
    }, [src, angle || 'deg']);
    if(got.e === undefined || !re.test(got.e)) notCaught.push(src + ' -> ' + JSON.stringify(got));
  }
  ok(ERRORS.length + ' impossible sums each refused, and say why',
     notCaught.length === 0, notCaught.join(' | '));

  // --- the tool itself -----------------------------------------------------
  await p.click('#topTabs button[data-tab="tools"]');
  ok('the calculator is on the launcher', await p.locator('[data-tool="calc"]').count() === 1);
  await p.click('[data-tool="calc"]');
  await p.waitForTimeout(300);
  ok('it opens', await p.locator('#toolCalc').isVisible());
  ok('titled Calculator', (await p.textContent('#pageTitle')).trim() === 'Calculator');

  ok('the scientific keys are out of the way until asked for',
     !(await p.locator('#calcSciKeys').isVisible()));

  const press = async k => { await p.click('[data-ck="' + k + '"]'); await p.waitForTimeout(60); };
  for(const k of ['1','2','+','3','4']) await press(k);
  ok('the keys build the sum', await p.inputValue('#calcExpr') === '12+34',
     await p.inputValue('#calcExpr'));
  ok('and the answer runs as you type', await p.textContent('#calcOut') === '46',
     await p.textContent('#calcOut'));
  await press('=');
  ok('equals settles it', await p.textContent('#calcOut') === '46');

  await press('⌫');
  ok('backspace takes one off', await p.inputValue('#calcExpr') === '12+3');
  await press('AC');
  ok('AC clears', await p.inputValue('#calcExpr') === '' && await p.textContent('#calcOut') === '0');

  // Ans carries the last result forward
  for(const k of ['5','×','5']) await press(k);
  await press('=');
  await press('AC');
  await p.click('[data-calcmode="sci"]');
  await p.waitForTimeout(200);
  ok('the scientific keys appear when asked for', await p.locator('#calcSciKeys').isVisible());
  await press('Ans');
  await press('+');
  await press('1');
  ok('Ans is the last result', await p.textContent('#calcOut') === '26',
     (await p.inputValue('#calcExpr')) + ' -> ' + (await p.textContent('#calcOut')));

  // degrees and radians
  await press('AC');
  await press('sin');
  for(const k of ['3','0']) await press(k);
  await press(')');
  ok('sin 30 in degrees is a half', await p.textContent('#calcOut') === '0.5',
     await p.textContent('#calcOut'));
  await p.click('#calcAngleBtn');
  await p.waitForTimeout(200);
  ok('the angle mode switches to radians', await p.textContent('#calcAngleBtn') === 'RAD');
  ok('and the same sum now means something else',
     await p.textContent('#calcOut') !== '0.5', await p.textContent('#calcOut'));
  await p.click('#calcAngleBtn');
  await p.waitForTimeout(200);

  // memory
  await press('AC');
  for(const k of ['1','0','0']) await press(k);
  await press('M+');
  ok('memory shows when it holds something', (await p.textContent('#calcMemFlag')).trim() === 'M');
  await press('AC');
  await press('MR');
  ok('and recalls it', await p.inputValue('#calcExpr') === '100', await p.inputValue('#calcExpr'));
  await press('MC');
  ok('MC empties it', (await p.textContent('#calcMemFlag')).trim() === '');

  // an error is shown, not swallowed
  await press('AC');
  for(const k of ['1','÷','0']) await press(k);
  await press('=');
  ok('an impossible sum says so on the display',
     /divides by zero/.test(await p.textContent('#calcOut')), await p.textContent('#calcOut'));

  // settings stick
  await p.click('#calcAngleBtn');
  await p.waitForTimeout(150);
  await p.reload();
  await p.waitForTimeout(600);
  await p.click('#topTabs button[data-tab="tools"]');
  await p.click('[data-tool="calc"]');
  await p.waitForTimeout(300);
  ok('the angle mode and the scientific keys are remembered',
     await p.textContent('#calcAngleBtn') === 'RAD' && await p.locator('#calcSciKeys').isVisible(),
     await p.textContent('#calcAngleBtn'));

  ok('no page errors', errs.length === 0, errs.join(' | '));
  await b.close();
  console.log('');
  console.log(fail ? fail + ' FAILED' : 'ALL PASS');
  process.exit(fail ? 1 : 0);
})();
