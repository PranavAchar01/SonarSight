"""SonarSight cloud detector — YOLO11x on GPU behind a tiny HTTP API.

The phone posts a JPEG frame; the server returns detections with normalized
boxes. The Android client maps them onto the same SceneState contract the
on-device model feeds (zone thirds, nearness = box-area * 3), so cloud and
edge tiers are interchangeable — fresh cloud results win, local YOLO takes
over the moment the network degrades.

Runs anywhere with a CUDA GPU (RunPod pod for development; portable to an
Alibaba Cloud ECS/PAI GPU instance for the hackathon deployment proof).

    pip install ultralytics fastapi uvicorn
    python server.py            # listens on :8000

API:
    GET  /health              -> {"ok": true, "detect": ..., "whisper": ..., "vlm": ...}
    POST /detect     (jpeg)   -> {"ms": <inference ms>, "dets":
                                  [{"label","conf","x1","y1","x2","y2"}, ...]}
    POST /transcribe (wav)    -> {"text": <question>, "ms": ...}
    POST /describe   (json {image_b64, question}) -> {"answer": ..., "ms": ...}
"""

import base64
import io
import os
import threading
import time

import numpy as np
import uvicorn
from fastapi import FastAPI, Request
from PIL import Image
from ultralytics import YOLO

MODEL_NAME = os.environ.get("SONARSIGHT_MODEL", "yolo11x.pt")
CONF = float(os.environ.get("SONARSIGHT_CONF", "0.25"))
IMGSZ = int(os.environ.get("SONARSIGHT_IMGSZ", "640"))
WHISPER_NAME = os.environ.get("SONARSIGHT_WHISPER", "large-v3")
VLM_NAME = os.environ.get("SONARSIGHT_VLM", "Qwen/Qwen2.5-VL-7B-Instruct-AWQ")

VLM_SYSTEM = (
    "You are SonarSight, the eyes of a blind or low-vision person wearing camera "
    "glasses. The image is exactly what is in front of them right now. Answer their "
    "question about their surroundings with extreme, concrete detail: name every "
    "relevant object, where it is (left/center/right, near/far, roughly how many "
    "steps away), colors, text you can read, people and what they are doing, and "
    "any hazards (steps, curbs, obstacles, moving things) FIRST. Be direct and "
    "specific; never say you cannot see. Speak in second person ('to your left...')."
)

app = FastAPI()
model = YOLO(MODEL_NAME)
# Warmup so the first phone frame doesn't pay for CUDA init / autotune.
model.predict(np.zeros((IMGSZ, IMGSZ, 3), dtype=np.uint8), imgsz=IMGSZ, verbose=False, half=True)
print(f"detect ready: {MODEL_NAME} imgsz={IMGSZ} conf={CONF} device={model.device}")

# Whisper + VLM load in the background so /detect is serving within seconds.
whisper_model = None
vlm_model = None
vlm_processor = None
vlm_lock = threading.Lock()


def _load_speech_and_vlm():
    global whisper_model, vlm_model, vlm_processor
    try:
        from faster_whisper import WhisperModel

        whisper_model = WhisperModel(WHISPER_NAME, device="cuda", compute_type="int8_float16")
        print(f"whisper ready: {WHISPER_NAME}")
    except Exception as e:  # noqa: BLE001
        print(f"whisper load failed: {e}")
    try:
        import torch
        from transformers import AutoProcessor, Qwen2_5_VLForConditionalGeneration

        vlm_processor = AutoProcessor.from_pretrained(VLM_NAME)
        vlm_model = Qwen2_5_VLForConditionalGeneration.from_pretrained(
            VLM_NAME, torch_dtype=torch.float16, device_map="cuda:0"
        )
        print(f"vlm ready: {VLM_NAME}")
    except Exception as e:  # noqa: BLE001
        print(f"vlm load failed: {e}")


threading.Thread(target=_load_speech_and_vlm, daemon=True).start()


@app.get("/health")
def health():
    return {
        "ok": True,
        "detect": MODEL_NAME,
        "whisper": WHISPER_NAME if whisper_model else "loading",
        "vlm": VLM_NAME if vlm_model else "loading",
        "device": str(model.device),
    }


@app.post("/transcribe")
async def transcribe(request: Request):
    if whisper_model is None:
        return {"text": "", "error": "whisper still loading"}
    body = await request.body()
    t0 = time.perf_counter()
    segments, _info = whisper_model.transcribe(io.BytesIO(body), language="en", beam_size=2)
    text = " ".join(s.text.strip() for s in segments).strip()
    return {"text": text, "ms": round((time.perf_counter() - t0) * 1000, 1)}


@app.post("/describe")
async def describe(request: Request):
    if vlm_model is None:
        return {"answer": "", "error": "vlm still loading"}
    payload = await request.json()
    question = payload.get("question") or "Describe my surroundings in detail."
    img = Image.open(io.BytesIO(base64.b64decode(payload["image_b64"]))).convert("RGB")
    t0 = time.perf_counter()
    messages = [
        {"role": "system", "content": [{"type": "text", "text": VLM_SYSTEM}]},
        {
            "role": "user",
            "content": [{"type": "image"}, {"type": "text", "text": question}],
        },
    ]
    with vlm_lock:
        prompt = vlm_processor.apply_chat_template(messages, add_generation_prompt=True)
        inputs = vlm_processor(text=[prompt], images=[img], return_tensors="pt").to("cuda:0")
        out = vlm_model.generate(**inputs, max_new_tokens=350, do_sample=False)
        answer = vlm_processor.batch_decode(
            out[:, inputs.input_ids.shape[1]:], skip_special_tokens=True
        )[0].strip()
    return {"answer": answer, "ms": round((time.perf_counter() - t0) * 1000, 1)}


@app.post("/detect")
async def detect(request: Request):
    body = await request.body()
    img = np.asarray(Image.open(io.BytesIO(body)).convert("RGB"))
    t0 = time.perf_counter()
    result = model.predict(img, imgsz=IMGSZ, conf=CONF, verbose=False, half=True)[0]
    ms = (time.perf_counter() - t0) * 1000
    dets = []
    boxes = result.boxes
    if boxes is not None:
        xyxyn = boxes.xyxyn.cpu().numpy()
        confs = boxes.conf.cpu().numpy()
        clses = boxes.cls.cpu().numpy().astype(int)
        for (x1, y1, x2, y2), conf, cls in zip(xyxyn, confs, clses):
            dets.append(
                {
                    "label": result.names[int(cls)],
                    "conf": round(float(conf), 3),
                    "x1": round(float(x1), 4),
                    "y1": round(float(y1), 4),
                    "x2": round(float(x2), 4),
                    "y2": round(float(y2), 4),
                }
            )
    return {"ms": round(ms, 1), "dets": dets}


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000, log_level="warning")
