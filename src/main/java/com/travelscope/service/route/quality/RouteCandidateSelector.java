package com.travelscope.service.route.quality;

import com.travelscope.collab.RoutePlan;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 最多三个已由同一矩阵生成的路线候选，本地评分并选优；零 Agent、零外部调用。 */
public final class RouteCandidateSelector {
    public Selection select(List<Candidate> candidates, RouteQualityScorer scorer,
                            RouteQualityScorer.Context context) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty() || candidates.size() > 3)
            throw new IllegalArgumentException("候选数必须为 1..3");
        List<ScoredCandidate> scored = candidates.stream()
                .map(c -> new ScoredCandidate(c.strategy(), c.plan(), scorer.score(c.plan(), context)))
                .sorted(Comparator.comparingDouble((ScoredCandidate c) -> c.quality().totalScore()).reversed()
                        .thenComparing(ScoredCandidate::strategy)).toList();
        ScoredCandidate winner = scored.getFirst();
        List<RoutePlan.CandidateSummary> summaries = scored.stream().map(c ->
                new RoutePlan.CandidateSummary(c.strategy(), c.quality().totalScore(), c == winner,
                        c == winner ? null : rejection(c, winner))).toList();
        return new Selection(winner.plan(), summaries);
    }

    private static String rejection(ScoredCandidate candidate, ScoredCandidate winner) {
        var worst = candidate.quality().scores().entrySet().stream()
                .min(java.util.Map.Entry.comparingByValue()).orElseThrow();
        return "总分 " + String.format(java.util.Locale.ROOT, "%.2f", candidate.quality().totalScore())
                + " 低于入选方案 " + String.format(java.util.Locale.ROOT, "%.2f", winner.quality().totalScore())
                + "；最低维度=" + worst.getKey();
    }

    public record Candidate(String strategy, RoutePlan plan) {
        public Candidate { Objects.requireNonNull(strategy); Objects.requireNonNull(plan); }
    }
    private record ScoredCandidate(String strategy, RoutePlan plan, RouteQualityScorer.Result quality) {}
    public record Selection(RoutePlan selected, List<RoutePlan.CandidateSummary> summaries) {}
}
