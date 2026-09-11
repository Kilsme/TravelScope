---
name: flight-ticket-query
description: 查询两城市间的实时航班与机票价格（飞常准 MCP 服务），返回真实航班号、起降时间、舱位票价，用于城际交通规划
---

# 飞机票查询技能

## 何时使用

- 跨城（尤其远距离，如 1000 公里以上）行程的大交通规划
- 用户询问两地间的航班、机票价格或某个航班的实时状态

## 可用工具（MCP：飞常准）

工具名前缀为 `mcp__variflight__`，常用调用链如下：

1. `mcp__variflight__getTodayDate`：获取今天日期。所有日期参数如果用户给的是"明天"等相对日期，先调用此工具解析
2. `mcp__variflight__getFlightPriceByCities`：按城市查逐航班、逐舱位实时票价（推荐首选）
   - `dep_city` / `arr_city`：出发/到达城市 IATA 三字码（如 "BJS"、"SHA"），必填
   - `dep_date`：出发日期，`yyyy-MM-dd` 格式，必填
3. `mcp__variflight__searchFlightsByDepArr`：按出发/到达查直飞航班（航班号、起降时间、机型）
   - 机场码用 `dep`/`arr`，城市码用 `depcity`/`arrcity`；**同一侧不得城市码与机场码混用**
   - `date`：`yyyy-MM-dd`，必填
4. `mcp__variflight__searchFlightsByNumber`：按航班号查询（如 "MU2157"，须含航司代码）
5. `mcp__variflight__flightHappinessIndex`：航班舒适度（准点率、机型、舱位、餐食等），适合已确定航班号后对比
6. `mcp__variflight__getFlightTransferInfo`：两地无直飞时的中转方案

## 城市 IATA 三字码对照（常用）

北京=BJS、上海=SHA、广州=CAN、深圳=SZX、成都=CTU、杭州=HGH、重庆=CKG、
西安=SIA、南京=NKG、武汉=WUH、长沙=CSX、厦门=XMN、昆明=KMG、青岛=TAO、
三亚=SYX、海口=HAK、哈尔滨=HRB、沈阳=SHE、大连=DLC、郑州=CGO、天津=TSN、
贵阳=KWE、南宁=NNG、兰州=LHW、乌鲁木齐=URC、拉萨=LXA

（工具只认 IATA 三字码，不收中文；表外城市若不确定，向用户确认后按 IATA 码查询）

## 使用要点

1. 查票价用 `getFlightPriceByCities`（逐舱位结构化数据）；只需航班时刻用 `searchFlightsByDepArr`
2. 大城市有多个机场（如北京首都 PEK/大兴 PKX、上海虹桥 SHA/浦东 PVG），票价结果中会体现具体起降机场，向用户说明便于结合住宿位置选择
3. 与火车方案对比时说明：飞机需提前 1.5-2 小时到机场，门到门时间未必比高铁短
4. 数据为飞常准实时数据，价格实时波动，**购票请以航司官网或订票平台为准**
5. 工具报错时如实告知，不要编造航班号、票价或时刻

## 输出建议

- 推荐 1-3 个航班：航班号、航司、起降机场与时间、飞行时长、主要舱位票价
- 列出起降机场及理由（距市区/住宿点的远近）
