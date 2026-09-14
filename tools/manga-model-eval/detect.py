"""Stage-1 check: does the comic detector find our bubbles, and does it keep
sound effects out of them?

Compared against the same hand-measured bubble rectangles the oracle experiment
used, so the answer is on the same footing as every other number in this work.
"""
import sys, time, json
import numpy as np
import onnxruntime as ort
from PIL import Image

CLASSES = {0: "bubble", 1: "text_bubble", 2: "text_free"}

# The oracle's rectangles, as fractions of the page. Same source as the ceiling
# measurement: read off measured line positions, not drawn by eye.
ORACLE = {
    "jap-mag-01.jpg": [
        (0.79, 0.02, 0.97, 0.25), (0.11, 0.02, 0.18, 0.14), (0.01, 0.16, 0.18, 0.35),
        (0.78, 0.47, 0.92, 0.72), (0.00, 0.52, 0.18, 0.74),
    ],
    "jap-mag-04.jpg": [
        (0.88, 0.00, 1.00, 0.19), (0.42, 0.03, 0.51, 0.15), (0.81, 0.33, 0.94, 0.57),
        (0.55, 0.47, 0.71, 0.72), (0.39, 0.33, 0.45, 0.49), (0.06, 0.54, 0.17, 0.71),
        (0.90, 0.76, 0.99, 0.91), (0.55, 0.73, 0.61, 0.92),
    ],
}

SCORE_FLOOR = 0.5


def detect(session, path):
    image = Image.open(path).convert("RGB")
    w, h = image.size
    data = np.asarray(image.resize((640, 640), Image.BILINEAR), dtype=np.float32) / 255.0
    data = data.transpose(2, 0, 1)[None]

    started = time.time()
    labels, boxes, scores = session.run(
        # (w, h), not (h, w). Passing them the other way put boxes past the
        # right edge of the page — the first run's near-zero scores were this,
        # not the model.
        None, {"images": data, "orig_target_sizes": np.array([[w, h]], dtype=np.int64)}
    )
    elapsed = time.time() - started

    keep = scores[0] >= SCORE_FLOOR
    return w, h, labels[0][keep], boxes[0][keep], scores[0][keep], elapsed


def contained(inner, outer):
    """How much of the text rectangle lies inside a detected balloon.

    Not IoU: a balloon is larger than the text it holds, so overlap over union
    scores a correct detection low. The question worth asking is whether the
    text is *in* the bubble.
    """
    x0, y0 = max(inner[0], outer[0]), max(inner[1], outer[1])
    x1, y1 = min(inner[2], outer[2]), min(inner[3], outer[3])
    inter = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    area = (inner[2] - inner[0]) * (inner[3] - inner[1])
    return inter / area if area > 0 else 0.0


def main():
    session = ort.InferenceSession(sys.argv[1], providers=["CPUExecutionProvider"])
    for path in sys.argv[2:]:
        name = path.replace("\\", "/").split("/")[-1]
        w, h, labels, boxes, scores, elapsed = detect(session, path)

        counts = {v: int((labels == k).sum()) for k, v in CLASSES.items()}
        print(f"\n{name}  {w}x{h}  {elapsed*1000:.0f}ms  {counts}")

        expected = ORACLE.get(name)
        if not expected:
            continue

        bubbles = [b for l, b in zip(labels, boxes) if l == 0]
        matched = 0
        for rect in expected:
            target = (rect[0] * w, rect[1] * h, rect[2] * w, rect[3] * h)
            best = max((contained(target, b) for b in bubbles), default=0.0)
            hit = best >= 0.8
            matched += hit
            print(f"   text area inside a bubble: {best:.0%} {'HIT' if hit else 'MISS'}")
        print(f"   matched {matched}/{len(expected)} bubbles,"
              f" detector found {len(bubbles)}")


main()
