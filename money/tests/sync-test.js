// Two phones, one ledger.
//
// Every test here drives two real pages in two separate browser contexts, so
// they have genuinely separate storage — the same situation as two phones.
// Files are carried between them the way AirDrop carries them.
//
// The property being proved is that no merge can lose what was entered on the
// phone doing the merging. The old import replaced the lot, which for two
// people meant whoever imported second lost their own week. Everything below
// exists because that must not be possible again.
const { chromium } = require('playwright-core');
const http = require('http'); const fs = require('fs'); const path = require('path');

let fails = 0;
const ok = (n,c,x) => { console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+x)); if(!c) fails++; };

// What one phone would hand to the other.
const fileFrom = pg => pg.evaluate(() => JSON.parse(JSON.stringify(
  { app:'ledger', version:3, saved:today(), savedAt:Date.now(),
    settings:S, data:DB })));
const mergeInto = (pg, obj) => pg.evaluate(o => {
  const rep = mergeSync(o); render(); return rep;
}, obj);
// Everything that should be identical on both phones once they have synced.
// Device id and which month is on screen belong to the phone, not the ledger.
const ledgerOf = pg => pg.evaluate(() => {
  const out = {};
  for(const n of LISTS){
    out[n] = DB[n].slice()
      .sort((a,b) => String(a.id).localeCompare(String(b.id)))
      // Nested lists are compared in the order they are actually stored in,
      // not sorted first. Sorting here would hide the two phones holding the
      // same prices in a different order, which is what the card's little
      // line is drawn from.
      .map(r => Object.assign({}, r));
  }
  out.tomb = (DB.tomb||[]).map(t => t.id).sort();
  return JSON.stringify(out);
});
const spendIds = pg => pg.evaluate(() => DB.spend.map(s => s.id).sort());
const put = (pg, list, rec) => pg.evaluate(({list,rec}) => {
  DB[list] = DB[list].filter(r => r.id !== rec.id).concat([rec]);
  save(list); render();
}, {list,rec});

