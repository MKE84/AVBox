package com.github.tvbox.osc.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.Observer;
import androidx.lifecycle.MutableLiveData;

import com.github.tvbox.osc.data.CacheManager;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.Preconnect;
import com.github.tvbox.osc.util.WatchProgressStore;
import com.github.tvbox.osc.util.thunder.Jianpian;
import com.github.tvbox.osc.sourcedata.SourceViewModel;
import com.github.tvbox.osc.util.KV;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 下一集预载的编排:目标评估时机(正片稳定/缓冲让路/缓冲结束补枪)、结果取用与冷却期都在这里。
 *
 * <p>归 {@code player} 而不是 {@code ui.player}:它由引擎创建、随引擎存活(页面只是喂快照),
 * 且不依赖任何页面类型 —— 放在 ui 包会让 {@code player} 反向依赖 UI。
 */
public final class PreloadCoordinator {
    private static final long EVALUATE_DELAY_MS = 1500L;
    /** 持续缓冲多久才让路(取消预载+清数据):短暂缓冲(拖动/瞬断)不停预载,否则每次拖动都从头重下 */
    private static final long BUFFERING_YIELD_MS = 3000L;
    private static final long BUFFERING_COOLDOWN_MS = 5_000L;
    private static final String PRELOAD_KEY_SUFFIX = "-preload";

    public static final class Snapshot {
        public final Context context;
        public final String sourceKey;
        public final String playFlag;
        public final String currentKey;
        public final String nextKey;
        public final String nextUrl;
        public final String nextSubtitleKey;
        public final long startSkipMs;
        public final boolean exoKernel;

        public Snapshot(Context context, String sourceKey, String playFlag, String currentKey,
                        String nextKey, String nextUrl, String nextSubtitleKey, long startSkipMs, boolean exoKernel) {
            this.context = context;
            this.sourceKey = sourceKey;
            this.playFlag = playFlag;
            this.currentKey = currentKey;
            this.nextKey = nextKey;
            this.nextUrl = nextUrl;
            this.nextSubtitleKey = nextSubtitleKey;
            this.startSkipMs = startSkipMs;
            this.exoKernel = exoKernel;
        }
    }

    private static final class CachedEntry {
        final JSONObject info;
        final long at;

        CachedEntry(JSONObject info, long at) {
            this.info = info;
            this.at = at;
        }
    }

    private final SourceViewModel sourceViewModel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean evaluatePending = new AtomicBoolean(false);

    private String requestToken;
    private String gaveUpKey;
    private String preloadedKey;
    private Snapshot activeSnapshot;
    private long bufferingCooldownUntil;
    private boolean observing;
    private boolean replayReadyPending;
    /** 预解析直链缓存池:键 = progressKey(含源/片/线路/集名),消费即删;TTL 与容量见 PreloadCachePolicy */
    private final LinkedHashMap<String, CachedEntry> cache = new LinkedHashMap<>();

    /** 缓冲持续到阈值才执行的让路:取消预载并清数据(短暂缓冲不执行,见 onMainPlayerBuffering) */
    private final Runnable bufferingYield = () -> {
        LOG.i("echo-preload-yield: sustained buffering");
        PreloadManagerHolder.clearAll();
        preloadedKey = null;
        bufferingCooldownUntil = System.currentTimeMillis() + BUFFERING_COOLDOWN_MS;
    };

    private final Observer<JSONObject> preloadResultObserver = new Observer<JSONObject>() {
        @Override
        public void onChanged(JSONObject info) {
            handlePreloadResult(info);
        }
    };

    public PreloadCoordinator(SourceViewModel sourceViewModel) {
        this.sourceViewModel = sourceViewModel;
        MutableLiveData<JSONObject> channel = sourceViewModel.preloadResult;
        if (channel != null) {
            channel.observeForever(preloadResultObserver);
            observing = true;
        }
    }

    public void scheduleEvaluate(Snapshot snapshot) {
        if (snapshot == null || !PreloadManagerHolder.enabled()) {
            LOG.i("echo-preload-skip: " + (snapshot == null ? "no next episode" : "switch off"));
            return;
        }
        // 已恢复播放/缓冲结束:撤销待执行的让路(短暂缓冲不打断预载,已下数据与在途下载都保留)
        handler.removeCallbacks(bufferingYield);
        postEvaluate(snapshot, EVALUATE_DELAY_MS);
    }

