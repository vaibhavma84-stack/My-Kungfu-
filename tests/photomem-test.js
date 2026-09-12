// Photographs are read when they are shown, not all at start-up.
//
// Every photograph used to be loaded into memory when the app opened. At about
// a hundred kilobytes each that is fine for thirty and fatal for a thousand,
// and a weekly report carrying four to a page builds a thousand over a couple
// of years. This checks the app holds a bounded handful however many are
// stored -- and that the tidy-up which deletes unreferenced photographs looks
// at the database rather than at what happens to be in memory, because against
// the memory it would delete almost everything.
const { chromium } = require('playwright-core');

let pass = 0, fail = 0;
function ok(name, cond, got){
  if(cond){ pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

const MANY = 240;

(async () => {
  const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
  const ctx = await b.newContext({ viewport:{width:420,height:900} });
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  await p.goto('file://' + process.env.APP_HTML);
  await p.waitForTimeout(700);

  // Put a lot of photographs in, the way the app stores them, without going
  // through the file picker MANY times.
  const made = await p.evaluate(async (n) => {
    // Detail everywhere, because a flat colour compresses to almost nothing and
    // would make a store of two hundred photographs smaller than the app itself.
    // A photograph of a deck fitting does not compress like that.
    function shot(i){
      const c = document.createElement('canvas');
      c.width = 900; c.height = 600;
      const g = c.getContext('2d');
      const im = g.createImageData(900, 600);
      let seed = i * 2654435761 % 4294967296;
      const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
      for(let k = 0; k < im.data.length; k += 4){
        const v = 60 + rnd() * 160;
        im.data[k] = v; im.data[k+1] = v * 0.8; im.data[k+2] = v * 0.6; im.data[k+3] = 255;
      }
      g.putImageData(im, 0, 0);
      g.fillStyle = '#000'; g.font = '90px sans-serif';
      g.fillText('#' + i, 40, 320);
      return c.toDataURL('image/jpeg', 0.65);
    }
    const db = await new Promise(res => {
      const r = indexedDB.open('gasplanet_media', 1);
      r.onsuccess = () => res(r.result);
      r.onerror = () => res(null);
    });
    if(!db) return { error: 'no indexeddb' };
    const jobs = JSON.parse(localStorage.getItem('gasplanet_todo_v1') || '[]');
    const refs = [];
    let bytes = 0;
    for(let i = 0; i < n; i++){
      const url = shot(i);
      bytes += url.length;
      const id = 'p:bulk' + i;
      await new Promise(res => {
        const tx = db.transaction('photos', 'readwrite');
        tx.objectStore('photos').put(url, id);
        tx.oncomplete = res; tx.onerror = res;
      });
      refs.push(id);
    }
    // every one attached to a real job, so none of them is rubbish to collect
    jobs.push({ id:'bulkjob', serial:9001, job:'Hold inspection', due:'', priority:'normal',
                remarks:'', weeklyReport:'yes', ad34Planner:'no', ra:false, ptw:false,
                ptwType:'', repeat:'', photos:refs, done:false, dateCompleted:'',
                createdAt:'2026-01-01' });
    localStorage.setItem('gasplanet_todo_v1', JSON.stringify(jobs));
    return { bytes };
  }, MANY);
  ok('a few hundred photographs went in', !made.error, JSON.stringify(made));
  console.log('        ' + MANY + ' photographs, ' + (made.bytes / 1048576).toFixed(1) + ' MB of image');

  // restart: this is where the old build read all of them into memory
  await p.reload();
  await p.waitForTimeout(1800);

  const st = await p.evaluate(() => window.__photoStats());
  ok('all of them are still stored after the restart', st.stored >= MANY, st.stored);
  ok('but memory holds only a bounded handful, not all of them',
     st.cached <= st.max, st.cached + ' held, cap ' + st.max);
  ok('and that cap is well under the number stored',
     st.max < MANY, st.max + ' vs ' + MANY + ' stored');

  // the heap is the thing that was actually killing it
  const heap = await p.evaluate(() => performance.memory ? performance.memory.usedJSHeapSize : 0);
  if(heap){
    ok('the heap is nowhere near the size of the whole photo store',
       heap < made.bytes / 2, (heap / 1048576).toFixed(1) + ' MB heap vs ' +
       (made.bytes / 1048576).toFixed(1) + ' MB of photographs');
  }

  // The tidy-up must look at the database rather than at what is in memory.
  // Against the cache it errs safe -- it simply stops reclaiming anything
  // outside the sixty it holds, and a deleted photograph sits in the database
  // forever. So this checks both directions: referenced ones survive, and an
  // orphan is actually reclaimed.
  const after = await p.evaluate(() => window.__photoStats());
  ok('the collector left every referenced photograph alone',
     after.stored >= MANY, after.stored + ' still stored');

  const orphaned = await p.evaluate(async () => {
    const db = await new Promise(res => {
      const r = indexedDB.open('gasplanet_media', 1);
      r.onsuccess = () => res(r.result); r.onerror = () => res(null);
    });
    // a photograph in the store that no job points at, placed well outside the
    // sixty the cache can hold
    await new Promise(res => {
      const tx = db.transaction('photos', 'readwrite');
      tx.objectStore('photos').put('data:image/jpeg;base64,AAAA', 'p:orphan');
      tx.oncomplete = res; tx.onerror = res;
    });
    return true;
  });
  ok('an orphaned photograph was planted', orphaned);
  await p.reload();
  await p.waitForTimeout(2000);
  const swept = await p.evaluate(() => new Promise(res => {
    const r = indexedDB.open('gasplanet_media', 1);
    r.onsuccess = () => {
      const q = r.result.transaction('photos', 'readonly').objectStore('photos').get('p:orphan');
      q.onsuccess = () => res(q.result === undefined);
      q.onerror = () => res(false);
    };
  }));
  ok('and the collector reclaimed it, even though it was never in memory', swept);
  const kept = await p.evaluate(() => window.__photoStats());
  ok('while every referenced photograph is still there', kept.stored >= MANY, kept.stored);

  // A job card keeps its thumbnails in a collapsed panel, so they are not
  // fetched at all until the card is opened. That is the saving working, and it
  // is worth asserting rather than assuming.
  const collapsed = await p.evaluate(() => {
    const img = document.querySelector('#listWrap img[data-photo]');
    return img ? { waiting: !!img.getAttribute('data-photo'),
                   placeholder: /^data:image\/svg/.test(img.src) } : null;
  });
  ok('a photograph inside a collapsed job card is not fetched',
     collapsed && collapsed.waiting && collapsed.placeholder, JSON.stringify(collapsed));

  // Open the card and scroll to it. Being off the bottom of the screen is
  // itself a reason not to fetch a photograph, so the test has to actually put
  // the thumbnail in front of the user before expecting it to appear.
  await p.evaluate(() => {
    const t = document.querySelector('#listWrap [data-action="expand"]');
    if(t) t.click();
  });
  await p.waitForTimeout(200);
  await p.evaluate(() => {
    const img = document.querySelector('#listWrap .thumb img');
    if(img) img.scrollIntoView({ block: 'center' });
  });
  const shown = await p.evaluate(async () => {
    for(let i = 0; i < 80; i++){
      const img = document.querySelector('#listWrap .thumb img');
      if(img && /^data:image\/jpeg/.test(img.src)) return img.src.slice(0, 22);
      await new Promise(r => setTimeout(r, 100));
    }
    const img = document.querySelector('#listWrap .thumb img');
    return img ? img.src.slice(0, 22) : 'no thumbnail found';
  });
  ok('and it fills itself in once the card is opened',
     /^data:image\/jpeg/.test(shown), shown);

  const st2 = await p.evaluate(() => window.__photoStats());
  ok('and looking at photographs still does not unbound memory',
     st2.cached <= st2.max, st2.cached + ' held, cap ' + st2.max);

  ok('no page errors', errs.length === 0, errs.join(' | '));
  await b.close();
  console.log('');
  console.log(fail ? fail + ' FAILED' : 'ALL PASS');
  process.exit(fail ? 1 : 0);
})();