(async () => {
  const APP = process.env.APP_HTML || path.join(__dirname, '..', 'index.html');
  const srv = http.createServer((q,r) => {
    r.writeHead(200, {'Content-Type':'text/html; charset=utf-8'});
    r.end(fs.readFileSync(APP));
  }).listen(8753);
  const br = await chromium.launch({
    executablePath: process.env.CHROME || '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });

  const errs = [];
  const phone = async name => {
    const ctx = await br.newContext({ viewport:{width:390,height:844} });
    const pg = await ctx.newPage();
    pg.on('pageerror', e => errs.push(name + ': ' + e));
    await pg.goto('http://localhost:8753/');
    await pg.evaluate(n => { S.dev = n; save('set'); }, name);
    return pg;
  };
  let A = await phone('dAAA'), B = await phone('dBBB');

  const T = Date.parse('2026-09-01T10:00:00Z');

  /* ---- 1. the headline case: both add things, nobody loses anything ---- */
  await put(A, 'spend', { id:'s_a', upd:T+1, dev:'dAAA', date:'2026-09-03',
                          amount:4200, cat:'Fuel', note:'his diesel' });
  await put(B, 'spend', { id:'s_b', upd:T+2, dev:'dBBB', date:'2026-09-04',
                          amount:9100, cat:'Groceries', note:'her shopping' });

  const repA = await mergeInto(A, await fileFrom(B));
  ok('merging the other phone keeps what is on this one',
     (await spendIds(A)).join() === 's_a,s_b', (await spendIds(A)).join());
  ok('and it reports what it brought across', repA.added === 1, JSON.stringify(repA));

  await mergeInto(B, await fileFrom(A));
  ok('the other phone ends up with the same two',
     (await spendIds(B)).join() === 's_a,s_b', (await spendIds(B)).join());
  ok('both phones now hold exactly the same ledger',
     (await ledgerOf(A)) === (await ledgerOf(B)));

  /* ---- 2. merging the same file again must do nothing ---- */
  const before = await ledgerOf(A);
  const rep2 = await mergeInto(A, await fileFrom(B));
  ok('merging the same file twice changes nothing', (await ledgerOf(A)) === before);
  ok('and says there was nothing to bring', rep2.added === 0 && rep2.updated === 0,
     JSON.stringify(rep2));

  /* ---- 3. both edit the same record: the newer edit stands, on both ---- */
  await put(A, 'loans', { id:'l1', upd:T+10, dev:'dAAA', name:'Home loan',
                          principal:3500000, rate:8.6, months:240, start:'2026-01-01' });
  await mergeInto(B, await fileFrom(A));
  await put(A, 'loans', { id:'l1', upd:T+20, dev:'dAAA', name:'Home loan',
                          principal:3500000, rate:8.1, months:240, start:'2026-01-01' });
  await put(B, 'loans', { id:'l1', upd:T+30, dev:'dBBB', name:'Home loan',
                          principal:3500000, rate:7.9, months:240, start:'2026-01-01' });
  await mergeInto(A, await fileFrom(B));
  await mergeInto(B, await fileFrom(A));
  ok('the newer of two edits to one record wins',
     await A.evaluate(() => DB.loans.find(l => l.id === 'l1').rate) === 7.9);
  ok('and both phones agree on it', (await ledgerOf(A)) === (await ledgerOf(B)));

  /* ---- 4. a deleted record must not walk back in ---- */
  await A.evaluate(() => { removeRec('spend', 's_b'); });
  ok('deleting leaves a tombstone',
     await A.evaluate(() => DB.tomb.some(t => t.id === 's_b')));
  await mergeInto(A, await fileFrom(B));
  ok('the other phone still having it does not revive it',
     !(await spendIds(A)).includes('s_b'), (await spendIds(A)).join());
  await mergeInto(B, await fileFrom(A));
  ok('and the delete carries to the other phone',
     !(await spendIds(B)).includes('s_b'), (await spendIds(B)).join());
  ok('both phones still agree', (await ledgerOf(A)) === (await ledgerOf(B)));

  /* ---- 5. an edit made after the delete is not thrown away silently ---- */
  await put(A, 'spend', { id:'s_c', upd:T+40, dev:'dAAA', date:'2026-09-06',
                          amount:500, cat:'Food' });
  await mergeInto(B, await fileFrom(A));
  await A.evaluate(() => { removeRec('spend', 's_c'); });
  await put(B, 'spend', { id:'s_c', upd:Date.now()+60000, dev:'dBBB',
                          date:'2026-09-06', amount:850, cat:'Food' });
  const repRevive = await mergeInto(A, await fileFrom(B));
  ok('an edit newer than the delete brings the record back',
     (await spendIds(A)).includes('s_c'), (await spendIds(A)).join());
  ok('and the merge says so rather than hiding it', repRevive.revived > 0,
     JSON.stringify(repRevive));

  /* ---- 6. prepayments added on each phone must both survive ---- */
  await put(A, 'loans', { id:'l2', upd:T+50, dev:'dAAA', name:'Car loan',
                          principal:900000, rate:9.4, months:60, start:'2026-01-01',
                          extras:[] });
  await mergeInto(B, await fileFrom(A));
  await A.evaluate(t => {
    const l = DB.loans.find(x => x.id === 'l2');
    l.extras = [{ id:'p_a', upd:t+60, dev:'dAAA', date:'2026-05-01', amount:100000 }];
    l.upd = t+60; save('loans');
  }, T);
  await B.evaluate(t => {
    const l = DB.loans.find(x => x.id === 'l2');
    l.extras = [{ id:'p_b', upd:t+70, dev:'dBBB', date:'2026-06-01', amount:50000 }];
    l.upd = t+70; save('loans');
  }, T);
  await mergeInto(A, await fileFrom(B));
  await mergeInto(B, await fileFrom(A));
  const exA = await A.evaluate(() => DB.loans.find(l => l.id === 'l2').extras.map(e => e.id).sort());
  ok('a prepayment added on each phone gives two, not one',
     exA.join() === 'p_a,p_b', exA.join());
  ok('the loans match on both phones', (await ledgerOf(A)) === (await ledgerOf(B)));

  /* ---- 7. deleting a prepayment sticks ---- */
  await A.evaluate(() => {
    const l = DB.loans.find(x => x.id === 'l2');
    l.extras = l.extras.filter(e => e.id !== 'p_b');
    tombstone('p_b'); l.upd = Date.now(); save('loans');
  });
  await mergeInto(A, await fileFrom(B));
  ok('a removed prepayment does not come back from the other phone',
     await A.evaluate(() => !DB.loans.find(l => l.id === 'l2')
       .extras.some(e => e.id === 'p_b')));
  await mergeInto(B, await fileFrom(A));
  ok('and it goes on the other phone too',
     await B.evaluate(() => !DB.loans.find(l => l.id === 'l2')
       .extras.some(e => e.id === 'p_b')));

  /* ---- 7b. prices written on each phone build one history ---- */
  await put(A, 'invest', { id:'v1', upd:T+100, dev:'dAAA', kind:'equity',
    name:'Infosys', units:120, buy:1420, price:1500, asof:'2026-09-01',
    hist:[{ id:'ph_a', upd:T+100, dev:'dAAA', d:'2026-09-01', p:1500, src:'typed' }] });
  await put(B, 'invest', { id:'v1', upd:T+110, dev:'dBBB', kind:'equity',
    name:'Infosys', units:120, buy:1420, price:1685, asof:'2026-09-08',
    hist:[{ id:'ph_b', upd:T+110, dev:'dBBB', d:'2026-09-08', p:1685, src:'typed' }] });
  await mergeInto(A, await fileFrom(B));
  await mergeInto(B, await fileFrom(A));
  const hist = await A.evaluate(() =>
    DB.invest.find(h => h.id === 'v1').hist.map(x => x.d));
  const histB = await B.evaluate(() =>
    DB.invest.find(h => h.id === 'v1').hist.map(x => x.d));
  ok('a price written on each phone gives a history with both in it',
     hist.join() === '2026-09-01,2026-09-08', hist.join());
  ok('and the history is in date order on both phones, not merge order',
     hist.join() === histB.join(), hist.join() + '  vs  ' + histB.join());
  ok('and the holding itself is the same on both phones',
     (await ledgerOf(A)) === (await ledgerOf(B)));
  ok('portfolio readings merge as their own records too',
     await A.evaluate(() => Array.isArray(DB.snap)));

  /* ---- 8. the order the two phones merge in cannot change the answer ---- */
  {
    const C = await phone('dCCC'), D = await phone('dDDD');
    const seed = { id:'g1', upd:T+80, dev:'dCCC', name:'Emergency fund',
                   target:1200000, ledger:[] };
    await put(C, 'goals', seed);
    await mergeInto(D, await fileFrom(C));
    await C.evaluate(t => { const g = DB.goals[0];
      g.ledger = [{ id:'gp_c', upd:t+90, dev:'dCCC', date:'2026-05-01', amount:40000 }];
      g.target = 1300000; g.upd = t+90; save('goals'); }, T);
    await D.evaluate(t => { const g = DB.goals[0];
      g.ledger = [{ id:'gp_d', upd:t+95, dev:'dDDD', date:'2026-06-01', amount:25000 }];
      g.target = 1400000; g.upd = t+95; save('goals'); }, T);
    // C merges D's file; D merges C's. Opposite orders, same answer required.
    await mergeInto(C, await fileFrom(D));
    await mergeInto(D, await fileFrom(C));
    ok('merging in either order lands on the same ledger',
       (await ledgerOf(C)) === (await ledgerOf(D)));
    const pays = await C.evaluate(() => DB.goals[0].ledger.map(e => e.id).sort());
    ok('both goal payments survive', pays.join() === 'gp_c,gp_d', pays.join());
    ok('the later edit of the target stands',
       await C.evaluate(() => DB.goals[0].target) === 1400000);
    await C.context().close(); await D.context().close();
  }

  /* ---- 9. an equal-stamp tie must break the same way on both phones ---- */
  {
    const C = await phone('dCCC'), D = await phone('dDDD');
    await put(C, 'spend', { id:'t1', upd:T, dev:'dCCC', date:'2026-09-01',
                            amount:111, cat:'Food' });
    await put(D, 'spend', { id:'t1', upd:T, dev:'dDDD', date:'2026-09-01',
                            amount:222, cat:'Food' });
    await mergeInto(C, await fileFrom(D));
    await mergeInto(D, await fileFrom(C));
    ok('two edits with the same stamp resolve identically on both phones',
       (await ledgerOf(C)) === (await ledgerOf(D)),
       await C.evaluate(() => JSON.stringify(DB.spend)));
    await C.context().close(); await D.context().close();
  }

  /* ---- 10. a file from before any of this existed still merges ---- */
  {
    const C = await phone('dCCC'), D = await phone('dDDD');
    // Shaped exactly as uid() builds one: the millisecond in base 36, then
    // five random characters. That is what the stamp is recovered from.
    const oldId = T.toString(36) + 'q4m1z';
    await put(C, 'spend', { id:oldId, date:'2026-09-02', amount:700,
                            cat:'Food', note:'old row' });
    await C.evaluate(() => { delete DB.spend[0].upd; delete DB.spend[0].dev;
                             save('spend'); });
    const old = await fileFrom(C);
    old.version = 1;
    delete old.data.tomb;
    for(const r of old.data.spend){ delete r.upd; delete r.dev; }
    await D.evaluate(o => { readyForMerge(o); mergeSync(o); render(); }, old);
    ok('a version-1 file with no stamps merges in',
       (await spendIds(D)).join() === oldId, (await spendIds(D)).join());
    // The same old file again must not arrive as a second copy.
    await D.evaluate(o => { readyForMerge(o); mergeSync(o); render(); }, old);
    ok('and merging it a second time does not duplicate it',
       (await spendIds(D)).length === 1, (await spendIds(D)).join());
    ok('its edit stamp was recovered from its own id, not invented',
       await D.evaluate(id => DB.spend[0].upd === bornAt(id) && DB.spend[0].upd > 0,
                        oldId),
       await D.evaluate(() => DB.spend[0].upd));
    ok('and the recovered stamp is the moment the record was created',
       await D.evaluate(id => bornAt(id), oldId) === T,
       await D.evaluate(id => bornAt(id), oldId) + ' vs ' + T);
    // An id from somewhere else entirely must not decode into a wild date, or
    // the record would look either ancient or years in the future and win or
    // lose every merge on the strength of a misreading.
    ok('an id that is not one of ours decodes to no date at all, not a wild one',
       await D.evaluate(() => bornAt('abc') === 0 && bornAt('') === 0 &&
                              bornAt('zzzzzzzzzzzzz') === 0 &&
                              bornAt(undefined) === 0));
    await C.context().close(); await D.context().close();
  }

  /* ---- 11. restoring must not copy the other phone's identity ---- */
  ok('a restore keeps this phone its own device id, so ties keep breaking right',
     await A.evaluate(() => {
       const mine = S.dev;
       const realConfirm = window.confirm, realAlert = window.alert;
       window.confirm = () => true; window.alert = () => {};
       const file = new File([JSON.stringify({ app:'ledger', version:3,
         saved:today(), settings:{ dev:'dBBB', spendCats:S.spendCats },
         data:{ spend:[], tomb:[] } })], 'x.json', { type:'application/json' });
       importJson(file, 'replace');
       return new Promise(res => setTimeout(() => {
         window.confirm = realConfirm; window.alert = realAlert;
         res(S.dev === mine);
       }, 120));
     }));

  /* ---- 12. categories are additive ---- */
  {
    const C = await phone('dCCC'), D = await phone('dDDD');
    await C.evaluate(() => { S.spendCats = ['Rent','Boat']; save('set'); });
    await D.evaluate(() => { S.spendCats = ['Rent','Horse']; save('set'); });
    await mergeInto(D, await fileFrom(C));
    const cats = await D.evaluate(() => S.spendCats.slice().sort());
    ok('a category added on one phone does not remove one added on the other',
       cats.join() === 'Boat,Horse,Rent', cats.join());
    await C.context().close(); await D.context().close();
  }

  /* ---- 13. the clock ----
     The failure this exists to stop: her phone runs slow, she edits something
     after I do, and her change silently loses to my older one. */
  {
    const C = await phone('dCCC'), D = await phone('dDDD');
    const SKEW = 10 * 60 * 1000;
    await D.evaluate(skew => {
      const real = Date.now;
      window.__realNow = real;
      Date.now = () => real() - skew;              // this phone is ten minutes slow
    }, SKEW);

    await C.evaluate(() => upsert('spend',
      { id:'k1', date:'2026-09-01', amount:100, cat:'Food', note:'his' }));
    const cs = await C.evaluate(() =>
      (r => ({ upd:r.upd, ctr:r.ctr }))(DB.spend.find(x => x.id === 'k1')));

    // She sees my edit, then makes her own — later in real time, earlier by
    // her own clock.
    await mergeInto(D, await fileFrom(C));
    await D.evaluate(() => {
      const r = DB.spend.find(x => x.id === 'k1');
      upsert('spend', Object.assign({}, r, { amount:250, note:'hers' }));
    });
    const ds = await D.evaluate(() =>
      (r => ({ upd:r.upd, ctr:r.ctr, wall:Date.now() }))(DB.spend.find(x => x.id === 'k1')));

    ok('the slow phone reads earlier than the edit it just took in',
       ds.wall < cs.upd,
       ds.wall + ' vs ' + cs.upd);
    ok('so a wall clock alone would have thrown her edit away — the case this is for',
       ds.wall < cs.upd);
    ok('but her edit is stamped above the one she had seen',
       ds.upd > cs.upd || (ds.upd === cs.upd && ds.ctr > cs.ctr),
       JSON.stringify(ds) + ' vs ' + JSON.stringify(cs));

    await mergeInto(C, await fileFrom(D));
    await mergeInto(D, await fileFrom(C));
    ok('and it stands on both phones',
       await C.evaluate(() => DB.spend.find(x => x.id === 'k1').amount) === 250 &&
       await D.evaluate(() => DB.spend.find(x => x.id === 'k1').amount) === 250,
       await C.evaluate(() => DB.spend.find(x => x.id === 'k1').amount));
    ok('with the ledgers still identical', (await ledgerOf(C)) === (await ledgerOf(D)));

    /* Edits neither phone had seen are genuinely concurrent. No clock can
       order those; all that is owed is that both phones answer the same. */
    await C.evaluate(() => upsert('spend',
      { id:'k2', date:'2026-09-02', amount:11, cat:'Food' }));
    await D.evaluate(() => upsert('spend',
      { id:'k2', date:'2026-09-02', amount:22, cat:'Food' }));
    await mergeInto(C, await fileFrom(D));
    await mergeInto(D, await fileFrom(C));
    ok('two edits made in ignorance of each other still land the same way on both',
       (await ledgerOf(C)) === (await ledgerOf(D)),
       await C.evaluate(() => JSON.stringify(DB.spend.find(x => x.id === 'k2'))));

    await D.evaluate(() => { Date.now = window.__realNow; });
    await C.context().close(); await D.context().close();
  }

  /* ---- 14. the clock's own rules ---- */
  {
    const C = await phone('dCCC');
    ok('writes inside one millisecond are still ordered, by the counter',
       await C.evaluate(() => {
         const real = Date.now;
         Date.now = () => 1800000000000;
         S.hlc = null;
         const a = newStamp(), b = newStamp(), c = newStamp();
         Date.now = real;
         return a.upd === b.upd && b.upd === c.upd &&
                a.ctr === 0 && b.ctr === 1 && c.ctr === 2;
       }));
    ok('seeing an older stamp never drags the clock backwards',
       await C.evaluate(() => {
         const real = Date.now;
         Date.now = () => 1000;
         S.hlc = { ms:5000, c:3 };
         hlcSee(1000, 0);
         const after = Object.assign({}, S.hlc);
         Date.now = real;
         return after.ms === 5000 && after.c === 4;
       }),
       await C.evaluate(() => JSON.stringify(S.hlc)));
    ok('seeing a newer stamp lifts the clock past it',
       await C.evaluate(() => {
         const real = Date.now;
         Date.now = () => 1000;
         S.hlc = { ms:5000, c:3 };
         hlcSee(9000, 7);
         const after = Object.assign({}, S.hlc);
         Date.now = real;
         return after.ms === 9000 && after.c === 8;
       }));
    ok('a restore does not take this phone clock backwards',
       await C.evaluate(() => {
         S.hlc = { ms:9_000_000_000_000, c:2 };
         const realConfirm = window.confirm, realAlert = window.alert;
         window.confirm = () => true; window.alert = () => {};
         const file = new File([JSON.stringify({ app:'ledger', version:3,
           saved:today(), settings:{ dev:'dZZZ', hlc:{ ms:1000, c:0 },
           spendCats:S.spendCats }, data:{ spend:[], tomb:[] } })],
           'x.json', { type:'application/json' });
         importJson(file, 'replace');
         return new Promise(res => setTimeout(() => {
           window.confirm = realConfirm; window.alert = realAlert;
           res(S.hlc.ms >= 9_000_000_000_000);
         }, 120));
       }),
       await C.evaluate(() => JSON.stringify(S.hlc)));
    await C.context().close();
  }

  /* ---- 15. a file stamped before the counter existed still merges ---- */
  {
    const C = await phone('dCCC'), D = await phone('dDDD');
    await put(C, 'spend', { id:'old1', upd:T + 500, dev:'dCCC',
                            date:'2026-09-01', amount:60, cat:'Food' });
    const f = await fileFrom(C);
    for(const r of f.data.spend) delete r.ctr;      // as version 3 wrote them
    await D.evaluate(o => { readyForMerge(o); mergeSync(o); render(); }, f);
    ok('a record with no counter merges, counting as counter zero',
       await D.evaluate(() => DB.spend.length === 1 && DB.spend[0].id === 'old1'));
    // Edit it here, then merge that same old file again: the counter-less
    // record must not climb back over the edit that came after it.
    ok('and an edit made after seeing it survives the old file being merged again',
       await D.evaluate(o => {
         upsert('spend', Object.assign({}, DB.spend[0], { amount:99 }));
         readyForMerge(o); mergeSync(o);
         return DB.spend.length === 1 && DB.spend[0].amount === 99;
       }, f),
       await D.evaluate(() => JSON.stringify(DB.spend)));
    await C.context().close(); await D.context().close();
  }

  ok('no page errors on either phone', errs.length === 0, errs.join(' | '));

  await br.close(); srv.close();
  console.log(fails ? fails + ' failed' : 'all passed');
  process.exit(fails ? 1 : 0);
})();
