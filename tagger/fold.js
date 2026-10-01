/*
 * Getting Indian song titles into a form two spellings of the same name agree on.
 *
 * A port of the Android app's `Transliterate`, and it exists for the same
 * reason: an English title is spelled one way, and a Hindi title is not spelled
 * at all -- it is transliterated, and everybody transliterates differently.
 *
 *     Kesariya   Kesaria    Kesarya
 *     Naatu      Natu       Nattu
 *     Zindagi    Jindagi
 *
 * Compared as plain words those score zero against each other, so the right
 * answer ranked level with an unrelated song. Two steps fix it: write the
 * Devanagari out in Latin letters, then fold away the spelling choices that
 * carry no meaning.
 *
 * The folding is one-way and lossy on purpose. It is only ever used to decide
 * whether two titles are the same; nothing folded is shown to anyone or written
 * into a file.
 *
 * Devanagari is written here as code points rather than as letters. Every one
 * of the vowel signs is a combining mark, which in a source file attaches
 * itself to whatever happens to precede it -- so the table would be unreadable
 * and, worse, would look readable while being wrong.
 */

/**
 * Consonants, without the inherent vowel.
 *
 * Short vowels throughout rather than `aa`/`ii`, because the output is meant to
 * read like the spelling people actually use: the name of the film Brahmastra
 * comes out "brahmastra", which is what the catalogue calls it, rather than
 * "brahmaastra".
 */
const CONSONANTS = {
  '\u0915': 'k', '\u0916': 'kh', '\u0917': 'g', '\u0918': 'gh', '\u0919': 'ng',
  '\u091a': 'ch', '\u091b': 'chh', '\u091c': 'j', '\u091d': 'jh', '\u091e': 'ny',
  '\u091f': 't', '\u0920': 'th', '\u0921': 'd', '\u0922': 'dh', '\u0923': 'n',
  '\u0924': 't', '\u0925': 'th', '\u0926': 'd', '\u0927': 'dh', '\u0928': 'n',
  '\u092a': 'p', '\u092b': 'ph', '\u092c': 'b', '\u092d': 'bh', '\u092e': 'm',
  '\u092f': 'y', '\u0930': 'r', '\u0932': 'l', '\u0935': 'v',
  '\u0936': 'sh', '\u0937': 'sh', '\u0938': 's', '\u0939': 'h', '\u0933': 'l',
  // Precomposed nukta forms, common in Urdu-influenced Hindi. Each also exists
  // as a base letter plus a separate nukta mark, handled below.
  '\u0958': 'q',
  '\u0959': 'kh',
  '\u095a': 'g',
  '\u095b': 'z',
  '\u095c': 'r',
  '\u095d': 'rh',
  '\u095e': 'f',
  '\u095f': 'y',
};

/** What a bare nukta does to the consonant before it. */
const NUKTA = {
  '\u0915': 'q',
  '\u0916': 'kh',
  '\u0917': 'g',
  '\u091c': 'z',
  '\u0921': 'r',
  '\u0922': 'rh',
  '\u092b': 'f',
};

const INDEPENDENT_VOWELS = {
  '\u0905': 'a', '\u0906': 'a', '\u0907': 'i', '\u0908': 'i',
  '\u0909': 'u', '\u090a': 'u',
  '\u090b': 'ri', '\u0960': 'ri', '\u090e': 'e', '\u090f': 'e', '\u0910': 'ai',
  '\u0912': 'o', '\u0913': 'o', '\u0914': 'au', '\u0911': 'o', '\u090d': 'e',
};

/** Vowel signs, which replace a consonant's inherent vowel. */
const MATRAS = {
  '\u093e': 'a', '\u093f': 'i', '\u0940': 'i', '\u0941': 'u', '\u0942': 'u',
  '\u0943': 'ri', '\u0944': 'ri', '\u0945': 'e', '\u0946': 'e', '\u0947': 'e',
  '\u0948': 'ai', '\u0949': 'o', '\u094a': 'o', '\u094b': 'o', '\u094c': 'au',
};

