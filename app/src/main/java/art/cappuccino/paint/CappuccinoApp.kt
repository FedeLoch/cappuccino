package art.cappuccino.paint

import android.app.Application
import java.util.Locale

private const val LANGUAGE_KEY = "language"

class CappuccinoApp : Application() {
    private val preferences by lazy { getSharedPreferences("studio", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        I18n.choose(preferences.getString(LANGUAGE_KEY, null), Locale.getDefault().language)
    }

    fun saveLanguage(language: Language) {
        preferences.edit().putString(LANGUAGE_KEY, language.code).apply()
        I18n.language = language
    }
}
