"""Export opus-mt-ja-zh to ONNX and quantise it to int8.

One-off, on the development machine only. Nothing here ships: the point is to
find out whether the model is worth carrying at all, and that answer decides
whether any of this is repeated in a form the app uses.

    python export_opus.py <weights-dir> <output-dir>

**Takes a local directory, not a repository id.** Passing `shun89/opus-mt-ja-zh`
hands the download to `huggingface_hub`, which on this network sat at 0MB for
twenty minutes without failing or progressing. Fetching the files directly and
pointing the exporter at them takes the downloader out of the picture entirely:

    curl -L -o models/opus-src/pytorch_model.bin \\
      https://huggingface.co/shun89/opus-mt-ja-zh/resolve/main/pytorch_model.bin
    # ...and config.json, generation_config.json, vocab.json,
    #    source.spm, target.spm, tokenizer_config.json, special_tokens_map.json

A 310MB file may well arrive in pieces — the first attempt here stopped after
28MB — so use something that resumes.
"""
import os
import sys

from onnxruntime.quantization import QuantType, quantize_dynamic
from optimum.exporters.onnx import main_export

WEIGHTS = sys.argv[1] if len(sys.argv) > 1 else "models/opus-src"
OUT = sys.argv[2] if len(sys.argv) > 2 else "models/opus-mt-ja-zh"


def listing(folder, note):
    print(f"--- {note} ---")
    total = 0.0
    for name in sorted(os.listdir(folder)):
        size = os.path.getsize(os.path.join(folder, name)) / 1e6
        if name.endswith(".onnx"):
            total += size
        print(f"  {name:45s} {size:8.1f}MB")
    print(f"  {'onnx total':45s} {total:8.1f}MB")
    return total


main_export(WEIGHTS, output=OUT, task="text2text-generation-with-past", opset=14)
print("EXPORT DONE ->", OUT)
listing(OUT, "as exported")

# Size is this candidate's only inherent advantage over the alternatives
# (Qwen2.5-0.5B is 483MB, M2M100-418M is 632MB), so an unquantised figure would
# not be a comparison at all.
print("--- quantising ---")
for name in sorted(os.listdir(OUT)):
    if not name.endswith(".onnx") or name.endswith("_int8.onnx"):
        continue
    source = os.path.join(OUT, name)
    target = os.path.join(OUT, name.replace(".onnx", "_int8.onnx"))
    quantize_dynamic(source, target, weight_type=QuantType.QInt8)
    print(f"  {name} -> {os.path.basename(target)}")

listing(OUT, "after quantising")
