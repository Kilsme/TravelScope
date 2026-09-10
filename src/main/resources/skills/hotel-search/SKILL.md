---
name: hotel-search
description: 搜索城市酒店与目的地周边 POI（高德地图 API），用于住宿选址与餐饮、商场等周边配套查询
---

# 酒店搜索技能

## 何时使用

- 行程规划中确定住宿地点
- 查询景点/酒店周边的餐厅、商场等配套设施

## 可用工具

- `searchHotels(city, keyword, pageSize)`：城市酒店搜索。keyword 可传品牌名（如 "如家"、"全季"）缩小范围，传空字符串则搜全城酒店
- `searchNearbyPois(location, type, radius)`：周边 POI 搜索。location 为经纬度（先用 `geocode` 或从酒店/景点结果中获取），type 支持：餐饮服务/风景名胜/住宿服务/购物服务/交通设施 等

## 使用要点

1. 选址逻辑：先根据行程重心（景点集中区/交通枢纽）用 `searchHotels` 找候选，再用 `searchNearbyAttractions` 或 `searchNearbyPois` 验证周边便利度
2. `pageSize` 建议 5-10，给用户留选择空间又避免信息过载
3. 工具返回的是 POI 信息（名称、地址、电话、经纬度），**不含实时房价**——房价需标注「以预订平台为准」
4. 返回 `error` 字段时如实告知，不要编造酒店

## 输出建议

- 推荐 2-3 家候选酒店，说明各自位置优势（近哪个景点/地铁站）
- 附带周边餐饮建议（来自 `searchNearbyPois` 的真实数据）
