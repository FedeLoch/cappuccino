package art.cappuccino.paint

private const val SKETCH_COVERAGE = 0.25f
private const val BLOCK_IN_COVERAGE = 0.7f
private const val CLEARER_VALUES = 0.1f
private const val MORE_FINISHED = 0.2f
private const val MUTE_TOPIC_AFTER = 3

enum class ArtStage { SKETCH, BLOCK_IN, FINISHING }

/** What the companion remembers about an artwork from an earlier sitting. */
data class ArtSnapshot(val time: Long, val contrast: Float, val coverage: Float, val colored: Float)

fun artStage(coverage: Float): ArtStage = when {
    coverage < SKETCH_COVERAGE -> ArtStage.SKETCH
    coverage < BLOCK_IN_COVERAGE -> ArtStage.BLOCK_IN
    else -> ArtStage.FINISHING
}

/** Puts first what matters now: shapes while sketching, values while blocking in, color at the end. */
fun orderForStage(tips: List<Tip>, stage: ArtStage): List<Tip> {
    val order = when (stage) {
        ArtStage.SKETCH -> listOf("Next step", "Silhouette", "Composition", "Values", "Light", "Palette")
        ArtStage.BLOCK_IN -> listOf("Next step", "Values", "Composition", "Light", "Silhouette", "Palette")
        ArtStage.FINISHING -> listOf("Palette", "Light", "Values", "Next step", "Composition", "Silhouette")
    }
    return tips.sortedBy { tip -> order.indexOf(tip.topic).let { if (it < 0) order.size else it } }
}

fun progressNote(before: ArtSnapshot, now: ArtSnapshot): String? = when {
    now.contrast - before.contrast >= CLEARER_VALUES ->
        tr("Your values are much clearer than last time. That's real progress.")
    now.coverage - before.coverage >= MORE_FINISHED ->
        tr("This has come a long way since last time. The big shapes are in.")
    now.colored - before.colored >= MORE_FINISHED ->
        tr("Color is arriving since last time, and it's starting to feel alive.")
    else -> null
}

/** A note you turned away stays away, and a topic you keep turning away goes quiet. */
fun withoutMuted(tips: List<Tip>, mutedNotes: Set<String>, mutedTopics: Map<String, Int>) =
    tips.filter { it.text !in mutedNotes && (mutedTopics[it.topic] ?: 0) < MUTE_TOPIC_AFTER }

/** A small image reads best with a wide range of values. Returns the index of the clearest one. */
fun clearestAtASmallSize(reports: List<AnalysisReport>): Int =
    reports.indices.maxByOrNull { reports[it].contrast } ?: -1
