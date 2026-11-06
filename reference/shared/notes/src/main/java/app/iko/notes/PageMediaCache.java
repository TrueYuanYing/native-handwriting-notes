package app.iko.notes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/** Deduplicated, byte-bounded media with cancellable page observers. No Android/UI dependency. */
final class PageMediaCache<K, V> {
  interface Loader<K, V> {
    V load(K key);
  }

  interface Weight<V> {
    int bytes(V value);
  }

  interface Request {
    void cancel();
  }

  private final int limit;
  private final Executor executor;
  private final Loader<K, V> loader;
  private final Weight<V> weight;
  private final LinkedHashMap<K, V> values = new LinkedHashMap<>(16, .75f, true);
  private final LinkedHashMap<K, Job> queued = new LinkedHashMap<>();
  private final Map<K, Job> jobs = new HashMap<>();
  private final Map<K, Set<Watch>> watches = new HashMap<>();
  private final LinkedHashSet<K> failures = new LinkedHashSet<>();
  private final Object decodeLock = new Object();
  private int bytes;
  private boolean draining;

  PageMediaCache(int limit, Executor executor, Loader<K, V> loader, Weight<V> weight) {
    this.limit = limit;
    this.executor = executor;
    this.loader = loader;
    this.weight = weight;
  }

  private final class Job {
    final K key;
    boolean running, done;
    V result;

    Job(K key) {
      this.key = key;
    }
  }

  private final class Watch implements Request {
    final Set<K> keys, pending = new HashSet<>();
    final Runnable ready;
    final boolean persistent;
    boolean cancelled;

    Watch(Set<K> keys, Runnable ready, boolean persistent) {
      this.keys = keys;
      this.ready = ready;
      this.persistent = persistent;
    }

    @Override
    public void cancel() {
      synchronized (PageMediaCache.this) {
        cancelled = true;
        detach(this);
      }
    }

    void deliver() {
      synchronized (PageMediaCache.this) {
        if (cancelled) return;
        if (!persistent) {
          cancelled = true;
          detach(this);
        }
      }
      // A broken consumer must not prevent other views from receiving their completion.
      if (ready != null) {
        try {
          ready.run();
        } catch (RuntimeException ignored) {
          // The cache and remaining consumers still need their completion transition.
        }
      }
    }
  }

  Request request(List<K> requested, Runnable ready, boolean persistent) {
    Watch watch = new Watch(new LinkedHashSet<>(requested), ready, persistent);
    boolean complete;
    synchronized (this) {
      for (K key : watch.keys) {
        watches.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(watch);
        if (values.get(key) == null) {
          watch.pending.add(key);
          failures.remove(key); // A new prepare/subscribe is an explicit retry, including same path.
          enqueue(key);
        }
      }
      complete = watch.pending.isEmpty();
    }
    if (complete) watch.deliver();
    startDrain();
    return watch;
  }

  synchronized V peek(K key) {
    return values.get(key);
  }

  synchronized boolean failed(K key) {
    return failures.contains(key);
  }

  /** A visible cached page may outlive its media entry; reload and notify its existing observers. */
  void reloadIfObserved(K key) {
    synchronized (this) {
      if (values.get(key) != null || failures.contains(key) || !watches.containsKey(key)) return;
      enqueue(key);
    }
    startDrain();
  }

  private void enqueue(K key) {
    if (jobs.containsKey(key)) return;
    Job job = new Job(key);
    jobs.put(key, job);
    queued.put(key, job);
  }

  private void detach(Watch watch) {
    for (K key : watch.keys) {
      Set<Watch> waiting = watches.get(key);
      if (waiting == null) continue;
      waiting.remove(watch);
      if (!waiting.isEmpty()) continue;
      watches.remove(key);
      Job queuedJob = queued.remove(key);
      if (queuedJob != null) jobs.remove(key);
    }
  }

  private void startDrain() {
    synchronized (this) {
      if (draining || queued.isEmpty()) return;
      draining = true;
    }
    executor.execute(this::drain);
  }

  private void drain() {
    while (true) {
      Job job;
      synchronized (this) {
        if (queued.isEmpty()) {
          draining = false;
          return;
        }
        K key = queued.keySet().iterator().next();
        job = queued.remove(key);
        job.running = true;
      }
      decode(job);
    }
  }

  /** Exports join an in-flight decode or take a queued job without writing a placeholder. */
  V loadBlocking(K key) {
    Job job;
    synchronized (this) {
      V cached = values.get(key);
      if (cached != null) return cached;
      job = jobs.get(key);
      if (job != null && job.running) {
        while (!job.done) {
          try {
            wait();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
          }
        }
        return job.result;
      }
      if (job == null) {
        job = new Job(key);
        jobs.put(key, job);
      } else queued.remove(key);
      job.running = true;
    }
    decode(job);
    return job.result;
  }

  private void decode(Job job) {
    V result = null;
    try {
      // Serialize background and export allocations, including independent PDF renderer instances.
      synchronized (decodeLock) {
        result = loader.load(job.key);
      }
    } catch (RuntimeException | OutOfMemoryError ignored) {
      // A failed attempt completes normally and may be retried by the next page request.
    }
    ArrayList<Watch> notify = new ArrayList<>();
    synchronized (this) {
      if (result != null) {
        failures.remove(job.key);
        int size = weight.bytes(result);
        if (size <= limit) {
          V previous = values.put(job.key, result);
          if (previous != null) bytes -= weight.bytes(previous);
          bytes += size;
          while (bytes > limit && !values.isEmpty()) {
            K oldest = values.keySet().iterator().next();
            bytes -= weight.bytes(values.remove(oldest));
          }
        }
      } else {
        failures.add(job.key);
        while (failures.size() > 128) failures.remove(failures.iterator().next());
      }
      job.result = result;
      job.done = true;
      jobs.remove(job.key);
      Set<Watch> waiting = watches.get(job.key);
      if (waiting != null) {
        for (Watch watch : waiting) {
          watch.pending.remove(job.key);
          if (watch.pending.isEmpty()) notify.add(watch);
        }
      }
      notifyAll();
    }
    for (Watch watch : notify) watch.deliver();
  }

  synchronized int byteCount() {
    return bytes;
  }

  synchronized int queuedCount() {
    return queued.size();
  }

  synchronized int observerCount() {
    Set<Watch> unique = new HashSet<>();
    for (Set<Watch> group : watches.values()) unique.addAll(group);
    return unique.size();
  }
}
