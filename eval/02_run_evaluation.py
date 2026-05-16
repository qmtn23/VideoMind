"""
02_run_evaluation.py

读取 testset.csv，逐条调用 VideoMind 后端 /api/v1/eval/answer，
拿到 {answer, contexts} 后用 Ragas 计算 5 个核心指标，最终输出：
  - report/eval_<timestamp>.csv  每条详细得分
  - report/eval_<timestamp>.html 可视化报告

裁判 LLM 与 Embedding 同样使用 DashScope qwen-max + text-embedding-v4。

执行方式：
    cd eval
    python 02_run_evaluation.py
"""

from __future__ import annotations

import ast
import os
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List

import pandas as pd
import requests
from datasets import Dataset
from dotenv import load_dotenv
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from ragas import evaluate
from ragas.embeddings import LangchainEmbeddingsWrapper
from ragas.llms import LangchainLLMWrapper
from ragas.metrics import (
    answer_correctness,
    answer_relevancy,
    context_precision,
    context_recall,
    faithfulness,
)
from tqdm import tqdm

# ---------------------------------------------------------------------------
# 1. 配置
# ---------------------------------------------------------------------------
load_dotenv()

DASHSCOPE_API_KEY = os.getenv("DASHSCOPE_API_KEY", "").strip()
DASHSCOPE_BASE_URL = os.getenv(
    "DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"
)
JUDGE_MODEL = os.getenv("JUDGE_MODEL", "qwen-max")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "text-embedding-v4")

API_BASE_URL = os.getenv("API_BASE_URL", "http://localhost:8081").rstrip("/")
EVAL_USERNAME = os.getenv("EVAL_AUTH_USERNAME", "admin")
EVAL_PASSWORD = os.getenv("EVAL_AUTH_PASSWORD", "admin123")
EVAL_TOP_K = int(os.getenv("EVAL_TOP_K", "5"))

ROOT = Path(__file__).parent
TESTSET_PATH = ROOT / "testset.csv"
REPORT_DIR = ROOT / "report"
REPORT_DIR.mkdir(parents=True, exist_ok=True)

if not DASHSCOPE_API_KEY:
    print("[ERROR] 环境变量 DASHSCOPE_API_KEY 未设置，请先填好 .env", file=sys.stderr)
    sys.exit(1)

if not TESTSET_PATH.exists():
    print(f"[ERROR] 测试集不存在: {TESTSET_PATH}\n        请先运行 01_generate_testset.py", file=sys.stderr)
    sys.exit(2)


# ---------------------------------------------------------------------------
# 2. 登录拿 JWT token
# ---------------------------------------------------------------------------
def login(session: requests.Session) -> str:
    url = f"{API_BASE_URL}/api/v1/users/login"
    print(f"[INFO] 登录: {url}, 用户: {EVAL_USERNAME}")
    resp = session.post(url, json={"username": EVAL_USERNAME, "password": EVAL_PASSWORD}, timeout=30)
    resp.raise_for_status()
    body = resp.json()
    if body.get("code") != 200:
        raise RuntimeError(f"登录失败: {body}")
    token = body["data"]["token"]
    return token


# ---------------------------------------------------------------------------
# 3. 调用评估接口
# ---------------------------------------------------------------------------
def call_eval_answer(
    session: requests.Session, token: str, question: str
) -> Dict[str, Any]:
    url = f"{API_BASE_URL}/api/v1/eval/answer"
    headers = {"Authorization": f"Bearer {token}"}
    payload = {"question": question, "topK": EVAL_TOP_K}
    resp = session.post(url, headers=headers, json=payload, timeout=600)
    resp.raise_for_status()
    body = resp.json()
    if body.get("code") != 200:
        raise RuntimeError(f"评估接口返回失败: {body}")
    return body["data"]


# ---------------------------------------------------------------------------
# 4. 读取测试集（兼容 contexts 字段为字符串列表/字面量列表）
# ---------------------------------------------------------------------------
def parse_contexts(value: Any) -> List[str]:
    if isinstance(value, list):
        return [str(x) for x in value]
    if not isinstance(value, str):
        return []
    s = value.strip()
    if not s:
        return []
    # 试着按 Python 字面量解析（Ragas 输出大概率是 ['c1', 'c2'] 形式）
    try:
        parsed = ast.literal_eval(s)
        if isinstance(parsed, list):
            return [str(x) for x in parsed]
    except Exception:
        pass
    return [s]


def load_testset() -> pd.DataFrame:
    df = pd.read_csv(TESTSET_PATH, encoding="utf-8-sig")
    if "question" not in df.columns or "ground_truth" not in df.columns:
        print("[ERROR] testset.csv 必须包含 question 和 ground_truth 列", file=sys.stderr)
        sys.exit(3)
    df = df[df["question"].notna() & df["ground_truth"].notna()].reset_index(drop=True)
    return df


