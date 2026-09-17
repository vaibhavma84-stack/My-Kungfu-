/*
 * Turning a downloaded filename into something searchable.
 *
 * A port of the Android app's `FilenameParser` and `MediaClassifier`, rule for
 * rule, because both were written from real filenames in a real collection and
 * every rule in them is a fault that was reported once. Two of them came from
 * reports written by this app's own search report, and the comments say which.
 *
 * Downloaded media is named in a handful of recognisable ways:
 *
 *     Adele - Hello (Official Music Video) [1080p].mp4
 *     03. Coldplay - Yellow.m4v
 *     Kesariya - Brahmastra | Ranbir Kapoor | Arijit Singh | Pritam.mp4
 *     Tum_Hi_Ho_-_Aashiqui_2_[YE7VzlLtp-4].mp4
 *     The.Family.Man.S02E04.1080p.WEB-DL.mp4
 *
 * The first is `Artist - Title`. The third is the Hindi film convention, which
 * is the opposite way round: the song comes first and the film, cast and
 * singers follow after pipes. All of them are handled, because the collection
 * has all of them.
 *
 * No lookbehind is used anywhere. Safari only learned it in 16.4 and an iPhone
 * that cannot run the page is worse than a rule expressed the long way round.
 */

import { hasDevanagari, devanagari, hasNonLatin } from './fold.js';

/** Junk that is never part of a title. Matched whole-word, any case. */
const NOISE = [
  'official music video', 'official video song', 'official video',
  'official audio', 'official lyric video', 'official trailer',
  'full video song', 'full video', 'video song', 'full song',
  'lyric video', 'lyrical video', 'with lyrics', 'lyrics', 'lyrical',
  'music video', 'official', 'hd video', 'audio song',
  'remastered', 'reupload', 'extended version',
  '4k', '8k', '2160p', '1440p', '1080p', '1080i', '720p', '480p', '360p',
  'hd', 'fhd', 'uhd', 'hq', 'x264', 'x265', 'h264', 'h265', 'hevc',
  'aac', 'mp3', 'm4a', 'webm', 'bluray', 'brrip', 'dvdrip', 'web-dl',
  '60fps', 'copyright free', 'free download',
  // Labels and channels, which are in the name of most Indian uploads and are
  // never part of the song.
  't-series', 't series', 'zee music company', 'zee music', 'sony music india',
  'sony music', 'tips official', 'tips music', 'saregama', 'yrf', 'eros now',
  'shemaroo', 'venus', 'speed records', 'white hill music', 'geet mp3',
  'times music', 'aditya music', 'lahari music', 'think music',
  'full audio', 'audio jukebox', 'jukebox', 'teaser', 'making of',
  'out now', 'latest hindi song', 'new hindi song', 'hindi song',
  'latest song', 'new song', 'bollywood song',
];

/** A YouTube id as yt-dlp leaves it: exactly eleven of this alphabet. */
const YOUTUBE_ID = /[\[(\-_ ][A-Za-z0-9_-]{11}[\])]?$/;

const BRACKETED = /[\[({][^\[\]{}()]*[\])}]/g;
const LEADING_TRACK = /^\s*(\d{1,3})\s*[.\-)]\s+/;
const SEPARATORS = [' - ', ' \u2013 ', ' \u2014 ', ' -- ', ' _ '];

/**
 * A run of underscores: a separator a download tool flattened.
 *
 * Two, not three. Tools differ in what they do with the pipe itself -- some
 * replace it, giving " | " -> "___", and some delete it, leaving the two spaces
 * around it as "__". Both are the same separator, and a real title almost never
 * carries a double space.
 */
const UNDERSCORE_RUN = /_{2,}/g;

/**
 * Words that are a whole segment and say nothing.
 *
 * "Maine Pi Rakhi Hai | Song | Tu Jhoothi Main Makkaar" has three fields and
 * only two of them are worth anything. Left in, the useless one is first in
 * line to be tried with the song -- so the search asks for "Maine Pi Rakhi Hai
 * Song" while the film sits unused behind it.
 */
