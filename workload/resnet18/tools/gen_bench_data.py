#!/usr/bin/env python3
"""Benchmark data generator for this workload.

The work is shared with the other workload, so it lives one level up in
workload/common/tools/bench_data.py. This file only points at it and at
this workload's own quantized model.

    tools/gen_bench_data.py <kind> -o <prefix>
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "..", "common", "tools"))

from bench_data import main   # noqa: E402

if __name__ == "__main__":
    main(default_model_dir=os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "model_int8"))