# ---------------------------------------------------------------------------
# 5. 主流程
# ---------------------------------------------------------------------------
def main() -> None:
    df_testset = load_testset()
    print(f"[INFO] 测试集共 {len(df_testset)} 条问题")

    session = requests.Session()
    token = login(session)

    questions: List[str] = []
    answers: List[str] = []
    contexts_list: List[List[str]] = []
    ground_truths: List[str] = []

    failures: List[Dict[str, Any]] = []

    for _, row in tqdm(df_testset.iterrows(), total=len(df_testset), desc="调后端拿回答"):
        q = str(row["question"]).strip()
        gt = str(row["ground_truth"]).strip()
        if not q:
            continue
        try:
            data = call_eval_answer(session, token, q)
            answer = (data.get("answer") or "").strip()
            ctxs = data.get("contexts") or []
            if not answer:
                # 防止 Ragas 报错；用占位符表明系统未给出回答
                answer = "（系统未返回回答）"
            if not ctxs:
                ctxs = ["（未检索到相关上下文）"]
            questions.append(q)
            answers.append(answer)
            contexts_list.append([str(c) for c in ctxs])
            ground_truths.append(gt)
        except Exception as e:
            print(f"[WARN] 问题失败: {q[:30]}... -> {e}")
            failures.append({"question": q, "error": str(e)})
            time.sleep(1)

    if not questions:
        print("[ERROR] 所有问题都失败了，无法继续评估", file=sys.stderr)
        sys.exit(4)

    print(f"[INFO] 成功获取 {len(questions)} 条回答（失败 {len(failures)} 条）")

    dataset = Dataset.from_dict({
        "question": questions,
        "answer": answers,
        "contexts": contexts_list,
        "ground_truth": ground_truths,
    })

    print(f"[INFO] 初始化裁判 LLM: {JUDGE_MODEL}, Embedding: {EMBEDDING_MODEL}")
    judge_llm = ChatOpenAI(
        model=JUDGE_MODEL,
        api_key=DASHSCOPE_API_KEY,
        base_url=DASHSCOPE_BASE_URL,
        temperature=0,
        timeout=180,
    )
    judge_embeddings = OpenAIEmbeddings(
        model=EMBEDDING_MODEL,
        api_key=DASHSCOPE_API_KEY,
        base_url=DASHSCOPE_BASE_URL,
        # 禁用 tiktoken 预分词，DashScope 兼容接口只接受原始字符串
        check_embedding_ctx_length=False,
    )

    metrics = [
        faithfulness,
        answer_relevancy,
        context_precision,
        context_recall,
        answer_correctness,
    ]

    print("[INFO] 开始 Ragas 评估，可能要等一阵...")
    result = evaluate(
        dataset=dataset,
        metrics=metrics,
        llm=LangchainLLMWrapper(judge_llm),
        embeddings=LangchainEmbeddingsWrapper(judge_embeddings),
        raise_exceptions=False,
    )

    df_result = result.to_pandas()

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    csv_path = REPORT_DIR / f"eval_{timestamp}.csv"
    html_path = REPORT_DIR / f"eval_{timestamp}.html"
    df_result.to_csv(csv_path, index=False, encoding="utf-8-sig")

    metric_cols = [m.name for m in metrics if m.name in df_result.columns]
    means = df_result[metric_cols].mean(numeric_only=True)

    print()
    print("=" * 60)
    print(f"评估完成（共 {len(df_result)} 条），各指标均值：")
    print("=" * 60)
    for name, value in means.items():
        print(f"  {name:25s}: {value:.4f}")
    print("=" * 60)
    print(f"详细 CSV: {csv_path}")

    render_html_report(df_result, means, html_path, failures)
    print(f"可视化报告: {html_path}")


# ---------------------------------------------------------------------------
# 6. HTML 报告
# ---------------------------------------------------------------------------
def render_html_report(
    df: pd.DataFrame, means: pd.Series, output_path: Path, failures: List[Dict[str, Any]]
) -> None:
    def color(value: float) -> str:
        if pd.isna(value):
            return "#999"
        if value >= 0.8:
            return "#2e7d32"
        if value >= 0.6:
            return "#f9a825"
        return "#c62828"

    summary_rows = "".join(
        f"<tr><td>{name}</td><td style='color:{color(value)}'><b>{value:.4f}</b></td></tr>"
        for name, value in means.items()
    )

    detail_rows: List[str] = []
    metric_cols = [c for c in means.index if c in df.columns]
    for i, row in df.iterrows():
        cells = "".join(
            f"<td style='color:{color(row[c])}'>{row[c]:.3f}</td>"
            if pd.notna(row.get(c)) else "<td>-</td>"
            for c in metric_cols
        )
        ctxs_raw = row.get("contexts", [])
        if isinstance(ctxs_raw, str):
            ctxs_list: List[str] = [ctxs_raw]
        else:
            try:
                ctxs_list = [str(c) for c in list(ctxs_raw)]
            except TypeError:
                ctxs_list = []
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

    failure_html = ""
    if failures:
        rows = "".join(f"<li><b>{f['error']}</b><br>{f['question']}</li>" for f in failures)
        failure_html = f"<h2>失败问题（{len(failures)}）</h2><ul>{rows}</ul>"

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

{failure_html}

<p style="color:#888;font-size:12px">生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</p>
</body>
</html>"""
    output_path.write_text(html, encoding="utf-8")


if __name__ == "__main__":
    main()
