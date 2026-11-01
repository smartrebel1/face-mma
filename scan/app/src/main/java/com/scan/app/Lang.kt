package com.scan.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** App language. Arabic is the default, English is optional (Settings). */
enum class Lang { AR, EN }

val LocalLang = staticCompositionLocalOf { Lang.AR }

/** Pick the string for the current UI language. */
@Composable
fun t(ar: String, en: String): String = if (LocalLang.current == Lang.AR) ar else en

fun tOf(lang: Lang, ar: String, en: String): String = if (lang == Lang.AR) ar else en
