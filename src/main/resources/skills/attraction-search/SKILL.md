---
name: attraction-search
description: 搜索城市景点（飞猪+高德双源，结果标注来源）与周边景点（高德），用于行程的景点选择、游览路线串联与每日安排
---

# 景点搜索技能

## 何时使用

- 行程规划中选择每日游览景点
- 以某地点（酒店/某景点）为中心串联周边游览路线

## 可用工具

- `searchPois(city, keyword, pageSize)`：城市景点搜索（**飞猪+高德双源**，POI 初查统一入口，Planner 直调）。keyword 传空字符串搜热门景点；传主题词（如 "博物馆"、"古镇"、"公园"）做主题筛选。返回 attractions[] 每条含 `source`（飞猪/高德）；飞猪条目带景区等级 poiLevel、榜单 listRank、票务 ticketInfo 与预订链接 jumpUrl
- `searchNearbyAttractions(location, radius, pageSize)`：周边景点搜索（仅高德）。radius 建议 1000-5000 米

## 使用要点

1. 经典流程：`searchPois` 拿到城市热门景点列表 → 按地理位置聚类分组 → 同组景点安排在同一天 → 用 `searchNearbyAttractions` 补充同区域的次要景点
2. 结果中 `location` 字段是经纬度，可直接作为 `getTransitRoute` / `searchNearbyPois` 的入参，实现"景点→交通→餐饮"串联
3. 结果可能带 `rating`（评分/景区等级）和 `cost`（参考消费），优先推荐高分景点；没有评分字段时不要虚构
4. **来源标注**：给用户的结果必须保留每条的来源标签（【飞猪】/【高德】）；飞猪条目的 ticketInfo.price 为平台参考票价，标注「以预订页为准」
5. 返回 `error` 字段时如实告知，不要编造景点

## 输出建议

- 每日安排 2-4 个景点，注明名称、地址与来源
- 标注景点间的大致方位关系（来自坐标），方便后续排交通
- 区分「必去」（高分/知名）与「可选」（时间充裕时去）
