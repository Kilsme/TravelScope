package com.travelscope.service.poi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 组合来源 ID、名称归一化和坐标距离，返回可解释且顺序稳定的实体簇。 */
public final class PoiEntityResolver {
    private static final double EARTH_RADIUS_METERS = 6_371_000d;

    public Resolution resolve(List<PoiInput> inputs, String city) {
        Objects.requireNonNull(inputs, "inputs");
        List<Node> nodes = inputs.stream().map(input -> new Node(input,
                        PoiNameNormalizer.normalize(input.name(), city)))
                .sorted(Comparator.comparing(node -> node.input().candidateId()))
                .toList();
        UnionFind union = new UnionFind(nodes.size());
        Map<Pair, MergeReason> reasons = new LinkedHashMap<>();
        List<Relation> relations = new ArrayList<>();

        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                Node left = nodes.get(i);
                Node right = nodes.get(j);
                MergeReason sourceReason = sameSourceReason(left, right);
                if (sourceReason != null) {
                    union.union(i, j);
                    reasons.put(new Pair(i, j), sourceReason);
                    continue;
                }
                if (isParentChild(left.name().normalizationKey(), right.name().normalizationKey())) {
                    relations.add(new Relation(left.input().candidateId(), right.input().candidateId(),
                            RelationType.PARENT_CHILD));
                    continue;
                }
                MergeReason reason = nameCoordinateReason(left, right);
                if (reason != null) {
                    union.union(i, j);
                    reasons.put(new Pair(i, j), reason);
                }
            }
        }

        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            groups.computeIfAbsent(union.find(i), ignored -> new ArrayList<>()).add(i);
        }
        List<ResolvedEntity> entities = groups.values().stream().map(indices -> {
            List<String> merged = indices.stream().map(index -> nodes.get(index).input().candidateId())
                    .sorted().toList();
            String canonicalId = merged.get(0);
            MergeReason reason = indices.size() == 1 ? MergeReason.NONE
                    : strongestReason(indices, reasons);
            return new ResolvedEntity(canonicalId, merged, reason);
        }).sorted(Comparator.comparing(ResolvedEntity::canonicalId)).toList();
        relations.sort(Comparator.comparing(Relation::leftId).thenComparing(Relation::rightId));
        return new Resolution(entities, List.copyOf(relations));
    }

    private static MergeReason sameSourceReason(Node left, Node right) {
        String leftSource = left.input().sourceId();
        String rightSource = right.input().sourceId();
        return leftSource != null && !leftSource.isBlank() && leftSource.equals(rightSource)
                ? MergeReason.SAME_SOURCE_ID : null;
    }

    private static MergeReason nameCoordinateReason(Node left, Node right) {
        double similarity = similarity(left.name().normalizationKey(), right.name().normalizationKey());
        double distance = distanceMeters(left.input(), right.input());
        if (similarity >= 0.85 && distance < 150d) return MergeReason.NAME_COORDINATE_MATCH;
        if (similarity >= 0.60 && distance < 50d) return MergeReason.NAME_COORDINATE_MATCH;
        return null;
    }

    private static boolean isParentChild(String left, String right) {
        if (left.equals(right)) return false;
        String shorter = left.length() <= right.length() ? left : right;
        String longer = left.length() > right.length() ? left : right;
        return shorter.length() >= 2 && longer.contains(shorter)
                && longer.length() - shorter.length() >= 2;
    }

    private static double similarity(String left, String right) {
        if (left.equals(right)) return 1d;
        int max = Math.max(left.length(), right.length());
        if (max == 0) return 1d;
        return 1d - (double) levenshtein(left, right) / max;
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    public static double distanceMeters(PoiInput left, PoiInput right) {
        double lat1 = Math.toRadians(left.lat());
        double lat2 = Math.toRadians(right.lat());
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(right.lng() - left.lng());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static MergeReason strongestReason(List<Integer> indices, Map<Pair, MergeReason> reasons) {
        for (int i : indices) for (int j : indices) {
            MergeReason reason = reasons.get(new Pair(Math.min(i, j), Math.max(i, j)));
            if (reason == MergeReason.SAME_SOURCE_ID) return reason;
        }
        return MergeReason.NAME_COORDINATE_MATCH;
    }

    public enum MergeReason { NONE, SAME_SOURCE_ID, NAME_COORDINATE_MATCH }
    public enum RelationType { PARENT_CHILD }

    public record PoiInput(String candidateId, String sourceId, String name, double lng, double lat) {
        public PoiInput {
            Objects.requireNonNull(candidateId, "candidateId");
            Objects.requireNonNull(name, "name");
        }
    }
    public record ResolvedEntity(String canonicalId, List<String> mergedFrom, MergeReason mergeReason) {
        public ResolvedEntity { mergedFrom = List.copyOf(mergedFrom); }
    }
    public record Relation(String leftId, String rightId, RelationType type) { }
    public record Resolution(List<ResolvedEntity> entities, List<Relation> relations) {
        public Resolution { entities = List.copyOf(entities); relations = List.copyOf(relations); }
    }
    private record Node(PoiInput input, PoiNameNormalizer.Result name) { }
    private record Pair(int left, int right) { }

    private static final class UnionFind {
        private final int[] parent;
        private UnionFind(int size) { parent = new int[size]; for (int i = 0; i < size; i++) parent[i] = i; }
        private int find(int value) { return parent[value] == value ? value : (parent[value] = find(parent[value])); }
        private void union(int left, int right) {
            int a = find(left); int b = find(right);
            if (a != b) parent[Math.max(a, b)] = Math.min(a, b);
        }
    }
}
