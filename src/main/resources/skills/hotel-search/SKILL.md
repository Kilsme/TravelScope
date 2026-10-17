---
name: hotel-search
description: 搜索城市酒店（飞猪+高德双源，结果标注来源）与目的地周边 POI（高德），用于住宿选址与餐饮、商场等周边配套查询
---

# 酒店搜索技能

## 何时使用

- 行程规划中确定住宿地点
- 查询景点/酒店周边的餐厅、商场等配套设施

## 可用工具

- `searchHotels(city, keyword, pageSize)`：城市酒店搜索（**飞猪+高德双源**）。keyword 可传品牌名（如 "如家"、"全季"）缩小范围，传空字符串则搜全城酒店。返回 hotels[] 每条含 `source`（飞猪/高德）；飞猪条目带参考价 price、星级 star 与预订链接 detailUrl
- `searchNearbyPois(location, type, radius)`：周边 POI 搜索（仅高德）。location 为经纬度（先用 `geocode` 或从酒店/景点结果中获取），type 支持：餐饮服务/风景名胜/住宿服务/购物服务/交通设施 等

## 使用要点

1. 选址逻辑：先根据行程重心（景点集中区/交通枢纽）用 `searchHotels` 找候选，再用 `searchNearbyAttractions` 或 `searchNearbyPois` 验证周边便利度
2. `pageSize` 建议 5-10，给用户留选择空间又避免信息过载
3. 飞猪条目的 price 为平台参考价，标注「以预订页实时价为准」；高德条目无价格
4. **来源标注**：给用户的结果必须保留每条的来源标签（【飞猪】/【高德】）；同一酒店两源信息不一致时以飞猪为准并注明
5. 返回 `error` 字段时如实告知，不要编造酒店

## 输出建议

- 推荐 2-3 家候选酒店，说明各自位置优势（近哪个景点/地铁站），标注来源
- 附带周边餐饮建议（来自 `searchNearbyPois` 的真实数据）
