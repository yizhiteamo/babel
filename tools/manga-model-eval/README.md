# Desktop evaluation of the manga models

Answers whether a comic-specific detector and recogniser are worth carrying onto
a phone, before any Android work is done. Two changes made on reasoning alone
have already had to be reverted in this area; these scripts exist so the next
decision rests on numbers.

Neither the models nor the manga pages are committed — both are somebody else's
work, and `models/` and `comic-sample/` are in `.gitignore`.

## Setup

```bash
pip install onnxruntime numpy pillow

mkdir -p models
curl -L -o models/detector.onnx \
  https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/main/detector-v4-s_int8.onnx
curl -L -o models/encoder_model_int8.onnx \
  https://huggingface.co/ogkalu/manga-ocr-onnx/resolve/main/encoder_model_int8.onnx
curl -L -o models/decoder_model_int8.onnx \
  https://huggingface.co/ogkalu/manga-ocr-onnx/resolve/main/decoder_model_int8.onnx
curl -L -o models/vocab.txt \
  https://huggingface.co/kha-white/manga-ocr-base/resolve/main/vocab.txt
```

Both models are Apache-2.0. `comic-text-detector`, which several desktop tools
use, is GPL-3.0 and was rejected for that reason alone — it would take the whole
app with it.

## Running

```bash
python tools/manga-model-eval/detect.py models/detector.onnx comic-sample/*.jpg
python tools/manga-model-eval/endtoend.py models comic-sample/jap-mag-01.jpg comic-sample/jap-mag-04.jpg
```

`detect.py` checks the detector alone against hand-measured bubble rectangles.
`endtoend.py` runs detector and recogniser together and scores the text with the
same measure as `BubbleScoring` on the Android side, so the figures line up with
everything else recorded in `docs/milestones/v2.md`.

## One trap worth knowing

`orig_target_sizes` is **(width, height)**. Passing it the other way puts boxes
past the right edge of the page and makes a working detector look broken — the
first run here scored 2 of 5 bubbles for exactly that reason.
