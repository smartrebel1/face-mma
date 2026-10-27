package com.facefusion.mobile

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Where the models are and which tier this chip loads.
 *
 * Extracted from MainActivity because the API server needs the same answers and there must
 * be exactly one rule: if the server resolves a different tier from the screen, "models
 * missing" and "model loaded" can disagree on the same device and the report reads like a
 * download bug.
 *
 * ⚠ The rule has to match `Pipeline::init` on the native side, which resolves against disk
 * the same way. Naming a tier the pipeline will not open is the failure mode this shape
 * exists to prevent.
 */
object ModelPaths {

    /**
     * The models directory, created by the APP.
     *
     * A directory created by `adb push` is owned by `shell`, and this app cannot traverse
     * it -- open() fails with a bare ENOENT. mkdirs() here makes it app-owned first.
     */
    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "models").apply { mkdirs() }

    /**
     * Every tier this chip can load, best first.
     *
     * NOT "this tier and every older one" -- see NativePipe.probeTierChain -- so it must
     * never be reconstructed from a single tier.
     */
    /**
     * Cached because the chain is a property of the SILICON and cannot change while the
     * process lives -- and because probing it brings the QNN backend up, which is far too
     * expensive to do from composition.
     *
     * ⚠ The chain is cached; the RESOLVED TIER below is deliberately not. That distinction
     * is the whole bug fixed on 2026-08-30: what a device *can* load is fixed, what is *on
     * disk* changes the moment a download finishes.
     */
    @Volatile private var chainCache: List<String>? = null
    @Volatile private var backendCache: String? = null

    // ---------------------------------------------------------------- forced runtime

    private const val PREFS = "ff_backend"
    private const val KEY_FORCED = "forced"

    /**
     * Which runtime the user has pinned: "qnn", "ncnn", or "" for automatic.
     *
     * Persisted, because the point of it is to survive the app restart that a runtime
     * change wants -- a switch that reset itself on launch could never be used to run a
     * whole video through the other backend.
     */
    fun forcedBackend(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FORCED, "") ?: ""

    /**
     * Pin the runtime, or "" to go back to automatic.
     *
     * ⚠ ORDER MATTERS and the caller must have released the pipeline first. This drops both
     * caches because they hold answers from the runtime being replaced: the cached backend
     * IS the question being changed, and the tier chain is "v79,v73,v68" on one runtime and
     * "ncnn" on the other. Leaving either in place makes the app download one runtime's
     * models and load the other's.
     */
    fun setForcedBackend(ctx: Context, value: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_FORCED, value).apply()
        apply(ctx)
    }

    // ------------------------------------------------------------------ ncnn GPU

    private const val KEY_NCNN_GPU = "ncnn_gpu"

    /**
     * Whether the ncnn backend may use the GPU: "auto" (the default), "gpu" or "cpu".
     *
     * Persisted for the same reason the runtime pin is: a user who has just watched the GPU
     * path detect faces in a wallpaper needs the answer to survive the restart that changing
     * it wants. "auto" lets the backend decide by checking itself -- see
     * [NativePipe.setNcnnGpu].
     *
     * ⚠ MMA v2.2: **On non-Qualcomm chips (MediaTek / Exynos / Kirin / UNISOC — anything
     * that is NOT Snapdragon), "auto" is FORCED to "cpu" at apply() time.** Mali/PowerVR
     * Vulkan drivers routinely pass ncnn's fp16 agreement canary but produce subtly-wrong
     * tensor values at full inference, which shows up as the melted/warped/split-face
     * artifact you see in screenshots. The fix is simple: always CPU on non-Snapdragon.
     * This is checked inside apply() so even a user who taps GPU gets CPU — until we have
     * a reliable agreement test there is no other safe default.
     */
    fun ncnnGpu(ctx: Context): String {
        val stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NCNN_GPU, "auto") ?: "auto"
        return resolveNcnnGpu(stored)
    }

    /**
     * Enforce CPU-only on every SoC that is NOT a Qualcomm Snapdragon. Returns the
     * effective pin. Called from ncnnGpu() (read path) and setNcnnGpu() (write path)
     * so the enforced value matches what is displayed in Settings.
     */
    private fun resolveNcnnGpu(requested: String): String {
        if (requested == "cpu") return "cpu"
        if (requested == "gpu" && isQualcomm()) return "gpu"
        // "auto" or "gpu" on a non-Qualcomm SoC -> CPU.
        return if (isQualcomm()) requested else "cpu"
    }

    /** True on Qualcomm Snapdragon SoCs (where the Vulkan backend is known correct). */
    private fun isQualcomm(): Boolean {
        val hw = (Build.HARDWARE + " " + Build.BOARD + " " + Build.SOC_MODEL
                + " " + Build.MANUFACTURER).lowercase()
        return hw.contains("qcom") || hw.contains("qualcomm") || hw.contains("snapdragon")
                || hw.contains("sm8") || hw.contains("sm7") || hw.contains("sm6")
                || hw.contains("sm4") || Build.HARDWARE.equals("qcom", ignoreCase = true)
    }

    /** Pin the ncnn unit. ⚠ The caller must have released the pipeline first. */
    fun setNcnnGpu(ctx: Context, value: String) {
        val effective = resolveNcnnGpu(value)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_NCNN_GPU, effective).apply()
        apply(ctx)
    }

    // -------------------------------------------------------------- low memory

    private const val KEY_LOW_MEM = "low_memory"

    /**
     * "auto" (the default), "on" or "off": whether ncnn builds its models without
     * Winograd. See [NativePipe.setNcnnLowMemory].
     *
     * The ncnn set holds ~2.0 GB once loaded, measured on the S25 CPU against 0.2 GB on
     * the NPU. A 4 GB phone has about 2 GB free and was killed for low memory the moment a
     * photo was added. Off Winograd it is about half, at ~3.5x the time per frame.
     */
    fun lowMemory(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOW_MEM, "auto")
            ?: "auto"

    /** Pin it. ⚠ The caller must have released the pipeline first. */
    fun setLowMemory(ctx: Context, value: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LOW_MEM, value).apply()
        apply(ctx)
    }

    /**
     * What "auto" means: under 5 GB of RAM. A "4 GB" phone reports ~3.6 GB here and a
     * "6 GB" one ~5.5, so the line sits between the class that was killed and the next.
     */
    fun lowMemoryOn(ctx: Context): Boolean = when (lowMemory(ctx)) {
        "on" -> true
        "off" -> false
        else -> runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.totalMem < 5L * 1024 * 1024 * 1024
        }.getOrDefault(false)
    }

    // -------------------------------------------------------------- rejected tiers

    private const val KEY_REJECTED = "rejected_tiers"
    private const val KEY_REJECTED_BUILD = "rejected_tiers_build"
    private const val KEY_REJECTED_WHY = "rejected_why_"

    /**
     * Tiers THIS DEVICE has loaded and then failed to execute.
     *
     * Recorded so the fallback can reach a tier that is not downloaded yet. Before this,
     * the chain `v81,v73,v68` was filtered to what is on DISK, and the downloader fetches
     * exactly one tier -- so an 8 Elite Gen 5 whose v81 tier will not run had a candidate
     * list of exactly `[v81]`, and the fallback added in 0.3.0 correctly detected the
     * failure and then had nowhere to go. The user got "Could not load the models" and a
     * CPU-only app. Remembering the rejection is what makes v73 offerable.
     *
     * ⚠ Keyed to the BUILD, and cleared when it changes. A rejection is a fact about this
     * silicon running THIS binary: the next release may be exactly the one that fixes the
     * tier, and a note this app wrote about itself must never outlive the app that wrote
     * it and silently hold a device on a slower tier for ever.
     */
    fun rejectedTiers(ctx: Context): Set<String> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getLong(KEY_REJECTED_BUILD, -1L) != buildCode(ctx)) return emptySet()
        return p.getStringSet(KEY_REJECTED, emptySet())?.toSet() ?: emptySet()
    }

    /**
     * Why [tier] was rejected here, as native reported it, or null.
     *
     * Same build key as [rejectedTiers]. Without it a phone that fell back shows its unused
     * set as "kept so it can be used again", and a bug report sent the next day carries no
     * trace of which model the chip refused: the run log that held it died with the process.
     */
    fun rejectedReason(ctx: Context, tier: String): String? {
        if (tier !in rejectedTiers(ctx)) return null
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REJECTED_WHY + tier, null)?.takeIf { it.isNotBlank() }
    }

    /** Remember [tier] as unrunnable on this device, for this build. No-op for "". */
    fun rejectTier(ctx: Context, tier: String, why: String = "") {
        if (tier.isBlank()) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = rejectedTiers(ctx) + tier
        val e = p.edit()
            .putStringSet(KEY_REJECTED, now)
            .putLong(KEY_REJECTED_BUILD, buildCode(ctx))
        if (why.isNotBlank()) e.putString(KEY_REJECTED_WHY + tier, why)
        e.apply()
        // The chain is filtered by this set, so the cached one is now a stale answer to a
        // question whose inputs just changed, and native needs telling too.
        chainCache = null
        NativePipe.setSkipTiers(now.joinToString(","))
        android.util.Log.i("ffmodels", "tier $tier rejected on this device; chain is now " +
                                       tierChain(ctx).joinToString(",") + "; " + why)
    }

    private fun buildCode(ctx: Context): Long = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(-1L)

    /**
     * Push the persisted choice into the native seam and invalidate what it invalidates.
     *
     * Called at startup BEFORE any probe -- `backend()` and `tierChain()` cache their first
     * answer, so a setting applied after them would be ignored for the life of the process.
     */
    fun apply(ctx: Context) {
        backendCache = null
        chainCache = null
        NativePipe.ensureLoaded()
        NativePipe.setForcedBackend(forcedBackend(ctx))
        // Thread count for ncnn CPU inference, set BEFORE ncnnInit so the native
        // pickThreadCount() honours it via FFNCNN_THREADS. Upstream default was 1
        // thread (memory-safest on budget Mediatek); we scale up on devices with
        // enough RAM to be worth the extra peak memory without getting OOM-killed.
        // The CPU path on Dimensity 7xxx with 4 threads on big cores is ~3-4x faster
        // than 1-thread on a single still, and ~2.5-3x on video.
        val threads = try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            val m = am.javaClass.getMethod("getLargeMemoryClass")
            val lhc = (m.invoke(am) as? Int) ?: am.memoryClass
            when {
                lhc < 192 -> 1   // ~1-2 GB large-heap: ultra-safe
                lhc < 320 -> 2   // ~3-4 GB
                lhc < 448 -> 3   // ~5-6 GB
                lhc < 576 -> 4   // ~7 GB
                lhc < 768 -> 6   // 8 GB V60 Lite / Dimensity 7360: 6 of the 8 cores
                else -> 8       // ≥10 GB / 12 GB flagships: all 8 cores
            }
        } catch (_: Exception) { 2 }
        // Clamp between 1 and Runtime.availableProcessors() so we never oversubscribe.
        val cap = try { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) } catch (_: Exception) { 4 }
        val safeThreads = threads.coerceIn(1, cap)
        setEnvNative("FFNCNN_THREADS", safeThreads.toString())
        // Before any model is opened, so the first open already knows whether it is allowed
        // to look at the GPU at all. Setting it afterwards would leave the models that the
        // pipeline opened first on the unit this is meant to move them off.
        NativePipe.setNcnnGpu(ncnnGpu(ctx))
        NativePipe.setNcnnLowMemory(lowMemoryOn(ctx))
        // Native has its own copy of the chain and its own presence check, so filtering
        // the Kotlin chain alone would only redirect the DOWNLOADER -- init would still
        // load the rejected tier first, fail its probe, and fall back, paying a full
        // context load on every launch to re-learn something already written down.
        NativePipe.setSkipTiers(rejectedTiers(ctx).joinToString(","))
    }

    /**
     * Set an environment variable in the current process before native init.
     * android.system.Os.setenv() is the public ART bridge to libc setenv()
     * since API 26 (this app's minSdk is 26). Native ncnnInit reads
     * FFNCNN_THREADS via getenv() at startup.
     */
    private fun setEnvNative(name: String, value: String) {
        runCatching {
            val osCls = Class.forName("android.system.Os")
            val setenv = osCls.getDeclaredMethod(
                "setenv", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            setenv.invoke(null, name, value, true)
        }.onFailure {
            // Fallback: libc's setenv via plain JNI-free approach using
            // Runtime.exec is useless (it sets env in a child). If the public
            // API is hidden on a future Android, accept the default thread
            // count rather than crash.
        }
    }

    /**
     * "qnn" or "ncnn" -- which runtime, and therefore which MODEL SET this device needs.
     *
     * A silicon property like the tier CHAIN, so it is cached; unlike the tier it does not
     * depend on what is on disk. "none" is not cached: a failed probe must not be
     * remembered as an answer.
     */
    fun backend(ctx: Context): String {
        backendCache?.let { return it }
        val lib = ctx.applicationInfo.nativeLibraryDir
        val b = NativePipe.probeBackend(lib, lib)
        if (b == "qnn" || b == "ncnn") backendCache = b
        return b
    }

    /**
     * The files one variant needs, by logical name -> filename.
     *
     * ⚠ This MIRRORS what ffnn_qnn.cpp and ffnn_ncnn.cpp resolve at runtime, and the two
     * must not drift. It exists because the app has to know what to DOWNLOAD before any
     * backend has opened anything -- native answers "where is yoloface", this answers
     * "which files must exist first", and only the second question can be asked offline.
     *
     * QNN is one context binary per model, tier in the name. ncnn is a param/bin PAIR per
     * model, no tier, named after the ONNX graph rather than the role.
     */
    fun filesFor(tier: String, name: String): List<String> =
        if (tier == NCNN_TIER) {
            val stem = NCNN_STEMS[name] ?: return emptyList()
            listOf("$stem.ncnn.param", "$stem.ncnn.bin")
        } else if (name == LIVEPORTRAIT) {
            LIVEPORTRAIT_FILES.map { it + "_" + tier + ".bin" }
        } else {
            listOf(name + "_" + tier + ".bin")
        }

    /**
     * The face editor is ONE model to the user and four files on disk: LivePortrait's
     * feature extractor, motion extractor and generator (from its hourglass on), and the
     * keypoint MLPs that run on the CPU. Native opens them as one set and refuses a partial
     * one, so [present] asks for all four. QNN only -- [NCNN_STEMS] has no entry, so ncnn
     * resolves it to nothing and the editor is simply not offered there.
     */
    const val LIVEPORTRAIT = "liveportrait"
    private val LIVEPORTRAIT_FILES = listOf("lp_feature", "lp_motion", "lp_gencut", "lp_mlp")

    const val NCNN_TIER = "ncnn"

    private val NCNN_STEMS = mapOf(
        "yoloface" to "yoloface_8n_b1",
        "fan2d" to "2dfan4_heatmaps",
        "arcface" to "arcface_w600k_r50_b1",
        "hyperswap" to "hyperswap_1a_256_fp32",
        // hyperswap_1b: same I/O as 1a, separate weights. QNN resolves
        // "<logical>_<tier>.bin" generically; ncnn needs the stem here.
        "hyperswap_1b" to "hyperswap_1b_256_fp32",
        // hyperswap_1c: same I/O again, separate weights.
        "hyperswap_1c" to "hyperswap_1c_256_fp32",
        "gpen" to "gpen_ncnn",
        // One graph serves both gate names: "nsfwq2" exists because a QNN tier below v79
        // cannot finalize the fp32 gate, which is a QNN fact and means nothing to ncnn.
        "nsfw" to "nsfw_2_sim",
        "nsfwq2" to "nsfw_2_sim",
    )

    /** Every QNN arch tier the app knows how to name. Not what this chip can LOAD. */
    val QNN_TIERS = listOf("v81s97", "v81", "v79", "v73", "v68")

    /**
     * Which model sets have files on this device, best-known first.
     *
     * Asked of the FILESYSTEM, not of the chip and not of the network. The Settings
     * inventory needs it because the app can hold a set it is not currently using: a phone
     * that ran the GPU path once has ~600 MB of ncnn weights that the NPU build never
     * looks at, and before this there was no screen on which they existed. A user could
     * neither see them nor delete them without switching the runtime back.
     *
     * The detector is the probe, as everywhere else: the downloader renames a file only
     * after its hash matches, so a set is never half-present.
     */
    fun variantsOnDisk(ctx: Context): List<String> {
        val d = dir(ctx)
        return (QNN_TIERS + NCNN_TIER).filter { present(d, it, "yoloface") }
    }

    fun tierChain(ctx: Context): List<String> {
        chainCache?.let { return it }
        val lib = ctx.applicationInfo.nativeLibraryDir
        val probed = NativePipe.probeTierChain(lib, lib)
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        // What the SILICON can load, minus what it has already proved it cannot RUN. This
        // is the line that redirects the downloader: `tier()` and `missing()` both read the
        // chain, so dropping v81 here is what makes the overlay offer v73 instead of
        // reporting a complete v81 download as fine while the app refuses to start.
        //
        // Never filtered to empty. A device that rejected everything still has to try
        // something, and native applies the same rule -- the skip list loses to having
        // nothing to run.
        val rejected = rejectedTiers(ctx)
        val c = probed.filterNot { it in rejected }.ifEmpty { probed }
        // Only a non-empty answer is cached: a failed probe must not be remembered as
        // "this chip can load nothing" for the rest of the process.
        if (c.isNotEmpty()) chainCache = c
        return c
    }

    /**
     * The tier whose files are actually on disk, else the best this chip could load.
     *
     * With nothing downloaded yet the answer is the best tier, because that is the set to
     * fetch.
     *
     * ⚠ **Never cache this, and never substitute `pickTier`/`probeDeviceInfo`'s tier for
     * it.** That tier is `tierChain().front()` -- what the SILICON can load, with no
     * reference to disk. They differ on exactly the devices whose best arch is not hosted
     * yet: a v81 part resolves the chain `v81,v73,v68`, the manifest publishes no v81, the
     * downloader correctly fetches **v73**, and anything naming files `_v81` then reports
     * a complete download as missing. That shipped in 0.2.0 and is what this warning is
     * for. Recomputing is a handful of `canRead()` calls; the chain above is what was
     * expensive, and it is cached.
     */
    fun tier(ctx: Context): String {
        // ncnn has no tiers: one set of files runs on every part, so the answer is fixed
        // and there is nothing on disk to resolve against.
        if (backend(ctx) == NCNN_TIER) return NCNN_TIER
        val chain = tierChain(ctx)
        val d = dir(ctx)
        return chain.firstOrNull { present(d, it, "yoloface") }
            ?: chain.firstOrNull()
            ?: ctx.applicationInfo.nativeLibraryDir.let { NativePipe.probeTier(it, it) }
    }

    /** Every file of [name] for [tier] is on disk. A pair is present only when BOTH are. */
    fun present(d: File, tier: String, name: String): Boolean {
        val f = filesFor(tier, name)
        return f.isNotEmpty() && f.all { File(d, it).canRead() }
    }

    /**
     * Which required files are absent for [tier], by display name.
     *
     * The gate counts as required because it BLOCKS: without it there is nothing to refuse
     * with, and a run that cannot check is a run that must not happen. Either build
     * satisfies it, fp32 (`nsfw_`) or the quantised `nsfwq2_` the lower tiers carry.
     * (`nsfwq2_`, not `nsfwq_`: the old name is calibrated for an input range this
     * app no longer produces -- see work/qnn/convert.sh.)
     */
    fun missing(ctx: Context, tier: String, swapper: String): List<String> {
        val d = dir(ctx)
        val absent = listOf("yoloface", "fan2d", "arcface", swapper)
            .filter { !present(d, tier, it) }
            .toMutableList()
        if (!present(d, tier, "nsfw") && !present(d, tier, "nsfwq2")) absent += "nsfw"
        return absent
    }
}
