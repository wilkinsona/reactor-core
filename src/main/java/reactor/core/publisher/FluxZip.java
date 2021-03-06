/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.publisher;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Cancellation;
import reactor.core.Fuseable;
import reactor.core.MultiReceiver;
import reactor.core.Producer;
import reactor.core.Receiver;
import reactor.core.Trackable;
import reactor.core.Exceptions;

/**
 * Repeatedly takes one item from all source Publishers and 
 * runs it through a function to produce the output item.
 *
 * @param <T> the common input type
 * @param <R> the output value type
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxZip<T, R> extends Flux<R> implements MultiReceiver, Trackable {

	final Publisher<? extends T>[] sources;

	final Iterable<? extends Publisher<? extends T>> sourcesIterable;

	final Function<? super Object[], ? extends R> zipper;

	final Supplier<? extends Queue<T>> queueSupplier;

	final int prefetch;

	@SuppressWarnings("unchecked")
	public <U> FluxZip(Publisher<? extends T> p1,
			Publisher<? extends U> p2,
			BiFunction<? super T, ? super U, ? extends R> zipper2,
			Supplier<? extends Queue<T>> queueSupplier,
			int prefetch) {
		this(new Publisher[]{Objects.requireNonNull(p1, "p1"),
						Objects.requireNonNull(p2, "p2")},
				new PairwiseZipper<>(new BiFunction[]{
						Objects.requireNonNull(zipper2, "zipper2")}),
				queueSupplier,
				prefetch);
	}

	public FluxZip(Publisher<? extends T>[] sources,
			Function<? super Object[], ? extends R> zipper,
			Supplier<? extends Queue<T>> queueSupplier,
			int prefetch) {
		if (prefetch <= 0) {
			throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
		}
		this.sources = Objects.requireNonNull(sources, "sources");
		this.sourcesIterable = null;
		this.zipper = Objects.requireNonNull(zipper, "zipper");
		this.queueSupplier = Objects.requireNonNull(queueSupplier, "queueSupplier");
		this.prefetch = prefetch;
	}

	public FluxZip(Iterable<? extends Publisher<? extends T>> sourcesIterable,
			Function<? super Object[], ? extends R> zipper, Supplier<? extends Queue<T>> queueSupplier, int prefetch) {
		if (prefetch <= 0) {
			throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
		}
		this.sources = null;
		this.sourcesIterable = Objects.requireNonNull(sourcesIterable, "sourcesIterable");
		this.zipper = Objects.requireNonNull(zipper, "zipper");
		this.queueSupplier = Objects.requireNonNull(queueSupplier, "queueSupplier");
		this.prefetch = prefetch;
	}

	@Override
	public long getPrefetch() {
		return prefetch;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public FluxZip<T, R> zipAdditionalSource(Publisher source, BiFunction zipper) {
		Publisher[] oldSources = sources;
		if (oldSources != null && this.zipper instanceof PairwiseZipper) {
			int oldLen = oldSources.length;
			Publisher<? extends T>[] newSources = new Publisher[oldLen + 1];
			System.arraycopy(oldSources, 0, newSources, 0, oldLen);
			newSources[oldLen] = source;

			Function<Object[], R> z = ((PairwiseZipper<R>) this.zipper).then(zipper);

			return new FluxZip<>(newSources, z, queueSupplier, prefetch);
		}
		return null;
	}

	@Override
	public void subscribe(Subscriber<? super R> s) {
		Publisher<? extends T>[] srcs = sources;
		if (srcs != null) {
			handleArrayMode(s, srcs);
		} else {
			handleIterableMode(s, sourcesIterable);
		}
	}

	@SuppressWarnings("unchecked")
	void handleIterableMode(Subscriber<? super R> s, Iterable<? extends Publisher<? extends T>> sourcesIterable) {
		Object[] scalars = new Object[8];
		Publisher<? extends T>[] srcs = new Publisher[8];

		int n = 0;
		int sc = 0;

		for (Publisher<? extends T> p : sourcesIterable) {
			if (p == null) {
				Operators.error(s, new NullPointerException("The sourcesIterable returned a null Publisher"));
				return;
			}

			if (p instanceof Callable) {
				Callable<T> callable = (Callable<T>) p;

				T v;

				try {
					v = callable.call();
				} catch (Throwable e) {
					Operators.error(s, Operators.onOperatorError(e));
					return;
				}

				if (v == null) {
					Operators.complete(s);
					return;
				}

				if (n == scalars.length) {
					Object[] b = new Object[n + (n >> 1)];
					System.arraycopy(scalars, 0, b, 0, n);

					Publisher<T>[] c = new Publisher[b.length];
					//noinspection SuspiciousSystemArraycopy
					System.arraycopy(srcs, 0, c, 0, n);

					scalars = b;
					srcs = c;
				}

				scalars[n] = v;
				sc++;
			} else {
				if (n == srcs.length) {
					Object[] b = new Object[n + (n >> 1)];
					System.arraycopy(scalars, 0, b, 0, n);

					Publisher<T>[] c = new Publisher[b.length];
					//noinspection SuspiciousSystemArraycopy
					System.arraycopy(srcs, 0, c, 0, n);

					scalars = b;
					srcs = c;
				}
				srcs[n] = p;
			}
			n++;
		}

		if (n == 0) {
			Operators.complete(s);
			return;
		}

		handleBoth(s, srcs, scalars, n, sc);
	}

	@SuppressWarnings("unchecked")
	void handleArrayMode(Subscriber<? super R> s, Publisher<? extends T>[] srcs) {

		int n = srcs.length;

		if (n == 0) {
			Operators.complete(s);
			return;
		}

		Object[] scalars = null;
		int sc = 0;

		for (int j = 0; j < n; j++) {
			Publisher<? extends T> p = srcs[j];

			if (p == null) {
				Operators.error(s, new NullPointerException("The sources contained a null Publisher"));
				return;
			}

			if (p instanceof Callable) {
				Object v;

				try {
					v = ((Callable<? extends T>)p).call();
				} catch (Throwable e) {
					Operators.error(s, Operators.onOperatorError(e));
					return;
				}

				if (v == null) {
					Operators.complete(s);
					return;
				}

				if (scalars == null) {
					scalars = new Object[n];
				}

				scalars[j] = v;
				sc++;
			}
		}

		handleBoth(s, srcs, scalars, n, sc);
	}

	void handleBoth(Subscriber<? super R> s, Publisher<? extends T>[] srcs, Object[] scalars, int n, int sc) {
		if (sc != 0) {
			if (n != sc) {
				ZipSingleCoordinator<T, R> coordinator =
						new ZipSingleCoordinator<>(s, scalars, n, zipper);

				s.onSubscribe(coordinator);

				coordinator.subscribe(n, sc, srcs);
			} else {
				Operators.MonoSubscriber<R, R>
						sds = new Operators.MonoSubscriber<>(s);

				s.onSubscribe(sds);

				R r;

				try {
					r = zipper.apply(scalars);
				} catch (Throwable e) {
					s.onError(Operators.onOperatorError(e));
					return;
				}

				if (r == null) {
					s.onError(new NullPointerException("The zipper returned a null value"));
					return;
				}

				sds.complete(r);
			}

		} else {

			ZipCoordinator<T, R> coordinator =
					new ZipCoordinator<>(s, zipper, n, queueSupplier, prefetch);

			s.onSubscribe(coordinator);

			coordinator.subscribe(srcs, n);
		}
	}

	@Override
	public Iterator<?> upstreams() {
		return sources == null ? sourcesIterable.iterator() : Arrays.asList(sources).iterator();
	}

	@Override
	public long getCapacity() {
		return prefetch;
	}

	@Override
	public long upstreamCount() {
		return sources == null ? -1 : sources.length;
	}

	static final class ZipSingleCoordinator<T, R> extends Operators.MonoSubscriber<R, R>
			implements MultiReceiver, Trackable {

		final Function<? super Object[], ? extends R> zipper;

		final Object[] scalars;

		final ZipSingleSubscriber<T>[] subscribers;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<ZipSingleCoordinator> WIP =
				AtomicIntegerFieldUpdater.newUpdater(ZipSingleCoordinator.class, "wip");

		@SuppressWarnings("unchecked")
		public ZipSingleCoordinator(Subscriber<? super R> subscriber,
				Object[] scalars,
				int n,
				Function<? super Object[], ? extends R> zipper) {
			super(subscriber);
			this.zipper = zipper;
			this.scalars = scalars;
			ZipSingleSubscriber<T>[] a = new ZipSingleSubscriber[n];
			for (int i = 0; i < n; i++) {
				if (scalars[i] == null) {
					a[i] = new ZipSingleSubscriber<>(this, i);
				}
			}
			this.subscribers = a;
		}

		void subscribe(int n, int sc, Publisher<? extends T>[] sources) {
			WIP.lazySet(this, n - sc);
			ZipSingleSubscriber<T>[] a = subscribers;
			for (int i = 0; i < n; i++) {
				if (wip <= 0 || isCancelled()) {
					break;
				}
				ZipSingleSubscriber<T> s = a[i];
				if (s != null) {
					sources[i].subscribe(s);
				}
			}
		}

		void next(T value, int index) {
			Object[] a = scalars;
			a[index] = value;
			if (WIP.decrementAndGet(this) == 0) {
				R r;

				try {
					r = zipper.apply(a);
				} catch (Throwable e) {
					actual.onError(Operators.onOperatorError(this, e, value));
					return;
				}

				if (r == null) {
					actual.onError(Operators.onOperatorError(this, new
							NullPointerException("The zipper returned a null value"),
							value));
				} else {
					complete(r);
				}
			}
		}

		void error(Throwable e, int index) {
			if (WIP.getAndSet(this, 0) > 0) {
				cancelAll();
				actual.onError(e);
			} else {
				Operators.onErrorDropped(e);
			}
		}

		void complete(int index) {
			if (scalars[index] == null) {
				if (WIP.getAndSet(this, 0) > 0) {
					cancelAll();
					actual.onComplete();
				}
			}
		}

		@Override
		public void cancel() {
			super.cancel();
			cancelAll();
		}

		@Override
		public long getCapacity() {
			return upstreamCount();
		}

		@Override
		public long getPending() {
			return wip;
		}

		@Override
		public Iterator<?> upstreams() {
			return Arrays.asList(subscribers).iterator();
		}

		@Override
		public long upstreamCount() {
			return subscribers.length;
		}

		void cancelAll() {
			for (ZipSingleSubscriber<T> s : subscribers) {
				if (s != null) {
					s.dispose();
				}
			}
		}
	}

	static final class ZipSingleSubscriber<T>
			implements Subscriber<T>, Trackable, Cancellation, Receiver {

		final ZipSingleCoordinator<T, ?> parent;

		final int index;

		volatile Subscription s;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<ZipSingleSubscriber, Subscription> S =
				AtomicReferenceFieldUpdater.newUpdater(ZipSingleSubscriber.class,
						Subscription.class,
						"s");

		boolean done;

		public ZipSingleSubscriber(ZipSingleCoordinator<T, ?> parent, int index) {
			this.parent = parent;
			this.index = index;
		}


		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.setOnce(S, this, s)) {
				this.s = s;
				s.request(Long.MAX_VALUE);
			}
		}


		@Override
		public void onNext(T t) {
			if (done) {
				Operators.onNextDropped(t);
				return;
			}
			done = true;
			Operators.terminate(S, this);
			parent.next(t, index);
		}


		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t);
				return;
			}
			done = true;
			parent.error(t, index);
		}


		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;
			parent.complete(index);
		}

		@Override
		public long getCapacity() {
			return 1;
		}

		@Override
		public long getPending() {
			return !done ? 1 : -1;
		}

		@Override
		public boolean isCancelled() {
			return s == Operators.cancelledSubscription();
		}

		@Override
		public boolean isStarted() {
			return !done && !isCancelled();
		}

		@Override
		public boolean isTerminated() {
			return done;
		}

		@Override
		public Object upstream() {
			return s;
		}

		@Override
		public void dispose() {
			Operators.terminate(S, this);
		}
	}

	static final class ZipCoordinator<T, R>
			implements Subscription, MultiReceiver, Trackable {

		final Subscriber<? super R> actual;

		final ZipInner<T>[] subscribers;

		final Function<? super Object[], ? extends R> zipper;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<ZipCoordinator> WIP =
				AtomicIntegerFieldUpdater.newUpdater(ZipCoordinator.class, "wip");

		volatile long requested;
		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<ZipCoordinator> REQUESTED =
				AtomicLongFieldUpdater.newUpdater(ZipCoordinator.class, "requested");

		volatile Throwable error;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<ZipCoordinator, Throwable> ERROR =
				AtomicReferenceFieldUpdater.newUpdater(ZipCoordinator.class,
						Throwable.class,
						"error");

		volatile boolean done;

		volatile boolean cancelled;

		final Object[] current;

		public ZipCoordinator(Subscriber<? super R> actual,
				Function<? super Object[], ? extends R> zipper,
				int n,
				Supplier<? extends Queue<T>> queueSupplier, int prefetch) {
			this.actual = actual;
			this.zipper = zipper;
			@SuppressWarnings("unchecked") ZipInner<T>[] a = new ZipInner[n];
			for (int i = 0; i < n; i++) {
				a[i] = new ZipInner<>(this, prefetch, i, queueSupplier);
			}
			this.current = new Object[n];
			this.subscribers = a;
		}

		void subscribe(Publisher<? extends T>[] sources, int n) {
			ZipInner<T>[] a = subscribers;
			for (int i = 0; i < n; i++) {
				if (done || cancelled || error != null) {
					return;
				}
				sources[i].subscribe(a[i]);
			}
		}

		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				Operators.getAndAddCap(REQUESTED, this, n);
				drain();
			}
		}

		@Override
		public void cancel() {
			if (!cancelled) {
				cancelled = true;

				cancelAll();
			}
		}

		@Override
		public long getCapacity() {
			return upstreamCount();
		}

		@Override
		public long getPending() {
			int nonEmpties = 0;
			for(int i =0; i < subscribers.length; i++){
				if(subscribers[i].queue != null && !subscribers[i].queue .isEmpty()){
					nonEmpties++;
				}
			}
			return nonEmpties;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}

		@Override
		public boolean isStarted() {
			return !done;
		}

		@Override
		public boolean isTerminated() {
			return done;
		}

		@Override
		public Throwable getError() {
			return error;
		}

		@Override
		public Iterator<?> upstreams() {
			return Arrays.asList(subscribers).iterator();
		}

		@Override
		public long upstreamCount() {
			return subscribers.length;
		}

		@Override
		public long requestedFromDownstream() {
			return requested;
		}

		void error(Throwable e, int index) {
			if (Exceptions.addThrowable(ERROR, this, e)) {
				drain();
			} else {
				Operators.onErrorDropped(e);
			}
		}

		void cancelAll() {
			for (ZipInner<T> s : subscribers) {
				s.cancel();
			}
		}

		void drain() {

			if (WIP.getAndIncrement(this) != 0) {
				return;
			}

			final Subscriber<? super R> a = actual;
			final ZipInner<T>[] qs = subscribers;
			final int n = qs.length;
			Object[] values = current;

			int missed = 1;

			for (; ; ) {

				long r = requested;
				long e = 0L;

				while (r != e) {

					if (cancelled) {
						return;
					}

					if (error != null) {
						cancelAll();

						Throwable ex = Exceptions.terminate(ERROR, this);

						a.onError(ex);

						return;
					}

					boolean empty = false;

					for (int j = 0; j < n; j++) {
						ZipInner<T> inner = qs[j];
						if (values[j] == null) {
							try {
								boolean d = inner.done;
								Queue<T> q = inner.queue;

								T v = q != null ? q.poll() : null;

								boolean sourceEmpty = v == null;
								if (d && sourceEmpty) {
									cancelAll();

									a.onComplete();
									return;
								}
								if (!sourceEmpty) {
									values[j] = v;
								}
								else {
									empty = true;
								}
							}
							catch (Throwable ex) {
								ex = Operators.onOperatorError(ex);

								cancelAll();

								Exceptions.addThrowable(ERROR, this, ex);
								ex = Exceptions.terminate(ERROR, this);

								a.onError(ex);

								return;
							}
						}
					}

					if (empty) {
						break;
					}

					R v;

					try {
						v = zipper.apply(values.clone());
					}
					catch (Throwable ex) {

						ex = Operators.onOperatorError(null, ex, values.clone());
						cancelAll();

						Exceptions.addThrowable(ERROR, this, ex);
						ex = Exceptions.terminate(ERROR, this);

						a.onError(ex);

						return;
					}

					if (v == null) {

						Throwable ex = Operators.onOperatorError(null, new
								NullPointerException(
								"The zipper returned a null value"), values.clone());

						cancelAll();
						Exceptions.addThrowable(ERROR, this, ex);
						ex = Exceptions.terminate(ERROR, this);

						a.onError(ex);

						return;
					}

					a.onNext(v);

					e++;

					Arrays.fill(values, null);
				}

				if (r == e) {
					if (cancelled) {
						return;
					}

					if (error != null) {
						cancelAll();

						Throwable ex = Exceptions.terminate(ERROR, this);

						a.onError(ex);

						return;
					}

					for (int j = 0; j < n; j++) {
						ZipInner<T> inner = qs[j];
						if (values[j] == null) {
							try {
								boolean d = inner.done;
								Queue<T> q = inner.queue;
								T v = q != null ? q.poll() : null;

								boolean empty = v == null;
								if (d && empty) {
									cancelAll();

									a.onComplete();
									return;
								}
								if (!empty) {
									values[j] = v;
								}
							}
							catch (Throwable ex) {
								ex = Operators.onOperatorError(null, ex, values);

								cancelAll();

								Exceptions.addThrowable(ERROR, this, ex);
								ex = Exceptions.terminate(ERROR, this);

								a.onError(ex);

								return;
							}
						}
					}

				}

				if (e != 0L) {

					for (int j = 0; j < n; j++) {
						ZipInner<T> inner = qs[j];
						inner.request(e);
					}

					if (r != Long.MAX_VALUE) {
						REQUESTED.addAndGet(this, -e);
					}
				}

				missed = WIP.addAndGet(this, -missed);
				if (missed == 0) {
					break;
				}
			}
		}
	}

	static final class ZipInner<T>
			implements Subscriber<T>, Receiver, Producer, Trackable {

		final ZipCoordinator<T, ?> parent;

		final int prefetch;

		final int limit;

		final int index;

		final Supplier<? extends Queue<T>> queueSupplier;

		volatile Queue<T> queue;

		volatile Subscription s;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<ZipInner, Subscription> S =
				AtomicReferenceFieldUpdater.newUpdater(ZipInner.class,
						Subscription.class,
						"s");

		long produced;

		volatile boolean done;

		int sourceMode;

		/** Running with regular, arbitrary source. */
		static final int NORMAL = 0;
		/** Running with a source that implements SynchronousSource. */
		static final int SYNC = 1;
		/** Running with a source that implements AsynchronousSource. */
		static final int ASYNC = 2;

		volatile int once;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<ZipInner> ONCE =
				AtomicIntegerFieldUpdater.newUpdater(ZipInner.class, "once");

		public ZipInner(ZipCoordinator<T, ?> parent,
				int prefetch,
				int index,
				Supplier<? extends Queue<T>> queueSupplier) {
			this.parent = parent;
			this.prefetch = prefetch;
			this.index = index;
			this.queueSupplier = queueSupplier;
			this.limit = prefetch - (prefetch >> 2);
		}

		@SuppressWarnings("unchecked")
		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.setOnce(S, this, s)) {
				if (s instanceof Fuseable.QueueSubscription) {
					Fuseable.QueueSubscription<T> f = (Fuseable.QueueSubscription<T>) s;

					int m = f.requestFusion(Fuseable.ANY);

					if (m == Fuseable.SYNC) {
						sourceMode = SYNC;
						queue = f;
						done = true;
						parent.drain();
						return;
					}
					else if (m == Fuseable.ASYNC) {
						sourceMode = ASYNC;
						queue = f;
					} else {
						try {
							queue = queueSupplier.get();
						} catch (Throwable e) {
							onError(Operators.onOperatorError(s, e));
							return;
						}
					}
				} else {

					try {
						queue = queueSupplier.get();
					} catch (Throwable e) {
						onError(Operators.onOperatorError(s, e));
						return;
					}

				}
				s.request(prefetch);
			}
		}

		@Override
		public void onNext(T t) {
			if (sourceMode != ASYNC) {
				queue.offer(t);
			}
			parent.drain();
		}

		@Override
		public void onError(Throwable t) {
			if (sourceMode != ASYNC || ONCE.compareAndSet(this, 0, 1)) {
				parent.error(t, index);
			}
		}

		@Override
		public void onComplete() {
			done = true;
			parent.drain();
		}

		@Override
		public long getCapacity() {
			return prefetch;
		}

		@Override
		public long getPending() {
			return queue != null ? queue.size() : -1;
		}

		@Override
		public boolean isStarted() {
			return !done;
		}

		@Override
		public boolean isTerminated() {
			return done && (queue == null || queue.isEmpty());
		}

		@Override
		public long expectedFromUpstream() {
			return produced;
		}

		@Override
		public long limit() {
			return limit;
		}

		@Override
		public Object upstream() {
			return s;
		}

		@Override
		public Object downstream() {
			return null;
		}

		void cancel() {
			Operators.terminate(S, this);
		}

		void request(long n) {
			if (sourceMode != SYNC) {
				long p = produced + n;
				if (p >= limit) {
					produced = 0L;
					s.request(p);
				} else {
					produced = p;
				}
			}
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	static final class PairwiseZipper<R> implements Function<Object[], R> {
		final BiFunction[] zippers;

		public PairwiseZipper(BiFunction[] zippers) {
			this.zippers = zippers;
		}

		@Override
		public R apply(Object[] args) {
			Object o = zippers[0].apply(args[0], args[1]);
			for (int i = 1; i < zippers.length; i++) {
				o = zippers[i].apply(o, args[i + 1]);
			}
			return (R) o;
		}

		public PairwiseZipper then(BiFunction zipper) {
			BiFunction[] zippers = this.zippers;
			int n = zippers.length;
			BiFunction[] newZippers = new BiFunction[n + 1];
			System.arraycopy(zippers, 0, newZippers, 0, n);
			newZippers[n] = zipper;

			return new PairwiseZipper(newZippers);
		}
	}
}
