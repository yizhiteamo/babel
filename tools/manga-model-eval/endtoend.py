"""Stage-1 gate: the comic detector plus manga-ocr, end to end, scored against
the same hand transcriptions everything else in this work is scored against.

Today's pipeline reaches 91% and 87% on the two transcribed pages; grouping them
perfectly by hand reaches 94% and 90%. This has to beat that to be worth
carrying onto a phone.
"""
import sys, time
import numpy as np
import onnxruntime as ort
from PIL import Image

# Speech bubbles only, transcribed by hand — the same text
# `BubbleScoring.groundTruth` holds on the Android side.
GROUND_TRUTH = {
    "jap-mag-01.jpg": [
        "先生も汗拭きシート使いますか", "いいの", "はいいくらでも使ってください",
        "そっちは私の使用済み", "先生先生",
    ],
    "jap-mag-04.jpg": [
        "スーパーアルバイターの資格次が最終試験この本も最終ですッ",
        "どんなことが書かれて",
        "仕事中突然視界が高くなったり増えたり手足色声が変化して",
        "周囲が泣いたり騒いだり逃げ出した時店主の言葉や誘導は無視して",
        "目を閉じて", "絶対に動かないこと", "あんまりわかんないケドッ", "がんばりまーす",
    ],
}

NOISE = set("?？!！.。,、…‥・．，:：;；「」『』()（）[]【】\"'　 \n\t")
CLS, SEP = 2, 3
MAX_TOKENS = 96


def meaningful(text):
    return "".join(c for c in text if c not in NOISE)


def distance(a, b):
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(current[-1] + 1, previous[j] + 1,
                               previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


def similarity(got, expected):
    a, b = meaningful(got), meaningful(expected)
    return 1.0 - distance(a, b) / max(len(a), len(b), 1)


def page_score(expected, regions):
    """Each bubble takes its best region, so a page that splits one is punished."""
    if not expected:
        return 1.0
    return sum(max((similarity(r, e) for r in regions), default=0.0)
               for e in expected) / len(expected)


class Ocr:
    def __init__(self, folder):
        self.encoder = ort.InferenceSession(f"{folder}/encoder_model_int8.onnx",
                                            providers=["CPUExecutionProvider"])
        self.decoder = ort.InferenceSession(f"{folder}/decoder_model_int8.onnx",
                                            providers=["CPUExecutionProvider"])
        with open(f"{folder}/vocab.txt", encoding="utf-8") as handle:
            self.vocab = [line.rstrip("\n") for line in handle]

    def read(self, crop):
        image = crop.convert("L").convert("RGB").resize((224, 224), Image.BILINEAR)
        pixels = np.asarray(image, dtype=np.float32) / 255.0
        pixels = (pixels - 0.5) / 0.5
        pixels = pixels.transpose(2, 0, 1)[None]

        hidden = self.encoder.run(None, {"pixel_values": pixels})[0]

        # Greedy, feeding the whole sequence each step: this export carries no
        # key/value cache, and a bubble is a few dozen tokens at most.
        tokens = [CLS]
        for _ in range(MAX_TOKENS):
            logits = self.decoder.run(
                None,
                {"input_ids": np.array([tokens], dtype=np.int64),
                 "encoder_hidden_states": hidden},
            )[0]
            nxt = int(logits[0, -1].argmax())
            if nxt == SEP:
                break
            tokens.append(nxt)

        return "".join(self.vocab[t] for t in tokens[1:]
                       if 0 <= t < len(self.vocab) and not self.vocab[t].startswith("["))


def detect(session, image):
    w, h = image.size
    data = np.asarray(image.resize((640, 640), Image.BILINEAR), dtype=np.float32) / 255.0
    data = data.transpose(2, 0, 1)[None]
    labels, boxes, scores = session.run(
        None, {"images": data, "orig_target_sizes": np.array([[w, h]], dtype=np.int64)})
    keep = scores[0] >= 0.5
    return labels[0][keep], boxes[0][keep]


def main():
    folder = sys.argv[1]
    detector = ort.InferenceSession(f"{folder}/detector.onnx",
                                    providers=["CPUExecutionProvider"])
    ocr = Ocr(folder)

    for path in sys.argv[2:]:
        name = path.replace("\\", "/").split("/")[-1]
        image = Image.open(path).convert("RGB")
        started = time.time()

        labels, boxes = detect(detector, image)
        texts = []
        for label, box in zip(labels, boxes):
            if label != 1:          # text inside a bubble
                continue
            x0, y0, x1, y1 = (max(0, int(box[0])), max(0, int(box[1])),
                              min(image.width, int(box[2])), min(image.height, int(box[3])))
            if x1 - x0 < 4 or y1 - y0 < 4:
                continue
            texts.append(ocr.read(image.crop((x0, y0, x1, y1))))

        elapsed = time.time() - started
        expected = GROUND_TRUTH.get(name)
        header = f"{name}  regions={len(texts)}  {elapsed:.1f}s"
        if expected:
            header += f"  score={page_score(expected, texts):.0%}"
        print(header)
        for text in texts:
            best = max(expected or [""], key=lambda e: similarity(text, e))
            mark = f"{similarity(text, best):.0%}" if expected else "  -"
            print(f'   {mark}  "{text}"')


main()
