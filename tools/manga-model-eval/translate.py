"""Stage-3 gate: can a local model translate comic dialogue better than the
shipped engine, at a size and speed worth shipping?

The control is ML Kit, measured on the device by `TranslationExperimentTest` on
exactly these fifteen bubbles. Its failures are not about punctuation — that was
fixed separately — they are about not understanding the sentence:

    いくらでも使ってください   ->  请使用任何数字      (please use any number)
    がんばりまーす!!          ->  祝你好运！          (good luck)
    先生も汗拭きシート使いますか? -> 你用汗水表吗？    (loses the teacher entirely)

So the question here is comprehension, and the answer is read, not scored: a
single reference translation compared automatically would be a worse guide than
looking at the output. Latency and size are reported because they decide whether
a better answer is shippable at all.

Two engines, one bubble list, so the outputs sit side by side:

    python translate.py qwen ../../models/qwen
    python translate.py opus ../../models/opus-mt-ja-zh
    python translate.py opus ../../models/opus-mt-ja-zh --limit 4

`PYTHONIOENCODING=utf-8` is not optional on Windows — without it the console
replaces every Japanese and Chinese character, which defeats the purpose.

Note on the Qwen export: `model_q4f16.onnx` will not load on the CPU provider
with graph optimisations on — ONNX Runtime's layer-norm fusion fails on it. It
is a GPU/WebGPU build. Optimisations are disabled for it here, which works, but
the same wall is waiting on a phone's CPU provider and is recorded in
`docs/milestones/v2.md` rather than discovered twice.
"""
import glob
import json
import os
import sys
import time

import numpy as np
import onnxruntime as ort

# Exactly what manga-ocr read from the two transcribed pages, with a reference
# rendering — the same fifteen the device harness uses, so the two are directly
# comparable. Punctuation is already repaired, as it now is in the app.
BUBBLES = [
    ("先生も汗拭きシート使いますか?", "老师也要用擦汗巾吗？"),
    ("いいの?", "可以吗？"),
    ("はい", "好的"),
    ("いくらでも使ってください", "请随便用"),
    ("そっちは私の使用済み…", "那个是我用过的…"),
    ("…あ", "……啊"),
    ("…先生?", "……老师？"),
    ("スーパーアルバイターの資格、次が最終試験…この本も最終ですッ",
     "超级兼职者的资格，下一场就是最终考试…这本书也是最后一本了！"),
    ("どんなことが書かれて…", "上面写了些什么…"),
    ("…仕事中、突然視界が高くなったり…増えたり…手足…色…声が変化して…",
     "工作时视野会突然变高…会增多…手脚、颜色、声音都会变化…"),
    ("周囲が泣いたり、騒いだり逃げ出した時…店主の…言葉や誘導は無視して…",
     "周围的人哭喊、骚动、逃跑时……请无视店主的话和指引……"),
    ("目を…閉じて…", "闭上眼睛…"),
    ("…絶対に…動かないこと…", "绝对…不要动…"),
    ("…あんまりわかんないケドッ", "……虽然不太懂啦"),
    ("がんばりまーす!!", "我会加油的！！"),
]

MAX_TOKENS = 96


def folder_size(folder, pattern="*.onnx"):
    return sum(os.path.getsize(p) for p in glob.glob(os.path.join(folder, pattern))) / 1e6





class Qwen:
    """An instruction-following LLM, prompted to translate."""

    # Kept deliberately plain. A long prompt full of instructions tunes the
    # measurement rather than the model, and costs tokens on every bubble —
    # which on a phone is the thing being measured.
    SYSTEM = "你是漫画翻译。把日文台词翻译成简体中文，只输出译文本身，不要加解释或注音。"

    label = "QWEN_MT"

    def __init__(self, folder):
        from tokenizers import Tokenizer

        options = ort.SessionOptions()
        # See the module docstring: q4f16 will not load with these on.
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
        options.log_severity_level = 3

        started = time.time()
        self.session = ort.InferenceSession(
            f"{folder}/model_q4f16.onnx", options, providers=["CPUExecutionProvider"])
        self.load_seconds = time.time() - started
        self.size_mb = folder_size(folder)

        self.tokenizer = Tokenizer.from_file(f"{folder}/tokenizer.json")
        config = json.load(open(f"{folder}/config.json", encoding="utf-8"))
        self.layers = config["num_hidden_layers"]
        self.kv_heads = config["num_key_value_heads"]
        self.head_dim = config["hidden_size"] // config["num_attention_heads"]
        self.stop = {config["eos_token_id"], 151643, 151645}
        self.outputs = [o.name for o in self.session.get_outputs()]

    def prompt(self, text):
        return (f"<|im_start|>system\n{self.SYSTEM}<|im_end|>\n"
                f"<|im_start|>user\n{text}<|im_end|>\n"
                f"<|im_start|>assistant\n")

    def translate(self, text):
        ids = self.tokenizer.encode(self.prompt(text), add_special_tokens=False).ids
        shape = (1, self.kv_heads, 0, self.head_dim)
        cache = {}
        for layer in range(self.layers):
            cache[f"past_key_values.{layer}.key"] = np.zeros(shape, dtype=np.float32)
            cache[f"past_key_values.{layer}.value"] = np.zeros(shape, dtype=np.float32)

        produced = []
        # Whole prompt on the first pass, then one token at a time: that is the
        # point of the key/value cache.
        step = np.array([ids], dtype=np.int64)
        position = 0

        for _ in range(MAX_TOKENS):
            length = step.shape[1]
            feeds = dict(cache)
            feeds["input_ids"] = step
            feeds["attention_mask"] = np.ones((1, position + length), dtype=np.int64)
            feeds["position_ids"] = np.arange(
                position, position + length, dtype=np.int64).reshape(1, length)

            outputs = self.session.run(None, feeds)
            nxt = int(outputs[0][0, -1].argmax())
            if nxt in self.stop:
                break

            produced.append(nxt)
            position += length
            step = np.array([[nxt]], dtype=np.int64)
            cache = {
                name.replace("present", "past_key_values"): value
                for name, value in zip(self.outputs, outputs)
                if name.startswith("present")
            }

        return self.tokenizer.decode(produced).strip()


