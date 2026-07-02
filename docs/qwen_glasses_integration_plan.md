# SixthSense × Ray-Ban Meta × Qwen Cloud — Integration Plan

**Event:** Global AI Hackathon Series with Qwen Cloud · Deadline **July 9, 2026 @ 2:00pm PDT** (8 days)
**Track:** **Track 5 — EdgeAgent** (perceive via edge sensors, reason via cloud, act locally)
**One-liner:** Ray-Ban Meta glasses are the eyes and ears; the Galaxy S25 is the edge hub;
Qwen on Alibaba Cloud is the brain; the ESP32 haptic belt is the body.

---

## 1. Reality check (read this first)

1. **No code runs on the glasses.** Ray-Ban Meta integration goes through Meta's
   **Wearables Device Access Toolkit (WDAT)** — public developer preview, Android SDK at
   `facebook/meta-wearables-dat-android`. Your phone app opens a session with the paired
   glasses and gets: POV **camera stream** (12 MP ultra-wide), **mic array audio**, and
   **speaker output**. That IS the direct integration — the glasses replace the chest camera
   and become the voice I/O.
2. **The glasses have no haptic actuators.** "Full haptic feedback" = the ESP32 belt you
   already built (3 motors, BLE, `[l, c, r, pattern]` packet) + phone `VibrationEffect`
   fallback + audio earcons through the glasses' open-ear speakers. This is a feature, not a
   compromise: multi-device haptics is a better EdgeAgent story than a buzzing temple tip.
3. **The old "never cloud" rule is superseded for this hackathon.** The ExecuTorch-event rule
   in CLAUDE.md inverts here: Qwen Cloud reasoning is *required*. The on-device models don't
   die — they become the **graceful-degradation tier**, which is literally a judged criterion
   for EdgeAgent ("graceful degradation in offline/weak-network scenarios").

## 1.5 Hardware update (Jul 1) — Galaxy A23 5G is the edge hub

The S25 Ultra is no longer available; the phone on hand is a **Galaxy A23 5G (SM-A236U1,
Snapdragon 695, 4 GB RAM, 64 GB, Android 14)**. Consequences:

- **Unaffected:** WDAT glasses session (needs Android 10+), Qwen Cloud path, BLE belt link,
  dashboard, MCP/adb tooling. The core demo pipeline is identical.
- **Rescaled — offline tier:** the QNN `.pte` models target SM8750/Hexagon v79 and will not
  load on the SD695. Use **XNNPACK/CPU** exports of the *smallest* variants only (e.g.
  YOLO11n int8 at ~320 px, expect ~3–6 fps; skip Depth-Anything or use a tiny depth model at
  ~1–2 fps). Drop local Llama entirely (4 GB RAM) — offline voice degrades to canned TTS
  phrases from SceneState.
- **Reflex-path rule of thumb:** detection-driven belt cues at 3–6 fps are still fine at
  walking pace; run one model at a time, low resolution, and watch thermals (budget SoC
  throttles). If CPU vision proves too slow, reflex cues fall back to "hold last cloud
  hazard state + spoken warning" and the cloud path carries the demo — acceptable for this
  hackathon since Qwen-in-the-loop is the point being judged.
- **Dev tip:** WDAT ships a **Mock Device Kit** (simulated glasses, permissions, media
  streams) — build against the mock on the laptop/emulator while the one physical phone is
  busy on the belt/BLE work.

## 2. Target architecture

```text
Ray-Ban Meta glasses  ──(WDAT session over BT/Wi-Fi)──►  Galaxy S25 Ultra (edge hub app)
  POV camera stream                                        │
  5-mic audio                                              ├── REFLEX PATH (local, <100 ms)
  open-ear speakers ◄── TTS audio / earcons                │     glasses frames → on-device depth/
                                                           │     detection (ExecuTorch, existing
                                                           │     VisionPipeline) → SceneState
                                                           │     → BeltMapper → BLE → ESP32 belt
                                                           │
                                                           ├── REASONING PATH (cloud, 1–3 s)
                                                           │     keyframes + audio → backend on
                                                           │     Alibaba Cloud (ECS/Function
                                                           │     Compute, WebSocket) → Qwen3-VL
                                                           │     scene understanding, Qwen ASR,
                                                           │     Qwen agent (tools + memory),
                                                           │     Qwen TTS → phone → glasses
                                                           │     speakers + semantic haptic cues
                                                           │
                                                           └── SceneSocket :8080 → dashboard
                                                                 (now shows edge vs cloud state,
                                                                  latency, degradation tier)
```

**Core design principle — reflexes on the edge, semantics in the cloud.**
Obstacle haptics must fire in <100 ms; they stay on-device (existing pipeline, new frame
source). Qwen never sits in the safety loop; it *shapes* it: scene narration, sign reading,
"find the exit" planning, user-preference memory, and issuing *semantic* haptic cues
(turn-left sweep, confirmation blip) that the edge blends with reflex cues.

**What survives untouched:** the `SceneState` contract, BeltMapper → BeltClient → ESP32 chain,
mock mode, dashboard socket, MCP dev tooling. The two big swaps: CameraX → WDAT glasses frames
as the `VisionPipeline` source, and the local Whisper/Llama voice agent → Qwen cloud agent
(local kept as fallback tier).

## 3. Qwen Cloud usage (judging: "sophisticated use of Qwen Cloud APIs")

