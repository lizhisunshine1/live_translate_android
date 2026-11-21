#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 ECDICT 的 ecdict.csv 裁剪成 App 内置离线词典。

输出：app/src/main/assets/dict/<首字母>.tsv
每行：单词\t音标\t释义（多条释义用 " | " 分隔，UI 再换行显示）

筛选策略：
  - 只保留纯英文小写词条（含 '-'、'\''），去掉词组/缩写/人名；
  - 必须有中文释义；
  - 按「牛津 3000 > 柯林斯星级 > BNC 词频 > 当代语料词频」排序取前 LIMIT 条，
    保证常用词齐全，又不至于把安装包撑大。
"""
import csv
import os
import re
import sys

SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.environ.get("TEMP", "."), "eye_dict", "ecdict.csv")
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "dict")
LIMIT = int(sys.argv[3]) if len(sys.argv) > 3 else 40000

WORD_RE = re.compile(r"^[a-z][a-z'\-]{0,24}$")
csv.field_size_limit(10 * 1024 * 1024)


def rank_key(row):
    """越小越常用：不在牛津/柯林斯、无词频的排后面。"""

    def num(key):
        try:
            return int(row.get(key) or 0)
        except ValueError:
            return 0

    oxford = 1 if (row.get("oxford") or "").strip() == "1" else 0
    collins = num("collins")
    bnc = num("bnc")
    frq = num("frq")
    return (
        0 if oxford else 1,
        0 if collins > 0 else 1,
        -collins,
        0 if bnc > 0 else 1,
        bnc if bnc > 0 else 10**9,
        0 if frq > 0 else 1,
        frq if frq > 0 else 10**9,
    )


def clean_text(text, max_len=180):
    # ECDICT 的换行在 CSV 里是字面量 "\n"（反斜杠 + n），不是真换行，两种都要处理
    for token in ("\\r\\n", "\\n", "\\r", "\r", "\n"):
        text = text.replace(token, " | ")
    text = text.replace("\t", " ")
    text = re.sub(r"  +", " ", text).strip(" |")
    if len(text) > max_len:
        text = text[:max_len].rsplit(" ", 1)[0] + "…"
    return text


def main():
    rows = []
    with open(SRC, "r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            w = (row.get("word") or "").strip().lower()
            if not WORD_RE.match(w):
                continue
            tr = clean_text(row.get("translation") or "")
            if not tr:
                continue
            ph = (row.get("phonetic") or "").strip().replace("/", "").strip()
            rows.append((rank_key(row), len(w), w, ph, tr))

    rows.sort(key=lambda r: (r[0], r[1], r[2]))

    picked = {}
    for _rank, _len, w, ph, tr in rows:
        if w in picked:
            continue
        picked[w] = (ph, tr)
        if len(picked) >= LIMIT:
            break

    os.makedirs(OUT, exist_ok=True)
    buckets = {}
    for w, (ph, tr) in picked.items():
        buckets.setdefault(w[0], []).append((w, ph, tr))

    total = 0
    for letter, items in sorted(buckets.items()):
        items.sort()
        path = os.path.join(OUT, letter + ".tsv")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            for w, ph, tr in items:
                f.write(f"{w}\t{ph}\t{tr}\n")
        total += len(items)
        print(f"{letter}.tsv  {len(items)} words  {os.path.getsize(path) / 1024:.1f} KB")

    print(f"total {total} words")


if __name__ == "__main__":
    main()
