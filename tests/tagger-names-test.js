// Filename parsing and ranking for the iPhone tagger.
//
// These are the Android app's own tests, carried across case for case. They are
// worth having twice because every one of them is a fault that was reported
// against a real file, and a port that quietly disagrees with the app on any of
// them would tag the same collection two different ways.
//
// Run with: node tests/tagger-names-test.js
let pass = 0, fail = 0;
function ok(name, cond, got) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (got !== undefined ? '  -> ' + got : '')); }
}
function eq(name, expected, actual) {
  ok(name, expected === actual, JSON.stringify(actual) + ' (wanted ' + JSON.stringify(expected) + ')');
}
function near(name, expected, actual) {
  ok(name, Math.abs(expected - actual) < 0.001, actual);
}

function candidate(extra) {
  return Object.assign({ source: 'iTunes', id: '1', title: '', artist: null, kind: 'song' }, extra);
}

async function main() {
  const { parseName, classify, stripExtension, extensionOf, KINDS } =
    await import('../tagger/names.js');
  const { rank, rankEpisodes, tokenOverlap, titleMatch, headline, CONFIDENT } =
    await import('../tagger/match.js');
  const { devanagari, sameWord, fold } = await import('../tagger/fold.js');

  // ---------------------------------------------------------- the usual shapes

  {
    const p = parseName('Adele - Hello (Official Music Video) [1080p].mp4');
    eq('artist from the usual western shape', 'Adele', p.artist);
    eq('title from the usual western shape', 'Hello', p.title);
    eq('one good query comes out of it', 'Adele Hello', p.query);
  }

  {
    const p = parseName('03. Coldplay - Yellow.m4v');
    eq('a leading track number is taken off', 3, p.trackNumber);
    eq('and the artist survives it', 'Coldplay', p.artist);
  }

  {
    const p = parseName('2013 - Some Song.mp4');
    eq('a leading year is not a track number', null, p.trackNumber);
  }

  {
    const p = parseName('Kesariya – Brahmastra | Ranbir Kapoor | Arijit Singh | Pritam.mp4');
    eq('the film convention finds the song', 'Kesariya', p.title);
    eq('and the film', 'Brahmastra', p.album);
    ok('an actor is never taken for the singer', p.artist === null, p.artist);
    ok(
      'the singer is kept as an extra to score against',
      p.extras.some((e) => e.includes('Arijit')),
      JSON.stringify(p.extras),
    );
  }

  {
    const p = parseName('Besharam_Rang_Song___Pathaan___Shah_Rukh_Khan,_Deepika_Padukone.mp4');
    eq('underscore runs are the pipes of the film convention', 'Besharam Rang', p.title);
    ok('and the film is one of the things asked',
      p.queries.some((q) => q.includes('Besharam Rang') && q.includes('Pathaan')),
      JSON.stringify(p.queries));
  }

  {
    const p = parseName('Tum Hi Ho_Aashiqui 2.mp4');
    eq('an underscore inside a spaced name is left alone', 'Tum Hi Ho_Aashiqui 2', p.title);
  }

  {
    const p = parseName('Arijit Singh - Kesariya - T-Series [1080p].mp4');
    ok('record labels are not searched for', !p.query.toLowerCase().includes('series'), p.query);
  }

  // ------------------------------------------- the pipe written as a letter I

  {
    // From a report: this came back with nothing at all, because no catalogue
    // has ever heard of the whole string.
    const p = parseName('BAILAMOS I PAYAL DEV I BADSHAH I ADITYA DEV I PAVAN BOB.mp4');
    eq('a capital I between words is the pipe it stands in for', 'BAILAMOS', p.title);
    ok('and the search stops being one long string',
      !p.query.includes('PAVAN'), p.query);
  }

  {
    const p = parseName('You And I.mp4');
    eq('one standalone I is a word and is left alone', 'You And I', p.title);
  }

  // ---------------------------------------------- artist first, song second

  {
    const name =
      'Guru_Randhawa__Nachle_Na_Video___DIL_JUUNGLEE___Neeti_M___Taapsee_P_Saqib_Saleem.mp4';
    const p = parseName(name);
    eq('the song is the segment with the marker on it', 'Nachle Na', p.title);
    eq('and the channel name is the artist', 'Guru Randhawa', p.artist);
    ok('the search asks for both together',
      p.queries.some((q) => q.includes('Nachle Na') && q.includes('Guru Randhawa')),
      JSON.stringify(p.queries));

    // The scoring half of the same fault, with the real runtimes from the
    // report: the album cut runs seventy-two seconds longer than the video,
    // which is ordinary and must not be allowed to decide anything.
    const azul = candidate({
      id: '1', title: 'AZUL', artist: 'Guru Randhawa, Gurjit Gill & Lavish Dhiman',
      durationMs: 149000, kind: 'musicVideo',
    });
    const right = candidate({
      id: '2', title: 'Nachle Na (From "Dil Juunglee")',
      artist: 'Guru Randhawa, Neeti Mohan & Rajat Nagpal',
      album: 'Nachle Na (From "Dil Juunglee") - Single', durationMs: 220000,
    });
    const ranked = rank([azul, right], p, 148000);
    eq('the song that matches the name beats a stranger with the right length',
      'Nachle Na (From "Dil Juunglee")', ranked[0].candidate.title);
  }

  {
    const p = parseName('Kesariya___Brahmastra___Arijit_Singh.mp4');
    eq('the song-first convention is untouched', 'Kesariya', p.title);
  }

  // ----------------------------------------- the headliner and a shared title

  {
    const name = 'The_Weeknd_ft._Dua_Lipa_-_Obsession__Official_Lyric_Video_(1080p).mp4';
    const p = parseName(name);
    eq('the name is read correctly to begin with', 'Obsession', p.title);
    eq('guests and all', 'The Weeknd ft. Dua Lipa', p.artist);
    ok('the headliner alone is one of the things asked',
      p.queries.includes('The Weeknd Obsession'), JSON.stringify(p.queries));

    const stranger = candidate({
      id: '1', title: 'Obsession', artist: 'EXO',
      album: 'OBSESSION - The 6th Album', durationMs: 203000,
    });
    const right = candidate({
      id: '2', title: 'Obsession', artist: 'The Weeknd',
      durationMs: 285000, kind: 'musicVideo',
    });
    const ranked = rank([stranger, right], p, 285000);
    eq('a stranger with the same title does not top the list',
      'The Weeknd', ranked[0].candidate.artist);
    ok('and it is beaten by a clear margin',
      ranked[0].score > ranked[ranked.length - 1].score + 0.1,
      ranked.map((r) => r.candidate.artist + ' ' + r.score.toFixed(2)).join(', '));
  }

  {
    const p = parseName('Arijit_Singh_-_Kesariya.mp4');
    eq('an artist with no guests adds no second query for itself', 1,
      p.queries.filter((q) => q.startsWith('Arijit Singh Kesariya')).length);
  }

  // ------------------------------------- a remix title is not an exact match

  {
    // From a report where the top result was "Butter (Megan Thee Stallion
    // Remix)" by BTS at 84% with the reason "title matches exactly" --
    // confident enough, by the app's own rule, to be written to the file
    // without anyone looking at it.
    const p = parseName('Megan_Thee_Stallion__Fantasy_Pool_Party_(1080p).mp4');
    ok('both halves of the name are still asked for',
      p.queries.some((q) => q.includes('Megan Thee Stallion') && q.includes('Fantasy Pool Party')),
      JSON.stringify(p.queries));

    near('an artist named inside a remix title is not a title match', 0,
      titleMatch('Megan Thee Stallion', 'Butter (Megan Thee Stallion Remix)'));

    const butter = candidate({
      title: 'Butter (Megan Thee Stallion Remix)', artist: 'BTS & Megan Thee Stallion',
      durationMs: 227000,
    });
    const top = rank([butter], p)[0];
    ok('it is no longer claimed as an exact title',
      !top.reasons.some((r) => r.includes('title')), JSON.stringify(top.reasons));
    ok('and it is no longer confident enough to apply on its own',
      !top.confident, top.score);
  }

  near('a longer name for the same song still matches exactly', 1,
    titleMatch('Nachle Na', 'Nachle Na (From "Dil Juunglee")'));
  near('a credited guest does not spoil an exact title', 1,
    titleMatch('Obsession', 'Obsession (feat. Dua Lipa)'));
  near('a filename that spells the qualifier out also matches exactly', 1,
    titleMatch('Butter Megan Thee Stallion Remix', 'Butter (Megan Thee Stallion Remix)'));
  eq('a title bracketed from its first character is not thrown away',
    '(Everything I Do) I Do It for You', headline('(Everything I Do) I Do It for You'));

  // -------------------------------------------------------------- Devanagari

  eq('devanagari is written out the way the catalogue spells it', 'kesariya',
    devanagari('केसरिया'));
  eq('and a sentence of it', 'tum hi ho',
    devanagari('तुम ही हो'));
  eq('the unpronounced final vowel is dropped', 'tum', devanagari('तुम'));
  eq('but not where it is the only vowel', 'ja', devanagari('ज'));
  eq('latin passes through untouched', 'Hello Adele', devanagari('Hello Adele'));

  for (const [a, b] of [
    ['केसरिया', 'Kesariya'],
    ['केसरिया', 'Kesariya (From "Brahmastra")'],
    ['Naatu Naatu', 'Natu Natu'],
    ['Tum Hii Ho', 'Tum Hi Ho'],
    ['तुम ही हो', 'Tum Hi Ho'],
    ['Zindagi Na Milegi', 'Jindagi Na Milegi'],
    ['Kesaria', 'Kesariya'],
    ['ब्रह्मास्त्र', 'Brahmastra'],
    ['Phir Le Aaya Dil', 'Fir Le Aya Dil'],
  ]) {
    near("'" + a + "' and '" + b + "' are the same song", 1, tokenOverlap(a, b));
  }

  for (const [a, b] of [['Kesariya', 'Malhari'], ['Tum Hi Ho', 'Channa Mereya'], ['Hello', 'Goodbye']]) {
    near("'" + a + "' and '" + b + "' are not", 0, tokenOverlap(a, b));
  }

  ok('short words are not merged by the fuzzy comparison', !sameWord('tera', 'mera'));
  ok('nor three-letter ones', !sameWord('din', 'bin'));
  ok('but a long one gets its edit of slack', sameWord('kesariya', 'kesariy'));

  {
    const p = parseName(
      'अरिजीत सिंह - ' +
      'केसरिया.mp4');
    ok('a devanagari filename is searched for in latin',
      p.queries.some((q) => q.includes('kesariya')), JSON.stringify(p.queries));
    eq('and is recognised as hindi', 'hi', p.language);
  }

  near('accents do not block a match', 1, tokenOverlap('Beyoncé', 'Beyonce'));
  near('nor apostrophes', 1, tokenOverlap("Don't Stop", 'Dont Stop'));

  // --------------------------------------------------------- what kind it is

  {
    const m = classify('The.Family.Man.S02E04.1080p.WEB-DL.x264.mp4');
    eq('an SxxExx name is an episode', KINDS.TV_EPISODE, m.kind);
    eq('with the show in front of it', 'The Family Man', m.name);
    eq('the season', 2, m.season);
    eq('and the episode', 4, m.episode);
  }

  {
    const m = classify('Some Show 1x02 Pilot.mkv');
    eq('the 1x02 form works too', KINDS.TV_EPISODE, m.kind);
    eq('its season', 1, m.season);
    eq('its episode', 2, m.episode);
  }

  {
    const m = classify('Show Name Season 3 Episode 11.mp4');
    eq('spelled out seasons work', KINDS.TV_EPISODE, m.kind);
    eq('their season', 3, m.season);
    eq('their episode', 11, m.episode);
  }

  {
    const m = classify('The Family Man 2000 1080p BluRay x264 AAC.mp4');
    eq('a release name with a year is a movie', KINDS.MOVIE, m.kind);
    eq('and its title stops at the year', 'The Family Man', m.name);
    eq('the year is kept', '2000', m.year);
  }

  {
    const m = classify('Adele - Hello 2015.mp4');
    eq('a song with a year in it is not mistaken for a movie', KINDS.MUSIC_VIDEO, m.kind);
  }

  {
    const m = classify('Adele - Hello (Official Music Video).mp4');
    eq('an ordinary music video stays a music video', KINDS.MUSIC_VIDEO, m.kind);
  }

  // ------------------------------------------------------- episode ranking

  {
    // An episode three times the length of what came back is not that
    // episode, whatever it is called. This one matched an aftershow at 95%.
    const real = candidate({ id: '1', title: 'The House That Dragons Built', durationMs: 4200000 });
    const aftershow = candidate({ id: '2', title: 'The House That Dragons Built', durationMs: 1320000 });
    const ranked = rankEpisodes([aftershow, real], 4200000);
    eq('length decides between an episode and its aftershow', '1', ranked[0].candidate.id);
    ok('and the aftershow is not confident', !ranked[ranked.length - 1].confident,
      ranked[ranked.length - 1].score);
  }

  {
    const ranked = rankEpisodes([candidate({ id: '1', title: 'Whatever' })], null);
    ok('an unknown length is not evidence against', ranked[0].reasons.includes('length not known'));
    ok('and the number alone still stands', ranked[0].score >= 0.9, ranked[0].score);
  }

  // ------------------------------------------------------------- extensions

  eq('an extension is split off', 'Adele - Hello', stripExtension('Adele - Hello.mp4'));
  eq('and handed back when asked', 'mp4', extensionOf('Adele - Hello.mp4'));
  eq('a name that is not an extension keeps it', 'Vol.2', stripExtension('Vol.2'));

  // ------------------------------------------------- the threshold itself

  ok('the confidence threshold is the same as the app owns', CONFIDENT === 0.8, CONFIDENT);
  ok('folding is case-insensitive', fold('KESARIYA') === fold('kesariya'));

  console.log('\n' + pass + ' passed, ' + fail + ' failed');
  process.exit(fail ? 1 : 0);
}

main();