    private void postEvaluate(Snapshot snapshot, long delayMs) {
        if (!evaluatePending.compareAndSet(false, true)) return;
        handler.postDelayed(() -> {
            evaluatePending.set(false);
            evaluate(snapshot);
        }, delayMs);
    }

    public void invalidate() {
        handler.removeCallbacksAndMessages(null);
        evaluatePending.set(false);
        replayReadyPending = false;
        requestToken = null;
        activeSnapshot = null;
    }

    public void dropPreloadData() {
        preloadedKey = null;
        PreloadManagerHolder.clearAll();
    }

    public void onMainPlayerBuffering() {
        if (!PreloadManagerHolder.enabled()) return;
        bufferingCooldownUntil = System.currentTimeMillis() + BUFFERING_COOLDOWN_MS;
        replayReadyPending = true;
        // 先不动预载:拖动/瞬断造成的短暂缓冲结束后数据仍在(evaluate 会重放就绪提示);
        // 持续缓冲才真正让路(取消 + 清数据),避免弱网下预载与正片抢带宽
        handler.removeCallbacks(bufferingYield);
        handler.postDelayed(bufferingYield, BUFFERING_YIELD_MS);
    }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        evaluatePending.set(false);
        replayReadyPending = false;
        requestToken = null;
        activeSnapshot = null;
        clearCache();
        if (observing && sourceViewModel != null) {
            sourceViewModel.preloadResult.removeObserver(preloadResultObserver);
            observing = false;
        }
        PreloadManagerHolder.release();
    }

    private void evaluate(Snapshot snapshot) {
        if (!PreloadManagerHolder.enabled()) return;
        if (!snapshot.exoKernel) {
            LOG.i("echo-preload-skip: non-exo kernel");
            return;
        }
        // 拖动/缓冲结束后:目标没变且已完成 → 立即重放「下一集已就绪」(数据未失效,不重下)
        if (replayReadyPending) {
            replayReadyPending = false;
            if (snapshot.nextKey.equals(preloadedKey)) {
                PreloadManagerHolder.replayReadyIfCompleted();
            }
        }
        long cooldownRemain = bufferingCooldownUntil - System.currentTimeMillis();
        if (cooldownRemain > 0) {
            LOG.i("echo-preload-skip: buffering cooldown, retry in " + cooldownRemain + "ms");
            postEvaluate(snapshot, cooldownRemain);
            return;
        }
        if (snapshot.nextKey.equals(snapshot.currentKey)) return;
        if (snapshot.currentKey.equals(gaveUpKey)) return;
        if (snapshot.nextKey.equals(preloadedKey)) {
            LOG.i("echo-preload-skip: already preloaded");
            return;
        }
        if (preloadedKey != null) {
            dropPreloadData();
        }
        if (snapshot.nextKey.equals(requestToken)) {
            LOG.i("echo-preload-skip: resolving in-flight");
            return;
        }
        if (Jianpian.isJpUrl(snapshot.nextUrl)) {
            gaveUp(snapshot);
            return;
        }
        activeSnapshot = snapshot;
        requestToken = snapshot.nextKey + PRELOAD_KEY_SUFFIX;
        LOG.i("echo-preload-resolve: " + snapshot.nextUrl);
        sourceViewModel.getPlayForPreload(
                snapshot.sourceKey,
                snapshot.playFlag,
                snapshot.nextKey + PRELOAD_KEY_SUFFIX,
                snapshot.nextUrl,
                snapshot.nextSubtitleKey + PRELOAD_KEY_SUFFIX);
    }

    private void handlePreloadResult(JSONObject info) {
        final Snapshot snapshot = activeSnapshot;
        String token = requestToken;
        requestToken = null;
        if (snapshot == null || token == null) {
            LOG.i("echo-preload-result-drop: no active snapshot (late result)");
            return;
        }
        if (info == null || !token.equals(info.optString("proKey", ""))) {
            LOG.i("echo-preload-giveup: stale result, target=" + token);
            gaveUp(snapshot);
            return;
        }
        String msg = info.optString("msg", "");
        boolean parse = info.optString("parse", "1").equals("1");
        boolean jx = info.optString("jx", "0").equals("1");
        String playUrl = info.optString("playUrl", "");
        Object rawUrl = info.opt("url");
        String url = rawUrl instanceof org.json.JSONArray ? rawUrl.toString()
                : (rawUrl == null ? "" : String.valueOf(rawUrl));
        if (parse || jx || !playUrl.isEmpty() || !msg.isEmpty()
                || url.isEmpty()
                || url.startsWith("[")
                || url.startsWith("data:application")
                || url.startsWith("tvbox-xg:")) {
            String reason = parse ? "parse=1" : jx ? "jx=1" : !playUrl.isEmpty() ? "playUrl=" + playUrl
                    : !msg.isEmpty() ? "msg=" + msg : url.isEmpty() ? "empty url"
                    : url.startsWith("[") ? "array url" : url.startsWith("data:application") ? "data: url" : "tvbox-xg";
            LOG.i("echo-preload-giveup: " + reason);
            gaveUp(snapshot);
            return;
        }
        if (isLocalProxyUrl(url)) {
            LOG.i("echo-preload-giveup: local proxy url");
            gaveUp(snapshot);
            return;
        }
        HashMap<String, String> headers = extractHeaders(info);
        long startPos = snapshot.startSkipMs;
        // 无痕:预载起点同样不认旧进度,否则自动连播的下一集会带着上次的位置起播
        if (!HistoryHelper.isIncognito()) {
            try {
                WatchProgressStore.awaitWrites();
                Object history = CacheManager.getCache(MD5.string2MD5(snapshot.nextKey));
                long rec = 0;
                if (history instanceof Long) {
                    rec = (Long) history;
                } else if (history instanceof String) {
                    rec = Long.parseLong((String) history);
                }
                startPos = Math.max(startPos, rec);
            } catch (Throwable ignored) {
                LOG.d("PreloadCoordinator", "read saved progress failed, use snapshot start");
            }
        }
        preloadedKey = snapshot.nextKey;
        LOG.i("echo-preload-resolve-ok: " + url);
        Preconnect.warm(url, headers);
        try {
            info.put("proKey", snapshot.nextKey);
            info.put("subtKey", snapshot.nextSubtitleKey);
        } catch (Throwable ignored) {
            LOG.d("PreloadCoordinator", "mark preload result keys failed");
        }
        putCache(snapshot.nextKey, info);
        PreloadManagerHolder.preload(snapshot.context, url, headers, startPos);
    }

    /** 取用并移除某集的预解析结果;开关关闭/未命中/过期都返回 null,由调用方走正常取流 */
    public JSONObject consumeResult(String realKey) {
        if (realKey == null) return null;
        if (!PreloadManagerHolder.enabled()) {
            // 开关已关:池里旧结果不再复用,否则"关了还在省解析"与开关语义不符
            if (!cache.isEmpty()) cache.clear();
            return null;
        }
        CachedEntry entry = cache.remove(realKey);
        if (entry == null) return null;
        if (PreloadCachePolicy.isExpired(System.currentTimeMillis(), entry.at)) {
            LOG.i("echo-preload-cache-expired: " + realKey);
            return null;
        }
        LOG.i("echo-preload-cache-hit: " + realKey + " size=" + cache.size());
        return entry.info;
    }

    private void clearCache() {
        cache.clear();
    }

    /** 写入一条预解析结果:顺带清过期项、超容量按插入序淘汰最旧(池很小,直接遍历比定时器简单) */
    private void putCache(String key, JSONObject info) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, CachedEntry>> entries = cache.entrySet().iterator();
        while (entries.hasNext()) {
            if (PreloadCachePolicy.isExpired(now, entries.next().getValue().at)) entries.remove();
        }
        cache.put(key, new CachedEntry(info, now));
        Iterator<String> keys = cache.keySet().iterator();
        while (PreloadCachePolicy.sizeExceeded(cache.size()) && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
        LOG.i("echo-preload-cache-put: " + key + " size=" + cache.size());
    }

    private void gaveUp(Snapshot snapshot) {
        gaveUpKey = snapshot.currentKey;
    }

    private static boolean isLocalProxyUrl(String url) {
        return url.startsWith("http://127.0.0.1") || url.startsWith("https://127.0.0.1")
                || url.startsWith("http://localhost") || url.startsWith("https://localhost");
    }

    private static HashMap<String, String> extractHeaders(JSONObject info) {
        return PlayerHelper.extractPlayHeaders(info);
    }
}
