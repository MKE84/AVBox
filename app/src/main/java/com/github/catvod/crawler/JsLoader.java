package com.github.catvod.crawler;


import android.util.Log;


import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;

import com.github.catvod.crawler.js.JsSpider;
import com.lzy.okgo.OkGo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import okhttp3.Response;
import com.github.tvbox.osc.util.AppContextHolder;

public class JsLoader {
    private static final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Class<?>> classes = new ConcurrentHashMap<>();
    //当前的Js爬虫key
    private volatile String recentKey = "";

    /** 后台刷新过期 js 缓存用;daemon 线程,不阻止进程退出 */
    private static final java.util.concurrent.ExecutorService refreshExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "js-cache-refresh");
                t.setDaemon(true);
                return t;
            });

    public static void destroy() {
        for (Spider spider : spiders.values()){
            spider.cancelByTag();
            spider.destroy();
        }
    }

    public synchronized void clear() {
        for (Spider spider : spiders.values()) {
            spider.cancelByTag();
            spider.destroy();
        }
        spiders.clear();
        classes.clear();
        recentKey = "";
    }

    public static void stopAll() {
        for (Spider spider : spiders.values()){
            spider.cancelByTag();
        }
    }

    private boolean loadClassLoader(String jar, String key) {
        boolean success = false;
        Class<?> classInit = null;
        try {
            File cacheDir = new File(AppContextHolder.context().getCacheDir().getAbsolutePath() + "/catvod_jsapi");
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            DexClassLoader classLoader = new DexClassLoader(jar, cacheDir.getAbsolutePath(), null, AppContextHolder.context().getClassLoader());
            int count = 0;
            do {
                try {
                    try {
                        classInit = classLoader.loadClass("com.github.catvod.js.Function");
                        classInit.getDeclaredConstructor(com.whl.quickjs.wrapper.QuickJSContext.class);
                        Log.i("JSLoader", "echo-load_com.github.catvod.js.Function");
                    } catch (Throwable ignored) {
                        classInit = classLoader.loadClass("com.github.catvod.js.Method");
                        classInit.getDeclaredConstructor(com.whl.quickjs.wrapper.QuickJSContext.class);
                        Log.i("JSLoader", "echo-load_com.github.catvod.js.Method");
                    }
                    if (classInit != null) {
                        Log.i("JSLoader", "echo-自定义jsapi代码加载成功!");
                        success = true;
                        break;
                    }
                    Thread.sleep(200);
                } catch (Throwable th) {
                    LOG.e("JsLoader", th);
                }
                count++;
            } while (count < 5);

            if (success) {
                classes.put(key, classInit);
            }
        } catch (Throwable th) {
            LOG.e("JsLoader", th);
        }
        return success;
    }

    /**
     * 后台异步刷新过期的 js 源缓存:先落临时文件,下载成功且非空才原子替换,
     * 避免半截文件覆盖掉可用的旧缓存;刷新期间用户已经在用旧缓存,无感知。
     */
    private void refreshJarAsync(String jar, File cache, String key) {
        if (jar == null || jar.isEmpty()) return;
        refreshExecutor.execute(() -> {
            File tmp = new File(cache.getAbsolutePath() + ".tmp");
            try {
                Response response = OkGo.<File>get(jar).execute();
                InputStream is = response.body().byteStream();
                OutputStream os = new FileOutputStream(tmp);
                try {
                    byte[] buffer = new byte[2048];
                    int length;
                    while ((length = is.read(buffer)) > 0) os.write(buffer, 0, length);
                    os.flush();
                } finally {
                    try { is.close(); } catch (Exception ignored) {}
                    try { os.close(); } catch (Exception ignored) {}
                }
                if (tmp.exists() && tmp.length() > 0) {
                    // 替换后 classes 里的旧 Class 仍可用(同一 key),下次启动自然读新文件
                    cache.delete();
                    if (tmp.renameTo(cache)) {
                        Log.i("JSLoader", "echo-cache refreshed: " + key);
                    } else {
                        tmp.delete();
                    }
                }
            } catch (Throwable e) {
                LOG.d("JsLoader", "refresh jar failed: " + key);
                tmp.delete();
            }
        });
    }

    private Class<?> loadJarInternal(String jar, String md5, String key) {
        if (classes.containsKey(key)){
            Log.i("JSLoader", "echo-loadJarInternal cached");
            return classes.get(key);
        }
        File cache = new File(AppContextHolder.context().getFilesDir().getAbsolutePath() + "/csp/" + key + ".jar");
        try {
            // BugReview #15:csp 父目录只有全局 jar 下载路径会创建;仅含站点级 jar 时目录
            // 不存在,new FileOutputStream(cache) 抛 FileNotFoundException,js 源全变 SpiderNull
            File parent = cache.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
        } catch (Throwable ignored) {
            LOG.d("JsLoader", "create csp dir failed");
        }
        if (!md5.isEmpty()) {
            if (cache.exists() && MD5.getFileMd5(cache).equalsIgnoreCase(md5)) {
                loadClassLoader(cache.getAbsolutePath(), key);
                return classes.get(key);
            }
        }else {
            if (cache.exists()) {
                if (!FileUtils.isWeekAgo(cache)) {
                    // 未过期:直接用缓存,不碰网络
                    if(loadClassLoader(cache.getAbsolutePath(), key)){
                        return classes.get(key);
                    }
                } else {
                    // 已过期:先用旧缓存立即建立(不阻塞用户),再后台异步刷新。
                    // 旧实现是同步下载,网络差/远端抽风时这里会卡住切源首屏 —— stale-while-revalidate。
                    boolean servedFromStale = loadClassLoader(cache.getAbsolutePath(), key);
                    refreshJarAsync(jar, cache, key);
                    if (servedFromStale) return classes.get(key);
                }
            }
        }
        try {
            Response response = OkGo.<File>get(jar).execute();
            InputStream is = response.body().byteStream();
            OutputStream os = new FileOutputStream(cache);
            try {
                byte[] buffer = new byte[2048];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }
            } finally {
                try {
                    is.close();
                    os.close();
                } catch (Exception e) {
                    LOG.e("JsLoader", e);
                }
            }
            loadClassLoader(cache.getAbsolutePath(), key);
            return classes.get(key);
        } catch (Throwable e) {
            LOG.e("JsLoader", e);
            // 网络抖动/远端 502 时不要把源整死:本地已有缓存就继续用它兜底,
            // 下次再试网络刷新(过期策略仍在,只是这次不因下载失败丢源)
            if (cache.exists() && cache.length() > 0) {
                LOG.i("echo-download failed, fallback to cached jar: " + key);
                if (loadClassLoader(cache.getAbsolutePath(), key)) {
                    return classes.get(key);
                }
            }
        }
        return null;
    }

    public synchronized Spider getSpider(String key, String api, String ext, String jar) {
        recentKey = key;
        if (spiders.containsKey(key)){
            Log.i("JSLoader", "echo-getSpider cached "+key);
            return spiders.get(key);
        }
        Class<?> classLoader = null;
        if (!jar.isEmpty()) {
            String[] urls = jar.split(";md5;");
            String jarUrl = urls[0];
            String jarKey = MD5.string2MD5(jarUrl);
            String jarMd5 = urls.length > 1 ? urls[1].trim() : "";
            classLoader = loadJarInternal(jarUrl, jarMd5, jarKey);
        }
        // BugReview #19:sp 声明放 try 外,init 抛异常时 JsSpider 已创建 QuickJSContext +
        // 单线程 executor,不 destroy 会泄漏 native runtime 与线程(构造失败场景由
        // JsSpider 构造函数自行兜底,此时 sp 为 null)
        Spider sp = null;
        try {
            Log.i("JSLoader", "echo-getSpider load");
            sp = new JsSpider(key, api, classLoader);
            sp.siteKey = key;
            sp.init(AppContextHolder.context(), ext);
            spiders.put(key, sp);
            return sp;
        } catch (Throwable th) {
            LOG.i("echo-getSpider-error "+th.getMessage());
            if (sp != null) {
                try {
                    sp.destroy();
                } catch (Throwable ignored) {
                    LOG.d("JsLoader", "destroy spider failed");
                }
            }
        }
        return new SpiderNull();
    }

    public Object[] proxyInvoke(Map<String, String> params) {
        try {
            Spider proxyFun = spiders.get(recentKey);
            if (proxyFun != null) {
                return proxyFun.proxyLocal(params);
            }
        } catch (Throwable th) {
            LOG.e("JsLoader", "proxy invoke failed", th);
        }
        return null;
    }
}
