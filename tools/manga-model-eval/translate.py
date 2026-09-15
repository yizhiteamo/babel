"""Stage-3 gate: can a small local LLM translate comic dialogue better than the
shipped engine?

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

Usage:

    python translate.py <models-dir>            # all fifteen bubbles
    python translate.py <models-dir> --limit 4  # a quick look

Note on the export: `model_q4f16.onnx` will not load on the CPU provider with
graph optimisations on — ONNX Runtime's layer-norm fusion fails on it. It is a
GPU/WebGPU-oriented build. Optimisations are disabled here, which works, but the
same wall is waiting on a phone's CPU provider and is recorded in
`docs/milestones/v2.md` rather than discovered twice.
"""
import json
import os
import sys
import time

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

# The same fifteen the device harness uses, so the two are directly comparable:
# exactly what manga-ocr read, with a reference rendering.
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

# Kept deliberately plain. A long prompt full of instructions is a way of
# tuning the measurement rather than the model, and it costs tokens on every
# bubble — which on a phone is the thing being measured.
SYSTEM = "你是漫画翻译。把日文台词翻译成简体中文，只输出译文本身，不要加解释或注音。"

MAX_TOKENS = 96


class Qwen:
    def __init__(self, folder):
        options = ort.SessionOptions()
        # See the module docstring: q4f16 will not load with these on.
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
        options.log_severity_level = 3

        started = time.time()
        self.session = ort.InferenceSession(
            f"{folder}/model_q4f16.onnx", options, providers=["CPUExecutionProvider"])
        self.load_seconds = time.time() - started

        self.tokenizer = Tokenizer.from_file(f"{folder}/tokenizer.json")
        config = json.load(open(f"{folder}/config.json", encoding="utf-8"))
        self.layers = config["num_hidden_layers"]
        self.kv_heads = config["num_key_value_heads"]
        self.head_dim = config["hidden_size"] // config["num_attention_heads"]
        self.stop = {config["eos_token_id"], 151643, 151645}

    def prompt(self, text):
        return (f"<|im_start|>system\n{SYSTEM}<|im_end|>\n"
                f"<|im_start|>user\n{text}<|im_end|>\n"
                f"<|im_start|>assistant\n")

    def empty_cache(self):
        shape = (1, self.kv_heads, 0, self.head_dim)
        cache = {}
        for layer in range(self.layers):
            cache[f"past_key_values.{layer}.key"] = np.zeros(shape, dtype=np.float32)
            cache[f"past_key_values.{layer}.value"] = np.zeros(shape, dtype=np.float32)
        return cache

    def translate(self, text):
        ids = self.tokenizer.encode(self.prompt(text), add_special_tokens=False).ids
        cache = self.empty_cache()
        tokens = list(ids)
        produced = []

        # Whole prompt on the first pass, then one token at a time: that is the
        # point of the key/value cache, and it is what makes this export worth
        # carrying over the cacheless one manga-ocr uses.
        step_input = np.array([ids], dtype=np.int64)
        position = 0

        for _ in range(MAX_TOKENS):
            length = step_input.shape[1]
            feeds = dict(cache)
            feeds["input_ids"] = step_input
            feeds["attention_mask"] = np.ones((1, position + length), dtype=np.int64)
            feeds["position_ids"] = np.arange(
                position, position + length, dtype=np.int64).reshape(1, length)

            outputs = self.session.run(None, feeds)
            logits = outputs[0][0, -1]
            nxt = int(logits.argmax())
            if nxt in self.stop:
                break

            produced.append(nxt)
            tokens.append(nxt)
            position += length
            step_input = np.array([[nxt]], dtype=np.int64)

            names = [o.name for o in self.session.get_outputs()]
            cache = {
                name.replace("present", "past_key_values"): value
                for name, value in zip(names, outputs)
                if name.startswith("present")
            }

        return self.tokenizer.decode(produced).strip()


def main():
    folder = sys.argv[1]
    limit = None
    if "--limit" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--limit") + 1])

    size = os.path.getsize(f"{folder}/model_q4f16.onnx") / 1e6
    model = Qwen(folder)
    print(f"QWEN_MT model_q4f16.onnx {size:.0f}MB, session in {model.load_seconds:.1f}s")

    total = 0.0
    for source, reference in BUBBLES[:limit]:
        started = time.time()
        output = model.translate(source)
        elapsed = time.time() - started
        total += elapsed
        print(f"QWEN_MT {elapsed:5.1f}s")
        print(f"QWEN_MT   ja  {source}")
        print(f"QWEN_MT   zh  {output}")
        print(f"QWEN_MT   ref {reference}")

    count = len(BUBBLES[:limit])
    print(f"QWEN_MT === {count} bubbles, {total:.1f}s total, {total / count:.1f}s each ===")


main()
