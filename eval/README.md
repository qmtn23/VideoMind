# VideoMind Ragas 评估

使用 [Ragas](https://github.com/explodinggradients/ragas) 框架评估本项目的 RAG 流程（混合检索 + DeepSeek 回答）。

裁判 LLM、生成器 LLM、Embedding 全部使用 **DashScope qwen-max + text-embedding-v4**，无需 OpenAI Key。

---

## 环境要求

- Python 3.10+
- VideoMind 后端能正常启动（`start.ps1` 或 `mvn spring-boot:run`）
- Elasticsearch 中已经有解析后的文档块（即至少上传过 1 个文件并解析完成）
- 一个有效的 DashScope API Key（与项目主程序复用同一个即可）

---

## 一次性配置

```powershell
cd D:\idea-workspace\VideoMind\eval

# 创建虚拟环境（可选但推荐）
python -m venv .venv
.\.venv\Scripts\Activate.ps1

# 安装依赖
pip install -r requirements.txt

# 复制并填写 .env
copy .env.example .env
# 然后编辑 .env，至少填入：
#   DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxxxxxx
#   ES_PASSWORD=PaiSmart2025（默认值如果你没改的话）
```

---

## 执行步骤

### 第一步：往项目里上传若干文档（5-10 个）

通过前端 `http://localhost:9527` 登录 admin，上传几个 PDF / 视频，等系统解析完成（前端文档列表显示「已完成」）。这一步是为了让 ES 索引里有足够的语料供 TestsetGenerator 使用。

### 第二步：生成测试集

```powershell
python 01_generate_testset.py
```

约 **5-15 分钟**，会从 ES 拉最多 80 个 chunk 作为素材，让 qwen-max 自动生成 20 条 Q&A 评估问题，输出 `testset.csv`。

可选：人工抽查 `testset.csv`，删掉低质量问题（CSV 用 Excel 直接打开即可）。

### 第三步：跑评估

```powershell
python 02_run_evaluation.py
```

脚本会：
1. 用 admin 账号登录后端拿 JWT token
2. 逐条把 question 发给 `POST /api/v1/eval/answer`，等待真实 RAG pipeline 给出 `{answer, contexts}`
3. 把全部结果喂给 Ragas 计算 5 个指标：
   - **faithfulness** —— 回答是否忠于检索内容（防幻觉）
   - **answer_relevancy** —— 回答是否切题
   - **context_precision** —— top-k 排序质量
   - **context_recall** —— 检索是否召回了关键信息
   - **answer_correctness** —— 与标准答案的语义+事实一致性
4. 输出：
   - `report/eval_<timestamp>.csv` 每条详细得分
   - `report/eval_<timestamp>.html` 可视化报告（建议直接用浏览器打开）

约 **10-30 分钟**（取决于 LLM 速度和题目数量）。

---

## 指标解读速查

| 指标 | 越高代表 | 低分排查方向 |
|---|---|---|
| faithfulness | 回答没有幻觉 | LLM 在编内容；检查 prompt 中 system 指令是否强制"只能基于参考"|
| answer_relevancy | 回答切题 | LLM 跑题；检查 prompt 是否清晰；考虑换更强模型 |
| context_precision | 检索结果排序好 | top-k 中混入了无关 chunk；调整 `topK`、加 reranker |
| context_recall | 检索到了所有关键信息 | 漏召；调大 `topK`、改 chunk 大小、增加召回路径 |
| answer_correctness | 回答和标准答案一致 | 综合性问题；先看是 retrieval 拉胯还是 generation 拉胯 |

---

## 常见问题

| 问题 | 解决办法 |
|---|---|
| `索引 knowledge_base 不存在` | 先在前端上传至少一个文档，并等待解析完成 |
| `登录失败` | 检查 `.env` 里 `EVAL_AUTH_USERNAME/PASSWORD` 是否正确，默认 admin/admin123 |
| `连接 ES 失败` | 检查 Docker 中 ES 容器是否启动；改 `ES_URL`、`ES_PASSWORD` |
| Ragas 评估卡很久 | qwen-max 单次约 2-5 秒，30 题 × 5 指标 ≈ 15-25 分钟，正常现象 |
| `429 Too Many Requests` | DashScope 触发限流；脚本会重试，或减小 `TESTSET_SIZE` |
| 某些指标为 NaN | 通常是裁判 LLM 输出格式不规范；可在 `.env` 临时把 `JUDGE_MODEL` 换成 `qwen-plus` 或重试 |
| 想换更便宜的模型 | 在 `.env` 把 `qwen-max` 改为 `qwen-plus` 或 `qwen-turbo`，质量略降但便宜 4-10 倍 |

---

## 接口约定（供后端开发参考）

`POST /api/v1/eval/answer`，需 JWT 鉴权（`USER` 或 `ADMIN` 角色）

请求：
```json
{ "question": "视频里讲了什么？", "topK": 5 }
```

响应：
```json
{
  "code": 200,
  "data": {
    "question": "...",
    "answer": "...",
    "contexts": ["chunk1", "chunk2"],
    "retrievalTimeMs": 120,
    "generationTimeMs": 8500
  }
}
```

底层复用 `HybridSearchService.searchWithPermission` + `DeepSeekClient.getResponse`，等价于关闭流式输出的对话流程。
