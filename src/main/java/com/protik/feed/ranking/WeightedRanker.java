package com.protik.feed.ranking;

import com.protik.feed.Candidate;
import com.protik.feed.ComponentScore;
import com.protik.feed.RankedItem;
import com.protik.feed.Ranker;
import com.protik.feed.RankingContext;
import com.protik.feed.ScoreFunction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Score = sum of {@code weight x value} over the registered components (ADR 0014). The explanation
 * is the very list of terms that were summed, so it cannot disagree with the score.
 */
@Component
public class WeightedRanker implements Ranker {

    /**
     * Best first: score, then newest, then video ID. IDs compare as unsigned bytes, which is how
     * PostgreSQL orders UUIDs, so a database cursor and this order always agree (java.util.UUID's own
     * compareTo is signed and would not). Comparing the two longs also avoids allocating strings.
     */
    private static final Comparator<Scored> ORDER = Comparator
            .<Scored>comparingDouble(s -> s.item.score()).reversed()
            .thenComparing((Scored s) -> s.candidate.publishedAt(), Comparator.reverseOrder())
            .thenComparing((a, b) -> compareUnsigned(b.candidate.videoId(), a.candidate.videoId()));

    static int compareUnsigned(java.util.UUID a, java.util.UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    private final List<ScoreFunction> functions;

    public WeightedRanker(List<ScoreFunction> functions) {
        Set<String> seen = new HashSet<>();
        for (ScoreFunction function : functions) {
            if (!seen.add(function.code())) {
                throw new IllegalStateException("Two ScoreFunctions have the code " + function.code());
            }
        }
        this.functions = List.copyOf(functions);
    }

    @Override
    public List<RankedItem> rank(RankingContext context, Collection<Candidate> candidates) {
        Set<String> known = new HashSet<>();
        functions.forEach(f -> known.add(f.code()));
        for (String code : context.weights().keySet()) {
            if (!known.contains(code)) {
                // A weight for a component nobody implements would silently do nothing: refuse.
                throw new IllegalArgumentException("No ScoreFunction for weighted component " + code);
            }
        }

        List<Scored> scored = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            if (context.followedOnly() && !context.followedCreators().contains(candidate.creatorId())) {
                continue;
            }
            scored.add(new Scored(candidate, explain(context, candidate)));
        }
        scored.sort(ORDER);
        return scored.stream().map(s -> s.item).toList();
    }

    private RankedItem explain(RankingContext context, Candidate candidate) {
        List<ComponentScore> components = new ArrayList<>(functions.size());
        double total = 0.0;
        for (ScoreFunction function : functions) {
            double value = function.score(context, candidate);
            if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
                throw new IllegalStateException(function.code() + " returned " + value + " for video "
                        + candidate.videoId() + "; components must return a value in [0, 1]");
            }
            double weight = context.weights().getOrDefault(function.code(), 0.0);
            double contribution = value * weight;
            components.add(new ComponentScore(function.code(), value, weight, contribution));
            total += contribution; // summed in component order, so the explanation reproduces it exactly
        }
        return new RankedItem(candidate.videoId(), total, components);
    }

    private record Scored(Candidate candidate, RankedItem item) {
    }
}
