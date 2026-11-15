#!/usr/bin/env python3
"""A whisper.cpp ggml model without its encoder's weights, for a model whose encoder runs on the NPU
(ModelKind.WhisperNpuEncoder; whisper.cpp with `skip_encoder`, third_party/whisper.cpp-external-encoder.patch).

  python3 whisper_ggml_decoder_only.py <model.bin> <out.bin>

Copies the header (hyperparameters, mel filters, vocabulary) as it is and every tensor whose name doesn't start with
"encoder.", byte for byte: the decoder is exactly the input's (quantize the input first, e.g. to q8_0). whisper.cpp
loads the result only with `skip_encoder` (without it, it stops: "not all tensors loaded").
"""
import struct
import sys

# ggml types in whisper.cpp model files: (block size, bytes per block).
TYPES = {0: (1, 4), 1: (1, 2), 2: (32, 18), 3: (32, 20), 6: (32, 22), 7: (32, 24), 8: (32, 34)}


def main():
    src, dst = sys.argv[1], sys.argv[2]
    with open(src, "rb") as f, open(dst, "wb") as out:
        def copy(n):
            b = f.read(n)
            if len(b) != n:
                raise SystemExit("truncated model file")
            out.write(b)
            return b

        magic, = struct.unpack("<I", copy(4))
        if magic != 0x67676D6C:
            raise SystemExit("not a whisper.cpp ggml model")
        hp = struct.unpack("<11i", copy(44))  # n_vocab ... n_mels, ftype
        n_mel, n_fft = struct.unpack("<ii", copy(8))
        copy(4 * n_mel * n_fft)  # mel filters
        n_words, = struct.unpack("<i", copy(4))
        for _ in range(n_words):
            n, = struct.unpack("<I", copy(4))
            copy(n)
        kept = dropped = 0
        while True:
            head = f.read(12)
            if not head:
                break
            n_dims, name_len, ttype = struct.unpack("<iii", head)
            dims = struct.unpack(f"<{n_dims}i", f.read(4 * n_dims))
            name = f.read(name_len)
            n = 1
            for d in dims:
                n *= d
            blk, size = TYPES[ttype]
            data = f.read(n // blk * size)
            if name.startswith(b"encoder."):
                dropped += 1
                continue
            out.write(head + struct.pack(f"<{n_dims}i", *dims) + name + data)
            kept += 1
    print(f"hparams {hp}: kept {kept} tensors, dropped {dropped} encoder tensors: {dst}")


if __name__ == "__main__":
    main()
