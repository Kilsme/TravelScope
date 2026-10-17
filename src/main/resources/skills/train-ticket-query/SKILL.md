---
name: train-ticket-query
description: 查询两城市间的火车票（飞猪 queryTrainTickets 主用 + 12306 MCP 对照），返回真实车次、车站、时间、历时、票价与预订链接，结果标注来源
---

# 火车票查询技能

## 何时使用

- 跨城行程中规划城际大交通（火车/高铁）
- 用户直接询问两地间的火车方案或车次余票

## 可用工具

### 飞猪源（主用）：`queryTrainTickets(origin, destination, depDate)`

- `origin` / `destination`：出发/到达城市或车站（中文即可，如 "上海"、"杭州"）
- `depDate`：出发日期 `YYYY-MM-DD`（可空=当天；用户说「明天」等相对日期时先调 `mcp__c12306__get-current-date` 解析）
- 返回 trains[]：价格 price、journeyType（直达/中转）、segments[]（车次 trainNo、起止站、时刻、座席、时长）、预订链接 jumpUrl、`source=飞猪`

### 12306 对照源（降级/核验，MCP）：工具名前缀 `mcp__c12306__`

1. `mcp__c12306__get-current-date`：获取今天日期（相对日期解析）
2. `mcp__c12306__get-tickets`：实时余票（date/fromStation/toStation/trainFilterFlags/sortFlag，format 传 "json"）
3. `mcp__c12306__get-interline-tickets`：无直达时的中转余票
4. `mcp__c12306__get-train-route-stations`：某车次经停站

## 使用要点

1. **主用飞猪 `queryTrainTickets`**（含价格与预订链接）；需要实时余票/席别明细时用 12306 对照
2. 优先推荐直达车次；无直达或余票紧张时查中转，并向用户说明中转站
3. 用户没说出发时间时，优先白天时段车次并说明历时
4. 票价实时变动，**购票请以 12306 或飞猪预订页为准**——输出时保留这一提示
5. **来源标注**：结果保留来源标签（【飞猪】/【12306】）；工具报错时如实告知，不要编造车次或余票

## 输出建议

- 推荐 1-3 个车次：车次、出发站/到达站、出发/到达时间、历时、主要席别票价，标注来源
- 与飞机方案对比时，结合门到门总耗时（火车站通常比机场更靠近市区）给出建议