const VIRAMA = '\u094d';
const NUKTA_SIGN = '\u093c';
const ANUSVARA = '\u0902';
const CHANDRABINDU = '\u0901';
const VISARGA = '\u0903';
const AVAGRAHA = '\u093d';
const DIGIT_ZERO = 0x0966;

const DEVANAGARI = /[\u0900-\u097f]/;

/** True if there is any Devanagari here worth converting. */
export function hasDevanagari(text) {
  return DEVANAGARI.test(text || '');
}

/** True if any of this is outside the Latin alphabet and common punctuation. */
export function hasNonLatin(text) {
  return /[^\u0000-\u024f\u2000-\u206f]/.test(text || '');
}

/**
 * Devanagari written out in Latin letters.
 *
 * Anything that is not Devanagari passes through untouched, so a title with an
 * English word in it survives intact.
 */
export function devanagari(text) {
  let out = '';
  // Where an inherent vowel was supplied rather than written. Only those are
  // candidates for the schwa deletion below; a vowel the writer actually put
  // there is never dropped.
  const inherent = [];
  let i = 0;
  while (i < text.length) {
    const ch = text[i];

    const consonant = CONSONANTS[ch];
    if (consonant !== undefined) {
      let base = consonant;
      i++;
      // A nukta written separately rather than precomposed.
      if (i < text.length && text[i] === NUKTA_SIGN) {
        if (NUKTA[ch] !== undefined) base = NUKTA[ch];
        i++;
      }
      out += base;
      if (i < text.length && text[i] === VIRAMA) {
        // A virama silences the inherent vowel: it is what joins consonants
        // into a cluster, as in "brahmastra".
        i++;
      } else if (i < text.length && MATRAS[text[i]] !== undefined) {
        out += MATRAS[text[i]];
        i++;
      } else {
        inherent.push(out.length);
        out += 'a';
      }
      continue;
    }

    const vowel = INDEPENDENT_VOWELS[ch];
    if (vowel !== undefined) {
      out += vowel;
      i++;
      continue;
    }

    const code = ch.charCodeAt(0);
    if (ch === ANUSVARA || ch === CHANDRABINDU) {
      out += 'n';
    } else if (ch === VISARGA) {
      out += 'h';
    } else if (code >= DIGIT_ZERO && code <= DIGIT_ZERO + 9) {
      out += String.fromCharCode(48 + (code - DIGIT_ZERO));
    } else if (MATRAS[ch] !== undefined || ch === VIRAMA || ch === NUKTA_SIGN ||
        ch === AVAGRAHA) {
      // Standalone matras, viramas and the avagraha carry nothing on their
      // own; dropping them is better than emitting noise.
    } else {
      out += ch;
    }
    i++;
  }
  return deleteFinalSchwa(out, inherent);
}

const LETTER = /\p{L}/u;

/**
 * Drops the inherent vowel at the end of a word, which Hindi does not pronounce.
 *
 * The pronoun "tum" is written with a final consonant carrying its inherent
 * vowel, and is "tum" rather than "tuma"; the name Dilbar is "dilbar". Writing
 * that vowel out leaves a word that matches nothing, and words this short are
 * below the floor where the fuzzy comparison can rescue them.
 *
 * Only the word-final schwa is dropped. The medial one -- the "a" that also
 * disappears from the middle of "dilbar" -- follows a rule with enough
 * exceptions that applying it naively turns "kesariya" into "kesriya", so it is
 * left in and `sameWord` absorbs the difference instead.
 */
