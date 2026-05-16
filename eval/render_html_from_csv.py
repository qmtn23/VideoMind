"""
独立的 HTML 报告生成脚本：读已有 eval_*.csv，重新生成同名 .html。
用法：
    python render_html_from_csv.py [csv 路径]
不传参时使用 report/ 目录下最新的 eval_*.csv
"""

from __future__ import annotations

import ast
import sys
from datetime import datetime
from pathlib import Path
from typing import List

import pandas as pd

ROOT = Path(__file__).parent
REPORT_DIR = ROOT / "report"

METRIC_COLS = [
    "faithfulness",
    "answer_relevancy",
    "context_precision",
    "context_recall",
    "answer_correctness",
]


def parse_contexts(raw) -> List[str]:
    if isinstance(raw, list):
        return [str(c) for c in raw]
    if isinstance(raw, str):
        try:
            v = ast.literal_eval(raw)
            if isinstance(v, list):
                return [str(c) for c in v]
        except Exception:
            pass
        return [raw]
    try:
        return [str(c) for c in list(raw)]
    except TypeError:
        return []


def color(value: float) -> str:
    if pd.isna(value):
        return "#999"
    if value >= 0.8:
        return "#2e7d32"
    if value >= 0.6:
        return "#f9a825"
    return "#c62828"


def render(csv_path: Path) -> Path:
    df = pd.read_csv(csv_path, encoding="utf-8-sig")
    metric_cols = [c for c in METRIC_COLS if c in df.columns]
    means = df[metric_cols].mean(numeric_only=True)

    summary_rows = "".join(
        f"<tr><td>{name}</td><td style='color:{color(value)}'><b>{value:.4f}</b></td></tr>"
        for name, value in means.items()
    )

    detail_rows = []
    for i, row in df.iterrows():
        cells = "".join(
            f"<td style='color:{color(row[c])}'>{row[c]:.3f}</td>"
            if pd.notna(row.get(c)) else "<td>-</td>"
            for c in metric_cols
        )
        ctxs_list = parse_contexts(row.get("contexts", []))
        ctxs_html = "<br><br>".join(
            f"<i>[{j+1}]</i> {c[:300]}" for j, c in enumerate(ctxs_list)
        ) if ctxs_list else "(无)"
        detail_rows.append(
            f"""
            <tr>
              <td>{i + 1}</td>
              <td><b>Q:</b> {row.get('question', '')}<br><br>
                  <b>GT:</b> {row.get('ground_truth', '')}</td>
              <td>{row.get('answer', '')}</td>
              <td><details><summary>展开 ({len(ctxs_list)})</summary>{ctxs_html}</details></td>
              {cells}
            </tr>"""
        )

    html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>Ragas 评估报告 - VideoMind</title>
<style>
body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; margin: 24px; color:#222; }}
h1 {{ border-bottom: 3px solid #1976d2; padding-bottom: 8px; }}
table {{ border-collapse: collapse; margin: 16px 0; width: 100%; }}
th, td {{ border: 1px solid #ddd; padding: 8px; vertical-align: top; }}
th {{ background:#f5f5f5; }}
tr:nth-child(even) td {{ background:#fafafa; }}
.summary td:first-child {{ width: 200px; font-weight: bold; }}
details {{ cursor: pointer; }}
</style>
</head>
<body>
<h1>Ragas 评估报告 - VideoMind RAG</h1>
<p>评估指标使用 DashScope qwen-max 作为裁判 LLM，text-embedding-v4 作为 embedding。</p>

<h2>指标均值（共 {len(df)} 条样本）</h2>
<table class="summary">
<tr><th>指标</th><th>均值</th></tr>
{summary_rows}
</table>

<h2>逐题详情</h2>
<table>
<tr>
  <th>#</th><th>Question / Ground Truth</th><th>Answer</th><th>Contexts</th>
  {''.join(f'<th>{c}</th>' for c in metric_cols)}
</tr>
{''.join(detail_rows)}
</table>

<p style="color:#888;font-size:12px">生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}（基于 {csv_path.name}）</p>
</body>
</html>"""
    out = csv_path.with_suffix(".html")
    out.write_text(html, encoding="utf-8")
    return out


def main():
    if len(sys.argv) > 1:
        csv_path = Path(sys.argv[1])
    else:
        csvs = sorted(REPORT_DIR.glob("eval_*.csv"))
        if not csvs:
            print("[ERROR] 找不到 eval_*.csv，请先跑 02_run_evaluation.py")
            sys.exit(1)
        csv_path = csvs[-1]
    print(f"[INFO] 渲染来源 CSV: {csv_path}")
    out = render(csv_path)
    print(f"[OK] HTML 已生成: {out}")


if __name__ == "__main__":
    main()
