package com.bumptech.glide.load.engine;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.v4.util.Pools;
import android.util.Log;

import com.bumptech.glide.GlideContext;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.engine.EngineResource.ResourceListener;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.engine.cache.DiskCacheAdapter;
import com.bumptech.glide.load.engine.cache.MemoryCache;
import com.bumptech.glide.load.engine.executor.GlideExecutor;
import com.bumptech.glide.request.ResourceCallback;
import com.bumptech.glide.util.Executors;
import com.bumptech.glide.util.LogTime;
import com.bumptech.glide.util.Preconditions;
import com.bumptech.glide.util.Synthetic;
import com.bumptech.glide.util.pool.FactoryPools;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Responsible for starting loads and managing active and cached resources.
 */
public class Engine implements EngineJobListener,
		MemoryCache.ResourceRemovedListener,
		EngineResource.ResourceListener {
	private static final String TAG = "Engine";
	private static final int JOB_POOL_SIZE = 150;
	private static final boolean VERBOSE_IS_LOGGABLE = Log.isLoggable(TAG, Log.VERBOSE);
	private final Jobs jobs;
	private final EngineKeyFactory keyFactory;
	private final MemoryCache cache;
	private final EngineJobFactory engineJobFactory;
	private final ResourceRecycler resourceRecycler;
	private final LazyDiskCacheProvider diskCacheProvider;
	private final DecodeJobFactory decodeJobFactory;
	private final ActiveResources activeResources;

	public Engine(
			MemoryCache memoryCache,
			DiskCache.Factory diskCacheFactory,
			GlideExecutor diskCacheExecutor,
			GlideExecutor sourceExecutor,
			GlideExecutor sourceUnlimitedExecutor,
			GlideExecutor animationExecutor,
			boolean isActiveResourceRetentionAllowed) {
		this(
				memoryCache,
				diskCacheFactory,
				diskCacheExecutor,
				sourceExecutor,
				sourceUnlimitedExecutor,
				animationExecutor,
				/*jobs=*/ null,
				/*keyFactory=*/ null,
				/*activeResources=*/ null,
				/*engineJobFactory=*/ null,
				/*decodeJobFactory=*/ null,
				/*resourceRecycler=*/ null,
				isActiveResourceRetentionAllowed);
	}

	@VisibleForTesting
	Engine(MemoryCache cache,
		   DiskCache.Factory diskCacheFactory,
		   GlideExecutor diskCacheExecutor,
		   GlideExecutor sourceExecutor,
		   GlideExecutor sourceUnlimitedExecutor,
		   GlideExecutor animationExecutor,
		   Jobs jobs,
		   EngineKeyFactory keyFactory,
		   ActiveResources activeResources,
		   EngineJobFactory engineJobFactory,
		   DecodeJobFactory decodeJobFactory,
		   ResourceRecycler resourceRecycler,
		   boolean isActiveResourceRetentionAllowed) {
		this.cache = cache;
		this.diskCacheProvider = new LazyDiskCacheProvider(diskCacheFactory);

		if (activeResources == null) {
			activeResources = new ActiveResources(isActiveResourceRetentionAllowed);
		}
		this.activeResources = activeResources;
		activeResources.setListener(this);

		if (keyFactory == null) {
			keyFactory = new EngineKeyFactory();
		}
		this.keyFactory = keyFactory;

		if (jobs == null) {
			jobs = new Jobs();
		}
		this.jobs = jobs;

		if (engineJobFactory == null) {
			engineJobFactory =
					new EngineJobFactory(
							diskCacheExecutor,
							sourceExecutor,
							sourceUnlimitedExecutor,
							animationExecutor,
							/*engineJobListener=*/ this,
							/*resourceListener=*/ this);
		}
		this.engineJobFactory = engineJobFactory;

		if (decodeJobFactory == null) {
			decodeJobFactory = new DecodeJobFactory(diskCacheProvider);
		}
		this.decodeJobFactory = decodeJobFactory;

		if (resourceRecycler == null) {
			resourceRecycler = new ResourceRecycler();
		}
		this.resourceRecycler = resourceRecycler;

		cache.setResourceRemovedListener(this);
	}

	/**
	 * Starts a load for the given arguments.
	 *
	 * <p>Must be called on the main thread.
	 *
	 * <p>The flow for any request is as follows:
	 *
	 * <ul>
	 * <li>Check the current set of actively used resources, return the active resource if present,
	 * and move any newly inactive resources into the memory cache.
	 * <li>Check the memory cache and provide the cached resource if present.
	 * <li>Check the current set of in progress loads and add the cb to the in progress load if one
	 * is present.
	 * <li>Start a new load.
	 * </ul>
	 *
	 * <p>Active resources are those that have been provided to at least one request and have not yet
	 * been released. Once all consumers of a resource have released that resource, the resource then
	 * goes to cache. If the resource is ever returned to a new consumer from cache, it is re-added to
	 * the active resources. If the resource is evicted from the cache, its resources are recycled and
	 * re-used if possible and the resource is discarded. There is no strict requirement that
	 * consumers release their resources so active resources are held weakly.
	 *
	 * @param width  The target width in pixels of the desired resource.
	 * @param height The target height in pixels of the desired resource.
	 * @param cb     The callback that will be called when the load completes.
	 */
	public synchronized <R> LoadStatus load(
			GlideContext glideContext,
			Object model,
			Key signature,
			int width,
			int height,
			Class<?> resourceClass,
			Class<R> transcodeClass,
			Priority priority,
			DiskCacheStrategy diskCacheStrategy,
			Map<Class<?>, Transformation<?>> transformations,
			boolean isTransformationRequired,
			boolean isScaleOnlyOrNoTransform,
			Options options,
			boolean isMemoryCacheable,
			boolean useUnlimitedSourceExecutorPool,
			boolean useAnimationPool,
			boolean onlyRetrieveFromCache,
			ResourceCallback cb,
			Executor callbackExecutor) {
		long startTime = VERBOSE_IS_LOGGABLE ? LogTime.getLogTime() : 0;
//    通过keyFactory工厂来构建一个EngineKey对象，key关联着model，也就是url，它很根据model，
// view的宽高等等属性来构建一个EngineKey对象，这个对象可以用来指定缓存地址，可以用来从缓存中查找资源等。
		EngineKey key = keyFactory.buildKey(model, signature, width, height, transformations,
				resourceClass, transcodeClass, options);
//从弱引用 获取
//		根据创建的key对象分别调用loadFromCache和loadFromActiveResources方法来从内存中查
//		找是否有缓存资源，如果有，则回调cb.onResourceReady来直接设置图片了。
		//是第一级缓存，表示当前正在活动中的资源。
		EngineResource<?> active = loadFromActiveResources(key, isMemoryCacheable);
		if (active != null) {
			cb.onResourceReady(active, DataSource.MEMORY_CACHE);
			if (VERBOSE_IS_LOGGABLE) {
				logWithTimeAndKey("Loaded resource from active resources", startTime, key);
			}
			return null;
		}
//get from LruCache
		EngineResource<?> cached = loadFromCache(key, isMemoryCacheable);
		if (cached != null) {
			cb.onResourceReady(cached, DataSource.MEMORY_CACHE);
			if (VERBOSE_IS_LOGGABLE) {
				logWithTimeAndKey("Loaded resource from cache", startTime, key);
			}
			return null;
		}

		EngineJob<?> current = jobs.get(key, onlyRetrieveFromCache);
		if (current != null) {
			current.addCallback(cb, callbackExecutor);
			if (VERBOSE_IS_LOGGABLE) {
				logWithTimeAndKey("Added to existing load", startTime, key);
			}
			return new LoadStatus(cb, current);
		}
//		EngineJob和DecodeJob的构建是基本一致的，比较复杂的DecodeJob的构建：在build方法中，首先通过pool来创建
// 一个DecodeJob对象，然后调用DecodeJob对象的init方法进行初始化，在初始化中值得注意的是调用了decodeHelper
// 对象的init方法。decodeHelper方法是DecodeJob的重要辅助类，后面我们会详细的接触它。
		EngineJob<R> engineJob =
				engineJobFactory.build(
						key,
						isMemoryCacheable,
						useUnlimitedSourceExecutorPool,
						useAnimationPool,
						onlyRetrieveFromCache);

		DecodeJob<R> decodeJob =
				decodeJobFactory.build(
						glideContext,
						model,
						key,
						signature,
						width,
						height,
						resourceClass,
						transcodeClass,
						priority,
						diskCacheStrategy,
						transformations,
						isTransformationRequired,
						isScaleOnlyOrNoTransform,
						onlyRetrieveFromCache,
						options,
						engineJob);

		jobs.put(key, engineJob);
//上面也提到回调，这里先cb添加到engineJob.addCallback();中，然后调用EngineJob的start方法来开启线程。
		engineJob.addCallback(cb, callbackExecutor);
// ④：分别使用engineJobFactory和decodeJobFactory构建EngineJob和DecodeJob对象，这两个对象是真正的加载资源的两个重要类，
// EngineJob对象负责开启线程去加载资源，并且加载得资源后转换到主线程并进行回调；DecodeJob是真正的执行者，它就是去网络
// 加载资源的地方，EngineJob开启线程，
// 真正执行的是DecodeJob，DecodeJob之后完毕之后叫道EngineJob去分发回调。这就是这两个类的关系。
		engineJob.start(decodeJob);

		if (VERBOSE_IS_LOGGABLE) {
			logWithTimeAndKey("Started new load", startTime, key);
		}
		return new LoadStatus(cb, engineJob);
	}

	private static void logWithTimeAndKey(String log, long startTime, Key key) {
		Log.v(TAG, log + " in " + LogTime.getElapsedMillis(startTime) + "ms, key: " + key);
	}

	@Nullable
	private EngineResource<?> loadFromActiveResources(Key key, boolean isMemoryCacheable) {
		if (!isMemoryCacheable) {
			return null;
		}
		EngineResource<?> active = activeResources.get(key);
		if (active != null) {
			active.acquire();
		}

		return active;
	}

	private EngineResource<?> loadFromCache(Key key, boolean isMemoryCacheable) {
		if (!isMemoryCacheable) {
			return null;
		}

		EngineResource<?> cached = getEngineResourceFromCache(key);
		if (cached != null) {
			cached.acquire();
			activeResources.activate(key, cached);
		}
		return cached;
	}

	private EngineResource<?> getEngineResourceFromCache(Key key) {
		Resource<?> cached = cache.remove(key);

		final EngineResource<?> result;
		if (cached == null) {
			result = null;
		} else if (cached instanceof EngineResource) {
			// Save an object allocation if we've cached an EngineResource (the typical case).
			result = (EngineResource<?>) cached;
		} else {
			result = new EngineResource<>(
					cached, /*isMemoryCacheable=*/ true, /*isRecyclable=*/ true, key, /*listener=*/ this);
		}
		return result;
	}

	public void release(Resource<?> resource) {
		if (resource instanceof EngineResource) {
			((EngineResource<?>) resource).release();
		} else {
			throw new IllegalArgumentException("Cannot release anything but an EngineResource");
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public synchronized void onEngineJobComplete(
			EngineJob<?> engineJob, Key key, EngineResource<?> resource) {
		// A null resource indicates that the load failed, usually due to an exception.
		if (resource != null && resource.isMemoryCacheable()) {
			activeResources.activate(key, resource);
		}

		jobs.removeIfCurrent(key, engineJob);
	}

	@Override
	public synchronized void onEngineJobCancelled(EngineJob<?> engineJob, Key key) {
		jobs.removeIfCurrent(key, engineJob);
	}

	@Override
	public void onResourceRemoved(@NonNull final Resource<?> resource) {
		resourceRecycler.recycle(resource);
	}

	@Override
	public synchronized void onResourceReleased(Key cacheKey, EngineResource<?> resource) {
		activeResources.deactivate(cacheKey);
		if (resource.isMemoryCacheable()) {
			cache.put(cacheKey, resource);
		} else {
			//bitmapPool.put(drawable.getBitmap()); 回收是放进bitmapPool中，因为弱引用，随时被回收
			resourceRecycler.recycle(resource);
		}
	}

	public void clearDiskCache() {
		diskCacheProvider.getDiskCache().clear();
	}

	@VisibleForTesting
	public void shutdown() {
		engineJobFactory.shutdown();
		diskCacheProvider.clearDiskCacheIfCreated();
		activeResources.shutdown();
	}

	/**
	 * Allows a request to indicate it no longer is interested in a given load.
	 *
	 * <p>Non-final for mocking.
	 */
	public class LoadStatus {
		private final EngineJob<?> engineJob;
		private final ResourceCallback cb;

		LoadStatus(ResourceCallback cb, EngineJob<?> engineJob) {
			this.cb = cb;
			this.engineJob = engineJob;
		}

		public void cancel() {
			// Acquire the Engine lock so that a new request can't get access to a particular EngineJob
			// just after the EngineJob has been cancelled. Without this lock, we'd allow new requests
			// to find the cancelling EngineJob in our Jobs data structure. With this lock, the EngineJob
			// is both cancelled and removed from Jobs atomically.
			synchronized (Engine.this) {
				engineJob.removeCallback(cb);
			}
		}
	}

	private static class LazyDiskCacheProvider implements DecodeJob.DiskCacheProvider {

		private final DiskCache.Factory factory;
		private volatile DiskCache diskCache;

		LazyDiskCacheProvider(DiskCache.Factory factory) {
			this.factory = factory;
		}

		@VisibleForTesting
		synchronized void clearDiskCacheIfCreated() {
			if (diskCache == null) {
				return;
			}
			diskCache.clear();
		}

		@Override
		public DiskCache getDiskCache() {
			if (diskCache == null) {
				synchronized (this) {
					if (diskCache == null) {
						diskCache = factory.build();
					}
					if (diskCache == null) {
						diskCache = new DiskCacheAdapter();
					}
				}
			}
			return diskCache;
		}
	}

	@VisibleForTesting
	static class DecodeJobFactory {
		@Synthetic
		final DecodeJob.DiskCacheProvider diskCacheProvider;
		@Synthetic
		final Pools.Pool<DecodeJob<?>> pool =
				FactoryPools.threadSafe(JOB_POOL_SIZE,
						new FactoryPools.Factory<DecodeJob<?>>() {
							@Override
							public DecodeJob<?> create() {
								return new DecodeJob<>(diskCacheProvider, pool);
							}
						});
		private int creationOrder;

		DecodeJobFactory(DecodeJob.DiskCacheProvider diskCacheProvider) {
			this.diskCacheProvider = diskCacheProvider;
		}

		@SuppressWarnings("unchecked")
		<R> DecodeJob<R> build(GlideContext glideContext,
							   Object model,
							   EngineKey loadKey,
							   Key signature,
							   int width,
							   int height,
							   Class<?> resourceClass,
							   Class<R> transcodeClass,
							   Priority priority,
							   DiskCacheStrategy diskCacheStrategy,
							   Map<Class<?>, Transformation<?>> transformations,
							   boolean isTransformationRequired,
							   boolean isScaleOnlyOrNoTransform,
							   boolean onlyRetrieveFromCache,
							   Options options,
							   DecodeJob.Callback<R> callback) {
			DecodeJob<R> result = Preconditions.checkNotNull((DecodeJob<R>) pool.acquire());
			return result.init(
					glideContext,
					model,
					loadKey,
					signature,
					width,
					height,
					resourceClass,
					transcodeClass,
					priority,
					diskCacheStrategy,
					transformations,
					isTransformationRequired,
					isScaleOnlyOrNoTransform,
					onlyRetrieveFromCache,
					options,
					callback,
					creationOrder++);
		}
	}

	@VisibleForTesting
	static class EngineJobFactory {
		@Synthetic
		final GlideExecutor diskCacheExecutor;
		@Synthetic
		final GlideExecutor sourceExecutor;
		@Synthetic
		final GlideExecutor sourceUnlimitedExecutor;
		@Synthetic
		final GlideExecutor animationExecutor;
		@Synthetic
		final EngineJobListener engineJobListener;
		@Synthetic
		final ResourceListener resourceListener;
		@Synthetic
		final Pools.Pool<EngineJob<?>> pool =
				FactoryPools.threadSafe(
						JOB_POOL_SIZE,
						new FactoryPools.Factory<EngineJob<?>>() {
							@Override
							public EngineJob<?> create() {
								return new EngineJob<>(
										diskCacheExecutor,
										sourceExecutor,
										sourceUnlimitedExecutor,
										animationExecutor,
										engineJobListener,
										resourceListener,
										pool);
							}
						});

		EngineJobFactory(
				GlideExecutor diskCacheExecutor,
				GlideExecutor sourceExecutor,
				GlideExecutor sourceUnlimitedExecutor,
				GlideExecutor animationExecutor,
				EngineJobListener engineJobListener,
				ResourceListener resourceListener) {
			this.diskCacheExecutor = diskCacheExecutor;
			this.sourceExecutor = sourceExecutor;
			this.sourceUnlimitedExecutor = sourceUnlimitedExecutor;
			this.animationExecutor = animationExecutor;
			this.engineJobListener = engineJobListener;
			this.resourceListener = resourceListener;
		}

		@VisibleForTesting
		void shutdown() {
			Executors.shutdownAndAwaitTermination(diskCacheExecutor);
			Executors.shutdownAndAwaitTermination(sourceExecutor);
			Executors.shutdownAndAwaitTermination(sourceUnlimitedExecutor);
			Executors.shutdownAndAwaitTermination(animationExecutor);
		}

		@SuppressWarnings("unchecked")
		<R> EngineJob<R> build(
				Key key,
				boolean isMemoryCacheable,
				boolean useUnlimitedSourceGeneratorPool,
				boolean useAnimationPool,
				boolean onlyRetrieveFromCache) {
			EngineJob<R> result = Preconditions.checkNotNull((EngineJob<R>) pool.acquire());
			return result.init(
					key,
					isMemoryCacheable,
					useUnlimitedSourceGeneratorPool,
					useAnimationPool,
					onlyRetrieveFromCache);
		}
	}
}
