package com.scan.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scan.app.t

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = ScanColors.Bg,
        topBar = { ScanTopBar(t("عن التطبيق", "About"), onBack = onBack) }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Text("SCAN", fontSize = 34.sp, fontWeight = FontWeight.Black, color = ScanColors.Accent)
            Text(
                t("عدسة تحليل لقطات الشاشة", "Screenshot analysis lens"),
                color = ScanColors.TextDim,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))

            CardBox {
                Text(
                    t("ما يفعله التطبيق", "What it does"),
                    color = ScanColors.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    t(
                        "تظهر عدسة عائمة فوق التطبيقات. تضغطها، تحدّد منطقة، فيُلتقط ما اخترته أنت فقط، ثم تُخفى التفاصيل الخاصة بفرشاة، ثم يُرسل ما تختاره إلى موديل ذكاء اصطناعي بمفتاحك أنت، ويُعرض التحليل في أربع طبقات ويُحفظ في السجل.",
                        "A floating lens sits above other apps. Tap it, select an area, and only what you chose is captured; hide private details with a brush, then send it to an AI model with your own key. The answer comes back in four layers and is saved to history."
                    ),
                    color = ScanColors.TextDim,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }

            Spacer(Modifier.height(12.dp))
            CardBox {
                Text(
                    t("الإخلاء", "Disclaimer"),
                    color = ScanColors.Warn,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    t(
                        "التحليل مساعد وليس إثباتًا نهائيًا، وقد يخطئ. النتائج قراءات محتملة لصورة واحدة، ولا تصلح للحكم على شخص أو لتشخيص نفسي أو لاتخاذ قرار (زواج، عمل، خلاف) بمفردها. اسم الحساب أو صورته لا يُثبتان الهوية، والمحتوى قد يكون مقتبسًا أو ساخرًا. أنت المسؤول عن استخدام النتائج وعن احترام خصوصية الآخرين.",
                        "Analysis is an aid, not proof, and it can be wrong. Results are possible readings of a single image: they are not a judgement of a person, not a diagnosis, and not a basis for decisions on their own. An account name or photo does not prove identity, and content may be quoted or sarcastic. You are responsible for how you use the results and for other people's privacy."
                    ),
                    color = ScanColors.TextDim,
                    fontSize = 12.sp,
                    lineHeight = 19.sp
                )
            }

            Spacer(Modifier.height(12.dp))
            CardBox {
                Text(
                    t("الخصوصية", "Privacy"),
                    color = ScanColors.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(8.dp))
                listOf(
                    t("لقطات شاشة فقط، وتبدأ منك دائمًا.", "Screenshots only, always started by you."),
                    t("لا تصفّح تلقائي ولا أتمتة لتطبيقات أخرى.", "No scraping and no automation of other apps."),
                    t("لا قراءة لرسائل خاصة ولا خدمات إتاحة.", "No reading of private messages and no accessibility service."),
                    t("البيانات مستثناة من النسخ الاحتياطي.", "App data is excluded from backups."),
                    t("المفاتيح مشفّرة على الجهاز.", "Keys are encrypted on the device."),
                    t("الصورة لا تُرسل إلا عند ضغط «تحليل».", "The image is sent only when you press Analyse.")
                ).forEach {
                    Text("• $it", color = ScanColors.TextDim, fontSize = 12.sp, lineHeight = 19.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            CardBox {
                Text(
                    t("المزوّدون المدعومون", "Supported providers"),
                    color = ScanColors.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Google Gemini · OpenAI · OpenRouter · Groq · Anthropic Claude · أي مزوّد متوافق مع OpenAI",
                    color = ScanColors.TextDim,
                    fontSize = 12.sp,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    t(
                        "قائمة الموديلات تُجلب من المزوّد نفسه، ولا تُكتب يدويًا هنا.",
                        "The model list is fetched from the provider itself, never hardcoded here."
                    ),
                    color = ScanColors.TextDim,
                    fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("الإصدار " + appVersion(), color = ScanColors.TextDim, fontSize = 11.sp)
            Spacer(Modifier.height(28.dp))
        }
    }
}
