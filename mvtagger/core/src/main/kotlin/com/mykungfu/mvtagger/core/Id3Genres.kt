package com.mykungfu.mvtagger.core

/**
 * The numeric ID3v1 genre list.
 *
 * Only needed for reading: older taggers wrote a `gnre` atom holding an index
 * into this table instead of the free text `©gen`. Anything this app writes
 * uses the text atom, so the number never has to be looked up in reverse.
 */
object Id3Genres {

    private val NAMES = arrayOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge",
        "Hip-Hop", "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop",
        "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial", "Alternative",
        "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient",
        "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical",
        "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel",
        "Noise", "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative",
        "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance",
        "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40",
        "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret",
        "New Wave", "Psychadelic", "Rave", "Showtunes", "Trailer", "Lo-Fi",
        "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical",
        "Rock & Roll", "Hard Rock", "Folk", "Folk-Rock", "National Folk",
        "Swing", "Fast Fusion", "Bebob", "Latin", "Revival", "Celtic",
        "Bluegrass", "Avantgarde", "Gothic Rock", "Progressive Rock",
        "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band",
        "Chorus", "Easy Listening", "Acoustic", "Humour", "Speech", "Chanson",
        "Opera", "Chamber Music", "Sonata", "Symphony", "Booty Bass", "Primus",
        "Porn Groove", "Satire", "Slow Jam", "Club", "Tango", "Samba",
        "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle",
        "Duet", "Punk Rock", "Drum Solo", "A capella", "Euro-House",
        "Dance Hall", "Goa", "Drum & Bass", "Club-House", "Hardcore", "Terror",
        "Indie", "BritPop", "Negerpunk", "Polsk Punk", "Beat",
        "Christian Gangsta Rap", "Heavy Metal", "Black Metal", "Crossover",
        "Contemporary Christian", "Christian Rock", "Merengue", "Salsa",
        "Thrash Metal", "Anime", "Jpop", "Synthpop",
    )

    /** The genre for a zero-based index, or null if it is out of range. */
    fun name(index: Int): String? = NAMES.getOrNull(index)
}
