/*
 * Copyright (c) 2011-2014 Pivotal Software, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package reactor.rx.action;

import org.reactivestreams.Subscriber;
import reactor.core.Environment;
import reactor.event.dispatch.Dispatcher;
import reactor.event.registry.Registration;
import reactor.function.Consumer;
import reactor.function.Supplier;
import reactor.rx.Stream;
import reactor.rx.action.support.SpecificationExceptions;
import reactor.rx.subscription.PushSubscription;
import reactor.timer.Timer;
import reactor.util.Assert;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author Stephane Maldini
 * @since 2.0
 */
public class ConcurrentAction<O> extends Action<O, Stream<O>> {

	private final int poolSize;
	private final ReentrantLock lock   = new ReentrantLock();
	private final AtomicInteger active = new AtomicInteger();
	private final ParallelAction[] publishers;

	private volatile int roundRobinIndex = 0;

	private Registration<? extends Consumer<Long>> consumerRegistration;

	protected final Consumer<Long> requestConsumer = new Consumer<Long>() {
		@Override
		public void accept(Long n) {
			lock.lock();
			try {
				if (subscription == null) {

					if ((pendingNextSignals += n) < 0) {
						lock.unlock();
						doError(SpecificationExceptions.spec_3_17_exception(pendingNextSignals, n));
					} else {
						lock.unlock();
					}
					return;
				}

				if (firehose) {
					currentNextSignals = 0;
					lock.unlock();
					subscription.request(n);
					return;
				}

				long previous = pendingNextSignals;
				pendingNextSignals += n;

				if (previous < capacity) {
					long toRequest = n + previous;
					toRequest = Math.min(toRequest, capacity);
					pendingNextSignals -= toRequest;
					currentNextSignals = 0;
					lock.unlock();
					subscription.request(toRequest);
					return;
				}

				lock.unlock();

			} catch (Throwable t) {

				if (lock.isHeldByCurrentThread())
					lock.unlock();

				doError(t);
			}
		}
	};

	@SuppressWarnings("unchecked")
	public ConcurrentAction(Dispatcher parentDispatcher,
	                        Supplier<Dispatcher> multiDispatcher,
	                        Integer poolSize) {
		super(parentDispatcher);
		Assert.state(poolSize > 0, "Must provide a strictly positive number of concurrent sub-streams (poolSize)");
		this.poolSize = poolSize;
		this.publishers = new ParallelAction[poolSize];
		for (int i = 0; i < poolSize; i++) {
			this.publishers[i] = new ParallelAction<O>(ConcurrentAction.this, multiDispatcher.get(), i);
		}
	}

	@Override
	public Action<O, Stream<O>> capacity(long elements) {
		int cumulatedReservedSlots = poolSize * RESERVED_SLOTS;
		if (elements < cumulatedReservedSlots) {
			super.capacity(elements);
		} else {
			long newCapacity = elements - cumulatedReservedSlots + RESERVED_SLOTS;
		/*	if (log.isTraceEnabled()) {
				log.trace("ParallelAction capacity has been altered to {}. Trying to book {} slots on ParallelAction but " +
								"we are capped {} slots to never overrun the underlying dispatchers. ", newCapacity,
						cumulatedReservedSlots + RESERVED_SLOTS);

			}*/
			super.capacity(newCapacity);
		}
		long size = capacity / poolSize;

		if (size == 0) {
			/*log.warn("Of course there are {} parallel streams and there can only be {} max items available at any given " +
							"time, " +
							"we baselined all parallel streams capacity to {}",
					poolSize, elements, elements);*/
			size = elements;
		}

		for (ParallelAction p : publishers) {
			p.capacity(size);
		}
		return this;
	}

	/**
	 * Monitor all sub-streams latency to hint the next elements to dispatch to the fastest sub-streams in priority.
	 *
	 * @param latencyInMs a period in milliseconds to tolerate before assigning a new sub-stream
	 */
	public ConcurrentAction<O> monitorLatency(long latencyInMs) {
		Assert.isTrue(environment != null, "Require an environment to retrieve the default timer");
		return monitorLatency(latencyInMs, environment.getTimer());
	}

