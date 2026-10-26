"""CIFAR-10 models that must later run as INT8 on the RV32IMA core.

The core has no FPU and no SIMD, so the models follow these rules:
- Every conv has no bias and is followed by BatchNorm. The BN can then be
  folded into the conv when we quantize.
- The only activation is ReLU. No ReLU6, sigmoid, swish, SE block or dropout.
- The model returns raw logits. There is no softmax; the C code takes argmax.

Each model also has a graph() method. It lists every layer in run order,
with the state_dict keys of its weights. The quantizer and the C code
generator read this list (saved as model_graph.json).

This file only imports torch, so it also runs on the container's CPU-only
PyTorch.
"""

import torch
import torch.nn as nn
import torch.nn.functional as F

BN_EPS = 1e-5
INPUT_SHAPE = (3, 32, 32)
NUM_CLASSES = 10


def _init_weights(model):
    for m in model.modules():
        if isinstance(m, nn.Conv2d):
            nn.init.kaiming_normal_(m.weight, mode="fan_out", nonlinearity="relu")
        elif isinstance(m, nn.BatchNorm2d):
            nn.init.ones_(m.weight)
            nn.init.zeros_(m.bias)


# ---------------------------------------------------------------------------
# Graph builder: turns the model structure into a flat list of layers.
# ---------------------------------------------------------------------------

class _GraphBuilder:
    def __init__(self):
        self.layers = []
        self.count = 0

    def _new_tensor(self):
        name = "t%d" % self.count
        self.count += 1
        return name

    def conv(self, name, conv, key, bn_key, relu, inp, in_shape):
        cin, h, w = in_shape
        k = conv.kernel_size[0]
        s = conv.stride[0]
        p = conv.padding[0]
        assert cin == conv.in_channels
        ho = (h + 2 * p - k) // s + 1
        wo = (w + 2 * p - k) // s + 1
        out_shape = [conv.out_channels, ho, wo]
        out = self._new_tensor()
        self.layers.append({
            "name": name, "type": "conv", "input": inp, "output": out,
            "cin": conv.in_channels, "cout": conv.out_channels,
            "kernel": k, "stride": s, "padding": p, "groups": conv.groups,
            "weight": key + ".weight", "bn": bn_key, "relu": relu,
            "in_shape": list(in_shape), "out_shape": out_shape,
        })
        return out, out_shape

    def add(self, name, inputs, shape, relu):
        out = self._new_tensor()
        self.layers.append({
            "name": name, "type": "add", "inputs": list(inputs), "output": out,
            "relu": relu, "shape": list(shape),
        })
        return out

    def gap(self, name, inp, in_shape):
        out = self._new_tensor()
        self.layers.append({
            "name": name, "type": "gap", "input": inp, "output": out,
            "in_shape": list(in_shape), "out_shape": [in_shape[0]],
        })
        return out, [in_shape[0]]

    def fc(self, name, fc, key, inp):
        self.layers.append({
            "name": name, "type": "fc", "input": inp, "output": "logits",
            "weight": key + ".weight", "bias": key + ".bias",
            "in_features": fc.in_features, "out_features": fc.out_features,
        })


def _graph_dict(model, layers):
    return {
        "model": model.name,
        "input_shape": list(INPUT_SHAPE),
        "num_classes": NUM_CLASSES,
        # Filled in by the caller that knows the data (see data.py).
        "mean": None,
        "std": None,
        "bn_eps": BN_EPS,
        "layers": layers,
    }


# ---------------------------------------------------------------------------
# ResNet18 for CIFAR-10 (3x3 stem, no maxpool)
# ---------------------------------------------------------------------------

