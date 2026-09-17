/*
 * Writing tags into an MP4, in a browser, without loading the film into memory.
 *
 * The same job the Android app does, and the same shape of solution: an MP4 is
 * a tree of boxes, the tags live in `moov/udta/meta/ilst`, and putting new ones
 * in means writing a new `moov` and then correcting every chunk offset that
 * moved because the new one is a different size.
 *
 * What is different here is the memory. A film is several gigabytes and a phone
 * browser will not hold one, so nothing here ever reads the picture. The output
 * is assembled as a Blob made of *slices of the original File* plus a few
 * kilobytes of new bytes; a slice is a reference, not a copy, so building it
 * costs nothing and the browser streams it to disk when it is saved.
 *
 * Only the `moov` is actually read, which is a few hundred kilobytes at the
 * worst, and only its own boxes are rewritten.
 */

const HEADER = 8;

/** `©` is 0xA9 in Latin-1, which is how these atom names are really spelled. */
const C = '©';

export const ATOMS = {
  title: C + 'nam',
  artist: C + 'ART',
  albumArtist: 'aART',
  album: C + 'alb',
  date: C + 'day',
  genre: C + 'gen',
  comment: C + 'cmt',
  composer: C + 'wrt',
  encoder: C + 'too',
  lyrics: C + 'lyr',
  description: 'desc',
  longDescription: 'ldes',
  mediaKind: 'stik',
  show: 'tvsh',
  season: 'tvsn',
  episode: 'tves',
  network: 'tvnn',
  cover: 'covr',
  freeform: '----',
};

/** What a `data` box says it is holding. */
const TYPE_TEXT = 1;
const TYPE_JPEG = 13;
const TYPE_PNG = 14;
const TYPE_INT = 21;

const MEAN = 'com.apple.iTunes';

// ---------------------------------------------------------------- reading

/**
 * The top-level boxes, by name, without reading their contents.
 *
 * Only the sixteen bytes of each header are fetched, which for a four gigabyte
 * film is four or five reads of sixteen bytes.
 */
export async function topLevelBoxes(file) {
  const boxes = [];
  let at = 0;
  while (at + HEADER <= file.size) {
    const head = new DataView(await file.slice(at, at + 16).arrayBuffer());
    if (head.byteLength < HEADER) break;
    let size = head.getUint32(0);
    const type = latin1(head, 4, 4);
    let headerSize = HEADER;
    if (size === 1) {
      if (head.byteLength < 16) break;
      // A 64-bit size, for the one box that is allowed to be enormous.
      size = Number(head.getBigUint64(8));
      headerSize = 16;
    } else if (size === 0) {
      // "To the end of the file", which is legal for the last box.
      size = file.size - at;
    }
    if (size < headerSize) break;
    boxes.push({ type, start: at, size, headerSize, end: at + size });
    at += size;
  }
  return boxes;
}

/** The children of a box already in memory. */
export function children(bytes, from, to) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const out = [];
  let at = from;
  while (at + HEADER <= to) {
    let size = view.getUint32(at);
    const type = latin1(view, at + 4, 4);
    let headerSize = HEADER;
    if (size === 1) {
      size = Number(view.getBigUint64(at + 8));
      headerSize = 16;
    } else if (size === 0) {
      size = to - at;
    }
    if (size < headerSize || at + size > to) break;
    out.push({ type, start: at, size, headerSize, payload: at + headerSize, end: at + size });
    at += size;
  }
  return out;
}

/** Finds one child by name, or null. */
export function child(bytes, from, to, type) {
  return children(bytes, from, to).find((box) => box.type === type) || null;
}

/**
 * The tags already in a file.
 *
 * Returns a plain object with only what was found, so a file with nothing in
 * it gives `{}` rather than a shape full of nulls.
 */
