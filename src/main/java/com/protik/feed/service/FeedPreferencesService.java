package com.protik.feed.service;

import com.protik.feed.persistence.FeedPreferencesRepository;
import com.protik.feed.persistence.FeedPreferencesRepository.Component;
import com.protik.feed.persistence.FeedPreferencesRepository.Mode;
import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The viewer's ranking choices: a mode (a published set of weights) plus personal overrides
 * (ADR 0014). Everything is resolved from data, so the same rules explain the feed to the viewer.
 */
@Service
public class FeedPreferencesService {

    static final String DEFAULT_MODE = "DEFAULT";
    private static final int MAX_WEIGHT_SCALE = 3; // the column is numeric(4,3): never round silently

    /** One component as the viewer sees it: the mode's weight, their override, and what actually applies. */
    public record ComponentView(String code, String description, BigDecimal modeWeight, BigDecimal override,
            BigDecimal effectiveWeight) {
    }

    public record Preferences(String mode, boolean followedOnly, List<ComponentView> components) {

        /** The weights the ranker will use. */
        public Map<String, Double> effectiveWeights() {
            Map<String, Double> weights = new LinkedHashMap<>();
            components.forEach(c -> weights.put(c.code(), c.effectiveWeight().doubleValue()));
            return weights;
        }
    }

    public record ModeView(String code, String description, boolean followedOnly, Map<String, BigDecimal> weights) {
    }

    private final FeedPreferencesRepository repository;

    FeedPreferencesService(FeedPreferencesRepository repository) {
        this.repository = repository;
    }

    /** Every mode with its published weights, so the viewer can see what choosing it means. */
    @Transactional(readOnly = true)
    public List<ModeView> modes() {
        var weights = repository.findModeWeights();
        return repository.findModes().stream()
                .map(m -> new ModeView(m.code(), m.description(), m.followedOnly(),
                        weights.getOrDefault(m.code(), Map.of())))
                .toList();
    }

    /** The viewer's current mode and weights; a viewer who never chose gets the default mode. */
    @Transactional(readOnly = true)
    public Preferences preferences(UUID userId) {
        String modeCode = repository.findChosenMode(userId).orElse(DEFAULT_MODE);
        Mode mode = repository.findMode(modeCode)
                .orElseThrow(() -> new IllegalStateException("Ranking mode " + modeCode + " is missing"));
        var modeWeights = repository.findModeWeights().getOrDefault(mode.code(), Map.of());
        var overrides = repository.findOverrides(userId);

        List<ComponentView> views = repository.findComponents().stream().map(c -> {
            BigDecimal modeWeight = modeWeights.getOrDefault(c.code(), BigDecimal.ZERO);
            BigDecimal override = overrides.get(c.code());
            return new ComponentView(c.code(), c.description(), modeWeight, override,
                    override != null ? override : modeWeight);
        }).toList();
        return new Preferences(mode.code(), mode.followedOnly(), views);
    }

    /**
     * Replaces the viewer's mode and all their overrides in one transaction. Idempotent: sending the
     * same request twice gives the same result. An empty map means "no overrides: use the mode as published".
     */
    @Transactional
    public Preferences save(UUID userId, String modeCode, Map<String, BigDecimal> overrides) {
        if (modeCode == null || repository.findMode(modeCode).isEmpty()) {
            throw invalid("Unknown ranking mode.");
        }
        Set<String> known = repository.findComponents().stream().map(Component::code).collect(Collectors.toSet());
        for (var entry : overrides.entrySet()) {
            if (!known.contains(entry.getKey())) {
                throw invalid("Unknown ranking component: " + safe(entry.getKey()) + ".");
            }
            BigDecimal weight = entry.getValue();
            if (weight == null || weight.signum() < 0 || weight.compareTo(BigDecimal.ONE) > 0) {
                throw invalid("Weight for " + entry.getKey() + " must be between 0 and 1.");
            }
            if (weight.stripTrailingZeros().scale() > MAX_WEIGHT_SCALE) {
                throw invalid("Weight for " + entry.getKey() + " may have at most " + MAX_WEIGHT_SCALE + " decimals.");
            }
        }

        repository.upsertMode(userId, modeCode); // also takes the per-user lock
        repository.deleteOverrides(userId);
        overrides.forEach((code, weight) -> repository.insertOverride(userId, code, weight));
        return preferences(userId);
    }

    private static ApiException invalid(String detail) {
        return new ApiException(ProblemType.VALIDATION_FAILED, detail);
    }

    /** Echoing client text into an error is fine, but bound it so a huge key cannot bloat the response. */
    private static String safe(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }
}