function deleteFinalSchwa(text, inherent) {
  if (inherent.length === 0) return text;
  const chars = Array.from(text);
  const drop = new Array(chars.length).fill(false);

  for (const at of inherent) {
    const wordFinal = at === chars.length - 1 || !LETTER.test(chars[at + 1]);
    if (!wordFinal) continue;

    // Keep it when the word has no other vowel, so a bare consonant does not
    // become unpronounceable: a lone "ja" stays "ja", not "j".
    let i = at - 1;
    let letters = 0;
    let hasVowel = false;
    while (i >= 0 && LETTER.test(chars[i])) {
      letters++;
      if ('aeiou'.includes(chars[i])) hasVowel = true;
      i--;
    }
    if (hasVowel && letters >= 2) drop[at] = true;
  }

  let out = '';
  for (let i = 0; i < chars.length; i++) if (!drop[i]) out += chars[i];
  return out;
}

/**
 * Collapses the spelling choices that carry no meaning.
 *
 * Every rule here is a pair people genuinely use for the same sound: doubling a
 * vowel for length (`Naatu`/`Natu`), `z` where Devanagari has a nukta and
 * others write `j` (`Zindagi`/`Jindagi`), `w` for `v` (`Wo`/`Vo`), `ph` for `f`
 * (`Phir`/`Fir`).
 *
 * Deliberately conservative. Folding `kh` to `k` would make "Khan" and "Kaan"
 * the same word, which is a different name -- so aspirated consonants are left
 * alone even though they too are spelled variously.
 */
export function latinFold(text) {
  let s = (text || '').toLowerCase();
  // Long vowels first, before doubles collapse, so "ee" becomes "i" rather
  // than "e".
  s = s.split('aa').join('a').split('ee').join('i').split('ii').join('i')
    .split('oo').join('u').split('uu').join('u');
  s = s.split('ph').join('f').split('z').join('j').split('w').join('v')
    .split('q').join('k').split('x').join('ks');
  // Any remaining doubled letter: "Nattu" and "Natu" are one word.
  let out = '';
  for (const ch of s) if (out.length === 0 || out[out.length - 1] !== ch) out += ch;
  return out;
}

/** Devanagari written out, then folded. The form used for comparing. */
export function fold(text) {
  return latinFold(hasDevanagari(text) ? devanagari(text) : text);
}

/**
 * Levenshtein distance, giving up once it passes `limit`.
 *
 * Folding cannot catch everything: "Kesaria" and "Kesariya" differ by an
 * inserted letter and no rule will ever unify them. One edit of slack does, and
 * the length floors in `sameWord` keep it from making short words equal.
 */
export function editDistance(a, b, limit) {
  if (a === b) return 0;
  if (Math.abs(a.length - b.length) > limit) return limit + 1;
  let previous = new Array(b.length + 1);
  let current = new Array(b.length + 1);
  for (let j = 0; j <= b.length; j++) previous[j] = j;
  for (let i = 1; i <= a.length; i++) {
    current[0] = i;
    let best = current[0];
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      current[j] = Math.min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost);
      best = Math.min(best, current[j]);
    }
    if (best > limit) return limit + 1;
    const swap = previous;
    previous = current;
    current = swap;
  }
  return previous[b.length];
}

/** A trailing run of one character, removed. */
function trimEnd(text, ch) {
  let end = text.length;
  while (end > 0 && text[end - 1] === ch) end--;
  return text.slice(0, end);
}

/**
 * Whether two already-folded words are the same word.
 *
 * The length floors matter: "tera" and "mera" are one edit apart and are
 * different words, so slack is only given to words long enough that a single
 * difference is far more likely to be a spelling choice than a different word.
 */
export function sameWord(a, b) {
  if (a === b) return true;

  // A trailing "a" is optional: Hindi drops it in speech and transliterations
  // disagree about whether to write it. "brahmastr" and "brahmastra" are one
  // word, and so are "tum" and "tuma". Stripping it from both sides keeps this
  // symmetric.
  const ta = trimEnd(a, 'a');
  const tb = trimEnd(b, 'a');
  if (ta === tb && ta.length >= 2) return true;

  const shortest = Math.min(a.length, b.length);
  if (shortest >= 9) return editDistance(a, b, 2) <= 2;
  if (shortest >= 6) return editDistance(a, b, 1) <= 1;
  return false;
}
