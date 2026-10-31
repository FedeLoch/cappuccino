package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SIZE = 100
private const val WHITE = 0xFFFFFFFF.toInt()

class AnalysisTest {
    private fun image(paint: (Int, Int) -> Int?) = IntArray(SIZE * SIZE) {
        paint(it % SIZE, it / SIZE) ?: WHITE
    }

    private fun tip(report: AnalysisReport, topic: String) =
        report.tips.first { it.topic == topic }.text

    @Test
    fun lightGuideFollowsWhereLighterMarksGather() {
        val pixels = image { x, _ ->
            when {
                x < 30 -> 0xFFC8C8C8.toInt()
                x > 70 -> 0xFF202020.toInt()
                else -> null
            }
        }
        assertTrue(tip(analyzeArtwork(pixels, SIZE, SIZE, 180), "Light").contains("follow"))
        assertTrue(tip(analyzeArtwork(pixels, SIZE, SIZE, 0), "Light").contains("away"))
    }

    @Test
    fun lightSketchGetsANextStep() {
        val report = analyzeArtwork(image { x, _ -> if (x < 8) 0xFF303030.toInt() else null }, SIZE, SIZE)
        assertTrue(tip(report, "Next step").contains("early sketch"))
    }

    @Test
    fun everyTipExplainsWhy() {
        val report = analyzeArtwork(image { x, _ -> if (x < 30) 0xFFB03030.toInt() else null }, SIZE, SIZE, 0)
        assertTrue(report.tips.all { it.why.isNotBlank() })
        assertTrue(tip(report, "Palette").isNotBlank())
        assertTrue(report.tips.first { it.topic == "Palette" }.why.contains("red"))
    }

    @Test
    fun coloredArtGetsAnOppositeColorIdeaAndTextureIdeas() {
        val report = analyzeArtwork(image { x, _ -> if (x < 40) 0xFFB03030.toInt() else null }, SIZE, SIZE)
        val accent = report.ideas.first { it.accentHue != null }.accentHue!!
        assertTrue(accent in 150f..220f)
        assertTrue(report.ideas.any { it.brushId == "sponge" })
    }

    @Test
    fun blankPaperGivesNoTips() {
        assertTrue(analyzeArtwork(image { _, _ -> null }, SIZE, SIZE).tips.isEmpty())
    }

    @Test
    fun grayOnlyArtworkIsCalledNeutral() {
        val report = analyzeArtwork(image { x, _ -> if (x < 30) 0xFF404040.toInt() else null }, SIZE, SIZE)
        assertTrue(tip(report, "Palette").contains("neutral"))
    }

    @Test
    fun equalOppositeHuesAreFlagged() {
        val report = analyzeArtwork(
            image { x, y ->
                when {
                    y !in 20..80 -> null
                    x in 20..49 -> 0xFFB03030.toInt()
                    x in 50..79 -> 0xFF30A0A0.toInt()
                    else -> null
                }
            },
            SIZE, SIZE,
        )
        assertTrue(tip(report, "Palette").contains("opposite"))
    }

    @Test
    fun weightOnTheLeftIsReported() {
        val report = analyzeArtwork(image { x, _ -> if (x < 20) 0xFF202020.toInt() else null }, SIZE, SIZE)
        assertTrue(tip(report, "Composition").contains("left"))
    }

    @Test
    fun midtonesAreReported() {
        val report = analyzeArtwork(
            image { x, y ->
                if (x in 20..80 && y in 20..80) (if (x % 2 == 0) 0xFF707070 else 0xFF8C8C8C).toInt() else null
            },
            SIZE, SIZE,
        )
        assertTrue(tip(report, "Values").contains("midtones") || tip(report, "Values").contains("close"))
    }

    @Test
    fun silhouetteMarksOnlyThePaintedArea() {
        val report = analyzeArtwork(image { x, _ -> if (x < 30) 0xFF202020.toInt() else null }, SIZE, SIZE)
        assertEquals(0xFF2C2C2A.toInt(), report.silhouette[0])
        assertEquals(WHITE, report.silhouette[99])
    }

    @Test
    fun valuesNotePointsToTheStrongestContrast() {
        val pixels = image { x, y ->
            when {
                x in 60..80 && y in 20..40 && (x + y) % 8 < 4 -> 0xFF000000.toInt()
                x in 60..80 && y in 20..40 -> WHITE
                else -> 0xFF808080.toInt()
            }
        }
        val mark = analyzeArtwork(pixels, SIZE, SIZE).tips.first { it.topic == "Values" }.marks.single()
        assertTrue(mark.x1 in 0.55f..0.85f)
        assertTrue(mark.y1 in 0.15f..0.45f)
    }

    @Test
    fun aLopsidedImageGetsASpotToBalanceIt() {
        val pixels = image { x, _ -> if (x < 15) 0xFF202020.toInt() else null }
        val marks = analyzeArtwork(pixels, SIZE, SIZE).tips.first { it.topic == "Composition" }.marks
        assertEquals(2, marks.size)
        assertTrue(marks[0].x1 < 0.2f)
        assertTrue(marks[1].x1 > 0.6f)
    }

    @Test
    fun theSilhouetteBoxFollowsTheMarks() {
        val pixels = image { x, y -> if (x in 20..39 && y in 30..59) 0xFF202020.toInt() else null }
        val mark = analyzeArtwork(pixels, SIZE, SIZE).tips.first { it.topic == "Silhouette" }.marks.single()
        assertEquals(0.2f, mark.x1, 0.01f)
        assertEquals(0.6f, mark.y2, 0.01f)
    }

    @Test
    fun everyMeasuredNoteOffersAnAction() {
        val pixels = image { x, y -> if (x in 20..80 && y in 20..80) 0xFF2060C0.toInt() else null }
        val tips = analyzeArtwork(pixels, SIZE, SIZE, lightDegrees = 0).tips
        assertTrue(tips.all { it.action != null })
    }
}
