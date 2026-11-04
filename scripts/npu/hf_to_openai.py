#!/usr/bin/env python3
"""Hugging Face Whisper (config.json + model.safetensors) -> openai-whisper checkpoint (.pt)."""
import json
import re
import sys

import torch
from safetensors.torch import load_file

src, dst = sys.argv[1], sys.argv[2]
cfg = json.load(open(f"{src}/config.json"))
hf = load_file(f"{src}/model.safetensors")

dims = dict(
    n_mels=cfg["num_mel_bins"],
    n_vocab=cfg["vocab_size"],
    n_audio_ctx=cfg["max_source_positions"],
    n_audio_state=cfg["d_model"],
    n_audio_head=cfg["encoder_attention_heads"],
    n_audio_layer=cfg["encoder_layers"],
    n_text_ctx=cfg["max_target_positions"],
    n_text_state=cfg["d_model"],
    n_text_head=cfg["decoder_attention_heads"],
    n_text_layer=cfg["decoder_layers"],
)

RULES = [
    (r"^model\.encoder\.embed_positions\.weight$", "encoder.positional_embedding"),
    (r"^model\.decoder\.embed_positions\.weight$", "decoder.positional_embedding"),
    (r"^model\.decoder\.embed_tokens\.weight$", "decoder.token_embedding.weight"),
    (r"^model\.encoder\.layer_norm\.", "encoder.ln_post."),
    (r"^model\.decoder\.layer_norm\.", "decoder.ln."),
    (r"^model\.(encoder|decoder)\.conv", r"\1.conv"),
    (r"^model\.(encoder|decoder)\.layers\.", r"\1.blocks."),
    (r"\.self_attn\.(q|k|v)_proj\.", lambda m: ".attn." + {"q": "query", "k": "key", "v": "value"}[m.group(1)] + "."),
    (r"\.self_attn\.out_proj\.", ".attn.out."),
    (r"\.encoder_attn\.(q|k|v)_proj\.", lambda m: ".cross_attn." + {"q": "query", "k": "key", "v": "value"}[m.group(1)] + "."),
    (r"\.encoder_attn\.out_proj\.", ".cross_attn.out."),
    (r"\.self_attn_layer_norm\.", ".attn_ln."),
    (r"\.encoder_attn_layer_norm\.", ".cross_attn_ln."),
    (r"\.final_layer_norm\.", ".mlp_ln."),
    (r"\.fc1\.", ".mlp.0."),
    (r"\.fc2\.", ".mlp.2."),
]

state = {}
for key, value in hf.items():
    if key == "proj_out.weight":  # tied to the token embedding
        continue
    new = key
    for pattern, repl in RULES:
        new = re.sub(pattern, repl, new)
    state[new] = value.float()

torch.save({"dims": dims, "model_state_dict": state}, dst)
print(dims)
print(f"{len(state)} tensors -> {dst}")
