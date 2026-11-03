package art.cappuccino.paint

private const val CLEARER_SINCE_START = 0.08f
private const val MORE_COLOR_SINCE_START = 0.15f

/** A short exercise the companion suggests before you start, aimed at what your notes keep asking for. */
data class Warmup(val topic: String, val title: String, val steps: String)

internal val WARMUPS = listOf(
    Warmup("Values", "Three values", "Paint a small scene with only black, mid gray, and white. Squint, and keep the big shapes readable."),
    Warmup("Values", "Light to dark", "Paint five blocks from lightest to darkest, then a sphere using only those five."),
    Warmup("Silhouette", "Silhouettes only", "Fill five characters or objects in solid black. Each one should read without any detail."),
    Warmup("Silhouette", "Shape hunt", "Draw ten small blobs, then turn three of them into something you recognize."),
    Warmup("Composition", "Tiny thumbnails", "Draw six little boxes and try a different composition in each, with three values at most."),
    Warmup("Composition", "One focal point", "Sketch a scene where everything leads to one spot. Put your strongest contrast there."),
    Warmup("Palette", "Three colors", "Pick one main hue and two quiet ones, then paint a small study using only those."),
    Warmup("Palette", "Warm and cool", "Paint the same object twice, once with warm light and cool shadows, then the opposite."),
    Warmup("Light", "One lamp", "Draw a cube, a sphere, and a cylinder lit from the same side. Watch where the shadows fall."),
    Warmup("Light", "Rim light", "Paint a simple head or object in shadow, then add a thin light along one edge."),
)

/** What the companion wants you to practice next, chosen from the topic your notes raised most recently. */
fun warmupFor(topic: String?, day: Int): Warmup {
    val matching = WARMUPS.filter { it.topic == topic }.ifEmpty { WARMUPS }
    return matching[Math.floorMod(day, matching.size)]
}

/** Topics a warm-up can help with, in the order a note's topic is checked. */
val WARMUP_TOPICS = WARMUPS.map { it.topic }.distinct()

/** A short look back when you export a finished piece, comparing it with where it started. */
fun finishNote(first: ArtSnapshot, now: ArtSnapshot): String = when {
    now.contrast - first.contrast >= CLEARER_SINCE_START ->
        tr("Finished! Your values are much clearer than when you started this one.")
    now.colored - first.colored >= MORE_COLOR_SINCE_START ->
        tr("Finished! This began almost without color, and now it really glows.")
    now.coverage > first.coverage ->
        tr("Finished! You took this from a few marks to a whole piece.")
    else -> tr("Finished! Nice work. I'll remember this one.")
}
