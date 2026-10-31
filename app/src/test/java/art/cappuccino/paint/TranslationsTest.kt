package art.cappuccino.paint

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TranslationsTest {
    @After
    fun backToEnglish() {
        I18n.language = Language.ENGLISH
    }

    @Test
    fun deviceLanguageIsFollowedAndSavedChoiceWins() {
        I18n.choose(null, "es")
        assertEquals(Language.SPANISH, I18n.language)
        I18n.choose("en", "es")
        assertEquals(Language.ENGLISH, I18n.language)
        I18n.choose(null, "fr")
        assertEquals(Language.ENGLISH, I18n.language)
    }

    @Test
    fun valuesFillNumberedMarks() {
        I18n.language = Language.SPANISH
        assertEquals("Capa 3", tr("Layer %1", 3))
    }

    @Test
    fun artistNamesStayTheSame() {
        I18n.language = Language.SPANISH
        assertEquals("Mi dragón", tr("Mi dragón"))
    }

    @Test
    fun everyTextInTheSourceHasSpanish() {
        val call = Regex("""\btr\(\s*"((?:[^"\\]|\\.)*)"""")
        val missing = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> call.findAll(file.readText()).map { it.groupValues[1] } }
            .filter { it !in SPANISH }
            .toSet()
        assertTrue("Missing Spanish: $missing", missing.isEmpty())
    }

    @Test
    fun catalogNamesHaveSpanish() {
        val names = BrushKind.entries.map { it.label } + BLEND_MODES +
            Light.entries.map { it.label } + CompanionLook.entries.map { it.label } +
            GUIDES.filter { it != "None" } + CANVAS_PRESETS.map { it.label } + LIQUIFY_TOOLS + FILTERS.map { it.label } +
            FILTERS.flatMap { kind -> kind.sliders.map { it.label } } + STARTER_PALETTES_NAMES + HARMONY_NAMES +
            listOf("Palette", "Values", "Composition", "Silhouette", "Mood", "Light", "Perspective", "Next step")
        val missing = names.filter { it !in SPANISH }
        assertTrue("Missing Spanish: $missing", missing.isEmpty())
    }
}
