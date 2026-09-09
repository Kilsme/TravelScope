# Elasticsearch IK 分词器配置目录

## 说明

此目录用于挂载 IK 中文分词器到 Elasticsearch 容器中。

## 安装 IK 分词器

ES 容器启动后，手动安装 IK 分词器插件（版本需与 ES 版本一致）：

```bash
# 进入 ES 容器
docker exec -it travelscope-elasticsearch bash

# 在容器内安装 IK 分词器（9.0.2 版本）
./bin/elasticsearch-plugin install https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-9.0.2.zip

# 退出并重启容器
exit
docker-compose restart elasticsearch
```

## 验证安装

```bash
curl -X POST "http://localhost:9200/_analyze?pretty" -H 'Content-Type: application/json' -d'
{
  "analyzer": "ik_max_word",
  "text": "北京旅游攻略"
}'
```

## 分词模式

| 模式 | 说明 | 适用场景 |
|------|------|---------|
| `ik_smart` | 粗粒度分词 | 索引字段、减少索引体积 |
| `ik_max_word` | 细粒度分词 | 搜索字段、提高召回率 |

## RAG 双路检索中的使用

- **向量检索路径**: PostgreSQL pgvector（kNN 向量相似度）
- **全文检索路径**: Elasticsearch BM25（IK 中文分词 + 关键词匹配）
- **双路融合**: RRF（Reciprocal Rank Fusion）或加权分数融合
