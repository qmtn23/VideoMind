"""
01_generate_testset.py

从 Elasticsearch 的 knowledge_base 索引拉取若干文档块（chunks）作为源材料，
调用 Ragas TestsetGenerator 自动生成评估测试集，输出 testset.csv。

LLM 与 Embedding 全部使用阿里云 DashScope 兼容接口（OpenAI SDK 风格），
无需 OpenAI API Key。

执行方式：
    cd eval
    pip install -r requirements.txt
    cp .env.example .env  # 填入 DASHSCOPE_API_KEY 等
    python 01_generate_testset.py
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import List

import pandas as pd
from dotenv import load_dotenv
from elasticsearch import Elasticsearch
from langchain_core.documents import Document
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from ragas.testset.generator import TestsetGenerator
from ragas.testset.evolutions import simple, reasoning, multi_context

# ---------------------------------------------------------------------------
# 1. 加载环境变量
# ---------------------------------------------------------------------------
load_dotenv()

DASHSCOPE_API_KEY = os.getenv("DASHSCOPE_API_KEY", "").strip()
DASHSCOPE_BASE_URL = os.getenv(
    "DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"
)
GENERATOR_MODEL = os.getenv("GENERATOR_MODEL", "qwen-max")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "text-embedding-v4")

ES_URL = os.getenv("ES_URL", "http://localhost:9200")
ES_USERNAME = os.getenv("ES_USERNAME", "elastic")
ES_PASSWORD = os.getenv("ES_PASSWORD", "PaiSmart2025")
ES_INDEX = os.getenv("ES_INDEX", "knowledge_base")

TESTSET_SIZE = int(os.getenv("TESTSET_SIZE", "20"))
MAX_CHUNKS_FOR_TESTSET = int(os.getenv("MAX_CHUNKS_FOR_TESTSET", "80"))

# 可选：用逗号分隔的 fileMd5 列表，仅生成基于这些文件的测试集（白名单）
INCLUDE_FILE_MD5_LIST = [
    s.strip() for s in os.getenv("INCLUDE_FILE_MD5_LIST", "").split(",") if s.strip()
]
# 可选：用逗号分隔的 fileMd5 列表，排除这些文件（黑名单）
EXCLUDE_FILE_MD5_LIST = [
    s.strip() for s in os.getenv("EXCLUDE_FILE_MD5_LIST", "").split(",") if s.strip()
]

OUTPUT_PATH = Path(__file__).parent / "testset.csv"

if not DASHSCOPE_API_KEY:
    print("[ERROR] 环境变量 DASHSCOPE_API_KEY 未设置，请先填好 .env", file=sys.stderr)
    sys.exit(1)


# ---------------------------------------------------------------------------
# 2. 从 Elasticsearch 拉文档块
# ---------------------------------------------------------------------------
def fetch_chunks_from_es(limit: int) -> List[Document]:
    print(f"[INFO] 连接 Elasticsearch: {ES_URL}, 索引: {ES_INDEX}")
    es = Elasticsearch(
        ES_URL,
        basic_auth=(ES_USERNAME, ES_PASSWORD) if ES_USERNAME else None,
        verify_certs=False,
    )
    if not es.indices.exists(index=ES_INDEX):
        print(f"[ERROR] 索引 {ES_INDEX} 不存在；请先在系统里上传并解析至少一个文件", file=sys.stderr)
        sys.exit(2)

    if INCLUDE_FILE_MD5_LIST:
        print(f"[INFO] 仅包含文件: {INCLUDE_FILE_MD5_LIST}")
        query = {"terms": {"fileMd5": INCLUDE_FILE_MD5_LIST}}
    elif EXCLUDE_FILE_MD5_LIST:
        print(f"[INFO] 排除文件: {EXCLUDE_FILE_MD5_LIST}")
        query = {
            "bool": {
                "must_not": [{"terms": {"fileMd5": EXCLUDE_FILE_MD5_LIST}}]
            }
        }
    else:
        query = {"match_all": {}}

    resp = es.search(
        index=ES_INDEX,
        size=limit,
        query=query,
        _source=["fileMd5", "chunkId", "textContent", "userId", "orgTag", "isPublic"],
    )

    hits = resp.get("hits", {}).get("hits", [])
    print(f"[INFO] 命中 {len(hits)} 个 chunk")

    documents: List[Document] = []
    for h in hits:
        src = h.get("_source", {})
        text = (src.get("textContent") or "").strip()
        if not text:
            continue
        metadata = {
            "fileMd5": src.get("fileMd5"),
            "chunkId": src.get("chunkId"),
            "userId": src.get("userId"),
            "orgTag": src.get("orgTag"),
            "isPublic": src.get("isPublic", False),
            # langchain/ragas 会用到 source/filename 字段
            "filename": f"{src.get('fileMd5')}_{src.get('chunkId')}",
            "source": f"{src.get('fileMd5')}#{src.get('chunkId')}",
        }
        documents.append(Document(page_content=text, metadata=metadata))

    if not documents:
        print("[ERROR] 索引内没有可用的 textContent，无法生成测试集", file=sys.stderr)
        sys.exit(3)

    return documents


# ---------------------------------------------------------------------------
# 3. 配置 DashScope LLM / Embedding（OpenAI 兼容接口）
# ---------------------------------------------------------------------------
def build_llm() -> ChatOpenAI:
    return ChatOpenAI(
        model=GENERATOR_MODEL,
        api_key=DASHSCOPE_API_KEY,
        base_url=DASHSCOPE_BASE_URL,
        temperature=0.3,
        timeout=120,
    )


def build_embeddings() -> OpenAIEmbeddings:
    # check_embedding_ctx_length=False：禁用 tiktoken 预分词，
    # 否则 langchain-openai 会把文本编码成 token 整数数组发送，
    # DashScope 兼容接口只接受原始字符串，会报 "contents is neither str nor list of str"。
    return OpenAIEmbeddings(
        model=EMBEDDING_MODEL,
        api_key=DASHSCOPE_API_KEY,
        base_url=DASHSCOPE_BASE_URL,
        check_embedding_ctx_length=False,
    )


# ---------------------------------------------------------------------------
# 4. 主流程
# ---------------------------------------------------------------------------
def main() -> None:
    print(f"[INFO] 拉取最多 {MAX_CHUNKS_FOR_TESTSET} 个 chunk 作为生成素材")
    documents = fetch_chunks_from_es(MAX_CHUNKS_FOR_TESTSET)

    print(f"[INFO] 初始化 LLM: {GENERATOR_MODEL}, Embedding: {EMBEDDING_MODEL}")
    llm = build_llm()
    embeddings = build_embeddings()

    print(f"[INFO] 调用 Ragas TestsetGenerator 生成 {TESTSET_SIZE} 条评估问题")
    generator = TestsetGenerator.from_langchain(
        generator_llm=llm,
        critic_llm=llm,
        embeddings=embeddings,
    )

    distributions = {
        simple: 0.5,
        reasoning: 0.25,
        multi_context: 0.25,
    }

    testset = generator.generate_with_langchain_docs(
        documents=documents,
        test_size=TESTSET_SIZE,
        distributions=distributions,
        with_debugging_logs=False,
        raise_exceptions=False,
    )

    df = testset.to_pandas()
    cols = [c for c in ["question", "ground_truth", "contexts", "evolution_type", "metadata"]
            if c in df.columns]
    df = df[cols]

    df.to_csv(OUTPUT_PATH, index=False, encoding="utf-8-sig")
    print(f"[OK] 测试集已保存: {OUTPUT_PATH}（{len(df)} 条）")
    print(df.head(3).to_string(index=False))
    print()
    print("[NEXT] 请人工抽查 testset.csv，删掉低质量问题；")
    print("       然后执行: python 02_run_evaluation.py")


if __name__ == "__main__":
    main()