const SEGMENT_NOISE = new Set([
  'song', 'songs', 'video', 'videos', 'audio', 'lyrical', 'lyric',
  'lyrics', 'official', 'full', 'hd', '4k', 'teaser', 'trailer',
  'promo', 'jukebox', 'reprise', 'cover', 'remix', 'new', 'latest',
]);

/** A trailing "song", with any of the words that usually come before it. */
const TRAILING_SONG = /\s+(full\s+)?(video\s+|audio\s+|lyrical\s+)?song\s*$/i;

/**
 * The words an uploader hangs on the end of the song and nobody else uses.
 *
 * `Nachle Na Video`, `Kesariya Full Video Song`, `Tera Hua Lyrical`. None of
 * them is part of a title and all of them wreck a search, because the catalogue
 * has the song under its own name and nothing else.
 */
const TRAILING_MARKER = /\s+((official|full|hd|4k)\s+)*(video|audio|lyrical|lyrics|song)\s*$/i;

/**
 * Whether this segment is the song rather than a name.
 *
 * The marker is the tell. An uploader writes `Nachle Na Video` for the song and
 * `Neeti M` for the singer, never the other way round.
 */
function looksLikeTheSong(part) {
  return TRAILING_MARKER.test(part);
}

/** The ways a guest is introduced, all of which mean the same thing. */
const FEATURING = /\s+(feat\.?|ft\.?|featuring|with|x|&|,)\s+/i;

const WORD_EDGE = '[^\\p{L}\\p{N}]';

