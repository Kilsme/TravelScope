---
name: route-planning
description: 市内两点间真实通勤路线查询（高德路径规划 API），返回驾车/公交的时长与距离，用于行程排线时计算相邻 POI 通勤、检验单日总通勤与地理聚簇
---

# 路线规划技能

## 何时使用

- 排线时计算相邻 POI 的真实通勤（严禁直线距离估算）
- 检验分日方案：单日总通勤是否超限、是否存在跨区折返
- 核验行程草案中声明的通勤时长（质检回炉场景）

## 可用工具

- `getDrivingRoute(origin, destination)`：驾车路线
- `getTransitRoute(origin, destination, city)`：公交/地铁路线（市内通勤优先）
- `geocode(address)`：地址 → 经纬度（清单缺坐标时补全）

## 参数格式

- `origin` / `destination`：`经度,纬度`（如 `120.113729,30.222641`）——
  poi_shortlist.md 中的「坐标」行可直接使用，无需转换
- `city`：城市名（如 `杭州`），getTransitRoute 必填

## 返回格式与单位（重要）

- **getDrivingRoute** 返回：`{"distance": "<米>", "duration": "<秒>", "strategy": "...", "steps": [...]}`
- **getTransitRoute** 返回：`{"count": N, "routes": [{"duration": "<秒>", "distance": "<米>", "cost": "<元>", "walking_distance": "<米>", "bus_lines": [...]}]}`（前 3 条方案，取首条即可）
- **单位换算**：`duration` 是**秒**、`distance` 是**米**——写入文档前换算：
  秒 ÷ 60 = 分钟（如 900 → 15 分钟）；米 ÷ 1000 = 公里。
  【常见错误】把秒数当分钟写（900 秒写成"900 分钟"）

## 使用要点

1. 只算**同日相邻 POI** 的通勤（两两调用），跨日不必算
2. 相互独立的查询同一轮并行发起；有依赖的（先 geocode 补坐标再算路线）按顺序
3. 单日通勤累加 ≤ 90 分钟为达标线，超限说明分日需要调整
4. 市内跨区优先 getTransitRoute（含步行距离与票价信息更贴近游客）；短距离/郊区用 getDrivingRoute
5. 返回 `{"error": "..."}` 时如实标注「通勤待确认」，不要估算顶替
