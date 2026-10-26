#!/bin/sh
# Print the commit a test run used, for the header of a log. Appends
# "+changes" only when files other than *.log differ from HEAD: the suites
# rewrite their own logs while they run, which says nothing about the code.
repo="$(cd "$(dirname "$0")/.." && pwd)"
head="$(git -C "$repo" rev-parse ${1:+--short} HEAD 2>/dev/null)" || { echo uncommitted; exit 0; }
if git -C "$repo" diff --quiet HEAD -- . ':(exclude)*.log' 2>/dev/null; then
    echo "$head"
else
    echo "$head+changes"
fi
