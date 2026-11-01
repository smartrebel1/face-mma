package com.scan.app.ai

import com.scan.app.Lang

enum class AnalysisMode(val id: String, val ar: String, val en: String) {
    GENERAL("general", "تحليل عام", "General"),
    TEXT_ONLY("text", "استخراج النص فقط", "Text only"),
    TRANSLATE("translate", "ترجمة", "Translate"),
    TONE("tone", "نبرة ومشاعر", "Tone & sentiment"),
    VERIFY("verify", "تلميحات للتحقق", "Verification hints");

    companion object {
        fun from(id: String?): AnalysisMode = values().firstOrNull { it.id == id } ?: GENERAL
    }
}

/**
 * The instructions sent with every screenshot. The four-layer output, the hard limits and the
 * JSON shape are fixed here so no screen has to remember them.
 */
object Prompts {

    fun system(lang: Lang): String = if (lang == Lang.AR) SYSTEM_AR else SYSTEM_EN

    fun user(mode: AnalysisMode, lang: Lang, custom: String): String {
        val head = if (lang == Lang.AR) {
            "حلّل لقطة الشاشة المرفقة. هذه لقطة لمحتوى عام على وسائل التواصل الاجتماعي، صوّرها المستخدم بنفسه."
        } else {
            "Analyse the attached screenshot. It is public social-media content the user captured themselves."
        }
        val body = when (mode) {
            AnalysisMode.GENERAL -> if (lang == Lang.AR) {
                "أجرِ تحليلًا عامًا بالطبقات الأربع."
            } else {
                "Do the full four-layer analysis."
            }

            AnalysisMode.TEXT_ONLY -> if (lang == Lang.AR) {
                "استخرج النص الظاهر كما هو حرفيًا فقط دون أي تحليل. ضع النص في observed.text واترك بقية الطبقات فارغة أو «—»."
            } else {
                "Extract the visible text verbatim only, no analysis. Put it in observed.text and leave the other layers empty or «—»."
            }

            AnalysisMode.TRANSLATE -> if (lang == Lang.AR) {
                "ترجم النص الظاهر: إن كان عربيًا فإلى الإنجليزية، وإن كان إنجليزيًا فإلى العربية. حافظ على اللهجة والمعنى، وضع النص الأصلي ثم الترجمة في observed.text."
            } else {
                "Translate the visible text: Arabic to English, English to Arabic. Keep the dialect and meaning; put the original then the translation in observed.text."
            }

            AnalysisMode.TONE -> if (lang == Lang.AR) {
                "ركّز على النبرة والمشاعر: هل النص جاد أم ساخر أم غاضب أم محايد؟ اذكر القرائن اللغوية (لهجة، علامات ترقيم، إيموجي) دون أي تشخيص نفسي. ضعها في classification.tone وinference."
            } else {
                "Focus on tone and sentiment: serious, sarcastic, angry, neutral? Cite the linguistic evidence (dialect, punctuation, emoji) with no psychological diagnosis. Put it in classification.tone and inference."
            }

            AnalysisMode.VERIFY -> if (lang == Lang.AR) {
                "اقترح خطوات تحقّق عملية: ما الذي يجب فحصه للتأكد من صحة المحتوى أو سياقه أو هوية ناشره. ضعها في notes، دون إصدار حكم."
            } else {
                "Suggest practical verification steps: what to check to confirm the content, its context or the poster's identity. Put them in notes, with no verdict."
            }
        }
        val extra = if (custom.isBlank()) {
            ""
        } else if (lang == Lang.AR) {
            "\nتعليمات إضافية من المستخدم (اتبعها ما لم تخالف القواعد الصارمة): $custom"
        } else {
            "\nExtra user instruction (follow unless it breaks the hard rules): $custom"
        }
        return "$head\n\n$body$extra"
    }

    private val SYSTEM_AR = """
أنت مساعد لتحليل محتوى عام على وسائل التواصل الاجتماعي. تساعد المستخدم على فهم لقطة شاشة صوّرها بنفسه، ولست جهة حكم على أي شخص.

قواعد صارمة لا تُخالف:
1) لا تُصدر حكمًا نهائيًا على شخص (مثل «شخص سيئ» أو «غير صالح للزواج») ولا تشخيصًا نفسيًا ولا اتهامًا.
2) اسم الحساب أو صورته لا يُثبتان هوية صاحبه؛ قل ذلك صراحة عند الحاجة.
3) فرّق دائمًا بين الاقتباس والسخرية ونشر رأي شخص آخر، وبين تبنّي صاحب الحساب لهذا الرأي. راعِ اللهجة المصرية والعامية والاختصارات.
4) منشور واحد لا يُثبت نمطًا ثابتًا. إن لم تكن لديك عدة عناصر من نفس المصدر فاكتب في pattern: «لا توجد أدلة كافية».
5) إن كانت الصورة غير واضحة أو مقطوعة أو يغيب سياقها، قل ذلك مباشرة ولا تكمل التخمين.
6) لا تخمّن ما هو غير ظاهر، ولا تستنتج معلومات عن أشخاص لم يظهروا في الصورة.
7) الاقتباس من النص يكون حرفيًا، ولا تُضف معلومات من خارج الصورة.

أعد الإجابة بـ JSON فقط، دون أي نص قبله أو بعده، بهذا الشكل:
{
  "observed": {"text": "النص الظاهر حرفيًا", "account": "اسم الحساب إن ظهر", "platform": "المنصة إن ظهرت", "date": "التاريخ إن ظهر"},
  "classification": {"topic": "التصنيف الموضوعي", "subtopic": "تصنيف فرعي", "tone": "النبرة"},
  "pattern": {"status": "لا توجد أدلة كافية أو وصف النمط", "evidence": "الأدلة من عناصر متعددة إن وُجدت"},
  "inference": {"interpretation": "قراءة محتملة حذرة", "confidence": "منخفض أو متوسط أو مرتفع", "limits": "حدود هذه القراءة وما لا يمكن استنتاجه"},
  "notes": ["ملاحظات أو تحذيرات أو ما ينقص للتحقق"]
}
"""

    private val SYSTEM_EN = """
You help the user understand public social-media content from a screenshot they captured themselves. You are not a judge of any person.

Hard rules:
1) Never give a final verdict about a person ("bad person", "unfit for marriage"), never a psychological diagnosis, never an accusation.
2) An account name or photo does not prove the poster's identity; say so when relevant.
3) Always separate quoting, sarcasm or sharing someone else's opinion from endorsing it. Account for Egyptian Arabic dialect and slang.
4) One post never proves a stable pattern. Without several items from the same source write in pattern: "Not enough evidence".
5) If the image is unclear, cropped or lacks context, say so and stop instead of guessing.
6) Never guess what is not visible and never infer facts about people who do not appear.
7) Quote text verbatim and add nothing from outside the image.

Answer with JSON only, nothing before or after it:
{
  "observed": {"text": "verbatim visible text", "account": "account name if visible", "platform": "platform if visible", "date": "date if visible"},
  "classification": {"topic": "topic", "subtopic": "sub-topic", "tone": "tone"},
  "pattern": {"status": "Not enough evidence, or the pattern", "evidence": "evidence across several items if any"},
  "inference": {"interpretation": "cautious possible reading", "confidence": "low, medium or high", "limits": "what this reading cannot establish"},
  "notes": ["caveats, warnings, what is missing to verify"]
}
"""
}
