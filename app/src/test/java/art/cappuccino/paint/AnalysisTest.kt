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
}