export async function readTags(file) {
  const boxes = await topLevelBoxes(file);
  const moovBox = boxes.find((b) => b.type === 'moov');
  if (!moovBox) return {};
  const moov = new Uint8Array(await file.slice(moovBox.start, moovBox.end).arrayBuffer());
  const ilst = findIlst(moov);
  if (!ilst) return {};
  return readIlst(moov, ilst);
}

function findIlst(moov) {
  const udta = child(moov, HEADER, moov.length, 'udta');
  if (!udta) return null;
  const meta = child(moov, udta.payload, udta.end, 'meta');
  if (!meta) return null;
  // `meta` is a full box: four bytes of version and flags before its children.
  // QuickTime writes it as a plain box, so rather than trusting the extension
  // this looks for the `hdlr` that comes first either way.
  const plain = latin1At(moov, meta.payload + 4, 4) === 'hdlr';
  const start = plain ? meta.payload : meta.payload + 4;
  return child(moov, start, meta.end, 'ilst');
}

function readIlst(moov, ilst) {
  const tags = {};
  for (const item of children(moov, ilst.payload, ilst.end)) {
    const data = child(moov, item.payload, item.end, 'data');
    if (!data) continue;
    const view = new DataView(moov.buffer, moov.byteOffset, moov.byteLength);
    const indicator = view.getUint32(data.payload) & 0x00ffffff;
    const from = data.payload + 8;
    const payload = moov.subarray(from, data.end);

    switch (item.type) {
      case ATOMS.title: tags.title = text(payload); break;
      case ATOMS.artist: tags.artist = text(payload); break;
      case ATOMS.albumArtist: tags.albumArtist = text(payload); break;
      case ATOMS.album: tags.album = text(payload); break;
      case ATOMS.date: tags.date = text(payload); break;
      case ATOMS.genre: tags.genre = text(payload); break;
      case ATOMS.comment: tags.comment = text(payload); break;
      case ATOMS.composer: tags.composer = text(payload); break;
      case ATOMS.lyrics: tags.lyrics = text(payload); break;
      case ATOMS.description: tags.description = text(payload); break;
      case ATOMS.longDescription: tags.longDescription = text(payload); break;
      case ATOMS.show: tags.show = text(payload); break;
      case ATOMS.network: tags.network = text(payload); break;
      case ATOMS.season: tags.season = number(payload); break;
      case ATOMS.episode: tags.episode = number(payload); break;
      case ATOMS.mediaKind: tags.mediaKind = payload.length ? payload[payload.length - 1] : null; break;
      case ATOMS.cover:
        tags.cover = {
          bytes: payload.slice(),
          mime: indicator === TYPE_PNG ? 'image/png' : 'image/jpeg',
        };
        break;
      case ATOMS.freeform: {
        const name = freeformName(moov, item);
        const value = text(payload);
        if (name && value) (tags.freeform ||= {})[name] = value;
        break;
      }
      default: break;
    }
  }
  return tags;
}

function freeformName(moov, item) {
  const box = child(moov, item.payload, item.end, 'name');
  if (!box) return null;
  // `name` is a full box too: four bytes of version and flags first.
  return utf8(moov.subarray(box.payload + 4, box.end));
}

// ---------------------------------------------------------------- writing

/**
 * The file with new tags in it, as a Blob that can be downloaded.
 *
 * The original is not touched -- a browser cannot touch it -- so this is a new
 * file, which is the same promise the Android app makes for a different reason.
 *
 * Throws when the file is not one this can write into. Better a sentence than a
 * file that looks tagged and will not play.
 */
