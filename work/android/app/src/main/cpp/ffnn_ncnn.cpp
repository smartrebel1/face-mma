// The ncnn side of the ffnn seam: CPU (NEON) or GPU (Vulkan), for parts with no Hexagon.
//
// Built only when FFNN_HAVE_NCNN is defined, so the QNN-only app and CLI still build with no
// ncnn checkout present. Without it the dispatcher's Ncnn case returns false, which is the
// honest answer rather than a silent fall-through to QNN.
#include "ffnn.h"

#ifdef FFNN_HAVE_NCNN

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <string>
#include <vector>
#include <unistd.h>

#include "net.h"

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace ffnn {
namespace {

// ---------------------------------------------------------------------------
// The name mapping, which is the whole reason this belongs in the BACKEND.
// ---------------------------------------------------------------------------
// pnnx names blobs positionally -- `in0`, `in1`, `out0` -- and discards the ONNX names the
// pipeline uses. So the backend has to know that hyperswap's "source" is in0 and its
// "target" is in1, and it has to know the file stems, which are the ONNX filenames rather
// than the pipeline's logical names.
//
// ⚠ hyperswap's input ORDER is source THEN target, from the ONNX graph. Reversing it
// produces a model that converts cleanly, runs, and computes nonsense -- the failure
// work/ncnn/convert_ncnn.sh already warns about. `ins` is in ncnn BLOB order, so its index
// IS the in<N> number, and that is the only place the order is written down.
// An input's shape, because `execute` is handed a bare `const float*` with no length --
// the caller cannot tell the backend how big a tensor is, so the backend has to know.
// c == 0 means a 1-D tensor of `w` floats (hyperswap's 512-d embedding).
struct InSpec {
  const char* name;   // the ONNX name ffpipe passes
  int w, h, c;
};

struct Spec {
  const char* stem;
  std::vector<InSpec> ins;   // in in0/in1 order
  const char* out;
};

const std::map<std::string, Spec>& specs() {
  static const std::map<std::string, Spec> m = {
      {"yoloface",  {"yoloface_8n_b1",        {{"input", 640, 640, 3}}, "out0"}},
      {"fan2d",     {"2dfan4_heatmaps",       {{"input", 256, 256, 3}}, "out0"}},
      {"arcface",   {"arcface_w600k_r50_b1",  {{"input", 112, 112, 3}}, "out0"}},
      // source FIRST -- in0 is the 512-d embedding, in1 the image. See the warning above.
      {"hyperswap", {"hyperswap_1a_256_fp32",
                     {{"source", 512, 1, 0}, {"target", 256, 256, 3}}, "out0"}},
      // hyperswap_1b: same I/O as 1a, separate weights.
      {"hyperswap_1b", {"hyperswap_1b_256_fp32",
                     {{"source", 512, 1, 0}, {"target", 256, 256, 3}}, "out0"}},
      // hyperswap_1c: same I/O again, separate weights.
      {"hyperswap_1c", {"hyperswap_1c_256_fp32",
                     {{"source", 512, 1, 0}, {"target", 256, 256, 3}}, "out0"}},
      {"gpen",      {"gpen_ncnn",             {{"input", 256, 256, 3}}, "out0"}},
      // One graph serves both gate names. ncnn has no quantised build -- "nsfwq2" exists
      // because a QNN tier below v79 cannot finalize the fp32 gate, which is a QNN fact.
      {"nsfw",      {"nsfw_2_sim",            {{"input", 384, 384, 3}}, "out0"}},
      {"nsfwq2",    {"nsfw_2_sim",            {{"input", 384, 384, 3}}, "out0"}},
      // fan685 is deliberately absent: it is not converted for ncnn, and the pipeline
      // already treats a missing landmark refiner as optional.
  };
  return m;
}

struct Model {
  ncnn::Net net;
  const Spec* spec = nullptr;
  bool gpu = false;
};

InitSpec g_spec;
std::string g_err;
bool g_vulkan = false;

// What the caller has allowed. Auto runs the agreement check below; see ffnn.h.
GpuPolicy g_policy = GpuPolicy::Auto;

// Does THIS GPU compute the same thing this device's own CPU does?
//
//   0  not asked yet, or asked before the models were on disk
//   1  the detector agreed -- the GPU is usable
//  -1  it did not, and nothing will run on the GPU for the rest of the process
//
// Sticky in the -1 direction only. 0 has to stay retryable because the tier PROBE opens
// models with `modelDir` pointing at the library directory, where no model exists: a load
// failure there is "not asked yet", and recording it as a verdict would pin every
// non-Qualcomm device to the CPU on the strength of a file that was never going to be
// found.
int g_gpuTrust = 0;

// No Winograd: see ffnn::setLowMemory. Read by openNet, so it reaches every model and the
// GPU agreement check alike.
bool g_lowMem = false;
int g_numThreads = 2;
std::string g_gpuWhy;

void logLine(const char* s) {
#ifdef __ANDROID__
  __android_log_print(ANDROID_LOG_INFO, "ffnn", "%s", s);
#endif
  fprintf(stderr, "%s\n", s);
}

// One place that turns a Spec into a loaded net, because the GPU check below has to open
// the detector the SAME way the pipeline does. Two copies of the option block is how a
// check ends up validating a configuration nothing actually runs.
bool openNet(ncnn::Net& net, const Spec& sp, bool gpu, std::string& err) {
  (void)gpu;   // Vulkan is OFF in this build (NCNN_VULKAN=OFF); keep arg for call sites.
  net.opt.lightmode = true;
  net.opt.num_threads = g_numThreads;
  // MAXIMUM-STABILITY CPU settings for Android. Vulkan is OFF in this build;
  // fp16/packing/Winograd/sgemm are all switched off because on mid-range
  // MediaTek Cortex-A78 parts we observed hard SIGBUS/SIGSEGV during the first
  // hyperswap inference with ncnn's speed-tuned defaults. A slower swap that
  // works is strictly better than a crash.
  net.opt.use_packing_layout = false;
  net.opt.use_fp16_packed = false;
  net.opt.use_fp16_storage = false;
  net.opt.use_fp16_arithmetic = false;
  net.opt.use_sgemm_convolution = false;
  net.opt.use_winograd_convolution = false;
  net.opt.openmp_blocktime = 0;
  (void)g_lowMem;  // low-mem path is the only path now

  const std::string base = g_spec.modelDir + "/" + sp.stem + ".ncnn.";
  if (net.load_param((base + "param").c_str()) != 0) {
    err = "ncnn: load_param failed for " + base + "param";
    return false;
  }
  if (net.load_model((base + "bin").c_str()) != 0) {
    err = "ncnn: load_model failed for " + base + "bin";
    return false;
  }
  return true;
}

// `out` as a flat tensor, channel by channel, row by row. ncnn pads each channel
// to its alignment (cstep), so we copy one ROW at a time rather than the whole
// channel at once; and we handle both 3-D (c>0, h>0) and 1-D (c==0, h==0,
// single vector of w floats) outputs -- yoloface produces 1-D detections, which
// the previous code handled as "0 elements * c channels" and wrote into a
// zero-sized vector (heap corruption / SIGSEGV).
void flattenMat(const ncnn::Mat& m, std::vector<float>& out) {
  if (m.c <= 1 && m.h <= 1) {
    // 1-D vector of w floats.
    out.resize((size_t)m.w);
    if (m.w > 0 && m.data) memcpy(out.data(), m.data, (size_t)m.w * sizeof(float));
    return;
  }
  const size_t rowFloats = (size_t)m.w;
  const size_t chFloats = (size_t)m.w * (size_t)m.h;
  out.resize(chFloats * (size_t)m.c);
  for (int q = 0; q < m.c; ++q) {
    const float* src = m.channel(q);
    float* dst = out.data() + (size_t)q * chFloats;
    if (!src) continue;
    for (int y = 0; y < m.h; ++y) {
      memcpy(dst + (size_t)y * rowFloats,
             src + (size_t)y * (size_t)m.w,   // per-row stride == w floats when packed
             rowFloats * sizeof(float));
    }
  }
}

bool runOne(ncnn::Net& net, const Spec& sp, const ncnn::Mat& in, std::vector<float>& out) {
  ncnn::Extractor ex = net.create_extractor();
  if (ex.input("in0", in) != 0) return false;
  ncnn::Mat o;
  if (ex.extract(sp.out, o) != 0) return false;
  flattenMat(o, out);
  return !out.empty();
}

// The GPU has to AGREE WITH THE CPU before anything is placed on it.
//
// Reported from the field on a non-Qualcomm phone: the detector "finds a lot and none of
// them is a face" -- boxes over a wall, a picture frame and a dressing gown -- and the swap
// that followed then died in the encoder with nothing worth encoding. That is what
// yoloface's head looks like when its output is noise, and it is not something this bench
// can reproduce: the per-model placement table in ffnn.h was measured on ONE Adreno, and
// ncnn's Vulkan is a different implementation on every vendor's driver.
//
// So the placement is no longer a fact baked in from one device. The detector is run twice
// over the same fixed frame -- once on the GPU, once on this device's own CPU -- and the
// GPU is used only if the two answers are the same tensor. The CPU is the reference because
// it is the path this project has exercised everywhere, and because a phone that fails this
// check still has to swap faces on something.
//
// ⚠ It costs one extra load of the detector and two inferences, ONCE, on the first open
// that asks for the GPU. It is not run at all on a QNN device, and not run twice.
constexpr double kGpuMinSnrDb = 20.0;

void verifyGpu() {
  auto it = specs().find("yoloface");
  if (it == specs().end()) return;
  const Spec& sp = it->second;

  ncnn::Net gpuNet, cpuNet;
  std::string err;
  if (!openNet(gpuNet, sp, true, err) || !openNet(cpuNet, sp, false, err)) {
    // NOT a verdict: see g_gpuTrust. The models are simply not there yet.
    return;
  }

  // A smooth, deterministic frame. Per-pixel noise would be the worse probe: it drives the
  // detector's activations toward zero, and the SNR below would then be comparing two piles
  // of rounding error and calling a broken GPU fine.
  const InSpec& s0 = sp.ins[0];
  ncnn::Mat in(s0.w, s0.h, s0.c);
  for (int c = 0; c < s0.c; ++c) {
    float* q = in.channel(c);
    for (int y = 0; y < s0.h; ++y)
      for (int x = 0; x < s0.w; ++x)
        *q++ = 0.5f + 0.4f * std::sin(0.031f * x + 0.7f * c) * std::cos(0.017f * y);
  }

  std::vector<float> g, c;
  if (!runOne(gpuNet, sp, in, g) || !runOne(cpuNet, sp, in, c) || g.size() != c.size()) {
    g_gpuTrust = -1;
    g_gpuWhy = "the detector would not run on both units";
    logLine("ffnn: GPU check FAILED -- the detector would not run on both units; CPU only");
    return;
  }

  double sig = 0, noi = 0;
  bool finite = true;
  for (size_t i = 0; i < g.size(); ++i) {
    if (!std::isfinite(g[i])) { finite = false; break; }
    sig += (double)c[i] * (double)c[i];
    double d = (double)g[i] - (double)c[i];
    noi += d * d;
  }
  // noi == 0 is bit-identical, which is a pass and not a division.
  double snr = !finite ? -1000.0 : noi <= 0.0 ? 999.0 : 10.0 * std::log10(sig / noi);
  char msg[192];
  if (finite && snr >= kGpuMinSnrDb) {
    g_gpuTrust = 1;
    snprintf(msg, sizeof(msg), "ffnn: GPU check passed -- detector agrees with the CPU at %.1f dB", snr);
  } else {
    g_gpuTrust = -1;
    g_gpuWhy = finite ? "the detector disagrees with the CPU" : "the detector returned NaN";
    snprintf(msg, sizeof(msg),
             "ffnn: GPU check FAILED -- %s (%.1f dB, want %.0f); running on the CPU",
             g_gpuWhy.c_str(), snr, kGpuMinSnrDb);
  }
  logLine(msg);
}

// Pick a safe thread count. 4 threads on a budget 4xA78+4xA55 MediaTek caused
// peak-memory OOM kills on 6-8 GB phones during video swap (each worker holds
// its own intermediate buffers). Big cores only (2) is the conservative
// default: slower per frame, but it does not get the process killed.
static int pickThreadCount() {
  // Allow explicit override for testing.
  if (const char* e = getenv("FFNCNN_THREADS")) {
    int v = atoi(e);
    if (v >= 1 && v <= 8) return v;
  }
  // Conservative single-thread default for Android. On budget MediaTek parts
  // (Dimensity 7xxx with 4 A78 + 4 A55) multi-threaded ncnn showed intermittent
  // native SIGSEGV/SIGBUS during the first hyperswap inference -- each worker
  // allocates its own ~120 MB scratch workspace and peak RSS blew past the
  // per-process LMK limit. 1 thread uses ~35 % less peak memory, which is the
  // difference between "works" and "the OS kills the process mid-swap".
  // Users on flagship SoCs can override with adb setprop FFNCNN_THREADS=N,
  // but out of the box we prefer stability.
  (void)sysconf;
  return 1;
}

}  // namespace

bool ncnnInit(const InitSpec& spec) {
  g_spec = spec;
  // FFNCNN_GPU=auto|force|off, for the headless CLI. The app sets the same policy through
  // the seam instead -- an Android process has no environment to set from outside.
  if (const char* pol = getenv("FFNCNN_GPU")) {
    if (strcmp(pol, "force") == 0) g_policy = GpuPolicy::Force;
    else if (strcmp(pol, "off") == 0) g_policy = GpuPolicy::Off;
    else g_policy = GpuPolicy::Auto;
  }
#if NCNN_VULKAN
  // Counted once. ncnn creates the instance lazily otherwise, and a device with no usable
  // Vulkan must be discovered here rather than at the first GPU model open.
  g_vulkan = ncnn::get_gpu_count() > 0;
#endif
  g_numThreads = pickThreadCount();
  // Force low-memory path on Android ncnn always: no Winograd, slightly smaller
  // peak scratch. Winograd saves ~2x on big cores, but on 1 thread with the
  // conservative settings above its memory cost is pure downside.
  g_lowMem = true;
  logLine((std::string("ffnn/ncnn: threads=") + std::to_string(g_numThreads) +
           " lowmem=1").c_str());
  // Verify the REQUIRED model files exist BEFORE reporting success. The detector is
  // the probe (as on QNN), but also require the rest of the mandatory set so we never
  // return "ready" while ffpipe's tier walk is about to fail on fan2d/arcface/hyperswap.
  // A tier is never half-present (downloader renames after SHA256), but if the user
  // cleared the cache or a partial download left things in a weird state we want an
  // actionable message, not a native crash.
  const char* requiredStems[] = {
    "yoloface_8n_b1", "2dfan4_heatmaps", "arcface_w600k_r50_b1",
    "hyperswap_1a_256_fp32", "nsfw_2_sim",
  };
  for (size_t i = 0; i < sizeof(requiredStems)/sizeof(requiredStems[0]); ++i) {
    const char* stem = requiredStems[i];
    std::string ppath = g_spec.modelDir + "/" + stem + ".ncnn.param";
    std::string bpath = g_spec.modelDir + "/" + stem + ".ncnn.bin";
    FILE* fp = fopen(ppath.c_str(), "rb");
    FILE* fb = fopen(bpath.c_str(), "rb");
    if (fp) fclose(fp);
    if (fb) fclose(fb);
    if (!fp || !fb) {
      g_err = std::string("ncnn model files incomplete (missing ") + stem +
              ") in " + g_spec.modelDir +
              ". Please download the CPU model set (Settings > Download models).";
      return false;
    }
  }
  return true;
}

Handle ncnnOpen(const std::string& logicalName, Placement p) {
  // Cleared first, so this string always describes the call that just failed. The QNN side
  // shipped without that invariant and a stale load error masked an execution failure on a
  // device nobody here owns for two releases -- see ffnn_qnn.cpp. Same shape of bug, and
  // this backend is about to get its first real users, so it does not get to repeat it.
  g_err.clear();
  auto it = specs().find(logicalName);
  if (it == specs().end()) {
    g_err = "ncnn: no model named " + logicalName;
    return nullptr;
  }
  std::unique_ptr<Model> m(new Model());
  m->spec = &it->second;

  // Placement, honoured rather than noted. Cpu is not a hint: the content gate and the
  // enhancer are pinned there because the GPU gets them WRONG, not because it is slower.
  //
  // ⚠ Default means CPU. That is deliberate but it is also easy to misread: the first
  // end-to-end ncnn run measured 354 ms/frame and was reported as "the ncnn path", when
  // every model in it was on the CPU because none had asked for Gpu. Nothing was wrong;
  // the number simply did not mean what it looked like.
  //
  // FFNCNN_PLACE=cpu|gpu overrides Default for MEASUREMENT, so one run gives a whole
  // stage table for each unit. It never overrides an explicit Cpu: those two are
  // correctness decisions and must not be movable by an environment variable.
  // Default PREFERS the GPU, measured per stage over 6 frames (ms/frame):
  //
  //     detector    CPU 38.4   GPU 20.6   1.9x
  //     landmarker  CPU 167.3  GPU 74.2   2.3x
  //     recogniser  CPU 22.7   GPU 19.7   1.2x
  //     swapper     CPU 285.4  GPU 182.2  1.6x
  //
  // The GPU wins every one, so Default meaning CPU would have shipped the slow half of a
  // path that is already 19x off the NPU. The gap also GROWS with clip length: the same
  // CPU run over 3 frames measured 354 ms/frame and over 6 measured 539, which is the
  // sustained-load throttling roadmap 6 already found (+40% avg, +142% worst). The GPU
  // stays flat, so on a real 300-frame clip this is worth more than the table shows.
  bool wantGpu = (p != Placement::Cpu);
  if (p == Placement::Default) {
    // Measurement escape hatch. It never overrides an explicit Cpu -- those two are
    // correctness decisions and must not be movable by an environment variable.
    const char* force = getenv("FFNCNN_PLACE");
    if (force) wantGpu = strcmp(force, "gpu") == 0;
  }

  // Trust, then placement. The check runs at most once per process and only for a model
  // that actually wants the GPU, so a CPU-pinned gate on a phone with no Vulkan never pays
  // for it -- and Off never pays for it at all, which is the point of having the pin.
  if (wantGpu && g_vulkan && g_policy == GpuPolicy::Auto && g_gpuTrust == 0) verifyGpu();
  if (g_policy == GpuPolicy::Off) wantGpu = false;
  else if (g_policy == GpuPolicy::Auto && g_gpuTrust < 0) wantGpu = false;

  m->gpu = wantGpu && g_vulkan;
  if (!openNet(m->net, it->second, m->gpu, g_err)) return nullptr;
  return m.release();
}

void ncnnRelease(Handle h) { delete static_cast<Model*>(h); }

bool ncnnExecute(Handle h, const std::vector<std::string>& names,
                 const std::vector<const float*>& data,
                 std::vector<std::vector<float>>& outs) {
  g_err.clear();
  Model* m = static_cast<Model*>(h);
  if (!m || names.size() != data.size()) {
    g_err = "ncnn: bad execute arguments";
    return false;
  }
  ncnn::Extractor ex = m->net.create_extractor();

  // BY NAME, never by position. The caller passes {"target","source"} and this graph wants
  // source in in0 -- feeding them in the order given would run the swapper with the
  // embedding as the image and produce a plausible-looking wrong answer.
  for (size_t i = 0; i < names.size(); ++i) {
    const auto& want = m->spec->ins;
    size_t idx = want.size();
    for (size_t k = 0; k < want.size(); ++k)
      if (names[i] == want[k].name) { idx = k; break; }
    if (idx == want.size()) {
      g_err = "ncnn: " + std::string(m->spec->stem) + " has no input named " + names[i];
      return false;
    }
    const InSpec& sh = want[idx];
    if (!data[i]) {
      g_err = "ncnn: null input buffer for " + std::string(names[i]);
      return false;
    }
    ncnn::Mat in;
    if (sh.c == 0) {
      // 1-D embedding (shape [w]). create(int w, size_t elemsize, Allocator*).
      in.create(sh.w, (size_t)4u, (ncnn::Allocator*)nullptr);
      if (in.empty() || (size_t)in.w != (size_t)sh.w) {
        g_err = "ncnn: alloc failed for 1d input " + std::string(names[i]);
        return false;
      }
      std::memcpy(in.data, data[i], sizeof(float) * (size_t)sh.w);
    } else {
      // 3-D CHW image input (shape [c,h,w] in ncnn parlance). Allocate via ncnn
      // and COPY plane-by-plane so ncnn's per-plane alignment is respected.
      // Packing type 0 = elempack=1 (plain floats, no fp16/int8 packing).
      in.create(sh.w, sh.h, sh.c, (size_t)4u, 0, (ncnn::Allocator*)nullptr);
      if (in.empty() || in.w != sh.w || in.h != sh.h || in.c != sh.c) {
        g_err = "ncnn: alloc failed for 3d input " + std::string(names[i]);
        return false;
      }
      const float* src = (const float*)data[i];
      const size_t planeBytes = sizeof(float) * (size_t)sh.w * (size_t)sh.h;
      for (int q = 0; q < sh.c; ++q) {
        float* dst = in.channel(q);
        if (!dst) { g_err = "ncnn: null channel"; return false; }
        // Since in was created with sh.w x sh.h and elemsize=4, row stride in
        // floats equals sh.w (= in.w) -- no per-row gap. So a single memcpy per
        // plane is correct. If ncnn padded per channel for alignment, only the
        // tail gap would differ between channels; src layout is packed CHW.
        std::memcpy(dst, src + (size_t)q * (size_t)sh.w * (size_t)sh.h, planeBytes);
      }
    }
    char blob[8];
    snprintf(blob, sizeof(blob), "in%zu", idx);
    if (ex.input(blob, in) != 0) {
      g_err = "ncnn: input " + std::string(blob) + " rejected";
      return false;
    }
  }

  ncnn::Mat out;
  if (ex.extract(m->spec->out, out) != 0) {
    g_err = "ncnn: extract " + std::string(m->spec->out) + " failed";
    return false;
  }
  outs.assign(1, std::vector<float>());
  flattenMat(out, outs[0]);
  return true;
}

std::vector<std::vector<int>> ncnnOutputShapes(Handle h) {
  Model* m = static_cast<Model*>(h);
  if (!m) return {};
  return {};   // ffpipe only asks this of the detector, whose shape it already knows
}

// The spec table is already the authority on what each graph wants -- `execute` is handed a
// bare `const float*` and sizes every input from it -- so the probe reads the same entries
// the real call does, rather than a second description that could disagree with it.
std::vector<std::string> ncnnInputNames(Handle h) {
  Model* m = static_cast<Model*>(h);
  if (!m || !m->spec) return {};
  std::vector<std::string> out;
  for (const InSpec& in : m->spec->ins) out.push_back(in.name);
  return out;
}

std::vector<std::vector<int>> ncnnInputShapes(Handle h) {
  Model* m = static_cast<Model*>(h);
  if (!m || !m->spec) return {};
  std::vector<std::vector<int>> out;
  // c == 0 is a 1-D tensor of `w` floats, not a zero-element one. Reporting {w,h,0} here
  // would make the prober allocate nothing and feed a 512-d embedding from a null buffer.
  for (const InSpec& in : m->spec->ins)
    out.push_back(in.c == 0 ? std::vector<int>{in.w} : std::vector<int>{in.c, in.h, in.w});
  return out;
}

const char* ncnnLastError() { return g_err.c_str(); }

bool ncnnVariantPresent(const std::string&) {
  // ncnn has ONE variant. Presence is whether the detector's pair is on disk -- the same
  // rule QNN uses, for the same reason: the downloader renames only after the hash matches.
  std::string probe = g_spec.modelDir + "/yoloface_8n_b1.ncnn.param";
  if (FILE* f = fopen(probe.c_str(), "rb")) { fclose(f); return true; }
  return false;
}

DeviceInfo ncnnDeviceInfo() {
  DeviceInfo d;
  d.ok = true;
  d.backend = Backend::Ncnn;
  // `gpu` is "is the GPU going to be USED", not "does this part have one". A device whose
  // Vulkan failed the agreement check has a GPU and must not be told it is running on it:
  // that row is the one thing a non-Qualcomm bug report has to get right.
  const bool off = g_policy == GpuPolicy::Off ||
                   (g_policy == GpuPolicy::Auto && g_gpuTrust < 0);
  d.gpu = g_vulkan && !off;
  d.name = !g_vulkan             ? "ncnn, CPU only (no Vulkan)"
           : g_policy == GpuPolicy::Off ? "ncnn, CPU only (GPU turned off here)"
           : g_gpuTrust < 0      ? "ncnn, CPU only -- " + g_gpuWhy
           : g_gpuTrust > 0      ? "ncnn, Vulkan checked against the CPU"
                                 : "ncnn, Vulkan available";
  return d;
}

void ncnnSetLowMemory(bool on) { g_lowMem = on; }

void ncnnSetGpuPolicy(GpuPolicy p) {
  if (p == g_policy) return;
  g_policy = p;
  // Forgotten, not kept. The verdict is about a configuration the caller has just changed,
  // and a stale -1 would make "GPU" pin to the CPU for ever.
  g_gpuTrust = 0;
  g_gpuWhy.clear();
}

}  // namespace ffnn

#endif  // FFNN_HAVE_NCNN
