// The MP4 tag writer that runs in the browser.
//
// Every test here exists because of the same fault: an MP4 whose tags are
// perfect and whose chunk offsets are wrong opens fine, shows its new title,
// and plays nothing. So the file is built with each chunk filled with a
// recognisable byte, tagged, and then followed through its own offsets to
// check they still land on the byte they used to.
//
// Run with: node tests/tagger-mp4-test.js
const { withTags, readTags, topLevelBoxes } = require('./tagger-mp4-harness.js');

let pass = 0, fail = 0;
function ok(name, cond, got) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}

const CHUNK = 64;

function be32(value) {
  const out = Buffer.alloc(4);
  out.writeUInt32BE(value >>> 0);
  return out;
}

function box(type, ...parts) {
  const body = Buffer.concat(parts.map((p) => (Buffer.isBuffer(p) ? p : Buffer.from(p))));
  return Buffer.concat([be32(body.length + 8), Buffer.from(type, 'latin1'), body]);
}

/**
 * A small but real MP4: ftyp, moov with one track whose stco points into mdat,
 * and an mdat whose chunks are each filled with their own letter.
 *
 * @param moovFirst whether moov comes before mdat, as a fast-start file has it.
 *   When false the file is the far more common "moov at the end" shape a
 *   download produces, where nothing moves and no offsets need correcting.
 */
function build({ moovFirst = true, chunks = 4, tagged = false, wide = false } = {}) {
  const markers = [];
  const media = Buffer.alloc(chunks * CHUNK);
  for (let i = 0; i < chunks; i++) {
    const marker = 0x41 + i;
    markers.push(marker);
    media.fill(marker, i * CHUNK, (i + 1) * CHUNK);
  }

  const ftyp = box('ftyp', Buffer.from('isom'), be32(512), Buffer.from('isomiso2mp41'));

  // Built twice: once to learn how big the moov is, then again with the real
  // offsets in it. The offsets depend on the size and the size depends on the
  // offsets, which is the one genuinely fiddly part of writing an MP4 by hand.
  const make = (mdatStart) => {
    const offsets = [];
    for (let i = 0; i < chunks; i++) offsets.push(mdatStart + 8 + i * CHUNK);
    const table = wide
      ? Buffer.concat(offsets.map((o) => {
          const b = Buffer.alloc(8);
          b.writeBigUInt64BE(BigInt(o));
          return b;
        }))
      : Buffer.concat(offsets.map(be32));
    const stco = box(wide ? 'co64' : 'stco', be32(0), be32(chunks), table);
    const stbl = box('stbl', stco);
    const minf = box('minf', stbl);
    const mdia = box('mdia', minf);
    const trak = box('trak', mdia);
    const mvhd = box('mvhd', Buffer.alloc(100));
    const parts = [mvhd, trak];
    if (tagged) {
      // An existing udta, so re-tagging a file that already has tags is covered.
      const data = box('data', be32(1), be32(0), Buffer.from('Old title', 'utf8'));
      const item = box('©nam', data);
      const ilst = box('ilst', item);
      const hdlr = box('hdlr', Buffer.alloc(8), Buffer.from('mdirappl'), Buffer.alloc(9));
      const meta = box('meta', be32(0), hdlr, ilst);
      parts.push(box('udta', meta));
    }
    return box('moov', ...parts);
  };

  let moov = make(0);
  const mdatStart = moovFirst ? ftyp.length + moov.length : ftyp.length;
  moov = make(mdatStart);
  const mdat = box('mdat', media);

  const bytes = moovFirst
    ? Buffer.concat([ftyp, moov, mdat])
    : Buffer.concat([ftyp, mdat, moov]);
  return { bytes, markers, chunks };
}

