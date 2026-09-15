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

## Stage 3: the translation engine

`translate.py` runs a local LLM over the same fifteen hand-transcribed bubbles
the device harness uses, so its output can be read beside ML Kit's.

```bash
pip install onnxruntime numpy tokenizers

mkdir -p models/qwen
curl -L -o models/qwen/model_q4f16.onnx \
  https://huggingface.co/onnx-community/Qwen2.5-0.5B-Instruct/resolve/main/onnx/model_q4f16.onnx
for f in tokenizer.json config.json generation_config.json; do
  curl -L -o "models/qwen/$f" \
    "https://huggingface.co/onnx-community/Qwen2.5-0.5B-Instruct/resolve/main/$f"
done

PYTHONIOENCODING=utf-8 python translate.py ../../models/qwen
```

`PYTHONIOENCODING=utf-8` is not optional on Windows: without it the console
replaces every Japanese and Chinese character, and the output is unreadable —
which is the entire point of the script.

### opus-mt-ja-zh, which has to be exported first

There is no ONNX build of opus-mt **ja→zh** anywhere on the Hub — `shun89/opus-mt-ja-zh`
ships PyTorch weights and sentencepiece models only. Exporting it needs a
toolchain that is emphatically development-only:

```bash
python -m venv <short-path>          # not under a deep directory: the export
                                     # hits Windows' path length limit
<short-path>/Scripts/pip install torch --index-url https://download.pytorch.org/whl/cpu
<short-path>/Scripts/pip install numpy optimum-onnx sentencepiece onnxruntime

# fetch the weights yourself: huggingface_hub's downloader sat at 0MB for
# twenty minutes here, while plain resolve URLs worked. Use something that
# resumes — a 310MB file did not arrive in one piece.
mkdir -p models/opus-src
for f in config.json generation_config.json source.spm target.spm          special_tokens_map.json tokenizer_config.json vocab.json pytorch_model.bin; do
  curl -L -C - -o "models/opus-src/$f"     "https://huggingface.co/shun89/opus-mt-ja-zh/resolve/main/$f"
done

<short-path>/Scripts/python export_opus.py models/opus-src models/opus-mt-ja-zh
PYTHONIOENCODING=utf-8 <short-path>/Scripts/python translate.py opus ../../models/opus-mt-ja-zh
```

`translate.py opus` reads the `_int8` build by default, which is what would
ship. `--fp32` reads the export as it came and `--mm8` the one with only its
matrix multiplies quantised; both exist because quantisation turned out to cost
real quality, and the three have to be read side by side. The numbers are in
`docs/milestones/v2.md`.

CPU-only torch is about 200MB rather than the 2.5GB of the default wheel, and
nothing from this toolchain ships — it exists to produce one `.onnx`, which is
not committed either.

Qwen2.5-0.5B-Instruct is Apache-2.0 upstream. The result was **negative**, and
the numbers are in `docs/milestones/v2.md`: 3.5s per bubble against a whole-page
budget of 1.9s, 483MB, and an instruction-following collapse on short bubbles.

One trap worth repeating here: `model_q4f16.onnx` does not load on the CPU
provider with graph optimisations on. It is a GPU/WebGPU build. `translate.py`
disables optimisations to get around it; anything CPU-bound should use `int8`
or `q4` instead.