export async function withTags(file, tags) {
  const boxes = await topLevelBoxes(file);
  const moovBox = boxes.find((b) => b.type === 'moov');
  if (!moovBox) throw new Error('This file has no moov box, so it is not an MP4 this can tag.');
  if (!boxes.some((b) => b.type === 'ftyp')) {
    throw new Error('This file has no ftyp box, so it is not an MP4 this can tag.');
  }

  const moov = new Uint8Array(await file.slice(moovBox.start, moovBox.end).arrayBuffer());
  const rebuilt = rebuildMoov(moov, tags);
  const shift = rebuilt.length - moov.length;

  /*
     Chunk offsets are absolute positions in the file, so anything after the
     moov has just moved. Fixing them is the whole difficulty of writing an MP4
     tag: get it wrong and the file still opens, still shows its new title, and
     plays silence or nothing at all.
  */
  if (shift !== 0) shiftChunkOffsets(rebuilt, moovBox.start, shift);

  const parts = [];
  for (const box of boxes) {
    if (box.type === 'moov') parts.push(rebuilt);
    else parts.push(file.slice(box.start, box.end));
  }
  return new Blob(parts, { type: 'video/mp4' });
}

/**
 * A new `moov` carrying the tags.
 *
 * Everything that is not `udta` is copied across untouched, because a moov
 * holds the tracks and the timing and rewriting any of that would be a
 * different and much more dangerous program.
 */
function rebuildMoov(moov, tags) {
  const kept = [];
  for (const box of children(moov, HEADER, moov.length)) {
    if (box.type !== 'udta') kept.push(moov.subarray(box.start, box.end));
  }
  kept.push(udta(tags));

  let size = HEADER;
  for (const part of kept) size += part.length;
  const out = new Uint8Array(size);
  const view = new DataView(out.buffer);
  view.setUint32(0, size);
  out.set(ascii('moov'), 4);
  let at = HEADER;
  for (const part of kept) {
    out.set(part, at);
    at += part.length;
  }
  return out;
}

/** `udta` → `meta` → `hdlr` + `ilst`, which is where iTunes tags live. */
function udta(tags) {
  const hdlr = box('hdlr', concat([
    new Uint8Array(8),
    ascii('mdirappl'),
    new Uint8Array(9),
  ]));
  const list = ilst(tags);
  const meta = box('meta', concat([new Uint8Array(4), hdlr, list]));
  return box('udta', meta);
}

function ilst(tags) {
  const items = [];
  const put = (type, value) => {
    if (value === undefined || value === null || value === '') return;
    items.push(box(type, dataBox(TYPE_TEXT, utf8Bytes(String(value)))));
  };

  put(ATOMS.title, tags.title);
  put(ATOMS.artist, tags.artist);
  put(ATOMS.albumArtist, tags.albumArtist || tags.artist);
  put(ATOMS.album, tags.album);
  put(ATOMS.date, tags.date);
  put(ATOMS.genre, tags.genre);
  put(ATOMS.comment, tags.comment);
  put(ATOMS.composer, tags.composer);
  put(ATOMS.lyrics, tags.lyrics);
  put(ATOMS.description, tags.description);
  put(ATOMS.longDescription, tags.longDescription);
  put(ATOMS.show, tags.show);
  put(ATOMS.network, tags.network);

  if (Number.isInteger(tags.season) && tags.season > 0) {
    items.push(box(ATOMS.season, dataBox(TYPE_INT, be32(tags.season))));
  }
  if (Number.isInteger(tags.episode) && tags.episode > 0) {
    items.push(box(ATOMS.episode, dataBox(TYPE_INT, be32(tags.episode))));
  }
  if (Number.isInteger(tags.mediaKind)) {
    items.push(box(ATOMS.mediaKind, dataBox(TYPE_INT, new Uint8Array([tags.mediaKind]))));
  }
  if (tags.cover && tags.cover.bytes && tags.cover.bytes.length) {
    const kind = tags.cover.mime === 'image/png' ? TYPE_PNG : TYPE_JPEG;
    items.push(box(ATOMS.cover, dataBox(kind, tags.cover.bytes)));
  }
  for (const [name, value] of Object.entries(tags.freeform || {})) {
    if (value) items.push(freeform(name, String(value)));
  }

  // Says which program wrote the file, which is the polite thing to do and
  // makes a file traceable back to this when something looks wrong.
  put(ATOMS.encoder, 'Media Centre (web)');

  return box('ilst', concat(items));
}

