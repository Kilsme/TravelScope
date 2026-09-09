#!/bin/bash
## ============================================================================
## TravelScope - Elasticsearch 索引初始化脚本
## ============================================================================
## 用法：
##   1. 先启动 Elasticsearch: docker-compose up -d elasticsearch
##   2. 等待 ES 就绪（约30秒）
##   3. 运行此脚本: bash src/main/resources/elasticsearch/init-index.sh
## ============================================================================

ES_URL="${ES_URL:-http://localhost:9200}"
INDEX_NAME="${INDEX_NAME:-document_chunks_fulltext}"
MAPPING_FILE="$(dirname "$0")/index-mapping.json"

echo "========================================"
echo " TravelScope ES 索引初始化"
echo " ES URL:       $ES_URL"
echo " 索引名称:     $INDEX_NAME"
echo " Mapping 文件: $MAPPING_FILE"
echo "========================================"

# 等待 ES 就绪
echo ">>> 等待 Elasticsearch 就绪..."
for i in $(seq 1 30); do
    if curl -s "$ES_URL/_cluster/health?wait_for_status=yellow&timeout=5s" | grep -q '"status"'; then
        echo "    Elasticsearch 已就绪"
        break
    fi
    echo "    等待中... ($i/30)"
    sleep 5
done

# 检查索引是否已存在
echo ">>> 检查索引 $INDEX_NAME 是否存在..."
if curl -s "$ES_URL/$INDEX_NAME" | grep -q "$INDEX_NAME"; then
    echo "    索引已存在，如需重建请先删除："
    echo "    curl -X DELETE $ES_URL/$INDEX_NAME"
    echo "    然后重新运行此脚本"
    exit 1
fi

# 创建索引
echo ">>> 创建索引 $INDEX_NAME..."
curl -s -X PUT "$ES_URL/$INDEX_NAME" \
    -H 'Content-Type: application/json' \
    -d @"$MAPPING_FILE" | jq .

if [ $? -eq 0 ]; then
    echo "    索引创建成功!"
else
    echo "    索引创建失败!"
    exit 1
fi

# 验证索引
echo ">>> 验证索引映射..."
curl -s "$ES_URL/$INDEX_NAME/_mapping" | jq .

echo ""
echo "========================================"
echo " 初始化完成!"
echo "========================================"
echo ""
echo "索引结构说明："
echo "  - content:  全文字段（IK 分词器，用于 BM25 检索）"
echo "  - embedding: 向量字段（dense_vector，1024维，用于 kNN 检索）"
echo "  - doc_id:   文档ID（keyword，用于过滤）"
echo "  - chunk_id: 块序号（integer）"
echo "  - title:    文档标题（text + keyword）"
echo "  - file_type:文件类型（keyword）"
echo ""
echo "RAG 双路检索路径："
echo "  向量检索: pgvector (PostgreSQL) → kNN 向量相似度"
echo "  全文检索: Elasticsearch → BM25 关键词匹配"
echo "  融合算法: RRF / 加权分数"
