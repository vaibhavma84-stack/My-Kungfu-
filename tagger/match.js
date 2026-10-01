/*
 * Ranking search results against what the filename suggested.
 *
 * A port of the Android app's `Matching`, weights and all. The scoring is
 * deliberately explainable rather than clever: each rule adds a named amount
 * and carries a sentence saying so, because a wrong tag that cannot be
 * explained is worse than no tag -- and on this page, where the answer is
 * written to a file the person then keeps, worse still.
 */

import { fold, sameWord, hasDevanagari, devanagari } from './fold.js';

/** High enough to apply without a person looking at it. */
export const CONFIDENT = 0.80;

/** A match this strong will not be improved on by searching again. */
export const GOOD_ENOUGH_TO_STOP = 0.75;

/**
 * Folds a title down to something comparable: lower case, no accents, no
 * punctuation. Devanagari and other Indic text is written out in Latin letters
 * first, because a Devanagari title and its Latin spelling share no characters
 * at all and without this step the right match scores exactly zero.
 */
export function normalise(text) {
  if (!text || !text.trim()) return '';
  const latin = hasDevanagari(text) ? devanagari(text) : text;
  return latin
    .normalize('NFKD')
    // Combining marks, which is what an accent decomposes into.
    .replace(/[\u0300-\u036f\u1ab0-\u1aff\u20d0-\u20ff\ufe20-\ufe2f]/g, '')
    .toLowerCase()
    // Apostrophes are dropped rather than turned into a space, so "Don't Stop"
    // and "Dont Stop" come out as the same two words instead of three
    // against two.
    .replace(/['\u2019\u02bc`]/g, '')
    .replace(/[^\p{L}\p{N}\s]/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Words, folded so that two spellings of the same Indian title come out the
 * same. See `fold.js` for why that is necessary at all.
 */
function tokens(text) {
  const out = new Set();
  for (const word of normalise(text).split(' ')) {
    if (!word) continue;
    const folded = fold(word);
    if (folded) out.add(folded);
  }
  return [...out];
}

/**
 * Proportion of the shorter side's words that appear on both.
 *
 * Words are compared through `sameWord` rather than by equality, because a
 * transliterated title has no single correct spelling and exact comparison
 * scored the right answer at zero.
 */
export function tokenOverlap(a, b) {
  const ta = tokens(a);
  const tb = tokens(b);
  if (ta.length === 0 || tb.length === 0) return 0;
  const shared = ta.filter((word) => tb.some((other) => sameWord(word, other))).length;
  const smaller = Math.min(ta.length, tb.length);
  return Math.min(shared, smaller) / smaller;
}

/**
 * A candidate's title with its bracketed qualifier taken off.
 *
 * Shops put a great deal inside brackets, and it is not all the same kind of
 * thing:
 *
 *     Nachle Na (From "Dil Juunglee")     the same song, longer name
 *     Obsession (feat. Dua Lipa)          the same song, guest credited
 *     Butter (Megan Thee Stallion Remix)  somebody else's record entirely
 *
 * The first two are the record the filename is asking for. The third only has
 * the artist's name in it, and compared whole the three are indistinguishable
 * -- `tokenOverlap` measures against the shorter side, so three words sitting
 * inside a five-word title came out at 1.0 and were reported as "title matches
 * exactly". That put a BTS single top of the list at 84% for a file called
 * `Megan Thee Stallion | Fantasy Pool Party`. The head of the title is the part
 * that has to agree.
 *
 * A blank head falls back to the whole title, because a record really can be
 * called "(Everything I Do) I Do It for You".
 */
export function headline(title) {
  if (!title) return '';
  const at = Math.min(
    ...['(', '['].map((ch) => {
      const i = title.indexOf(ch);
      return i < 0 ? Number.MAX_SAFE_INTEGER : i;
    }),
  );
  if (at <= 0 || at === Number.MAX_SAFE_INTEGER) return title;
  return title.slice(0, at).trim() || title;
}

/**
 * How well a name matches a candidate's title.
 *
 * The qualifier is dropped rather than weighed. Nothing is lost in the other
 * direction: a filename that spells the qualifier out in full -- "Butter Megan
 * Thee Stallion Remix" -- is the longer side, so every word of the head still
 * counts and the match is still exact.
 */
export function titleMatch(mine, theirs) {
  return tokenOverlap(mine, headline(theirs));
}

/**
 * A segment with the uploader's habit taken off the end.
 *
 * "Nachle Na Video" is the song plus a word that is in no catalogue anywhere.
 * Comparing with it attached costs a third of the overlap and turns an exact
 * match into a partial one.
 */
const TRAILING_MARKER = /\s+((official|full|hd|4k)\s+)*(video|audio|lyrical|lyrics|song)\s*$/i;

function stripMarker(text) {
  return text.replace(TRAILING_MARKER, '').trim() || text;
}

function minutes(ms) {
  const total = Math.floor(ms / 1000);
  return Math.floor(total / 60) + ':' + String(total % 60).padStart(2, '0');
}

/** Within this of each other, two lengths are the same episode. */
const CLOSE_ENOUGH = 0.85;

/** Below this, they are not the same programme at all. */
const SOMEWHAT = 0.6;

const NUMBER_AND_LENGTH = 0.95;
const NUMBER_ONLY = 0.9;
const NUMBER_ODD_LENGTH = 0.7;
const NUMBER_WRONG_LENGTH = 0.3;

/**
 * Scores episodes, which are a different problem from songs.
 *
 * A season and episode number is an exact answer, so there is nothing to rank on
 * the words -- every result is the right number of the show it came from, and
 * the only question left is whether it is the right show.
 *
 * Length is what answers that, and it was being thrown away. A file of "House
 * of the Dragon The House That Dragons Built S03E08" matched the aftershow of
 * that name at 95%: same number, right show for the words in the name, and
 * twenty-two minutes against the file's seventy. An episode three times the
 * length of what came back is not that episode, whatever it is called.
 *
 * Where a length is missing on either side the number stands on its own, as it
 * did before -- an unknown is not evidence against.
 */
export function rankEpisodes(found, durationMs) {
  return found
    .map((candidate) => {
      const reasons = ['season and episode matched'];
      if (candidate.showName && candidate.showName.trim()) reasons.push(candidate.showName);

      const theirs = candidate.durationMs;
      let score;
      if (!theirs || theirs <= 0 || !durationMs || durationMs <= 0) {
        reasons.push('length not known');
        score = NUMBER_ONLY;
      } else {
        const ratio = Math.min(theirs, durationMs) / Math.max(theirs, durationMs);
        if (ratio >= CLOSE_ENOUGH) {
          reasons.push('length agrees');
          score = NUMBER_AND_LENGTH;
        } else if (ratio >= SOMEWHAT) {
          reasons.push('length differs: ' + minutes(theirs) +
            " against this file's " + minutes(durationMs));
          score = NUMBER_ODD_LENGTH;
        } else {
          reasons.push('length is nothing like it: ' + minutes(theirs) +
            " against this file's " + minutes(durationMs) +
            ' -- probably a different series');
          score = NUMBER_WRONG_LENGTH;
        }
      }
      return { candidate, score, reasons, confident: score >= CONFIDENT };
    })
    .sort((a, b) => b.score - a.score);
}

const INDIAN_FILM = ['hi', 'ta', 'te', 'ml', 'kn', 'bn', 'mr', 'pa', 'gu', 'ur'];

const LANGUAGE_NAMES = {
  hi: 'Hindi', ta: 'Tamil', te: 'Telugu', ml: 'Malayalam', kn: 'Kannada',
  bn: 'Bengali', mr: 'Marathi', pa: 'Punjabi', gu: 'Gujarati', ur: 'Urdu',
  en: 'English', es: 'Spanish', fr: 'French', de: 'German', ko: 'Korean',
  ja: 'Japanese', pt: 'Portuguese', it: 'Italian', ar: 'Arabic', zh: 'Chinese',
};

export function languageName(code) {
  return LANGUAGE_NAMES[code] || code;
}

function maxOf(values) {
  return values.reduce((best, v) => (v > best ? v : best), 0);
}

function score(c, parsed, durationMs, preferredLanguage) {
  let total = 0;
  const reasons = [];

  // The whole cleaned filename against "artist title" -- catches the case where
  // the dash split guessed the two the wrong way round.
  const whole = tokenOverlap(parsed.query, [c.artist, c.title].filter(Boolean).join(' '));
  total += whole * 0.35;

  /*
     The title, against every part of the name that could be one.

     Artist and album have always been tried against the segments after the
     pipes; the title was not, and that asymmetry was the whole fault in a real
     report. A file called

       Guru Randhawa | Nachle Na Video | DIL JUUNGLEE | Neeti M | ...

     scored "AZUL by Guru Randhawa" at 73% -- artist right, title matching
     nothing at all, runtime a coincidence -- and the actual song, "Nachle Na
     (From Dil Juunglee)", tenth at 63%, because the only place the words
     "Nachle Na" appeared was in a segment the title check never looked at.
  */
  const titleHit = maxOf([
    titleMatch(parsed.title, c.title),
    // The film convention puts the song first, so the parser's "artist" may in
    // fact be the title. Try it both ways.
    titleMatch(parsed.artist, c.title),
    ...(parsed.extras || []).map((e) => titleMatch(stripMarker(e), c.title)),
  ]);
  total += titleHit * 0.30;
  if (titleHit >= 0.99) reasons.push('title matches exactly');
  else if (titleHit >= 0.6) reasons.push('title mostly matches');

  // Said here rather than above, because it is only true with the title check
  // in hand. Every word of the filename can be accounted for by the
  // candidate's *artist* alone -- an artist's name and a record of theirs the
  // file has nothing to do with -- and claiming the title matched in that case
  // is the reason a wrong match looked convincing.
  if (whole >= 0.75 && titleHit >= 0.5) reasons.push('filename matches artist and title');

  const artistHit = maxOf([
    tokenOverlap(parsed.artist, c.artist),
    tokenOverlap(parsed.title, c.artist),
    // Singers are often listed after pipes in the filename.
    ...(parsed.extras || []).map((e) => tokenOverlap(e, c.artist)),
  ]);
  total += artistHit * 0.20;
  if (artistHit >= 0.75) reasons.push('artist matches');

  /*
     A title that matches and an artist that matches nothing.

     "Obsession" is a song title thirty-seven records share. When the filename
     names an artist and the candidate's has nothing to do with it, the title
     alone is not evidence -- it is a coincidence, and without this the top of
     the list is whichever stranger the shop happened to return first.

     Unless the field called "artist" was the title all along. "Kesariya -
     Brahmastra" parses as artist Kesariya, title Brahmastra, and the song is
     the other way round -- so an artist field that matched the candidate's
     *title* is evidence the parse was inverted, not evidence of a wrong artist.
  */
  const artistNamed = Boolean(parsed.artist && parsed.artist.trim());
  const artistWasReallyTheTitle = titleMatch(parsed.artist, c.title) >= 0.6;
  if (artistNamed && !artistWasReallyTheTitle && titleHit >= 0.6 && artistHit < 0.2) {
    total -= 0.12;
    reasons.push('but nothing in the name matches this artist');
  }

  const albumHit = maxOf([
    tokenOverlap(parsed.album, c.album),
    ...(parsed.extras || []).map((e) => tokenOverlap(e, c.album)),
  ]);
  total += albumHit * 0.08;
  if (albumHit >= 0.75) reasons.push('album or film matches');

  const candidateYear = c.year || (c.date ? c.date.slice(0, 4) : null);
  if (parsed.year && candidateYear) {
    if (parsed.year === candidateYear) {
      total += 0.05;
      reasons.push('year matches');
    } else if (Math.abs(parseInt(parsed.year, 10) - parseInt(candidateYear, 10)) > 2) {
      total -= 0.05;
    }
  }

  // Duration is worth a lot when it lines up, because titles repeat and running
  // times do not. Within three seconds is the same recording.
  if (durationMs && c.durationMs && c.durationMs > 0) {
    const gapSec = Math.abs(durationMs - c.durationMs) / 1000;
    // A song entry's length is the audio track. The video of the same song
    // routinely runs a minute longer -- an intro, dialogue, a fade -- so only a
    // like-for-like entry may be punished for a gap. Penalising the rest was
    // demoting correct matches.
    const comparable = c.kind === 'musicVideo';
    if (gapSec <= 3) {
      total += 0.15;
      reasons.push('length matches within 3s');
    } else if (gapSec <= 10) {
      total += 0.05;
    } else if (comparable && gapSec > 45) {
      total -= 0.20;
      reasons.push('length is off by ' + Math.trunc(gapSec) + 's');
    } else if (!comparable && gapSec > 150) {
      total -= 0.15;
      reasons.push('length is off by ' + Math.trunc(gapSec) + 's');
    }
  }

  const language = preferredLanguage || parsed.language;
  if (language && c.language === language) {
    total += 0.05;
    reasons.push(languageName(language) + ' matches');
  }

  // A music-video entry is the better description of the file, but its artwork
  // is a video still; the artwork rule handles that separately.
  if (c.kind === 'musicVideo') total += 0.03;

  const clamped = Math.max(0, Math.min(1, total));
  return { candidate: c, score: clamped, reasons, confident: clamped >= CONFIDENT };
}

export function rank(candidates, parsed, durationMs = null, preferredLanguage = null) {
  return candidates
    .map((c) => score(c, parsed, durationMs, preferredLanguage))
    .sort((a, b) => b.score - a.score);
}

/**
 * Which picture to embed.
 *
 * The rule asked for: the album front for English, the film cover for Hindi.
 * Those are the same instruction underneath -- use the *release* artwork, not a
 * frame from the video -- because a Hindi song's release is its film
 * soundtrack, whose cover is the film poster.
 *
 * So the ordering is always release artwork first, video stills last, and for
 * Indian-language tracks a soundtrack release ahead of a compilation.
 */
export function artworkUrls(chosen, alternatives = [], language = null, posterUrls = []) {
  const lang = language || chosen.language;
  const isFilmMusic = Boolean(lang) && INDIAN_FILM.includes(lang);
  const out = [];
  const add = (url) => {
    if (url && !out.includes(url)) out.push(url);
  };

  // A genuine film poster, when the optional film lookup found one.
  if (isFilmMusic) posterUrls.forEach(add);

  // Release artwork from entries that describe the same recording. A song entry
  // carries the album cover; a music-video entry carries a still.
  const sameRecording = [chosen, ...alternatives].filter(
    (c) => c.kind !== 'musicVideo' && sameEnough(c, chosen),
  );
  const soundtrackFirst = sameRecording.slice().sort((a, b) => {
    const rankOf = (c) => (isFilmMusic && looksLikeSoundtrack(c.album) ? 1 : 0);
    return rankOf(b) - rankOf(a);
  });
  for (const c of soundtrackFirst) (c.artworkUrls || []).forEach(add);

  // Anything else from the same search, then the video still as a last resort.
  for (const c of alternatives) if (c.kind !== 'musicVideo') (c.artworkUrls || []).forEach(add);
  (chosen.artworkUrls || []).forEach(add);
  for (const c of alternatives) (c.artworkUrls || []).forEach(add);

  return out;
}

export function looksLikeSoundtrack(album) {
  if (!album) return false;
  const lower = album.toLowerCase();
  return [
    'original motion picture', 'soundtrack', 'original soundtrack',
    'motion picture', 'from the film', 'film version',
  ].some((w) => lower.includes(w));
}

function sameEnough(a, b) {
  return tokenOverlap(a.title, b.title) >= 0.6 &&
    (!a.artist || !b.artist || tokenOverlap(a.artist, b.artist) >= 0.5);
}