function escapeRe(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * A whole-word replacement, written without lookbehind.
 *
 * The character in front of the word is captured and put back, which is the
 * standard way of saying "not in the middle of a word" to a regex engine that
 * can only look forward.
 */
function replaceWholeWord(text, word, replacement) {
  const re = new RegExp('(^|' + WORD_EDGE + ')' + escapeRe(word) + '(?!\\p{L}|\\p{N})', 'giu');
  return text.replace(re, (whole, before) => before + replacement);
}

/** The first year in the text, with where it starts, or null. */
function findYear(text) {
  const re = /(^|[^0-9])(19\d{2}|20\d{2})(?![0-9])/g;
  const m = re.exec(text);
  if (!m) return null;
  return { value: m[2], at: m.index + m[1].length };
}

export function stripExtension(fileName) {
  const dot = fileName.lastIndexOf('.');
  // Only treat a short trailing run as an extension: "Vol.2" keeps its .2
  // only because that is two characters and numeric, so guard on letters.
  if (dot > 0 && dot >= fileName.length - 6) {
    const tail = fileName.slice(dot + 1);
    if (/^[\p{L}\p{N}]+$/u.test(tail) && /\p{L}/u.test(tail)) return fileName.slice(0, dot);
  }
  return fileName;
}

export function extensionOf(fileName) {
  const stripped = stripExtension(fileName);
  return stripped.length === fileName.length ? '' : fileName.slice(stripped.length + 1);
}

function stripNoise(text) {
  let out = text;
  for (const word of NOISE) out = replaceWholeWord(out, word, ' ');
  return out;
}

/** Trims any of these characters from both ends. */
function trimChars(text, chars) {
  let start = 0;
  let end = text.length;
  while (start < end && chars.includes(text[start])) start++;
  while (end > start && chars.includes(text[end - 1])) end--;
  return text.slice(start, end);
}

const EDGE_JUNK = ' -\u2013\u2014|._,';

/**
 * The pipe that could not be a pipe.
 *
 * `BAILAMOS I PAYAL DEV I BADSHAH I ADITYA DEV I PAVAN BOB` is the film
 * convention -- song, then singers, then whoever else -- written with a capital
 * I where each pipe belongs. It is not a typo and it is not rare: no filesystem
 * will accept `|` in a name, so the uploader or the downloader puts the nearest
 * thing that looks the same, and every one of these arrives as one unbroken
 * string that no catalogue has ever heard of. Searched whole, it returned
 * nothing, which is exactly what it did in the report this came from.
 *
 * Two or more of them, or none. A single standalone I is far more likely a word
 * -- "You And I", "Me And I" -- and turning that one into a separator would cut
 * a title in half to fix a filename shape that is not there.
 */
function pipesWrittenAsLetters(text) {
  // Punctuation that is simply a pipe wearing a different code point can be
  // swapped outright; there is nothing else it could be.
  const work = text.split('\uff5c').join('|').split('\u00a6').join('|')
    .split('\u01c0').join('|');
  const standalone = /(\s)I(?=\s)/g;
  const count = (work.match(/\sI(?=\s)/g) || []).length;
  if (count < 2) return work;
  return work.replace(standalone, (whole, before) => before + '|');
}

/**
 * The artist a record is filed under, without the guests.
 *
 * "The Weeknd ft. Dua Lipa" is filed as The Weeknd everywhere that sells
 * anything; the feature is a credit, not part of the name. Returns null when
 * there is nothing to take off, so the caller does not end up with the same
 * query twice.
 *
 * From a report where the artist-and-title search was asked three times and
 * answered empty every time, while the bare title returned thirty-seven
 * strangers.
 */
function headliner(artist) {
  if (!artist || !artist.trim()) return null;
  const cut = artist.split(FEATURING)[0].trim();
  if (!cut) return null;
  return cut.toLowerCase() === artist.trim().toLowerCase() ? null : cut;
}

/**
 * Whether a field carries anything worth searching for.
 *
 * A bare "Song", a stray track number: these are fields in the name and nothing
 * in the answer. Dropping them matters more than it looks, because the first
 * field after the song is the one tried alongside it, and every attempt spent on
 * a word like "Song" is one the film does not get.
 */
function meaningless(part) {
  const words = part.split(/[^\p{L}\p{N}]+/u).filter((w) => w.length > 0);
  if (words.length === 0) return true;
  // A lone short number is a track number, not a field. Four digits is a year,
  // which is worth keeping.
  if (words.length === 1 && words[0].length <= 3 && /^\d+$/.test(words[0])) return true;
  return words.every((w) => SEGMENT_NOISE.has(w.toLowerCase()));
}

function split(text) {
  // Pipes first: a name with pipes is the film convention, and its dashes (if
  // any) are inside one of the fields rather than the top-level split.
  if (text.includes('|')) {
    const parts = text.split('|').map((p) => p.trim()).filter((p) => !meaningless(p));
    if (parts.length >= 2) {
      /*
         Which way round this one is.

         Two conventions share the pipe and they are opposites:

           Kesariya | Brahmastra | Arijit Singh      song first
           Guru Randhawa | Nachle Na Video | DIL ... artist first

         The second is what a channel uploads under its own name, and it read as
         a song called "Guru Randhawa" -- which no catalogue has, so the search
         found his other records and scored the right one tenth.

         The marker word is what tells them apart. "Nachle Na Video" is the song
         with the uploader's habit on the end of it; "Guru Randhawa" is a
         person. So when the first segment carries no marker and the second
         does, they swap.
      */
      const artistFirst = !looksLikeTheSong(parts[0]) && looksLikeTheSong(parts[1]);
      if (artistFirst) {
        const song = parts[1].replace(TRAILING_MARKER, '').trim();
        return {
          artist: parts[0].trim() || null,
          title: song || parts[1],
          album: null,
          extras: parts.slice(2),
        };
      }

      // The first field is the song, often with the film attached by a dash:
      // "Kesariya - Brahmastra". Those have to come apart -- searching for the
      // two glued together finds nothing at all. "Besharam Rang Song",
      // "Kesariya Song": the word is on the end of most of these uploads and is
      // part of no title. Only stripped here, inside the film convention, where
      // it is a reliable habit rather than a guess -- an English song really can
      // be called "Song 2".
      const head = parts[0].replace(TRAILING_SONG, '').trim();
      let song = head;
      let film = null;
      for (const sep of SEPARATORS) {
        const at = head.indexOf(sep);
        if (at > 0) {
          song = head.slice(0, at).trim();
          film = head.slice(at + sep.length).trim() || null;
          break;
        }
      }
      return {
        // The fields after the song are the film, the cast, the singers and the
        // composer, in no reliable order -- the one straight after the song is
        // as often an actor as a singer. Naming an actor as the artist poisons
        // the search and gets written to the file if the lookup then fails, so
        // nothing is claimed here. They are kept as extras, which the scoring
        // tries against every field.
        artist: null,
        title: song,
        album: film,
        extras: parts.slice(1),
      };
    }
  }

  for (const sep of SEPARATORS) {
    const at = text.indexOf(sep);
    if (at > 0) {
      const left = text.slice(0, at).trim();
      const right = text.slice(at + sep.length).trim();
      if (left && right) return { artist: left, title: right, album: null, extras: [] };
    }
  }

  // A bare dash with no spaces around it, as in "Adele-Hello".
  const bare = /^([^-]{2,})-([^-]{2,})$/.exec(text);
  if (bare) {
    return { artist: bare[1].trim(), title: bare[2].trim(), album: null, extras: [] };
  }

  return { artist: null, title: text || null, album: null, extras: [] };
}

/**
 * What could be worked out from a filename before anything was looked up.
 *
 * `query` is what actually gets sent to the search services -- they do better
 * with the whole cleaned phrase than with a guessed split, so the split into
 * `artist` and `title` is for showing the user and for scoring the results,
 * not for the search itself. `queries` is several attempts, best first: Indian
 * film music needs it, because which combination works depends entirely on how
 * the uploader happened to name the file.
 */
export function parseName(fileName) {
  const base = stripExtension(fileName);
  // Non-breaking spaces survive many download tools and break matching.
  let work = base.split('\u00a0').join(' ');

  // A run of underscores is a separator the download tool flattened. " | " is
  // three characters and a fussy filesystem takes all three, so the pipes that
  // carry the whole film convention arrive as "___":
  //
  //     Besharam Rang Song | Pathaan | Shah Rukh Khan, Deepika Padukone
  //     Besharam_Rang_Song___Pathaan___Shah_Rukh_Khan,_Deepika_Padukone
  //
  // This has to be undone before the single underscores are, or the separator
  // becomes an ordinary space, the name reads as one long title, and the search
  // asks for the song, the film and the entire cast at once -- which no
  // catalogue has anything filed under.
  const hadSpaces = work.includes(' ');
  work = work.replace(UNDERSCORE_RUN, ' | ');

  // yt-dlp writes underscores for spaces when a filesystem is fussy. Only undo
  // that if the name had no real spaces, so "Tum Hi Ho_Aashiqui" is untouched.
  if (!hadSpaces && work.includes('_')) work = work.split('_').join(' ');

  let trackNumber = null;
  const track = LEADING_TRACK.exec(work);
  if (track) {
    const n = parseInt(track[1], 10);
    // A leading "2013" is a year, not track two hundred and thirteen.
    if (n >= 1 && n <= 199) {
      trackNumber = n;
      work = work.slice(0, track.index) + work.slice(track.index + track[0].length);
    }
  }

  // Bracketed groups are almost always noise -- resolution, a video id, a
  // channel name. Keep a bracketed year, which is not.
  let yearFromBrackets = null;
  for (const group of work.match(BRACKETED) || []) {
    const found = findYear(group);
    if (found) {
      yearFromBrackets = found.value;
      break;
    }
  }
  work = work.replace(BRACKETED, ' ');
  work = work.replace(YOUTUBE_ID, ' ');

  work = stripNoise(work);

  const yearInText = findYear(work);
  const year = yearFromBrackets || (yearInText ? yearInText.value : null);

  work = trimChars(work.replace(/\s+/g, ' ').trim(), EDGE_JUNK);
  work = pipesWrittenAsLetters(work);

  const { artist, title, album, extras } = split(work);

  const joined = (parts) => parts.filter((p) => p && p.trim()).join(' ').trim();

  // Several attempts, best first, deduplicated. The film is worth as much as
  // the singer for finding an Indian track, and the uploader decides which of
  // the two is even in the name.
  const wanted = [
    joined([artist, title]),
    // The same thing without whoever was featured on it. A shop files a record
    // under the artist it was released by, and the guest is often only in the
    // subtitle or not there at all -- so "The Weeknd ft. Dua Lipa Obsession"
    // can find nothing where "The Weeknd Obsession" finds it at once.
    joined([headliner(artist), title]),
    joined([title, album]),
    extras.length > 0 ? joined([title, extras[0]]) : '',
    (title || '').trim(),
    work.trim(),
  ].filter((q) => q.length > 0);

  // A Devanagari title has to be searched for in Latin letters: the catalogues
  // index the transliterated spelling and searching in the original script
  // finds nothing at all. The Latin form goes first because it is the one that
  // will actually hit.
  const all = hasDevanagari(work)
    ? wanted.map(devanagari).concat(wanted).map((q) => q.trim()).filter((q) => q.length > 0)
    : wanted;

  const queries = [];
  for (const q of all) if (!queries.includes(q)) queries.push(q);
  const attempts = queries.slice(0, 4);

  return {
    artist: artist && artist.trim() ? artist.trim() : null,
    title: title && title.trim() ? title.trim() : null,
    album: album && album.trim() ? album.trim() : null,
    year,
    trackNumber,
    query: attempts[0] || work.trim(),
    queries: attempts,
    extras,
    language: languageFromScript(work),
  };
}

/**
 * The likeliest language of a title, from the script it is written in.
 *
 * Only ever a hint: a romanised Hindi title is indistinguishable from an
 * English one by script alone, which is why the storefront is asked as well
 * when a lookup happens.
 */
function languageFromScript(text) {
  if (!hasNonLatin(text)) return null;
  if (hasDevanagari(text)) return 'hi';
  if (/[\u0b80-\u0bff]/.test(text)) return 'ta';
  if (/[\u0c00-\u0c7f]/.test(text)) return 'te';
  if (/[\u0c80-\u0cff]/.test(text)) return 'kn';
  if (/[\u0d00-\u0d7f]/.test(text)) return 'ml';
  if (/[\u0980-\u09ff]/.test(text)) return 'bn';
  if (/[\u0a00-\u0a7f]/.test(text)) return 'pa';
  if (/[\u0a80-\u0aff]/.test(text)) return 'gu';
  if (/[\u0600-\u06ff]/.test(text)) return 'ur';
  return null;
}

// -------------------------------------------------------------- what kind

/** `S01E02`, `s1e2`, `S01.E02`, `S01 E02`. */
const SXXEXX = /(^|[^\p{L}\p{N}])[Ss](\d{1,2})[\s._-]*[Ee](\d{1,3})(?!\p{N})/u;

/** `1x02`, the other common way of writing it. */
const NxNN = /(^|[^\p{L}\p{N}])(\d{1,2})[xX](\d{2,3})(?!\p{N})/u;

/** `Season 1 Episode 2`, spelled out. */
const SPELLED = /(^|[^\p{L}\p{N}])Season[\s._-]*(\d{1,2})[\s._-]*Episode[\s._-]*(\d{1,3})/iu;

const YEAR_IN_BRACKETS = /[\[(](19\d{2}|20\d{2})[\])]/;

/**
 * Words that only ever appear in a scene release name. Two or more of these
 * alongside a year is a film, not a song with a year in the title.
 */
const RELEASE_NOISE = [
  'bluray', 'blu-ray', 'brrip', 'bdrip', 'dvdrip', 'dvdscr', 'web-dl', 'webdl',
  'webrip', 'hdrip', 'hdtv', 'hdcam', 'camrip', 'predvd', 'remux',
  'x264', 'x265', 'h264', 'h265', 'hevc', 'xvid', 'divx', 'avc',
  'aac', 'ac3', 'dts', 'ddp', 'dd5', '5 1', '7 1', 'atmos',
  '1080p', '720p', '2160p', '480p', '4k', 'uhd', 'hdr', 'sdr',
  'dual audio', 'multi audio', 'esub', 'esubs', 'msubs', 'subs',
  'yify', 'yts', 'rarbg', 'psa', 'hdhub', 'filmyzilla', 'extended',
  'uncut', 'proper', 'repack', 'limited', 'internal',
];

/** The media kinds this app writes, with Apple's `stik` number for each. */
export const KINDS = {
  MUSIC_VIDEO: { name: 'MUSIC_VIDEO', stik: 6, label: 'Music video' },
  MOVIE: { name: 'MOVIE', stik: 9, label: 'Film' },
  TV_EPISODE: { name: 'TV_EPISODE', stik: 10, label: 'TV episode' },
  PODCAST: { name: 'PODCAST', stik: 21, label: 'Podcast' },
  AUDIOBOOK: { name: 'AUDIOBOOK', stik: 2, label: 'Audiobook' },
  FITNESS: { name: 'FITNESS', stik: 9, label: 'Fitness' },
  LEARNING: { name: 'LEARNING', stik: 23, label: 'Learning' },
  NEWS: { name: 'NEWS', stik: 10, label: 'News' },
};

function noiseCount(text) {
  const lower = text.toLowerCase();
  return RELEASE_NOISE.filter((w) => lower.includes(w)).length;
}

function stripNoiseWords(text) {
  let out = text;
  for (const word of RELEASE_NOISE) out = replaceWholeWord(out, word, ' ');
  return out;
}

function cleanEdges(text) {
  return trimChars(text.replace(BRACKETED, ' ').replace(/\s+/g, ' ').trim(), EDGE_JUNK);
}

/** The part of the name in front of the release year. */
function titleBefore(text, year) {
  const at = text.indexOf(year);
  const head = at > 0 ? text.slice(0, at) : text;
  return cleanEdges(stripNoiseWords(head));
}

function episodeOf(spaced) {
  let m = SXXEXX.exec(spaced);
  let season;
  let episode;
  if (m) {
    season = parseInt(m[2], 10);
    episode = parseInt(m[3], 10);
  } else {
    m = NxNN.exec(spaced);
    if (m) {
      season = parseInt(m[2], 10);
      episode = parseInt(m[3], 10);
    } else {
      m = SPELLED.exec(spaced);
      if (!m) return null;
      season = parseInt(m[2], 10);
      episode = parseInt(m[3], 10);
    }
  }
  if (!Number.isFinite(season) || !Number.isFinite(episode)) return null;

  const markerStart = m.index + m[1].length;
  const markerEnd = m.index + m[0].length;
  const show = cleanEdges(spaced.slice(0, markerStart));
  const after = cleanEdges(stripNoiseWords(spaced.slice(markerEnd)));
  // Whatever is left after the marker and the release junk is the episode
  // title -- often nothing at all, which is fine.
  const episodeTitle = after.length >= 2 && after.length <= 80 && !/^\d+$/.test(after)
    ? after
    : null;

  // "S01E02 - Something" with no series name in front of it.
  const name = show || episodeTitle;
  if (!name) return null;

  const year = findYear(spaced);
  return {
    kind: KINDS.TV_EPISODE,
    name,
    season,
    episode,
    episodeTitle: show ? episodeTitle : null,
    year: year ? year.value : null,
    query: name,
  };
}

/**
 * What kind of thing this file is.
 *
 * Music videos are the default, so this only has to spot the other two.
 */
export function classify(fileName) {
  const base = stripExtension(fileName);
  const spaced = base.split('.').join(' ').split('_').join(' ')
    .replace(/\s+/g, ' ').trim();

  const episode = episodeOf(spaced);
  if (episode) return episode;

  const bracketed = YEAR_IN_BRACKETS.exec(spaced);
  const bare = findYear(spaced);
  const year = bracketed ? bracketed[1] : (bare ? bare.value : null);
  if (year && noiseCount(spaced) >= 2) {
    const name = titleBefore(spaced, year);
    if (name) return { kind: KINDS.MOVIE, name, year, query: name };
  }

  const parsed = parseName(fileName);
  return {
    kind: KINDS.MUSIC_VIDEO,
    name: parsed.title || spaced,
    year: parsed.year,
    query: parsed.query || spaced,
  };
}
