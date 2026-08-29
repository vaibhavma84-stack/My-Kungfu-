// Photographs moved from localStorage into IndexedDB. The risk here is not a
// broken feature, it is silent data loss during migration, so that is what most
// of this checks.
const { chromium } = require('playwright-core');
const http=require('http'), fs=require('fs'), path=require('path');
let fails=0; const ok=(n,c,x)=>{console.log((c?'  PASS  ':'  FAIL  ')+n+(c?'':'  -> '+(x===undefined?'':x))); if(!c)fails++;};
(async()=>{
  const APP = process.env.APP_HTML || path.join(__dirname,'..','index.html');
  const OUT = process.env.OUT || fs.mkdtempSync('/tmp/photos-');
  const srv=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});r.end(fs.readFileSync(APP));}).listen(8772);
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome'});
  const ctx=await b.newContext({viewport:{width:412,height:900},acceptDownloads:true});
  const p=await ctx.newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
  p.on('dialog',async d=>await d.accept());
  await p.goto('http://localhost:8772/');
  await p.waitForTimeout(400);

  // ---- a photo added now must not sit in localStorage ----
  await p.fill('#inJob','Renew pilot ladder'); await p.fill('#inDue','2026-08-19');
  await p.click('#addBtn'); await p.waitForTimeout(150);
  await p.setInputFiles('#taskPhotoInput', path.join(OUT,'pic.jpg')).catch(()=>{});
  await p.locator('[data-action="addphoto"]').first().click();
  await p.setInputFiles('#taskPhotoInput', path.join(OUT,'pic.jpg'));
  await p.waitForTimeout(800);

  const state = await p.evaluate(()=>{
    const raw = localStorage.getItem('gasplanet_todo_v1');
    const t = JSON.parse(raw)[0];
    return { raw, photos: t.photos, len: raw.length };
  });
  ok('the photo is attached', state.photos.length===1, JSON.stringify(state.photos));
  ok('it is kept as a reference, not the image',
     /^p:/.test(state.photos[0]), state.photos[0].slice(0,40));
  ok('no image data is left in localStorage',
     state.raw.indexOf('data:image') < 0);
  ok('the written record stays small', state.len < 2000, state.len + ' bytes');
  ok('and it still shows on the card',
     await p.locator('.task .thumbstrip img').count()===1);
  const shown = await p.locator('.task .thumbstrip img').first().evaluate(i=>i.naturalWidth);
  ok('the image really decodes', shown > 0, shown);

  // ---- it survives a reload ----
  await p.reload(); await p.waitForTimeout(700);
  ok('the photo is still there after a reload',
     await p.locator('.task .thumbstrip img').count()===1);
  ok('and still decodes',
     await p.locator('.task .thumbstrip img').first().evaluate(i=>i.naturalWidth) > 0);

  // ---- migration from an older version ----
  // a realistic photo, not the tiny fixture — otherwise "localStorage shrank"
  // proves nothing, since a 200-byte image barely fills it in the first place
  const px = await p.evaluate(()=>{
    const c=document.createElement('canvas'); c.width=900; c.height=650;
    const g=c.getContext('2d'), img=g.createImageData(900,650);
    for(let i=0;i<img.data.length;i+=4){
      img.data[i]=(i*3)%255; img.data[i+1]=(i*7)%255; img.data[i+2]=(i*11)%255; img.data[i+3]=255;
    }
    g.putImageData(img,0,0);
    return c.toDataURL('image/jpeg',0.7);
  });
  console.log('        legacy test photo: %d KB each', Math.round(px.length/1024));
  await p.evaluate((photo)=>{
    // exactly what an older build would have written: images inline
    localStorage.setItem('gasplanet_todo_v1', JSON.stringify([
      { id:'old1', serial:1, job:'Legacy job one', due:'2026-08-19', priority:'normal',
        remarks:'', weeklyReport:'yes', ad34Planner:'no', ra:false, ptw:false, ptwType:'',
        repeat:'', photos:[photo, photo], done:true, dateCompleted:'2026-08-19', createdAt:'2026-08-19' },
      { id:'old2', serial:2, job:'Legacy job two', due:'2026-08-20', priority:'normal',
        remarks:'', weeklyReport:'no', ad34Planner:'no', ra:false, ptw:false, ptwType:'',
        repeat:'', photos:[photo], done:false, dateCompleted:'', createdAt:'2026-08-19' }
    ]));
    localStorage.setItem('gasplanet_extra_v1', JSON.stringify([
      { id:'ex1', job:'Legacy extra', date:'2026-08-19', ra:false, ptwType:'N/A',
        remarks:'', photos:[photo], created:'2026-08-19' }
    ]));
  }, px);
  const beforeLen = await p.evaluate(()=>localStorage.getItem('gasplanet_todo_v1').length);
  await p.reload(); await p.waitForTimeout(1200);

  const after = await p.evaluate(()=>({
    todo: localStorage.getItem('gasplanet_todo_v1'),
    extra: localStorage.getItem('gasplanet_extra_v1')
  }));
  const tasksAfter = JSON.parse(after.todo);
  ok('every legacy photo was migrated, none dropped',
     tasksAfter[0].photos.length===2 && tasksAfter[1].photos.length===1,
     JSON.stringify(tasksAfter.map(t=>t.photos.length)));
  ok('legacy photos became references',
     tasksAfter.every(t=>t.photos.every(v=>/^p:/.test(v))));
  ok('the AD-19 extras migrated too',
     JSON.parse(after.extra)[0].photos.every(v=>/^p:/.test(v)));
  ok('no image data left behind anywhere in localStorage',
     after.todo.indexOf('data:image')<0 && after.extra.indexOf('data:image')<0);
  ok('localStorage shrank dramatically',
     after.todo.length < beforeLen/10, beforeLen + ' -> ' + after.todo.length);
  console.log('        localStorage for jobs: %d -> %d bytes', beforeLen, after.todo.length);

  await p.selectOption('#filterStatus','all');
  await p.waitForTimeout(200);
  const decoded = await p.locator('.task .thumbstrip img').evaluateAll(
    els=>els.map(i=>i.naturalWidth).filter(w=>w>0).length);
  ok('the migrated photos still display', decoded>=3, decoded);

  // ---- far more room than before, driven through the app's own photo button ----
  // The app's code lives inside a closure, so this cannot call keepPhoto
  // directly; going through the file input is the honest test anyway, since it
  // exercises compression, storage and saving exactly as the phone does.
  const N = 60;
  const shots = await p.evaluate((n)=>{
    const out=[];
    for(let k=0;k<n;k++){
      const c=document.createElement('canvas'); c.width=1000; c.height=750;
      const g=c.getContext('2d'), img=g.createImageData(1000,750);
      for(let i=0;i<img.data.length;i+=4){
        img.data[i]=(i*(k+3))%255; img.data[i+1]=(i*7+k)%255; img.data[i+2]=(i*13+k*5)%255; img.data[i+3]=255;
      }
      g.putImageData(img,0,0);
      out.push(c.toDataURL('image/jpeg',0.8).split(',')[1]);
    }
    return out;
  }, N);
  const files = shots.map((b64,i)=>{
    const f = path.join(OUT, 'big'+i+'.jpg');
    fs.writeFileSync(f, Buffer.from(b64,'base64'));
    return f;
  });
  const rawMB = files.reduce((n,f)=>n+fs.statSync(f).size,0)/1048576;
  console.log('        feeding %d photos, %s MB of source images', N, rawMB.toFixed(1));

  await p.locator('[data-action="addphoto"]').first().click();
  await p.setInputFiles('#taskPhotoInput', files);
  await p.waitForTimeout(15000);

  const big = await p.evaluate(()=>{
    const t = JSON.parse(localStorage.getItem('gasplanet_todo_v1'));
    const first = t.find(x=>x.photos && x.photos.length>2) || t[0];
    return { count: first.photos.length,
             refs: first.photos.every(v=>/^p:/.test(v)),
             lsBytes: localStorage.getItem('gasplanet_todo_v1').length };
  });
  ok('all sixty photographs were kept', big.count>=N, big.count);
  ok('all of them as references', big.refs);
  ok('the written record is still tiny', big.lsBytes < 20000, big.lsBytes + ' bytes');
  const usedMB = await p.evaluate(async ()=>{
    const e = await navigator.storage.estimate();
    return e.usage/1048576;
  });
  ok('far past the old 5 MB localStorage ceiling', usedMB > 5, usedMB.toFixed(1)+' MB in use');
  console.log('        %d photos held, %s MB in use, %d bytes of localStorage',
              big.count, usedMB.toFixed(1), big.lsBytes);

  await p.reload(); await p.waitForTimeout(2500);
  await p.selectOption('#filterStatus','all');
  await p.waitForTimeout(500);
  const stillThere = await p.evaluate(()=>{
    const t = JSON.parse(localStorage.getItem('gasplanet_todo_v1'));
    const first = t.find(x=>x.photos && x.photos.length>2) || t[0];
    return first.photos.length;
  });
  ok('and they all survive a restart', stillThere>=N, stillThere);

  ok('no page errors', errs.length===0, errs.join(' | '));
  await b.close(); srv.close();
  console.log(fails? '\n'+fails+' FAILED' : '\nALL PASS');
  process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
