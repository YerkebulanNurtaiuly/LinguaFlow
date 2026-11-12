package com.linguaflow.learning.practice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;

import com.linguaflow.learning.practice.MaterialSelector.Limits;
import com.linguaflow.learning.practice.MaterialSelector.Selection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaterialSelectorTests {

	private static UUID id(int n) {
		return new UUID(0, n);
	}

	/** n candidates; the first {@code fresh} are fresh, weakness and staleness rise with n. */
	private static List<PracticeCandidate> pool(int n, int fresh) {
		return IntStream.range(0, n)
			.mapToObj(i -> new PracticeCandidate(id(i), i < fresh, (double) i / n, (double) i / n, (double) i / n))
			.toList();
	}

	private static Selection select(PracticeMode mode, List<PracticeCandidate> words, int limit, long seed) {
		return MaterialSelector.select(mode, words, pool(10, 3), new Limits(limit, 3, 0.6), seed);
	}

	@ParameterizedTest
	@EnumSource(PracticeMode.class)
	void theSameSeedGivesTheSameSelection(PracticeMode mode) {
		List<PracticeCandidate> words = pool(50, 20);
		assertThat(select(mode, words, 15, 42)).isEqualTo(select(mode, words, 15, 42));
	}

	@ParameterizedTest
	@EnumSource(PracticeMode.class)
	void theInputOrderDoesNotMatter(PracticeMode mode) {
		List<PracticeCandidate> words = pool(50, 20);
		List<PracticeCandidate> shuffled = new ArrayList<>(words);
		Collections.shuffle(shuffled, new Random(7));
		assertThat(select(mode, shuffled, 15, 42)).isEqualTo(select(mode, words, 15, 42));
	}

	@ParameterizedTest
	@EnumSource(PracticeMode.class)
	void anotherSeedGivesAnotherSelection(PracticeMode mode) {
		List<PracticeCandidate> words = pool(50, 20);
		long distinct = IntStream.range(0, 10)
			.mapToObj(seed -> new HashSet<>(select(mode, words, 15, seed).vocabularyItemIds()))
			.distinct()
			.count();
		assertThat(distinct).isGreaterThan(5);
	}

	@ParameterizedTest
	@EnumSource(PracticeMode.class)
	void picksAreDistinctAndWithinTheLimits(PracticeMode mode) {
		Selection selection = select(mode, pool(50, 20), 15, 3);
		assertThat(selection.vocabularyItemIds()).hasSize(15).doesNotHaveDuplicates();
		assertThat(selection.grammarTopicIds()).hasSize(3).doesNotHaveDuplicates();
		// Fewer candidates than the limit: all of them
		assertThat(select(mode, pool(4, 2), 15, 3).vocabularyItemIds()).hasSize(4);
		assertThat(select(mode, List.of(), 15, 3).vocabularyItemIds()).isEmpty();
		assertThat(MaterialSelector.select(mode, pool(5, 2), pool(5, 2), new Limits(0, 0, 0.6), 3))
			.isEqualTo(new Selection(List.of(), List.of()));
	}

	@Test
	void mixedPracticeTakesTheNewRatioFromFreshMaterial() {
		List<PracticeCandidate> words = pool(100, 40);
		Selection selection = MaterialSelector.select(PracticeMode.MIXED, words, List.of(), new Limits(10, 0, 0.6), 1);
		assertThat(fresh(selection, words)).isEqualTo(6);

		Selection allOld = MaterialSelector.select(PracticeMode.MIXED, words, List.of(), new Limits(10, 0, 0), 1);
		assertThat(fresh(allOld, words)).isZero();
		Selection allNew = MaterialSelector.select(PracticeMode.MIXED, words, List.of(), new Limits(10, 0, 1), 1);
		assertThat(fresh(allNew, words)).isEqualTo(10);
	}

	@Test
	void mixedPracticeFillsFromTheOtherPool() {
		// Only 2 fresh: the other 8 are older
		List<PracticeCandidate> fewFresh = pool(50, 2);
		Selection selection = MaterialSelector.select(PracticeMode.MIXED, fewFresh, List.of(),
				new Limits(10, 0, 0.6), 1);
		assertThat(selection.vocabularyItemIds()).hasSize(10);
		assertThat(fresh(selection, fewFresh)).isEqualTo(2);

		// Only 3 older: the other 7 are fresh
		List<PracticeCandidate> fewOld = pool(50, 47);
		Selection mostlyFresh = MaterialSelector.select(PracticeMode.MIXED, fewOld, List.of(),
				new Limits(10, 0, 0.6), 1);
		assertThat(mostlyFresh.vocabularyItemIds()).hasSize(10);
		assertThat(fresh(mostlyFresh, fewOld)).isEqualTo(7);
	}

	@Test
	void weakerMaterialIsPickedMoreOften() {
		// Two halves: weak and strong; over many seeds the weak half dominates
		List<PracticeCandidate> words = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			boolean weak = i % 2 == 0;
			words.add(new PracticeCandidate(id(i), false, weak ? 0.9 : 0.05, 0.5, 0.5));
		}
		for (PracticeMode mode : List.of(PracticeMode.FULL_REVISION, PracticeMode.MIXED, PracticeMode.WEAK_SPOTS)) {
			int weakPicks = 0;
			for (long seed = 0; seed < 200; seed++) {
				for (UUID picked : MaterialSelector
					.select(mode, words, List.of(), new Limits(10, 0, 0), seed)
					.vocabularyItemIds()) {
					weakPicks += picked.getLeastSignificantBits() % 2 == 0 ? 1 : 0;
				}
			}
			// 2000 picks in all; random would be about half
			assertThat(weakPicks).as(mode.name()).isGreaterThan(1300);
		}
	}

	@Test
	void fullRevisionFavoursDueMaterialAndWeakSpotsFavoursWeakMaterialMore() {
		List<PracticeCandidate> words = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			boolean due = i % 2 == 0;
			words.add(new PracticeCandidate(id(i), false, 0.3, 0, due ? 1 : 0));
		}
		assertThat(share(PracticeMode.FULL_REVISION, words)).isGreaterThan(0.6);

		List<PracticeCandidate> weakness = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			weakness.add(new PracticeCandidate(id(i), false, i % 2 == 0 ? 0.6 : 0.3, 0.5, 0.5));
		}
		assertThat(share(PracticeMode.WEAK_SPOTS, weakness)).isGreaterThan(share(PracticeMode.FULL_REVISION, weakness));
	}

	@Test
	void nothingWithAPositiveWeightIsRuledOut() {
		List<PracticeCandidate> words = pool(20, 0);
		Map<UUID, Integer> seen = new HashMap<>();
		for (long seed = 0; seed < 500; seed++) {
			MaterialSelector.select(PracticeMode.FULL_REVISION, words, List.of(), new Limits(5, 0, 0), seed)
				.vocabularyItemIds()
				.forEach(picked -> seen.merge(picked, 1, Integer::sum));
		}
		assertThat(seen).hasSize(20);
	}

	@Test
	void limitsAreChecked() {
		assertThatThrownBy(() -> new Limits(-1, 0, 0.5)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Limits(1, 0, 1.5)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Limits(1, 0, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
	}

	private static long fresh(Selection selection, List<PracticeCandidate> pool) {
		return selection.vocabularyItemIds()
			.stream()
			.filter(picked -> pool.stream().anyMatch(c -> c.id().equals(picked) && c.fresh()))
			.count();
	}

	/** The share of picks among the even-numbered candidates over many seeds. */
	private static double share(PracticeMode mode, List<PracticeCandidate> words) {
		int even = 0;
		int all = 0;
		for (long seed = 0; seed < 200; seed++) {
			for (UUID picked : MaterialSelector.select(mode, words, List.of(), new Limits(10, 0, 0), seed)
				.vocabularyItemIds()) {
				even += picked.getLeastSignificantBits() % 2 == 0 ? 1 : 0;
				all++;
			}
		}
		return (double) even / all;
	}

}
