package com.trollman.linernotes.data

import androidx.compose.runtime.Immutable

/**
 * Sending a set (SPEC.md, "Delivery and the recipient's side"). The backend keeps one send per set
 * in sends/{uid_setId}; these are the app's views of it.
 */

/** The sender's view of a set they've scheduled or delivered. */
@Immutable
data class SendInfo(
    val id: String,
    val setId: String,
    /** "scheduled" or "delivered". */
    val status: String,
    val deliverAt: Long,
    val deliveredAt: Long?,
    /** False until the recipient accepts the invite the set was scheduled against. */
    val recipientJoined: Boolean,
    val test: Boolean,
    val pairId: String? = null,
    /** "group" or "monthly". */
    val kind: String = "group",
    val month: String? = null,
    val fromName: String = "",
    /** The copy the recipient sees. */
    val set: GroupSet? = null,
    /** The recipient's reactions, by album position. */
    val reactions: Map<Int, Reaction> = emptyMap(),
    /** What it did to the recipient's listening, when both turned sharing on. */
    val influence: Influence = Influence(),
) {
    val delivered: Boolean get() = status == "delivered"

    /** This send as the recipient sees it, for the sender to look back at. */
    fun asReceived(): Received? = set?.let {
        Received(id, fromName, deliveredAt ?: deliverAt, it, test, pairId, kind, reactions, mine = true, influence = influence)
    }
}

/** The recipient's reaction to one album: "loved", "liked" or "notForMe", and a comment. */
@Immutable
data class Reaction(val reaction: String?, val comment: String) {
    val label: String get() = reactionLabel(reaction)
}

fun reactionLabel(reaction: String?): String = when (reaction) {
    "loved" -> "Loved"
    "liked" -> "Liked"
    "notForMe" -> "Not for me"
    else -> ""
}

/** A set that has arrived in the signed-in person's inbox. */
@Immutable
data class Received(
    val id: String,
    val fromName: String,
    val deliveredAt: Long,
    /** The booklet: the sender's set, cut down to what the recipient sees. */
    val set: GroupSet,
    /** A copy the sender sent themselves to check it. */
    val test: Boolean,
    val pairId: String? = null,
    /** "group" or "monthly". */
    val kind: String = "group",
    val reactions: Map<Int, Reaction> = emptyMap(),
    /** True when it's one of the signed-in person's own sends, seen as the recipient sees it. */
    val mine: Boolean = false,
    val influence: Influence = Influence(),
) {
    val isMonthly: Boolean get() = kind == "monthly"
}

/** An invite link the sender has made for a set. */
data class Invite(val token: String, val url: String)

/** Who an invite is from, before the person joins. [problem] says why it can't be used. */
@Immutable
data class InviteInfo(
    val inviterName: String,
    val forName: String,
    val problem: String?,
    val alreadyJoined: Boolean,
)

/** What scheduleSend answered. */
data class Scheduled(val status: String, val deliverAt: Long, val waitingForJoin: Boolean)

object InviteLinks {
    private val inPath = Regex("""invite/([A-Za-z0-9_-]{16,64})""")
    private val bare = Regex("""^[A-Za-z0-9_-]{16,64}$""")

    /** The token in a pasted invite link (web or app form), or a bare token; null otherwise. */
    fun token(text: String): String? {
        val t = text.trim()
        inPath.find(t)?.let { return it.groupValues[1] }
        return t.takeIf { bare.matches(it) }
    }
}

/**
 * The copy of a set that goes to the recipient: everything private to the sender left behind
 * (their description of the recipient's taste, Claude's drafts, fact-check flags and sources, the
 * shortlist and fit scores, and their play counts). The backend cuts it down again on arrival.
 */
fun GroupSet.forRecipient(): GroupSet = copy(
    taste = "",
    shortlist = emptyList(),
    fit = emptyMap(),
    inviteToken = null,
    inviteUrl = null,
    scheduledVersion = null,
    picks = emptyList(),
    stretch = false,
    parked = emptyList(),
    entries = entries.map {
        it.copy(aiDraft = "", flags = emptyList(), sources = emptyList(), connection = "", transcripts = emptyList(), fixes = emptyList(), album = it.album.copy(plays = 0, weeks = 0))
    },
)

/** Whether the set has changed since the version that was scheduled. */
val GroupSet.changedSinceScheduled: Boolean
    get() = scheduledVersion != null && updatedAt > scheduledVersion