/** Follows the file's own offsets and reports the byte each chunk begins with. */
async function chunkBytes(blob) {
  const bytes = Buffer.from(await blob.arrayBuffer());
  const boxes = await topLevelBoxes(blob);
  const moovBox = boxes.find((b) => b.type === 'moov');
  const moov = bytes.subarray(moovBox.start, moovBox.end);

  // Find the offset table wherever it is in the tree.
  const found = [];
  const walk = (from, to) => {
    let at = from;
    while (at + 8 <= to) {
      const size = moov.readUInt32BE(at);
      const type = moov.toString('latin1', at + 4, at + 8);
      if (size < 8 || at + size > to) break;
      if (type === 'stco' || type === 'co64') {
        const count = moov.readUInt32BE(at + 12);
        for (let i = 0; i < count; i++) {
          found.push(type === 'co64'
            ? Number(moov.readBigUInt64BE(at + 16 + i * 8))
            : moov.readUInt32BE(at + 16 + i * 4));
        }
      } else if (['trak', 'mdia', 'minf', 'stbl', 'udta'].includes(type)) {
        walk(at + 8, at + size);
      }
      at += size;
    }
  };
  walk(8, moov.length);
  return found.map((offset) => bytes[offset]);
}

async function main() {
  console.log('MP4 tag writer');

  for (const shape of [
    { moovFirst: true, name: 'moov first' },
    { moovFirst: false, name: 'moov last' },
    { moovFirst: true, tagged: true, name: 'already tagged' },
    { moovFirst: true, wide: true, name: 'co64 offsets' },
  ]) {
    const made = build(shape);
    const file = new Blob([made.bytes]);
    const out = await withTags(file, {
      title: 'Kesariya',
      artist: 'Arijit Singh',
      album: 'Brahmastra',
      date: '2022-07-17',
      mediaKind: 6,
      season: 2,
      episode: 4,
      show: 'A Series',
      cover: { bytes: new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3]), mime: 'image/jpeg' },
      freeform: { LANGUAGE: 'hi', MVTAGGER_KIND: 'MUSIC_VIDEO' },
    });

    const back = await readTags(out);
    ok(shape.name + ': title', back.title === 'Kesariya', back.title);
    ok(shape.name + ': artist', back.artist === 'Arijit Singh', back.artist);
    ok(shape.name + ': album', back.album === 'Brahmastra', back.album);
    ok(shape.name + ': date', back.date === '2022-07-17', back.date);
    ok(shape.name + ': media kind', back.mediaKind === 6, back.mediaKind);
    ok(shape.name + ': season', back.season === 2, back.season);
    ok(shape.name + ': episode', back.episode === 4, back.episode);
    ok(shape.name + ': show', back.show === 'A Series', back.show);
    ok(shape.name + ': cover kept', back.cover && back.cover.bytes.length === 7, back.cover && back.cover.bytes.length);
    ok(shape.name + ': cover type', back.cover && back.cover.mime === 'image/jpeg', back.cover && back.cover.mime);
    ok(shape.name + ': language', back.freeform && back.freeform.LANGUAGE === 'hi', back.freeform && back.freeform.LANGUAGE);
    ok(shape.name + ': kind kept', back.freeform && back.freeform.MVTAGGER_KIND === 'MUSIC_VIDEO', back.freeform && back.freeform.MVTAGGER_KIND);

    // The one that matters.
    const landed = await chunkBytes(out);
    ok(
      shape.name + ': every chunk offset still lands on its own chunk',
      landed.length === made.chunks && landed.every((byte, i) => byte === made.markers[i]),
      landed.map((b) => String.fromCharCode(b)).join(''),
    );

    ok(shape.name + ': the picture is not copied about', out.size >= made.bytes.length, out.size);
  }

  // Re-tagging twice must not stack two sets of tags or drift the offsets.
  const twice = build({ moovFirst: true });
  const once = await withTags(new Blob([twice.bytes]), { title: 'First' });
  const again = await withTags(once, { title: 'Second' });
  const back = await readTags(again);
  ok('re-tagging replaces rather than stacks', back.title === 'Second', back.title);
  const landed = await chunkBytes(again);
  ok(
    're-tagging keeps the offsets right',
    landed.every((byte, i) => byte === twice.markers[i]),
    landed.map((b) => String.fromCharCode(b)).join(''),
  );

  // A file this cannot write into says so rather than producing rubbish.
  let refused = false;
  try {
    await withTags(new Blob([Buffer.from('not an mp4 at all, not even close')]), { title: 'x' });
  } catch (e) {
    refused = true;
  }
  ok('a file that is not an MP4 is refused', refused);

  console.log('\n' + pass + ' passed, ' + fail + ' failed');
  process.exit(fail ? 1 : 0);
}

main();
