"""Fixed-length NPU graphs of a WeSpeaker voice-print model (docs/npu-build.md, section 2):

    python voiceprint_graphs.py <model.onnx> <out dir> <name>

From the full-precision ONNX (input `feats` [B, T, 80] fbank, output [B, dim]): three batch-1 graphs of 150, 300 and
600 frames (1.5, 3 and 6 s), <out dir>/<name>_t<frames>.onnx, with the per-utterance mean normalization WeSpeaker
trains with put in front (ReduceMean over time, Sub), simplified with onnxsim. Needs onnx and onnxsim. Then
scripts/npu/compile-for-phone.sh on the three; the converter makes the input [1, 80, T].
"""
import sys

import onnx
import onnxsim
from onnx import TensorProto, helper

src, out, name = sys.argv[1:4]
for frames in (150, 300, 600):
    m = onnx.load(src)
    g = m.graph
    assert g.input[0].name == "feats", g.input[0].name
    for n in g.node:
        for i, x in enumerate(n.input):
            if x == "feats":
                n.input[i] = "feats_cmn"
    opset = max(o.version for o in m.opset_import if o.domain in ("", "ai.onnx"))
    if opset >= 18:
        g.initializer.append(helper.make_tensor("cmn_axes", TensorProto.INT64, [1], [1]))
        mean = helper.make_node("ReduceMean", ["feats", "cmn_axes"], ["feats_mean"], keepdims=1)
    else:
        mean = helper.make_node("ReduceMean", ["feats"], ["feats_mean"], axes=[1], keepdims=1)
    g.node.insert(0, helper.make_node("Sub", ["feats", "feats_mean"], ["feats_cmn"]))
    g.node.insert(0, mean)
    dim = g.output[0].type.tensor_type.shape.dim[-1].dim_value
    for v, dims in ((g.input[0], [1, frames, 80]), (g.output[0], [1, dim])):
        for d, val in zip(v.type.tensor_type.shape.dim, dims):
            d.Clear()
            d.dim_value = val
    del m.metadata_props[:]
    m, ok = onnxsim.simplify(m)
    assert ok
    onnx.checker.check_model(m)
    onnx.save(m, f"{out}/{name}_t{frames}.onnx")
    print(f"{out}/{name}_t{frames}.onnx: {len(m.graph.node)} nodes, ops {sorted({n.op_type for n in m.graph.node})}")
