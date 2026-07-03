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
    GET  /health          -> {"ok": true, "model": ..., "device": ...}
    POST /detect  (jpeg)  -> {"ms": <inference ms>, "dets":
                              [{"label","conf","x1","y1","x2","y2"}, ...]}
"""

import io
import os
import time

import numpy as np
import uvicorn
from fastapi import FastAPI, Request
from PIL import Image
from ultralytics import YOLO

MODEL_NAME = os.environ.get("SONARSIGHT_MODEL", "yolo11x.pt")
CONF = float(os.environ.get("SONARSIGHT_CONF", "0.25"))
IMGSZ = int(os.environ.get("SONARSIGHT_IMGSZ", "640"))

app = FastAPI()
model = YOLO(MODEL_NAME)
device = "cuda:0" if model.device.type != "cpu" else "cpu"
# Warmup so the first phone frame doesn't pay for CUDA init / autotune.
model.predict(np.zeros((IMGSZ, IMGSZ, 3), dtype=np.uint8), imgsz=IMGSZ, verbose=False, half=True)
print(f"ready: {MODEL_NAME} imgsz={IMGSZ} conf={CONF} device={model.device}")


@app.get("/health")
def health():
    return {"ok": True, "model": MODEL_NAME, "device": str(model.device)}


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