function freeform(name, value) {
  const mean = box('mean', concat([new Uint8Array(4), ascii(MEAN)]));
  const label = box('name', concat([new Uint8Array(4), utf8Bytes(name)]));
  return box(ATOMS.freeform, concat([mean, label, dataBox(TYPE_TEXT, utf8Bytes(value))]));
}

function dataBox(indicator, payload) {
  return box('data', concat([be32(indicator), new Uint8Array(4), payload]));
}

/**
 * Moves every chunk offset that points past the moov.
 *
 * `stco` holds 32-bit offsets and `co64` 64-bit ones; both mean the same thing,
 * and a file with either will have every one of them wrong if the moov changed
 * size and nobody corrected them.
 *
 * Only offsets past where the moov started are touched. An offset before it
 * points at something that has not moved.
 */
function shiftChunkOffsets(moov, moovStart, shift) {
  const view = new DataView(moov.buffer, moov.byteOffset, moov.byteLength);
  walk(moov, HEADER, moov.length);

  function walk(bytes, from, to) {
    for (const box of children(bytes, from, to)) {
      if (box.type === 'stco' || box.type === 'co64') {
        const count = view.getUint32(box.payload + 4);
        const wide = box.type === 'co64';
        let at = box.payload + 8;
        for (let i = 0; i < count; i++) {
          if (wide) {
            const value = view.getBigUint64(at);
            if (Number(value) > moovStart) view.setBigUint64(at, value + BigInt(shift));
            at += 8;
          } else {
            const value = view.getUint32(at);
            if (value > moovStart) view.setUint32(at, value + shift);
            at += 4;
          }
        }
      } else if (CONTAINERS.has(box.type)) {
        walk(bytes, box.payload, box.end);
      }
    }
  }
}

/** Boxes that hold other boxes on the way down to the chunk offsets. */
const CONTAINERS = new Set(['trak', 'mdia', 'minf', 'stbl', 'edts', 'udta']);

// ---------------------------------------------------------------- plumbing

function box(type, payload) {
  const body = payload instanceof Uint8Array ? payload : concat(payload);
  const out = new Uint8Array(HEADER + body.length);
  new DataView(out.buffer).setUint32(0, out.length);
  out.set(latin1Bytes(type), 4);
  out.set(body, HEADER);
  return out;
}

function concat(parts) {
  let size = 0;
  for (const part of parts) size += part.length;
  const out = new Uint8Array(size);
  let at = 0;
  for (const part of parts) {
    out.set(part, at);
    at += part.length;
  }
  return out;
}

function be32(value) {
  const out = new Uint8Array(4);
  new DataView(out.buffer).setUint32(0, value);
  return out;
}

function ascii(text) {
  return Uint8Array.from(text, (c) => c.charCodeAt(0) & 0xff);
}

/** Atom names are Latin-1, which is why `©` is one byte rather than two. */
function latin1Bytes(text) {
  return Uint8Array.from(text, (c) => c.charCodeAt(0) & 0xff);
}

function utf8Bytes(text) {
  return new TextEncoder().encode(text);
}

function utf8(bytes) {
  return new TextDecoder().decode(bytes);
}

function text(bytes) {
  const value = utf8(bytes);
  return value.length ? value : undefined;
}

function number(bytes) {
  if (bytes.length < 4) return undefined;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const value = view.getUint32(bytes.length - 4);
  return value > 0 ? value : undefined;
}

function latin1(view, at, length) {
  let out = '';
  for (let i = 0; i < length; i++) out += String.fromCharCode(view.getUint8(at + i));
  return out;
}

function latin1At(bytes, at, length) {
  if (at + length > bytes.length) return '';
  let out = '';
  for (let i = 0; i < length; i++) out += String.fromCharCode(bytes[at + i]);
  return out;
}