class BasicBlock(nn.Module):
    def __init__(self, cin, cout, stride):
        super().__init__()
        self.conv1 = nn.Conv2d(cin, cout, 3, stride, 1, bias=False)
        self.bn1 = nn.BatchNorm2d(cout, eps=BN_EPS)
        self.conv2 = nn.Conv2d(cout, cout, 3, 1, 1, bias=False)
        self.bn2 = nn.BatchNorm2d(cout, eps=BN_EPS)
        if stride != 1 or cin != cout:
            # 1x1 projection when the shape changes.
            self.shortcut = nn.Sequential(
                nn.Conv2d(cin, cout, 1, stride, 0, bias=False),
                nn.BatchNorm2d(cout, eps=BN_EPS),
            )
        else:
            self.shortcut = nn.Identity()

    def forward(self, x):
        out = F.relu(self.bn1(self.conv1(x)))
        out = self.bn2(self.conv2(out))
        return F.relu(out + self.shortcut(x))


class ResNet18(nn.Module):
    # Activation buffers the C code needs: the block input stays alive for
    # the shortcut while conv1 and conv2 write two more maps.
    act_buffers = 3

    def __init__(self, width=16, num_classes=NUM_CLASSES):
        super().__init__()
        self.width = width
        self.name = "resnet18_w%d" % width
        w = width
        self.stem = nn.Sequential(
            nn.Conv2d(3, w, 3, 1, 1, bias=False),
            nn.BatchNorm2d(w, eps=BN_EPS),
            nn.ReLU(inplace=True),
        )
        self.layer1 = self._make_stage(w, w, 1)
        self.layer2 = self._make_stage(w, 2 * w, 2)
        self.layer3 = self._make_stage(2 * w, 4 * w, 2)
        self.layer4 = self._make_stage(4 * w, 8 * w, 2)
        self.pool = nn.AdaptiveAvgPool2d(1)
        self.fc = nn.Linear(8 * w, num_classes)
        _init_weights(self)

    @staticmethod
    def _make_stage(cin, cout, stride):
        return nn.Sequential(BasicBlock(cin, cout, stride), BasicBlock(cout, cout, 1))

    def forward(self, x):
        x = self.stem(x)
        x = self.layer1(x)
        x = self.layer2(x)
        x = self.layer3(x)
        x = self.layer4(x)
        x = torch.flatten(self.pool(x), 1)
        return self.fc(x)

    def graph(self):
        g = _GraphBuilder()
        x, s = g.conv("stem", self.stem[0], "stem.0", "stem.1", True, "input", INPUT_SHAPE)
        for li in range(1, 5):
            for bi, blk in enumerate(getattr(self, "layer%d" % li)):
                p = "layer%d.%d" % (li, bi)
                a, sa = g.conv(p + ".conv1", blk.conv1, p + ".conv1", p + ".bn1", True, x, s)
                b, sb = g.conv(p + ".conv2", blk.conv2, p + ".conv2", p + ".bn2", False, a, sa)
                if isinstance(blk.shortcut, nn.Sequential):
                    sc, _ = g.conv(p + ".shortcut", blk.shortcut[0], p + ".shortcut.0",
                                   p + ".shortcut.1", False, x, s)
                else:
                    sc = x
                x = g.add(p + ".add", [b, sc], sb, relu=True)
                s = sb
        x, _ = g.gap("pool", x, s)
        g.fc("fc", self.fc, "fc", x)
        return _graph_dict(self, g.layers)


# ---------------------------------------------------------------------------
# MobileNetV1 for CIFAR-10 (stride-1 stem, 32x32 -> 2x2)
# ---------------------------------------------------------------------------

# Original MobileNetV1 output channels and depthwise strides of the 13 blocks.
# Strides are changed for 32x32 input: the map goes 32 -> 16 -> 8 -> 4 -> 2.
_MBV1_CHANNELS = [64, 128, 128, 256, 256, 512, 512, 512, 512, 512, 512, 1024, 1024]
_MBV1_STRIDES = [1, 2, 1, 2, 1, 2, 1, 1, 1, 1, 1, 2, 1]
_MBV1_STEM = 32


