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

    fun user(
        mode: AnalysisMode,
        lang: Lang,
        custom: String,
        historyContext: String = ""
    ): String {
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
        val history = if (historyContext.isBlank()) {
            ""
        } else if (lang == Lang.AR) {
            "\n\nسياق من سجل المستخدم — لقطات سابقة لنفس المصدر (استخدمه في طبقة pattern فقط ولا تنسبه للحالية):\n$historyContext"
        } else {
            "\n\nUser history context — earlier captures of the same source (use it for the pattern layer only, never for this image):\n$historyContext"
        }
        return "$head\n\n$body$extra$history"
    }

    /**
     * Pass 1 of a series: the model looks at one batch of captures and writes a compact, plain-text
     * digest per screenshot so pass 2 can reason over 30 captures without 30 images in one call.
     */
    /**
     * Used right after a capture: only read the account shown in the header band of the shot, so
     * the picture can be filed in the right account folder without the user typing anything.
     */
    fun accountDetect(lang: Lang): String = if (lang == Lang.AR) {
        "هذه شريط علوي من لقطة شاشة لتطبيق تواصل اجتماعي.\n" +
            "مهمتك الوحيدة: قراءة اسم الحساب الظاهر (اسم العرض أو المعرّف @handle) واسم المنصة.\n" +
            "قواعد: لا تخمّن. إن لم يكن الاسم واضحًا ومقروءًا في الصورة اترك الحقل فارغًا. " +
            "لا تعليق ولا تحليل ولا حكم على الشخص.\n" +
            "أعد JSON فقط بالشكل: {\"platform\": \"\", \"account\": \"\", \"confidence\": \"high|medium|low\"}"
    } else {
        "This is the top band of a social app screenshot.\n" +
            "Your only job: read the visible account name (display name or @handle) and the platform.\n" +
            "Rules: never guess. If the name is not clearly readable leave the field empty. " +
            "No commentary, no analysis, no judgement about the person.\n" +
            "Return JSON only: {\"platform\": \"\", \"account\": \"\", \"confidence\": \"high|medium|low\"}"
    }

    fun batch(
        batchIndex: Int,
        batchCount: Int,
        imagesInBatch: Int,
        lang: Lang,
        label: String,
        totalImages: Int
    ): String {
        val shown = label.ifBlank { if (lang == Lang.AR) "غير محدد" else "unknown" }
        return if (lang == Lang.AR) {
            "أنت تمرّر لقطات شاشة لحساب واحد اسمه «" + shown + "».\n" +
                "هذه الدفعة " + (batchIndex + 1) + " من " + batchCount +
                " وتحتوي " + imagesInBatch + " لقطة من إجمالي " + totalImages + " لقطة.\n\n" +
                "لكل لقطة بالترتيب، اكتب ٤ إلى ٦ أسطر فقط بهذا الشكل:\n" +
                "[لقطة " + (batchIndex + 1) + ".١] الحساب الظاهر / المنصة / التاريخ إن وُجد | موضوع المنشور | " +
                "النص المهم حرفيًا (مقتبصًا وقصيرًا) | النبرة | هل الكلام لصاحب الحساب أم اقتباس/سخرية؟ | أي إشارة تستحق الملاحظة.\n\n" +
                "قواعد: لا حكم نهائي على الشخص، لا تشخيص نفسي، لا تخمين لما لا يظهر. " +
                "إن كانت لقطة غير واضحة اكتب «غير واضحة» واذكر ما ينقص. نص عادي فقط بدون JSON وبدون مقدمات."
        } else {
            "You are reading screenshots of one account named \"" + shown + "\".\n" +
                "This is batch " + (batchIndex + 1) + " of " + batchCount + " with " + imagesInBatch +
                " of " + totalImages + " captures total.\n\n" +
                "For each capture in order write 4-6 lines only:\n" +
                "[capture " + (batchIndex + 1) + ".1] visible account / platform / date if any | topic | " +
                "the important text verbatim (short quote) | tone | is it the account owner's own claim or a quote/sarcasm? | anything notable.\n\n" +
                "Rules: no final verdict about the person, no psychological diagnosis, never guess what is not visible. " +
                "If a capture is unclear write \"unclear\" and say what is missing. Plain text only, no JSON, no preamble."
        }
    }

    fun series(
        lang: Lang,
        mode: AnalysisMode,
        custom: String,
        label: String,
        captureCount: Int,
        digests: String
    ): String {
        val shown = label.ifBlank { if (lang == Lang.AR) "غير محدد" else "unknown" }
        val modeLine = when (mode) {
            AnalysisMode.GENERAL -> if (lang == Lang.AR) {
                "أجرِ التحليل الكامل بالطبقات الأربع على مستوى السلسلة كلها."
            } else {
                "Run the full four-layer analysis across the whole series."
            }

            AnalysisMode.TEXT_ONLY -> if (lang == Lang.AR) {
                "اجمع أهم النصوص الظاهرة في اللقطات في observed، واترك بقية الطبقات مختصرة."
            } else {
                "Collect the key visible texts in observed and keep the other layers short."
            }

            AnalysisMode.TRANSLATE -> if (lang == Lang.AR) {
                "اجمع النصوص المهمة في observed وترجمها (عربي/إنجليزي) مع الحفاظ على اللهجة."
            } else {
                "Collect the key texts in observed and translate them (Arabic/English) keeping the dialect."
            }

            AnalysisMode.TONE -> if (lang == Lang.AR) {
                "ركّز على النبرة والمشاعر وتطورها بين اللقطات، بقرائن لغوية فقط ودون أي تشخيص نفسي."
            } else {
                "Focus on tone and sentiment and how they shift across captures: linguistic evidence only, no diagnosis."
            }

            AnalysisMode.VERIFY -> if (lang == Lang.AR) {
                "اقترح خطوات تحقق عملية مما يظهر في السلسلة، وضعها في notes دون حكم."
            } else {
                "Suggest practical verification steps from the series and put them in notes, no verdict."
            }
        }
        val extra = if (custom.isBlank()) "" else if (lang == Lang.AR) {
            "\nتعليمات إضافية من المستخدم (اتبعها ما لم تخالف القواعد الصارمة): " + custom
        } else {
            "\nExtra user instruction (follow unless it breaks the hard rules): " + custom
        }
        return if (lang == Lang.AR) {
            "لديك " + captureCount + " لقطة شاشة محفوظة للحساب «" + shown + "» التقطها المستخدم بنفسه.\n" +
                "فيما يلي ملخص كل دفعة (كل سطر يخص لقطة). استخدمها كأدلة فقط، ولا تخترع ما ليس فيها.\n\n" +
                digests + "\n\n" +
                modeLine + extra + "\n\n" +
                "المطلوب الآن تحليل السلسلة ككل بـ JSON فقط بنفس الشكل المتفق عليه:\n" +
                "• observed: وصف المحتوى المتكرر عبر اللقطات (الموضوعات، الأسلوب، عبارات متكررة، اسم الحساب والمنصة والفترة الزمنية إن ظهرت).\n" +
                "• classification: الموضوع الغالب والنبرة الغالبة.\n" +
                "• pattern: مطلوب هنا نمط حقيقي مبني على الأدلة — ما يتكرر، ما يتناقض، ما يتطور مع الوقت — مع ذكر رقم اللقطة في الدليل. " +
                "لا تكتب «لا توجد أدلة كافية» إلا إذا كان عدد اللقطات القابلة للقراءة أقل من ٣ فعلاً، وحينها اذكر بالضبط ما ينقص.\n" +
                "• inference: قراءة محتملة حذرة + درجة ثقة + حدود الاستنتاج.\n" +
                "• notes: ما يجب التحقق منه وما لا يمكن استنتاجه."
        } else {
            "You have " + captureCount + " screenshots of the account \"" + shown + "\" captured by the user.\n" +
                "Below are per-batch digests (one line per capture). Use them as evidence only, never invent.\n\n" +
                digests + "\n\n" +
                modeLine + extra + "\n\n" +
                "Now analyse the whole series, JSON only, same shape as agreed:\n" +
                "• observed: recurring content across the captures (topics, style, repeated phrases, account/platform/date range when visible).\n" +
                "• classification: dominant topic and tone.\n" +
                "• pattern: a real, evidence-based pattern — what repeats, what contradicts, what shifts over time — citing the capture number. " +
                "Only write \"Not enough evidence\" when fewer than 3 captures are actually readable, and then say exactly what is missing.\n" +
                "• inference: cautious reading + confidence + limits.\n" +
                "• notes: what to verify and what cannot be concluded."
        }
    }

    private val SYSTEM_AR = """
أنت مساعد لتحليل محتوى عام على وسائل التواصل الاجتماعي. تساعد المستخدم على فهم لقطة شاشة صوّرها بنفسه، ولست جهة حكم على أي شخص.

قواعد صارمة لا تُخالف:
1) لا تُصدر حكمًا نهائيًا على شخص (مثل «شخص سيئ» أو «غير صالح للزواج») ولا تشخيصًا نفسيًا ولا اتهامًا.
2) اسم الحساب أو صورته لا يُثبتان هوية صاحبه؛ قل ذلك صراحة عند الحاجة.
3) فرّق دائمًا بين الاقتباس والسخرية ونشر رأي شخص آخر، وبين تبنّي صاحب الحساب لهذا الرأي. راعِ اللهجة المصرية والعامية والاختصارات.
4) منشور واحد لا يُثبت نمطًا ثابتًا. إن ورد «سياق من سجل المستخدم» وفيه عنصران سابقان أو أكثر لنفس المصدر، فأمكن وصف نمط متكرر مع ذكر الأدلة من كل لقطة وحدود الاستنتاج؛ وإن غاب السياق أو قلّ عن ذلك فاكتب في pattern: «لا توجد أدلة كافية».
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
4) One post never proves a stable pattern. If "User history context" lists two or more earlier captures of the same source, you may describe a recurring pattern with the evidence from each capture and its limits; otherwise write in pattern: "Not enough evidence".
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
