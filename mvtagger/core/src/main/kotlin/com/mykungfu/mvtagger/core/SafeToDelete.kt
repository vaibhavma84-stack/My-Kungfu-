package com.mykungfu.mvtagger.core

/**
 * Whether the copy just written is good enough to delete the original.
 *
 * This is the one decision in the app that cannot be taken back. Everything
 * else it does is a new file, a tag that can be rewritten, a name that can be
 * changed; a deleted video is gone, and if the copy that replaced it was
 * half-written then so is the recording.
 *
 * It used to live inside the file-writing code, tangled up with opening
 * documents and querying providers, which is exactly the shape of thing this
 * sandbox cannot run and so nothing tested it. The reads stay where they have
 * to be -- only Android can ask a provider how big a file is -- and the
 * judgement moves here, where it is a function of five known values and every
 * one of its refusals has a test.
 *
 * The rule throughout is that silence is not consent. An unknown is never read
 * as permission: a size that could not be queried, a title that could not be
 * read back, a check that threw rather than answering, all mean no. A stray
 * copy left on disk is a nuisance a person can delete in a second; the other
 * mistake they cannot undo at all.
 */
object SafeToDelete {

    /**
     * What was learned about the copy after writing it.
     *
     * Nulls are honest here: they mean nobody could find out, which is a
     * refusal rather than a pass.
     */
    class Written(
        /** The copy's size in bytes, or null when the provider would not say. */
        val size: Long?,
        /** The original's size, or null when that could not be read either. */
        val originalSize: Long?,
        /** Whether tags were written into the copy, as opposed to beside it. */
        val embedded: Boolean,
        /** The title that was asked for, if any was. */
        val wantedTitle: String?,
        /**
         * The title read back out of the copy, or null if it could not be read.
         *
         * Reading it back is not about the title. It is about whether the box
         * tree is still walkable, which is the thing a bad write breaks, and a
         * title is simply the cheapest proof that it is.
         */
        val titleReadBack: String?,
        /** False when the copy landed on the original, which is not a copy. */
        val isDifferentFile: Boolean = true,
    )

    /** Below this fraction of the original, a copy is a failed copy. */
    private const val LEAST_PLAUSIBLE_FRACTION = 2

    /**
     * Yes, or the reason why not, which the app shows the person.
     *
     * A refusal is always explained. "The original was KEPT" with nothing after
     * it invites somebody to switch the check off.
     */
    class Verdict(val ok: Boolean, val why: String? = null)

    private val YES = Verdict(true)

    fun check(written: Written): Verdict {
        if (!written.isDifferentFile) {
            return Verdict(false, "the copy is the original, so there is nothing to delete")
        }

        val size = written.size
            ?: return Verdict(false, "the size of the new file could not be read")
        if (size <= 0) return Verdict(false, "the new file is empty")

        val original = written.originalSize
        if (original != null && original > 0 && size < original / LEAST_PLAUSIBLE_FRACTION) {
            // Repackaging legitimately loses subtitle and attachment tracks, so
            // some shrinkage is expected; half the size is not.
            return Verdict(
                false,
                "the new file is " + size + " bytes against the original's " + original +
                        ", which is too small to be a complete copy",
            )
        }

        // Tags written beside the file cannot be read back out of it, and their
        // absence proves nothing about the video, which was copied rather than
        // rewritten.
        if (!written.embedded) return YES

        val wanted = written.wantedTitle?.trim()
        if (wanted.isNullOrEmpty()) {
            // Nothing was asked for, so nothing can be checked -- but the file
            // was still rewritten, and that is the case the read-back exists
            // for. Only a file that can be opened at all passes.
            return if (written.titleReadBack != null) YES
            else Verdict(false, "the new file could not be read back after writing")
        }

        val back = written.titleReadBack?.trim()
        return when {
            back == null ->
                Verdict(false, "the new file could not be read back after writing")
            back != wanted ->
                Verdict(
                    false,
                    "the new file reads back as \"" + back + "\" rather than \"" + wanted + "\"",
                )
            else -> YES
        }
    }
}
