package com.linguaflow.ai.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.IntStream;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.ai.LlmException;
import com.linguaflow.ai.usage.AiQuotaExceededException;
import com.linguaflow.common.config.LinguaFlowProperties;
import com.linguaflow.common.config.LinguaFlowProperties.Ai.Jobs;
import com.linguaflow.testsupport.MutableClock;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The queue and worker against a real PostgreSQL (AI-04). Workers are built by hand,
 * with a test clock and their own handlers; the app's own worker is off in the test
 * profile.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AiJobWorkerTests {

	private static final Duration BACKOFF = Duration.ofSeconds(30);

	private static final Duration STALE = Duration.ofMinutes(10);

	@Autowired
	private AiJobRepository jobs;

	@Autowired
	private AiJobQueue appQueue;

	@Autowired
	private PlatformTransactionManager transactions;

	@Autowired
	private JdbcTemplate jdbc;

	private final MutableClock clock = new MutableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));

	private final List<AiJobWorker> started = new ArrayList<>();

	private AiJobQueue queue;

	/** A handler whose behaviour each test sets. */
	static final class TestHandler implements AiJobHandler {

		final List<UUID> handled = new CopyOnWriteArrayList<>();

		final List<UUID> gaveUp = new CopyOnWriteArrayList<>();

		volatile Function<AiJob, @Nullable Map<String, Object>> behaviour = job -> Map.of("ok", true);

		@Override
		public AiJobType type() {
			return AiJobType.VOCABULARY_ENRICHMENT;
		}

		@Override
		public @Nullable Map<String, Object> handle(AiJob job) {
			handled.add(job.getId());
			return behaviour.apply(job);
		}

		@Override
		public void onGiveUp(AiJob job) {
			gaveUp.add(job.getId());
		}

	}

	private final TestHandler handler = new TestHandler();

	@BeforeEach
	void setUp() {
		jdbc.update("delete from ai_jobs");
		queue = new AiJobQueue(jobs, clock, properties(jobSettings(false, Duration.ofMinutes(1))),
				mock(ApplicationEventPublisher.class));
	}

	@AfterEach
	void stopWorkers() {
		started.forEach(AiJobWorker::stop);
		jdbc.update("delete from ai_jobs");
	}

	private static Jobs jobSettings(boolean enabled, Duration pollInterval) {
		return new Jobs(enabled, pollInterval, 4, 20, 3, BACKOFF, Duration.ofMinutes(5), STALE);
	}

	private static LinguaFlowProperties properties(Jobs settings) {
		LinguaFlowProperties properties = mock(LinguaFlowProperties.class);
		when(properties.ai()).thenReturn(new LinguaFlowProperties.Ai(null, null, null, null, null, settings, null, null, null));
		return properties;
	}

	private AiJobWorker worker(Jobs settings, AiJobHandler... handlers) {
		return new AiJobWorker(jobs, List.of(handlers), transactions, clock, properties(settings));
	}

	private AiJobWorker worker() {
		return worker(jobSettings(false, Duration.ofMinutes(1)), handler);
	}

	private AiJob enqueue() {
		return queue.enqueue(AiJobType.VOCABULARY_ENRICHMENT, null, Map.of("vocabularyItemIds", List.of("a")));
	}

	private AiJob reload(AiJob job) {
		return jobs.findForProcessingById(job.getId()).orElseThrow();
	}

	@Test
	void runsAJobAndStoresItsResult() {
		handler.behaviour = job -> Map.of("enriched", job.getPayload().get("vocabularyItemIds"));
		AiJob job = enqueue();

		assertThat(worker().runDueJobs()).isEqualTo(1);

		AiJob done = reload(job);
		assertThat(done.getStatus()).isEqualTo(AiJobStatus.SUCCEEDED);
		assertThat(done.getResult()).isEqualTo(Map.of("enriched", List.of("a")));
		assertThat(done.getAttempts()).isEqualTo(1);
		assertThat(done.getLockedBy()).isNull();
		assertThat(worker().runDueJobs()).isZero();
	}

	@Test
	void retriesWithExponentialBackoff() {
		AtomicInteger calls = new AtomicInteger();
		handler.behaviour = job -> {
			if (calls.incrementAndGet() < 3) {
				throw new LlmException(LlmException.Kind.RATE_LIMITED, "slow down");
			}
			return Map.of();
		};
		AiJob job = enqueue();
		AiJobWorker worker = worker();
		Instant start = clock.instant();

		worker.runDueJobs();
		AiJob waiting = reload(job);
		assertThat(waiting.getStatus()).isEqualTo(AiJobStatus.QUEUED);
		assertThat(waiting.getNextRunAt()).isEqualTo(start.plus(BACKOFF));
		assertThat(waiting.getLastError()).isEqualTo("RATE_LIMITED: slow down");
		// Not due before the backoff has passed
		assertThat(worker.runDueJobs()).isZero();

		clock.advance(BACKOFF);
		worker.runDueJobs();
		// The second wait is twice as long
		assertThat(reload(job).getNextRunAt()).isEqualTo(start.plus(BACKOFF).plus(BACKOFF.multipliedBy(2)));

		clock.advance(BACKOFF.multipliedBy(2));
		worker.runDueJobs();
		AiJob done = reload(job);
		assertThat(done.getStatus()).isEqualTo(AiJobStatus.SUCCEEDED);
		assertThat(done.getAttempts()).isEqualTo(3);
		assertThat(done.getLastError()).isNull();
		assertThat(handler.gaveUp).isEmpty();
	}

	@Test
	void deadLettersAJobWhenEveryAttemptFails() {
		handler.behaviour = job -> {
			throw new LlmException(LlmException.Kind.UNAVAILABLE, "overloaded");
		};
		AiJob job = enqueue();
		AiJobWorker worker = worker();

		for (int attempt = 0; attempt < 3; attempt++) {
			worker.runDueJobs();
			clock.advance(Duration.ofMinutes(5));
		}

		AiJob dead = reload(job);
		assertThat(dead.getStatus()).isEqualTo(AiJobStatus.DEAD);
		assertThat(dead.getAttempts()).isEqualTo(3);
		assertThat(dead.getLastError()).isEqualTo("UNAVAILABLE: overloaded");
		assertThat(handler.gaveUp).containsExactly(job.getId());
		assertThat(worker.runDueJobs()).isZero();
	}

	@Test
	void failsAtOnceWhenRetryingCannotHelp() {
		handler.behaviour = job -> {
			throw new LlmException(LlmException.Kind.INVALID_OUTPUT, "still invalid");
		};
		AiJob invalid = enqueue();
		AiJob orphan = queue.enqueue(AiJobType.GRAMMAR_EXPLANATION, null, Map.of());

		worker().runDueJobs();

		assertThat(reload(invalid).getStatus()).isEqualTo(AiJobStatus.FAILED);
		assertThat(reload(invalid).getAttempts()).isEqualTo(1);
		assertThat(handler.gaveUp).containsExactly(invalid.getId());
		assertThat(reload(orphan).getStatus()).isEqualTo(AiJobStatus.FAILED);
		assertThat(reload(orphan).getLastError()).isEqualTo("No handler for AI jobs of type GRAMMAR_EXPLANATION");
	}

	@Test
	void treatsUnexpectedErrorsAsTransientAndCutsLongMessages() {
		handler.behaviour = job -> {
			throw new IllegalStateException("x".repeat(5_000));
		};
		AiJob job = enqueue();

		worker().runDueJobs();

		AiJob waiting = reload(job);
		assertThat(waiting.getStatus()).isEqualTo(AiJobStatus.QUEUED);
		assertThat(waiting.getLastError()).startsWith("IllegalStateException: xxx").hasSize(AiJob.MAX_ERROR_LENGTH);
	}

	@Test
	void jobsSurviveAWorkerThatStoppedMidJob() {
		AiJob job = enqueue();
		AiJobWorker crashed = worker();
		// The first worker claims the job, then the process dies before it finishes
		assertThat(crashed.claim(10)).hasSize(1);
		assertThat(reload(job).getStatus()).isEqualTo(AiJobStatus.RUNNING);

		// After a restart, a new worker leaves it alone until it is stale…
		AiJobWorker restarted = worker();
		clock.advance(STALE.minusSeconds(1));
		assertThat(restarted.runDueJobs()).isZero();

		// …then queues it again and runs it
		clock.advance(Duration.ofSeconds(2));
		assertThat(restarted.runDueJobs()).isEqualTo(1);
		AiJob done = reload(job);
		assertThat(done.getStatus()).isEqualTo(AiJobStatus.SUCCEEDED);
		assertThat(done.getAttempts()).isEqualTo(2);
		assertThat(handler.handled).containsExactly(job.getId());

		// The first worker's late result is dropped rather than overwriting the new one
		crashed.execute(job);
		assertThat(reload(job).getAttempts()).isEqualTo(2);
	}

	@Test
	void aStaleJobOnItsLastAttemptIsDeadLettered() {
		AiJob job = enqueue();
		handler.behaviour = j -> {
			throw new LlmException(LlmException.Kind.TIMEOUT, "slow");
		};
		AiJobWorker worker = worker();
		worker.runDueJobs();
		clock.advance(Duration.ofMinutes(5));
		worker.runDueJobs();
		clock.advance(Duration.ofMinutes(5));
		// The third and last attempt never finishes
		worker.claim(10);
		clock.advance(STALE.plusSeconds(1));

		worker().runDueJobs();

		assertThat(reload(job).getStatus()).isEqualTo(AiJobStatus.DEAD);
		assertThat(reload(job).getLastError()).isEqualTo("The worker stopped while running the job");
		assertThat(handler.gaveUp).containsExactly(job.getId());
	}

	@Test
	void twoWorkersNeverRunTheSameJob() throws InterruptedException {
		List<UUID> ids = IntStream.range(0, 40).mapToObj(i -> enqueue().getId()).toList();
		handler.behaviour = job -> {
			sleep(10);
			return Map.of();
		};
		AiJobWorker first = worker();
		AiJobWorker second = worker();
		CountDownLatch go = new CountDownLatch(1);
		List<Thread> threads = List.of(first, second)
			.stream()
			.map(worker -> Thread.ofVirtual().start(() -> {
				await(go);
				worker.runDueJobs();
			}))
			.toList();
		go.countDown();
		for (Thread thread : threads) {
			thread.join(Duration.ofSeconds(30));
		}

		assertThat(handler.handled).hasSize(40).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
		assertThat(jdbc.queryForObject("select count(*) from ai_jobs where status = 'SUCCEEDED'", Integer.class))
			.isEqualTo(40);
	}

	@Test
	void splitsLargeBatchesAndEnqueuesWithTheCallersTransaction() {
		List<UUID> words = IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toList();
		TransactionTemplate tx = new TransactionTemplate(transactions);

		List<AiJob> created = tx.execute(
				status -> appQueue.enqueueInBatches(AiJobType.VOCABULARY_ENRICHMENT, null, "vocabularyItemIds", words));

		assertThat(created).hasSize(5).allSatisfy(job -> {
			assertThat(job.getStatus()).isEqualTo(AiJobStatus.QUEUED);
			assertThat((List<?>) job.getPayload().get("vocabularyItemIds")).hasSize(20);
		});
		List<Object> all = new ArrayList<>();
		created.forEach(job -> all.addAll((List<?>) job.getPayload().get("vocabularyItemIds")));
		assertThat(all).containsExactlyElementsOf(words.stream().map(UUID::toString).toList());

		// If the caller's work rolls back, so do its jobs
		tx.executeWithoutResult(status -> {
			appQueue.enqueue(AiJobType.VOCABULARY_ENRICHMENT, null, Map.of());
			status.setRollbackOnly();
		});
		assertThat(jdbc.queryForObject("select count(*) from ai_jobs", Integer.class)).isEqualTo(5);
	}

	/** AI-04's "done when": a bulk add of 100 words is processed in the background. */
	@Test
	void processes100WordsInTheBackgroundWithoutBlockingTheCaller() throws InterruptedException {
		Map<String, Boolean> enriched = new ConcurrentHashMap<>();
		handler.behaviour = job -> {
			sleep(200);
			((List<?>) job.getPayload().get("vocabularyItemIds")).forEach(id -> enriched.put((String) id, true));
			return Map.of("count", 20);
		};
		AiJobWorker background = worker(jobSettings(true, Duration.ofMinutes(10)), handler);
		background.start();
		started.add(background);
		List<UUID> words = IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toList();

		long begin = System.nanoTime();
		queue.enqueueInBatches(AiJobType.VOCABULARY_ENRICHMENT, null, "vocabularyItemIds", words);
		Duration enqueueTime = Duration.ofNanos(System.nanoTime() - begin);
		// The request only writes 5 rows; the 5 × 200 ms of AI work happens later
		assertThat(enqueueTime).isLessThan(Duration.ofMillis(800));

		// The poll interval is long, so this is the commit signal waking the worker
		background.onJobsEnqueued(new AiJobQueue.JobsEnqueued(5));
		waitUntil(() -> enriched.size() == 100, Duration.ofSeconds(15));

		assertThat(enriched.keySet()).containsExactlyInAnyOrderElementsOf(words.stream().map(UUID::toString).toList());
		waitUntil(() -> jdbc.queryForObject("select count(*) from ai_jobs where status = 'SUCCEEDED'",
				Integer.class) == 5, Duration.ofSeconds(5));
	}

	@Test
	void aJobThatHitsTheDailyLimitWaitsForTheResetWithoutUsingAnAttempt() {
		Instant resetsAt = clock.instant().plus(Duration.ofHours(5));
		AtomicInteger calls = new AtomicInteger();
		handler.behaviour = job -> {
			if (calls.incrementAndGet() == 1) {
				throw new AiQuotaExceededException("Daily AI limit reached", resetsAt);
			}
			return Map.of();
		};
		AiJob job = enqueue();
		AiJobWorker worker = worker();

		worker.runDueJobs();
		AiJob waiting = reload(job);
		assertThat(waiting.getStatus()).isEqualTo(AiJobStatus.QUEUED);
		assertThat(waiting.getAttempts()).isZero();
		assertThat(waiting.getNextRunAt()).isEqualTo(resetsAt);
		assertThat(waiting.getLastError()).isEqualTo("Daily AI limit reached");
		assertThat(handler.gaveUp).isEmpty();

		clock.set(resetsAt);
		worker.runDueJobs();
		assertThat(reload(job).getStatus()).isEqualTo(AiJobStatus.SUCCEEDED);
		assertThat(reload(job).getAttempts()).isEqualTo(1);
	}

	@Test
	void aJobCallsOnBehalfOfItsUser() {
		UUID user = UUID.randomUUID();
		AiJob job = new AiJob(AiJobType.VOCABULARY_ENRICHMENT, user, Map.of(), 3, clock.instant());
		assertThat(job.caller().userId()).isEqualTo(user);
	}

	@Test
	void backoffDoublesUpToTheMaximum() {
		AiJobWorker worker = worker();
		assertThat(List.of(1, 2, 3, 4, 10).stream().map(worker::backoff).toList()).containsExactly(BACKOFF,
				Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4), Duration.ofMinutes(5));
	}

	@Test
	void refusesTwoHandlersForOneType() {
		assertThatThrownBy(() -> worker(jobSettings(false, Duration.ofMinutes(1)), handler, new TestHandler()))
			.hasMessageContaining("Two AI job handlers for VOCABULARY_ENRICHMENT");
	}

	private static void waitUntil(BooleanSupplier condition, Duration timeout)
			throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (!condition.getAsBoolean()) {
			assertThat(System.nanoTime()).as("condition reached within %s", timeout).isLessThan(deadline);
			TimeUnit.MILLISECONDS.sleep(20);
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
