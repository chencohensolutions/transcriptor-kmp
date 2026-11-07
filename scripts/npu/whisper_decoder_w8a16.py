#!/usr/bin/env python3
"""Rewrites a Whisper decoder from sherpa-onnx's QNN export so it quantizes w8a16 (docs/npu-build.md, section 3).
The float model computes the same: check its text on the CPU against the original.

  venv-export/bin/python whisper_decoder_w8a16.py <decoder.onnx> <out.onnx> [--fill -100]

1. Masked-score fill. The export fills masked self-attention scores with -60000 (`qk.masked_fill_(mask, -60000)`, a
   `Where` per layer). Quantized, the scores and the fill share one 16-bit range, [-60000, ~30]: a step of 0.92 that
   wipes out the real scores, and the text comes out empty or looping. -100 is still zero after the softmax (e^-130)
   and gives a range of about [-100, 30], a step of 0.002.
2. The current token's score, `(q*s) @ transpose(k1*s)`, a [1, h, 1, d] x [1, h, d, 1] MatMul per layer, becomes
   `ReduceSum(Mul(q*s, k1*s), -1)`. QAIRT 2.40 needs a MatMul's run-time second input in a 16-bit symmetric range;
   when calibration gives one exactly symmetric already, the converter cuts it to 8 bits instead, and the HTP can't
   build the op ("could not create op"). Without the MatMul the question doesn't arise.

Only the graph is rewritten: the weights stay in their external file, which must sit beside <out.onnx> under the same
name as beside the input (link it).
"""
import argparse

import numpy as np
import onnx
from onnx import helper, numpy_helper, shape_inference


def lower_fill(m, old, new):
    where_inputs = {i for n in m.graph.node if n.op_type == "Where" for i in n.input}
    changed = 0
    for n in m.graph.node:
        if n.op_type != "Constant" or n.output[0] not in where_inputs:
            continue
        for at in n.attribute:
            if at.name == "value" and at.t.data_location != onnx.TensorProto.EXTERNAL:
                v = numpy_helper.to_array(at.t)
                if v.size == 1 and float(v) == old:
                    at.t.CopyFrom(numpy_helper.from_array(np.array(new, dtype=v.dtype).reshape(v.shape), at.t.name))
                    changed += 1
    return changed


def reduce_current_score(m):
    """MatMul(a, Transpose(b, perm=[0,1,3,2])) with b of shape [.., 1, d] -> ReduceSum(Mul(a, b), axis -1)."""
    inferred = shape_inference.infer_shapes(m)
    shapes = {v.name: [d.dim_value for d in v.type.tensor_type.shape.dim] for v in inferred.graph.value_info}
    producer = {o: n for n in m.graph.node for o in n.output}
    nodes, changed = [], 0
    axes = numpy_helper.from_array(np.array([-1], np.int64), "w8a16_reduce_axes")
    for n in m.graph.node:
        t = producer.get(n.input[1]) if n.op_type == "MatMul" else None
        perm = [list(a.ints) for a in t.attribute if a.name == "perm"] if t is not None and t.op_type == "Transpose" else []
        src = shapes.get(t.input[0]) if perm else None
        if perm == [[0, 1, 3, 2]] and src and len(src) == 4 and src[2] == 1:
            prod = n.output[0] + "_w8a16_mul"
            nodes.append(helper.make_node("Mul", [n.input[0], t.input[0]], [prod], name=n.name + "_w8a16_mul"))
            nodes.append(helper.make_node("ReduceSum", [prod, axes.name], [n.output[0]], name=n.name + "_w8a16_sum",
                                          keepdims=1))
            changed += 1
        else:
            nodes.append(n)
    if changed:
        del m.graph.node[:]
        m.graph.node.extend(nodes)
        m.graph.initializer.append(axes)
        # Transposes left with no consumer.
        used = {i for n in m.graph.node for i in n.input} | {o.name for o in m.graph.output}
        keep = [n for n in m.graph.node if not (n.op_type == "Transpose" and not any(o in used for o in n.output))]
        del m.graph.node[:]
        m.graph.node.extend(keep)
    return changed


def main():
    p = argparse.ArgumentParser()
    p.add_argument("decoder")
    p.add_argument("out")
    p.add_argument("--fill", type=float, default=-100.0)
    p.add_argument("--old-fill", type=float, default=-60000.0)
    a = p.parse_args()

    m = onnx.load(a.decoder, load_external_data=False)
    fills = lower_fill(m, a.old_fill, a.fill)
    scores = reduce_current_score(m)
    if not fills or not scores:
        raise SystemExit(f"expected both rewrites: {fills} fills, {scores} scores")
    onnx.save(m, a.out)
    print(f"{fills} masked fills {a.old_fill} -> {a.fill}, {scores} current-token scores as Mul+ReduceSum: {a.out}")


if __name__ == "__main__":
    main()
