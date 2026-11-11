package com.linguaflow.ai.job;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.linguaflow.ai.LlmException;
import com.linguaflow.common.config.LinguaFlowProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs queued AI jobs in the background (AI-04).
 * <ol>
 * <li>Every {@code poll-interval}, and right after jobs are enqueued, it claims due jobs
 * in a short transaction ({@code FOR UPDATE SKIP LOCKED}, so several app instances never
 * run the same job) and marks them RUNNING with one more attempt.</li>
 * <li>Each job runs on a virtual thread, at most {@code concurrency} at a time, outside
 * any transaction.</li>
 * <li>The outcome is recorded in a new transaction: SUCCEEDED; QUEUED again after an
 * exponential backoff ({@code backoff-base} doubling per attempt, capped at
 * {@code backoff-max}); DEAD after the last attempt; FAILED at once for errors retrying
 * can't fix. On FAILED and DEAD the handler's {@code onGiveUp} runs.</li>
 * <li>A job RUNNING for longer than {@code stale-after} belongs to a worker that stopped
 * (a crash or a restart mid-job). It is queued again, or dead-lettered if it has no
 * attempts left, so work survives a restart.</li>
 * </ol>
 * Off when {@code linguaflow.ai.jobs.enabled=false} (the test profile); tests call
 * {@link #runDueJobs()} instead.
 */
@Component
public class AiJobWorker implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(AiJobWorker.class);

	private static final String STALE_ERROR = "The worker stopped while running the job";

	private final AiJobRepository jobs;

	private final Map<AiJobType, AiJobHandler> handlers = new EnumMap<>(AiJobType.class);

	private final TransactionTemplate tx;

	private final Clock clock;

	private final LinguaFlowProperties.Ai.Jobs settings;

	private final String workerId;

	private final Semaphore slots;

	private volatile @Nullable ScheduledExecutorService scheduler;

	private volatile @Nullable ExecutorService executor;

	private volatile boolean running;

	public AiJobWorker(AiJobRepository jobs, List<AiJobHandler> handlers, PlatformTransactionManager transactions,
			Clock clock, LinguaFlowProperties properties) {
		this.jobs = jobs;
		for (AiJobHandler handler : handlers) {
			AiJobHandler previous = this.handlers.put(handler.type(), handler);
			if (previous != null) {
				throw new IllegalStateException("Two AI job handlers for " + handler.type() + ": "
						+ previous.getClass().getName() + " and " + handler.getClass().getName());
			}
		}
		this.tx = new TransactionTemplate(transactions);
		this.clock = clock;
		this.settings = properties.ai().jobs();
		this.workerId = hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
		this.slots = new Semaphore(settings.concurrency());
	}

	// --- Lifecycle -------------------------------------------------------------------

	@Override
	public void start() {
		if (!settings.enabled()) {
			log.info("AI job worker disabled (linguaflow.ai.jobs.enabled=false)");
			return;
		}
		executor = Executors.newVirtualThreadPerTaskExecutor();
		ScheduledExecutorService poller = Executors
			.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("ai-job-poller").daemon().factory());
		scheduler = poller;
		running = true;
		poller.scheduleWithFixedDelay(this::poll, 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
		log.info("AI job worker {} started: {} at a time, polling every {}, handlers for {}", workerId,
				settings.concurrency(), settings.pollInterval(), handlers.keySet());
	}

	/** Stops claiming jobs and gives running ones a moment to finish; the rest are recovered as stale. */
	@Override
	public void stop() {
		running = false;
		ScheduledExecutorService poller = scheduler;
		ExecutorService pool = executor;
		if (poller != null) {
			poller.shutdownNow();
		}
		if (pool != null) {
			pool.shutdown();
			try {
				if (!pool.awaitTermination(20, TimeUnit.SECONDS)) {
					log.warn("AI jobs still running at shutdown; another worker will pick them up after {}",
							settings.staleAfter());
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	/** Jobs committed: look for work now rather than at the next poll. */
	@TransactionalEventListener
	public void onJobsEnqueued(AiJobQueue.JobsEnqueued event) {
		wakeUp();
	}

	/** Polls now, on the poller thread, so claims never run in parallel on one instance. */
	private void wakeUp() {
		ScheduledExecutorService poller = scheduler;
		if (running && poller != null) {
			try {
				poller.execute(this::poll);
			}
			catch (RejectedExecutionException ex) {
				// Shutting down
			}
		}
	}

	private void poll() {
		try {
			recoverStaleJobs();
			int free = slots.availablePermits();
			if (free == 0) {
				return;
			}
			for (AiJob job : claim(free)) {
				slots.acquireUninterruptibly();
				ExecutorService pool = executor;
				if (pool == null) {
					slots.release();
					return;
				}
				pool.execute(() -> {
					try {
						execute(job);
					}
					finally {
						slots.release();
						// A slot is free: take the next due job instead of waiting for the poll
						wakeUp();
					}
				});
			}
		}
		catch (RuntimeException ex) {
			log.error("AI job poll failed", ex);
		}
	}

	// --- Processing ------------------------------------------------------------------

	/**
	 * Recovers stale jobs, then runs every due job in the calling thread until none is
	 * left; for tests and tools. Returns how many attempts ran.
	 */
	public int runDueJobs() {
		recoverStaleJobs();
		int ran = 0;
		List<AiJob> batch;
		while (!(batch = claim(settings.concurrency())).isEmpty()) {
			for (AiJob job : batch) {
				execute(job);
				ran++;
			}
		}
		return ran;
	}

	/** Claims up to {@code limit} due jobs for this worker. */
	List<AiJob> claim(int limit) {
		List<AiJob> claimed = tx.execute(status -> {
			Instant now = clock.instant();
			List<UUID> ids = jobs.lockDueForProcessing(now, limit);
			if (ids.isEmpty()) {
				return List.<AiJob>of();
			}
			List<AiJob> due = jobs.findAllForProcessingByIdIn(ids);
			due.forEach(job -> job.start(workerId, now));
			return due;
		});
		return claimed == null ? List.of() : claimed;
	}

	void execute(AiJob job) {
		AiJobHandler handler = handlers.get(job.getType());
		long started = System.nanoTime();
		try {
			if (handler == null) {
				throw new AiJobException("No handler for AI jobs of type " + job.getType(), false);
			}
			Map<String, Object> result = handler.handle(job);
			record(job, held -> held.succeed(result));
			log.info("AI job {} ({}) succeeded on attempt {} in {} ms", job.getId(), job.getType(), job.getAttempts(),
					Duration.ofNanos(System.nanoTime() - started).toMillis());
		}
		catch (LlmException ex) {
			failed(job, handler, ex.kind().retryable(), ex.kind() + ": " + ex.getMessage());
		}
		catch (AiJobException ex) {
			failed(job, handler, ex.retryable(), ex.getMessage());
		}
		catch (RuntimeException ex) {
			log.error("AI job {} ({}) failed unexpectedly", job.getId(), job.getType(), ex);
			failed(job, handler, true, ex.getClass().getSimpleName() + ": " + ex.getMessage());
		}
	}

	private void failed(AiJob job, @Nullable AiJobHandler handler, boolean retryable, String error) {
		AiJob ended = record(job, held -> {
			if (retryable && held.attemptsLeft()) {
				Instant next = clock.instant().plus(backoff(held.getAttempts()));
				held.retryAt(next, error);
				log.warn("AI job {} ({}) attempt {} of {} failed, retrying at {}: {}", held.getId(), held.getType(),
						held.getAttempts(), held.getMaxAttempts(), next, error);
			}
			else if (retryable) {
				held.deadLetter(error);
				log.error("AI job {} ({}) dead-lettered after {} attempts: {}", held.getId(), held.getType(),
						held.getAttempts(), error);
			}
			else {
				held.fail(error);
				log.error("AI job {} ({}) failed: {}", held.getId(), held.getType(), error);
			}
		});
		if (ended != null && ended.getStatus().finished()) {
			giveUp(ended, handler);
		}
	}

	/**
	 * Applies {@code change} if this worker still holds the job. It may not: the user
	 * was deleted, or the job went stale and another worker took it over.
	 */
	private @Nullable AiJob record(AiJob job, Consumer<AiJob> change) {
		return tx.execute(status -> jobs.findForProcessingById(job.getId()).filter(held -> {
			if (!held.heldBy(workerId)) {
				log.warn("AI job {} is no longer held by worker {}; its outcome is dropped", job.getId(), workerId);
				return false;
			}
			return true;
		}).map(held -> {
			change.accept(held);
			return held;
		}).orElse(null));
	}

	/** Queues again, or dead-letters, jobs whose worker stopped. */
	void recoverStaleJobs() {
		List<AiJob> dead = tx.execute(status -> {
			Instant now = clock.instant();
			List<UUID> ids = jobs.lockStaleForProcessing(now.minus(settings.staleAfter()), 100);
			List<AiJob> deadLettered = new ArrayList<>();
			for (AiJob job : ids.isEmpty() ? List.<AiJob>of() : jobs.findAllForProcessingByIdIn(ids)) {
				String previousWorker = job.getLockedBy();
				if (job.attemptsLeft()) {
					job.retryAt(now, STALE_ERROR);
					log.warn("AI job {} ({}) was left running by {}; queued again", job.getId(), job.getType(),
							previousWorker);
				}
				else {
					job.deadLetter(STALE_ERROR);
					deadLettered.add(job);
					log.error("AI job {} ({}) was left running on its last attempt; dead-lettered", job.getId(),
							job.getType());
				}
			}
			return deadLettered;
		});
		if (dead != null) {
			dead.forEach(job -> giveUp(job, handlers.get(job.getType())));
		}
	}

	private void giveUp(AiJob job, @Nullable AiJobHandler handler) {
		if (handler == null) {
			return;
		}
		try {
			handler.onGiveUp(job);
		}
		catch (RuntimeException ex) {
			log.error("onGiveUp failed for AI job {} ({})", job.getId(), job.getType(), ex);
		}
	}

	/** {@code backoff-base × 2^(attempt-1)}, at most {@code backoff-max}. */
	Duration backoff(int attempt) {
		Duration wait = settings.backoffBase();
		for (int i = 1; i < attempt && wait.compareTo(settings.backoffMax()) < 0; i++) {
			wait = wait.multipliedBy(2);
		}
		return wait.compareTo(settings.backoffMax()) > 0 ? settings.backoffMax() : wait;
	}

	String workerId() {
		return workerId;
	}

	private static String hostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		}
		catch (Exception ex) {
			return "worker";
		}
	}

}