	/**
	 * Monitor all sub-streams latency to hint the next elements to dispatch to the fastest sub-streams in priority.
	 *
	 * @param latencyInMs a period in milliseconds to tolerate before assigning a new sub-stream
	 * @param timer       a timer to run on periodically
	 */
	public ConcurrentAction<O> monitorLatency(final long latencyInMs, Timer timer) {
		consumerRegistration = timer.schedule(new Consumer<Long>() {
			@Override
			public void accept(Long aLong) {
				trySyncDispatch(aLong, new Consumer<Long>() {
					@Override
					public void accept(Long aLong) {

						try {
							if (aLong - publishers[roundRobinIndex].getLastRequestedTime() < latencyInMs) return;
						} catch (NullPointerException npe) {
							//ignore
						}

						int fasterParallelIndex = -1;
						for (ParallelAction parallelStream : publishers) {
							try {
								if (aLong - parallelStream.getLastRequestedTime() < latencyInMs) {
									fasterParallelIndex = parallelStream.getIndex();
									break;
								}
							} catch (NullPointerException npe) {
								//ignore
							}
						}

						if (fasterParallelIndex == -1) return;

						roundRobinIndex = fasterParallelIndex;
					}
				});
			}
		}, latencyInMs, TimeUnit.MILLISECONDS, latencyInMs);
		return this;
	}

	public int getPoolSize() {
		return poolSize;
	}

	public ParallelAction[] getPublishers() {
		return publishers;
	}

	@Override
	protected void onRequest(long n) {
		checkRequest(n);
		trySyncDispatch(n, requestConsumer);
	}

	@Override
	public Action<O, Stream<O>> env(Environment environment) {
		for (ParallelAction p : publishers) {
			p.env(environment);
		}
		return super.env(environment);
	}

	@Override
	public Action<O, Stream<O>> keepAlive(boolean keepAlive) {
		super.keepAlive(keepAlive);
		for (ParallelAction p : publishers) {
			p.keepAlive(keepAlive);
		}
		return this;
	}

	@Override
	public Action<O, Stream<O>> ignoreErrors(boolean ignoreError) {
		super.ignoreErrors(ignoreError);
		for (ParallelAction p : publishers) {
			p.ignoreErrors(ignoreError);
		}
		return this;
	}

	void clean(int index) {
		publishers[index] = null;

		if (active.decrementAndGet() <= 0) {
			cancel();
		}
	}

	void parallelRequest(long elements, int index) {
		roundRobinIndex = index;
		onRequest(elements);
	}

	@Override
	@SuppressWarnings("unchecked")
	protected PushSubscription<Stream<O>> createSubscription(final Subscriber<? super Stream<O>> subscriber, boolean reactivePull) {
		return new PushSubscription<Stream<O>>(this, subscriber) {
			long cursor = 0l;

			@Override
			public void request(long elements) {
				int i = 0;
				while (i < poolSize && i < cursor) {
					i++;
				}

				while (i < elements && i < poolSize) {
					cursor++;
					active.incrementAndGet();
					onNext(publishers[i]);
					i++;
				}

				if (i == poolSize) {
					onComplete();
				}
			}
		};
	}

	@Override
	@SuppressWarnings("unchecked")
	protected void doNext(final O ev) {

		ParallelAction<O> publisher;
		boolean hasCapacity;
		int tries = 0;
		int lastExistingPublisher = -1;
		int currentRoundRobIndex = roundRobinIndex;

		while (tries < poolSize) {
			publisher = publishers[currentRoundRobIndex];

			if (publisher != null) {
				lastExistingPublisher = currentRoundRobIndex;

				hasCapacity = publisher.getCurrentCapacity() > publisher.getCapacity() * 0.15;

				if (hasCapacity) {
					try {
						publisher.broadcastNext(ev);
					} catch (Throwable e) {
						publisher.broadcastError(e);
					}
					return;
				}
			}

			if (++currentRoundRobIndex >= active.get()) {
				currentRoundRobIndex = 0;
			}

			tries++;
		}

		if (lastExistingPublisher != -1) {
			roundRobinIndex = lastExistingPublisher;
			publisher = publishers[lastExistingPublisher];
			try {
				publisher.broadcastNext(ev);
			} catch (Throwable e) {
				publisher.broadcastError(e);
			}
		} /*else {
			if (log.isTraceEnabled()) {
				log.trace("event dropped " + ev + " as downstream publisher is shutdown");
			}
		}*/

	}

	protected void onShutdown() {
		dispatch(new Consumer<Void>() {
			@Override
			public void accept(Void aVoid) {
				if (active.get() == 0) {
					cancel();
				}
			}
		});
	}

	@Override
	protected void doError(Throwable throwable) {
		super.doError(throwable);
		if (consumerRegistration != null) consumerRegistration.cancel();
		for (ParallelAction parallelStream : publishers) {
			parallelStream.broadcastError(throwable);
		}
	}

	@Override
	protected void doComplete() {
		super.doComplete();
		if (consumerRegistration != null) consumerRegistration.cancel();
		for (ParallelAction parallelStream : publishers) {
			parallelStream.broadcastComplete();
		}
	}

}
