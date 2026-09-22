# TravelScope - 智能旅游助手

基于 **AgentScope Java 2.0** + **Spring Boot 3** + **Java 21** 构建的智能旅游助手。

## 功能特性

- **酒店查询** - 基于高德地图 API 的 POI 搜索
- **天气查询** - 基于和风天气 API 的实时天气
- **知识库 RAG** - 文档上传 + 向量检索（PostgreSQL + pgvector）
- **多轮对话** - 通义千问（DashScope）模型驱动
- **文档存储** - MinIO 对象存储

## 技术栈

| 组件 | 技术选型 | 版本 |
|------|---------|------|
| 语言 | Java | 21 |
| Agent 框架 | AgentScope Java | 2.0.3 |
| Web 框架 | Spring Boot | 3.3.5 |
| 业务数据库 | PostgreSQL | 本地已安装 |
| 向量数据库 | PostgreSQL + pgvector | 同上 |
| 缓存 | Redis | 7 (Docker) |
| 对象存储 | MinIO | Latest (Docker) |
| LLM 模型 | 通义千问 (DashScope) | qwen-plus |
| Embedding | DashScope text-embedding-v3 | 1024维 |
| 地图 API | 高德地图 | Web API |
| 天气 API | 和风天气 | DevAPI |

## 项目结构

```
TravelScope/
├── pom.xml                          # Maven 依赖配置
├── docker-compose.yml               # Redis + MinIO 容器编排
├── .env.example                     # 环境变量示例
├── src/
│   └── main/
│       ├── java/com/travelscope/
│       │   ├── TravelScopeApplication.java   # 启动类
│       │   ├── config/              # 配置类
│       │   ├── controller/          # REST API 控制器
│       │   ├── service/             # 业务服务层
│       │   ├── repository/          # 数据访问层
│       │   ├── entity/              # JPA 实体
│       │   ├── dto/                 # 数据传输对象
│       │   ├── agent/               # AgentScope Agent
│       │   │   └── tools/           # Agent 工具定义
│       │   └── common/              # 公共组件
│       └── resources/
│           ├── application.yml      # 主配置文件
│           ├── schema.sql           # 数据库建表脚本
│           └── data.sql             # 初始化数据
```

## 快速开始

### 1. 环境准备

```bash
# 1) 启动 Redis 和 MinIO
docker-compose up -d

# 2) 创建数据库（PostgreSQL 已本地安装）
psql -U root -d postgres -c "CREATE DATABASE travelscope;"

# 3) 安装 pgvector 扩展
psql -U root -d travelscope -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 4) 执行建表脚本
psql -U root -d travelscope -f src/main/resources/schema.sql
```

### 2. 配置 API Key

复制 `.env.example` 为 `.env`，填入你的 API Key：

```bash
cp .env.example .env
```

或直接在 IDE 高级设置 / 系统环境变量中配置：

| 环境变量 | 说明 | 获取地址 |
|---------|------|---------|
| `API_KEY` | 通义千问 API Key | https://dashscope.console.aliyun.com/apiKey |
| `AMAP_WEB_API_KEY` | 高德地图 Web API Key | https://lbs.amap.com/ |
| `WEATHER_API_HOST` | 和风天气 API Host | https://dev.qweather.com/ |
| `WEATHER_API_KEY` | 和风天气 API Key | https://dev.qweather.com/ |

### 3. 启动项目

```bash
mvn spring-boot:run
```

或直接在 IDE 中运行 `TravelScopeApplication.java`。

### 4. 访问

- API: http://localhost:8080/api
- Actuator: http://localhost:8080/api/actuator/health
- MinIO Console: http://localhost:9001 (root / root123456)

## 数据库表结构

| 表名 | 说明 |
|------|------|
| `users` | 用户表 |
| `conversations` | 会话表 |
| `messages` | 消息表 |
| `documents` | 文档表 |
| `document_chunks` | 文档块表（向量检索，兼容 AgentScope PgVectorStore） |

## AgentScope 工具

| 工具 | 说明 | API |
|------|------|-----|
| 酒店查询 | POI 搜索 | 高德地图 |
| 天气查询 | 实时天气 | 和风天气 |
| 知识库检索 | RAG 向量检索 | AgentScope + pgvector |
