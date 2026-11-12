package com.linguaflow.learning.practice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * Picks practice material (PRC-01). Pure: the same candidates, mode, limits and seed
 * always give the same selection, whatever order the candidates come in.
 * <ul>
 * <li><b>Sampling:</b> weighted sampling without replacement (Efraimidis–Spirakis). Each
 * candidate, in ID order, draws u from a {@link Random} seeded with the seed and the pool,
 * and gets the key ln(u) / weight; the largest keys win. Heavier candidates are more
 * likely, but nothing with a positive weight is ruled out, so practice varies.</li>
 * <li><b>CURRENT_LESSON:</b> the candidates are the lesson's material; weight 0.5 + 0.5 ×
 * weakness.</li>
 * <li><b>MIXED:</b> {@code newRatio} of the picks come from fresh material (equal
 * weights), the rest from older material, weight 0.1 + 0.5 × weakness + 0.4 ×
 * staleness. When one pool runs short, the other fills the gap.</li>
 * <li><b>FULL_REVISION:</b> everything, weight 0.1 + 0.5 × weakness + 0.4 × dueness.</li>
 * <li><b>WEAK_SPOTS:</b> everything, weight 0.02 + weakness², so weak material
 * dominates.</li>
 * </ul>
 * Words and topics are picked separately, each with its own random stream.
 */
public final class MaterialSelector {

	/** The default share of fresh material in MIXED practice. */
	public static final double DEFAULT_NEW_RATIO = 0.6;

	private static final long WORDS = 0x5EED_0001L;

	private static final long TOPICS = 0x5EED_0002L;

	private static final long FRESH = 0x0F;

	private static final long OLDER = 0xA0;

	private MaterialSelector() {
	}

	/**
	 * How much to pick.
	 * @param newRatio MIXED only: the share of fresh material, 0–1
	 */
	public record Limits(int words, int topics, double newRatio) {

		public Limits {
			if (words < 0 || topics < 0) {
				throw new IllegalArgumentException("limits must not be negative");
			}
			if (Double.isNaN(newRatio) || newRatio < 0 || newRatio > 1) {
				throw new IllegalArgumentException("newRatio must be between 0 and 1");
			}
		}

	}

	/** The picks, most strongly chosen first. */
	public record Selection(List<UUID> vocabularyItemIds, List<UUID> grammarTopicIds) {

		public Selection {
			vocabularyItemIds = List.copyOf(vocabularyItemIds);
			grammarTopicIds = List.copyOf(grammarTopicIds);
		}

	}

	public static Selection select(PracticeMode mode, List<PracticeCandidate> words, List<PracticeCandidate> topics,
			Limits limits, long seed) {
		return new Selection(pick(mode, words, limits.words(), limits.newRatio(), seed ^ WORDS),
				pick(mode, topics, limits.topics(), limits.newRatio(), seed ^ TOPICS));
	}

	private static List<UUID> pick(PracticeMode mode, List<PracticeCandidate> candidates, int limit, double newRatio,
			long seed) {
		if (limit == 0 || candidates.isEmpty()) {
			return List.of();
		}
		return switch (mode) {
			case CURRENT_LESSON -> sample(candidates, limit, c -> 0.5 + 0.5 * c.weakness(), seed);
			case FULL_REVISION -> sample(candidates, limit, c -> 0.1 + 0.5 * c.weakness() + 0.4 * c.dueness(), seed);
			case WEAK_SPOTS -> sample(candidates, limit, c -> 0.02 + c.weakness() * c.weakness(), seed);
			case MIXED -> mixed(candidates, limit, newRatio, seed);
		};
	}

	private static List<UUID> mixed(List<PracticeCandidate> candidates, int limit, double newRatio, long seed) {
		List<PracticeCandidate> fresh = candidates.stream().filter(PracticeCandidate::fresh).toList();
		List<PracticeCandidate> older = candidates.stream().filter(c -> !c.fresh()).toList();
		ToDoubleFunction<PracticeCandidate> olderWeight = c -> 0.1 + 0.5 * c.weakness() + 0.4 * c.staleness();
		// Rank each whole pool once; taking a prefix keeps the result stable when the quota moves
		List<UUID> freshRanked = sample(fresh, fresh.size(), c -> 1, seed ^ FRESH);
		List<UUID> olderRanked = sample(older, older.size(), olderWeight, seed ^ OLDER);

		int freshQuota = (int) Math.round(limit * newRatio);
		int takeFresh = Math.min(freshQuota, freshRanked.size());
		int takeOlder = Math.min(limit - takeFresh, olderRanked.size());
		takeFresh = Math.min(limit - takeOlder, freshRanked.size());

		List<UUID> picked = new ArrayList<>(freshRanked.subList(0, takeFresh));
		picked.addAll(olderRanked.subList(0, takeOlder));
		return picked;
	}

	/** Up to {@code limit} candidates, by weighted sampling without replacement. */
	static List<UUID> sample(List<PracticeCandidate> candidates, int limit, ToDoubleFunction<PracticeCandidate> weight,
			long seed) {
		Random random = new Random(seed);
		record Keyed(UUID id, double key) {
		}
		List<Keyed> keyed = new ArrayList<>();
		candidates.stream().sorted(Comparator.comparing(PracticeCandidate::id)).forEach(candidate -> {
			// Draw for every candidate, weightless or not, so the stream doesn't depend on weights
			double u = 1 - random.nextDouble();
			double w = weight.applyAsDouble(candidate);
			if (w > 0) {
				keyed.add(new Keyed(candidate.id(), Math.log(u) / w));
			}
		});
		return keyed.stream()
			.sorted(Comparator.comparingDouble(Keyed::key).reversed().thenComparing(Keyed::id))
			.limit(limit)
			.map(Keyed::id)
			.toList();
	}

}
