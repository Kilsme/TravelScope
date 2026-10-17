---
name: flight-ticket-query
description: 查询两城市间的机票（飞猪 queryFlightTickets 主用 + 飞常准 MCP 对照），返回真实航班号、起降时间、舱位票价与预订链接，结果标注来源
---

# 飞机票查询技能

## 何时使用

- 跨城（尤其远距离，如 1000 公里以上）行程的大交通规划
- 用户询问两地间的航班、机票价格或某个航班的实时状态

## 可用工具

### 飞猪源（主用）：`queryFlightTickets(origin, destination, depDate)`

- `origin` / `destination`：出发/到达城市或机场（中文即可，如 "上海"、"北京"）
- `depDate`：出发日期 `YYYY-MM-DD`（可空=当天）
- 返回 flights[]：票价 price、segments[]（航班号 flightNo、航司 airline、起降机场、时刻、舱位 cabinClass、时长）、预订链接 jumpUrl、`source=飞猪`

### 飞常准对照源（降级/核验，MCP）：工具名前缀 `mcp__variflight__`

1. `mcp__variflight__getTodayDate`：获取今天日期（相对日期解析）
2. `mcp__variflight__getFlightPriceByCities`：按城市查逐航班逐舱位实时票价（dep_city/arr_city 为 IATA 三字码）
3. `mcp__variflight__searchFlightsByDepArr`：按出发/到达查直飞航班
4. `mcp__variflight__searchFlightsByNumber`：按航班号查询
5. `mcp__variflight__flightHappinessIndex`：航班舒适度（准点率等）
6. `mcp__variflight__getFlightTransferInfo`：无直飞时的中转方案

## 城市 IATA 三字码对照（飞常准工具常用）

北京=BJS、上海=SHA、广州=CAN、深圳=SZX、成都=CTU、杭州=HGH、重庆=CKG、
西安=SIA、南京=NKG、武汉=WUH、长沙=CSX、厦门=XMN、昆明=KMG、青岛=TAO、
三亚=SYX、海口=HAK、哈尔滨=HRB、沈阳=SHE、大连=DLC、郑州=CGO、天津=TSN、
贵阳=KWE、南宁=NNG、兰州=LHW、乌鲁木齐=URC、拉萨=LXA

（飞常准工具只认 IATA 三字码；飞猪 queryFlightTickets 直接收中文）

## 使用要点

1. **主用飞猪 `queryFlightTickets`**（中文直查、含票价与预订链接）；需要逐舱位明细/准点率时用飞常准对照
2. 大城市有多个机场（如北京首都 PEK/大兴 PKX、上海虹桥 SHA/浦东 PVG），结果中体现具体起降机场，向用户说明便于结合住宿位置选择
3. 与火车方案对比时说明：飞机需提前 1.5-2 小时到机场，门到门时间未必比高铁短
4. 价格实时波动，**购票请以航司官网或订票平台为准**
5. **来源标注**：结果保留来源标签（【飞猪】/【飞常准】）；工具报错时如实告知，不要编造航班号、票价或时刻

## 输出建议

- 推荐 1-3 个航班：航班号、航司、起降机场与时间、飞行时长、主要舱位票价，标注来源
- 列出起降机场及理由（距市区/住宿点的远近）