def _scale_channels(c, alpha):
    # Scale by alpha, round to a multiple of 8, keep at least 8.
    return max(8, int(c * alpha + 4) // 8 * 8)


class DepthwiseSeparable(nn.Module):
    def __init__(self, cin, cout, stride):
        super().__init__()
        self.conv_dw = nn.Conv2d(cin, cin, 3, stride, 1, groups=cin, bias=False)
        self.bn_dw = nn.BatchNorm2d(cin, eps=BN_EPS)
        self.conv_pw = nn.Conv2d(cin, cout, 1, 1, 0, bias=False)
        self.bn_pw = nn.BatchNorm2d(cout, eps=BN_EPS)

    def forward(self, x):
        x = F.relu(self.bn_dw(self.conv_dw(x)))
        return F.relu(self.bn_pw(self.conv_pw(x)))


class MobileNetV1(nn.Module):
    # Activation buffers the C code needs: each layer reads one map and
    # writes the other (ping-pong).
    act_buffers = 2

    def __init__(self, alpha=0.5, num_classes=NUM_CLASSES):
        super().__init__()
        self.alpha = alpha
        self.name = "mobilenet_v1_a%03d" % round(alpha * 100)
        c = _scale_channels(_MBV1_STEM, alpha)
        self.stem = nn.Sequential(
            nn.Conv2d(3, c, 3, 1, 1, bias=False),
            nn.BatchNorm2d(c, eps=BN_EPS),
            nn.ReLU(inplace=True),
        )
        blocks = []
        for base, stride in zip(_MBV1_CHANNELS, _MBV1_STRIDES):
            cout = _scale_channels(base, alpha)
            blocks.append(DepthwiseSeparable(c, cout, stride))
            c = cout
        self.blocks = nn.Sequential(*blocks)
        self.pool = nn.AdaptiveAvgPool2d(1)
        self.fc = nn.Linear(c, num_classes)
        _init_weights(self)

    def forward(self, x):
        x = self.stem(x)
        x = self.blocks(x)
        x = torch.flatten(self.pool(x), 1)
        return self.fc(x)

    def graph(self):
        g = _GraphBuilder()
        x, s = g.conv("stem", self.stem[0], "stem.0", "stem.1", True, "input", INPUT_SHAPE)
        for i, blk in enumerate(self.blocks):
            p = "blocks.%d" % i
            x, s = g.conv(p + ".dw", blk.conv_dw, p + ".conv_dw", p + ".bn_dw", True, x, s)
            x, s = g.conv(p + ".pw", blk.conv_pw, p + ".conv_pw", p + ".bn_pw", True, x, s)
        x, _ = g.gap("pool", x, s)
        g.fc("fc", self.fc, "fc", x)
        return _graph_dict(self, g.layers)


# ---------------------------------------------------------------------------

MODEL_NAMES = ("resnet18", "mobilenet_v1")


def build_model(name, width=16, alpha=0.5, **kw):
    """Build a model by name. Unused size options are ignored."""
    if name == "resnet18":
        return ResNet18(width=width, **kw)
    if name == "mobilenet_v1":
        return MobileNetV1(alpha=alpha, **kw)
    raise ValueError("unknown model %r, expected one of %s" % (name, MODEL_NAMES))


def check_graph(model):
    """Run one dummy input and check graph() shapes against real conv shapes."""
    graph = model.graph()
    seen = {}
    hooks = []
    for mname, m in model.named_modules():
        if isinstance(m, nn.Conv2d):
            def hook(mod, inp, out, mname=mname):
                seen[mname] = (list(inp[0].shape[1:]), list(out.shape[1:]))
            hooks.append(m.register_forward_hook(hook))
    was_training = model.training
    model.eval()
    try:
        with torch.no_grad():
            dev = next(model.parameters()).device
            model(torch.zeros(1, *INPUT_SHAPE, device=dev))
    finally:
        for h in hooks:
            h.remove()
        model.train(was_training)
    convs = [l for l in graph["layers"] if l["type"] == "conv"]
    assert len(convs) == len(seen), "graph has %d convs, model has %d" % (len(convs), len(seen))
    for l in convs:
        key = l["weight"][: -len(".weight")]
        assert seen[key] == (l["in_shape"], l["out_shape"]), "shape mismatch at " + l["name"]
    return graph