class Opus:
    """A narrow ja->zh translator: Marian encoder-decoder, no prompt, no chat."""

    label = "OPUS_MT"

    def __init__(self, folder):
        from transformers import AutoTokenizer

        options = ort.SessionOptions()
        options.log_severity_level = 3

        # int8 by default, because that is what would ship. The other variants
        # exist because quantisation turned out to cost real quality, and the
        # only way to know what it costs is to read all three side by side:
        #   _int8  everything quantised          (smallest)
        #   _mm8   matrix multiplies only, embeddings left in float
        #   (none) as exported                   (largest, best)
        suffix = "_int8"
        if "--fp32" in sys.argv:
            suffix = ""
        elif "--mm8" in sys.argv:
            suffix = "_mm8"
        encoder_path = f"{folder}/encoder_model{suffix}.onnx"
        decoder_path = f"{folder}/decoder_model{suffix}.onnx"

        started = time.time()
        self.encoder = ort.InferenceSession(
            encoder_path, options, providers=["CPUExecutionProvider"])
        self.decoder = ort.InferenceSession(
            decoder_path, options, providers=["CPUExecutionProvider"])
        self.load_seconds = time.time() - started
        # Only what is actually loaded: the export folder holds every variant,
        # and summing all of them would report a number nobody would ship.
        self.size_mb = sum(os.path.getsize(p) for p in (encoder_path, decoder_path)) / 1e6

        self.tokenizer = AutoTokenizer.from_pretrained(folder)
        config = json.load(open(f"{folder}/config.json", encoding="utf-8"))
        # Marian starts the decoder on the pad token, not on a BOS.
        self.start = config.get("decoder_start_token_id", config["pad_token_id"])
        self.eos = config["eos_token_id"]

    def translate(self, text):
        encoded = self.tokenizer(text, return_tensors="np")
        input_ids = encoded["input_ids"].astype(np.int64)
        attention_mask = encoded["attention_mask"].astype(np.int64)

        hidden = self.encoder.run(
            None, {"input_ids": input_ids, "attention_mask": attention_mask})[0]

        # Without the key/value cache: a bubble is a few dozen tokens, and what
        # this run has to establish is what the model says, not how fast a
        # desktop says it.
        tokens = [self.start]
        for _ in range(MAX_TOKENS):
            logits = self.decoder.run(None, {
                "input_ids": np.array([tokens], dtype=np.int64),
                "encoder_attention_mask": attention_mask,
                "encoder_hidden_states": hidden,
            })[0]
            nxt = int(logits[0, -1].argmax())
            if nxt == self.eos:
                break
            tokens.append(nxt)

        return self.tokenizer.decode(tokens[1:], skip_special_tokens=True).strip()


ENGINES = {"qwen": Qwen, "opus": Opus}


def main():
    engine_name = sys.argv[1]
    folder = sys.argv[2]
    limit = None
    if "--limit" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--limit") + 1])

    model = ENGINES[engine_name](folder)
    tag = model.label
    print(f"{tag} {folder} {model.size_mb:.0f}MB of onnx, sessions in {model.load_seconds:.1f}s")

    total = 0.0
    bubbles = BUBBLES[:limit]
    for source, reference in bubbles:
        started = time.time()
        output = model.translate(source)
        elapsed = time.time() - started
        total += elapsed
        print(f"{tag} {elapsed:5.1f}s")
        print(f"{tag}   ja  {source}")
        print(f"{tag}   zh  {output}")
        print(f"{tag}   ref {reference}")

    print(f"{tag} === {len(bubbles)} bubbles, {total:.1f}s total, "
          f"{total / len(bubbles):.1f}s each ===")


main()