| Capability | Model / service | Role |
|---|---|---|
| Scene understanding | **Qwen3-VL** (Model Studio / DashScope) | Keyframe → rich scene description, hazard semantics, sign OCR + meaning |
| Speech in | **Qwen ASR** (or omni model audio-in) | Glasses mic → transcript |
| Agent core | **Qwen3 + tool calling** | Tools: `get_scene_state`, `set_haptic_cue`, `read_sign`, `remember_preference`, `recall_route` |
| Speech out | **Qwen TTS** streaming | Agent answer → glasses speakers |
| Memory (bonus) | Backend store (Alibaba RDS/Tair) + agent memory tools | Remembers routes, preferred verbosity, standing instructions ("always warn about stairs early") — cross-session |

Backend: a small FastAPI (or Node) service on **Alibaba Cloud ECS or Function Compute**,
WebSocket to the phone, calling Model Studio APIs. This is your **proof-of-Alibaba-Cloud
deployment** artifact — keep the deploy config and API-calling code in one obvious file.

## 4. Full haptic feedback plan

Belt packet stays `[left, center, right, pattern]`; firmware grows a richer pattern table.

**Tier 1 — Reflex cues (edge-generated, existing):**
- Direction: per-motor intensity 0–255 (left/center/right obstacle bearing)
- Nearness: intensity ramp
- Pattern 0 steady · 1 caution pulse · 2 double pulse (curb/step)

**Tier 2 — Semantic cues (Qwen-generated, new firmware patterns):**
- 3 **sweep left** (C→L rolling buzz) / 4 **sweep right** — "turn here" guidance
- 5 **confirmation blip** — agent heard you / action done
- 6 **attention** (short triple tick, low intensity) — agent has something to say (paired with earcon in glasses speakers)
- 7 **all-clear breath** (slow fade in/out) — path confirmed clear by cloud

**Arbitration (in BeltMapper):** reflex cues always preempt semantic cues; semantic cues play
only when no obstacle above threshold. Cloud can *raise* urgency, never suppress a reflex.

**Fallbacks:** belt unreachable → phone `VibrationEffect` waveforms mirroring the same
vocabulary; total silence → spoken warnings via glasses. `belt_test` MCP tool already
exercises all of this for demos and debugging; extend it to patterns 3–7.

## 5. Graceful degradation ladder (demo this explicitly — it's judged)

1. **Full**: glasses + Qwen Cloud + belt → reflex haptics + rich voice agent + memory
2. **Weak network**: keyframe rate drops, agent answers get terse, reflex haptics unaffected
3. **Offline**: on-device ExecuTorch depth/detection + local TTS canned answers; belt fully live
4. **Glasses die**: chest-mounted phone camera (original CameraX path) takes over — one toggle

The dashboard should show the current tier live. Cutting Wi-Fi mid-demo and having the belt
never stutter is the single most convincing 20 seconds of your video.

## 6. Eight-day schedule (deadline July 9)

| Day | Goal |
|---|---|
| **Jul 1** | WDAT dev setup: Meta developer account, glasses in dev mode, run the sample app, confirm camera + audio streaming to the S25. In parallel: Alibaba Cloud account, hackathon credits coupon, Model Studio API key, hello-Qwen3-VL call. |
| **Jul 2** | Frame source abstraction: `FrameSource` interface with `CameraXSource` and `GlassesSource`; glasses frames flowing into existing VisionPipeline → SceneState → belt. **Milestone: glasses-driven reflex haptics.** |
| **Jul 3** | Backend on Alibaba Cloud (ECS/Function Compute + WebSocket). Phone uploads keyframes; Qwen3-VL scene description returns; spoken through glasses speakers. |
| **Jul 4** | Voice loop: glasses mic → ASR → Qwen agent with tools (`get_scene_state`, `read_sign`, `set_haptic_cue`) → TTS → glasses. Firmware patterns 3–7 + BeltMapper arbitration. |
| **Jul 5** | Degradation ladder + tier switching + dashboard tier/latency view. Optional: memory tools (preferences, routes). |
| **Jul 6** | Hardening: session drops (WDAT + BLE reconnect), latency measurement (log p50/p95 per path), mock mode end-to-end for rehearsal. |
| **Jul 7** | Submission assets: architecture diagram, README + docs, **open-source LICENSE at repo root**, Alibaba deployment-proof recording + code-file link, blog post (extra $500 prize). |
| **Jul 8** | 3-min demo video (blindfold walk → belt reflexes → voice Q&A through glasses → Wi-Fi kill degradation beat), upload public to YouTube. Buffer. |
| **Jul 9** | Final checks against submission checklist, submit before 2:00pm PDT. |

## 7. Submission checklist mapping

- ☐ Public repo + **detectable OSS license** (MIT/Apache-2.0 in About section)
- ☐ Proof of Alibaba Cloud deployment: short recording + link to the backend file calling Alibaba/DashScope APIs
- ☐ Architecture diagram (the diagram in §2, drawn properly)
- ☐ ~3-min public video
- ☐ Text description + declare **Track 5: EdgeAgent**
- ☐ Optional blog post → Blog Post Award

## 8. Top risks

1. **WDAT preview constraints** — session limits, capture-LED behavior, streaming resolution/fps caps. Day-1 job is discovering these; the CameraX fallback path removes existential risk.
2. **Glasses↔phone bandwidth/latency** — if the stream can't feed the reflex path fast enough, run reflexes off the chest phone camera and use glasses frames only for the Qwen reasoning path (still a legit architecture — say so in the docs).
3. **China-region API access/quota** — get Model Studio credits and test rate limits Day 1; cache TTS earcons locally.
4. **BLE + BT audio coexistence** — glasses (BT) and belt (BLE) both on the phone radio; test together early, not on Day 6.
5. **Scope creep** — protect the ladder in order: reflex haptics → voice loop → degradation demo. Memory and sweep patterns are additive.
