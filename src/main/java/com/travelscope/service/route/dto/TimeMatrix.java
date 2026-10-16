package com.travelscope.service.route.dto;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 通勤时间矩阵（VRPTW 的 transit callback 数据源）：n×n，单元为秒。
 * <p>
 * 节点顺序 = nodeNames 顺序；对角线为 0；不可达单元置 -1（构建器对失败的
 * 路径对降级处理，见 CommuteMatrixBuilder）。
 * equals/hashCode 自定义为内容语义（record 默认对数组是引用比较）。
 * </p>
 */
public record TimeMatrix(List<String> nodeNames, long[][] durationsSec) {

    public TimeMatrix {
        Objects.requireNonNull(nodeNames, "nodeNames");
        Objects.requireNonNull(durationsSec, "durationsSec");
        nodeNames = List.copyOf(nodeNames);
        if (durationsSec.length != nodeNames.size()) {
            throw new IllegalArgumentException("矩阵行数(" + durationsSec.length
                    + ")与节点数(" + nodeNames.size() + ")不一致");
        }
        for (int i = 0; i < durationsSec.length; i++) {
            if (durationsSec[i] == null || durationsSec[i].length != nodeNames.size()) {
                throw new IllegalArgumentException("矩阵第 " + i + " 行长度与节点数不一致");
            }
        }
    }

    /** 节点 i → j 的通勤时长（秒）；不可达为 -1 */
    public long get(int i, int j) {
        return durationsSec[i][j];
    }

    public int size() {
        return nodeNames.size();
    }

    /** 节点名下标（不存在返回 -1） */
    public int indexOf(String nodeName) {
        return nodeNames.indexOf(nodeName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TimeMatrix other)) {
            return false;
        }
        return nodeNames.equals(other.nodeNames)
                && Arrays.deepEquals(durationsSec, other.durationsSec);
    }

    @Override
    public int hashCode() {
        return 31 * nodeNames.hashCode() + Arrays.deepHashCode(durationsSec);
    }

    @Override
    public String toString() {
        return "TimeMatrix{nodes=" + nodeNames.size() + ", names=" + nodeNames + "}";
    }
}
