package com.github.tvbox.osc.player;

import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.sourcedata.SourceViewModel;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlayerHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;

/**
 * 取流状态与结果观察者:持有取流通道并把结果落到会话数据(清晰度/进度键/字幕/歌词/封面),
 * 起播与解析分发仍由宿主完成。
 */
final class PlaybackFetch {

    private final PlaybackController controller;

    private SourceViewModel sourceViewModel;
    private Observer<JSONObject> playResultObserver;

    PlaybackFetch(PlaybackController controller) {
        this.controller = controller;
    }

    /** 建立取流结果观察者(预载协调器仍归页面) */
    void init() {
        sourceViewModel = new SourceViewModel();
        playResultObserver = new Observer<JSONObject>() {
            @Override
            public void onChanged(JSONObject info) {
                handlePlayResult(info);
            }
        };
        sourceViewModel.playResult.observeForever(playResultObserver);
    }

    /** 页面销毁时注销观察者(对应原 hostDestroy 的 removeObserver) */
    void release() {
        if (sourceViewModel != null && playResultObserver != null) {
            sourceViewModel.playResult.removeObserver(playResultObserver);
            playResultObserver = null;
        }
    }

    /** 把“已准备好的取流结果”直接喂给解析链(页面 play() 命中预载数据时调用) */
    void deliver(JSONObject info) {
        if (playResultObserver != null) playResultObserver.onChanged(info);
    }

    /** 预载协调器需要它取流(PreloadCoordinator 构造参数) */
    @Nullable
    SourceViewModel sourceViewModel() {
        return sourceViewModel;
    }

    /** 取消在途取流请求 */
    void cancelPlayRequest() {
        if (sourceViewModel != null) sourceViewModel.cancelPlayRequest();
    }

    private void handlePlayResult(JSONObject info) {
        if (info == null) controller.publishQuality(null);
        if (info != null) {
            try {
                if (controller.isStalePlayResult(info)) {
                    LOG.i("echo-ignore stale play result");
                    return;
                }
                PlaybackViewBridge view = controller.viewBridge();
                if (view != null && controller.isSwitchStopPending()) {
                    // 换源点击即停后,旧源在途的取流结果不得再拉起播放
                    LOG.i("echo-ignore play result while source switching");
                    return;
                }
                controller.cancelResolvePlayUrlTimeout();
                controller.publishQuality(info);
                controller.setWebPlayUrl(null);
                controller.setProgressKey(info.optString("proKey", null));
                boolean parse = info.optString("parse", "1").equals("1");
                boolean jx = info.optString("jx", "0").equals("1");
                controller.setPlaySubtitle(info.optString("subt", ""));
                controller.setPlayLyric(info.optString("lyric", ""));
                controller.setLyricCacheKey(info.optString("lyricKey", null));
                if (TextUtils.isEmpty(controller.lyricCacheKey()) && !TextUtils.isEmpty(controller.progressKey())) {
                    controller.setLyricCacheKey(controller.progressKey() + "-lyric");
                }
                JSONArray lyrics = info.optJSONArray("lyrics");
                if (lyrics != null && lyrics.length() > 0) {
                    controller.setPlayLyric(getSubtitleUrl(lyrics.optJSONObject(0)));
                }
                JSONArray subtitles = info.optJSONArray("subs");
                if (subtitles != null) {
                    for (int i = 0; i < subtitles.length(); i++) {
                        JSONObject obj = subtitles.optJSONObject(i);
                        if (obj == null) continue;
                        String url = getSubtitleUrl(obj);
                        String name = obj.optString("name", "");
                        if (isLyricSubtitle(name)) {
                            if (TextUtils.isEmpty(controller.playLyric())) controller.setPlayLyric(url);
                        } else if (TextUtils.isEmpty(controller.playSubtitle())) {
                            controller.setPlaySubtitle(url);
                        }
                    }
                }
                controller.setSubtitleCacheKey(info.optString("subtKey", null));
                String lyricPick = controller.playLyric();
                LOG.i("echo-lyric pick: " + (TextUtils.isEmpty(lyricPick) ? "none"
                        : lyricPick.startsWith("data:") ? "inline len=" + lyricPick.length() : lyricPick));
                String playUrl = info.optString("playUrl", "");
                String flag = info.optString("flag");
                Object rawUrl = info.opt("url");
                String url = rawUrl instanceof JSONArray ? rawUrl.toString() : String.valueOf(rawUrl);
                if (url.startsWith("[") && view != null) {
                    url = view.firstUrlByArray(url);
                }
                // 音乐源取流结果的封面字段常是 cover 而不是 artwork;漏读会让换集后海报不刷新
                String artwork = info.optString("artwork", "");
                if (TextUtils.isEmpty(artwork)) artwork = info.optString("cover", "");
                if (TextUtils.isEmpty(artwork) && !TextUtils.isEmpty(controller.playLyric()) && controller.vod() != null) {
                    artwork = controller.vod().pic;
                }
                controller.setCurrentArtwork(artwork);
                if (view != null) view.setArtwork(artwork);
                String msg = info.optString("msg", "");
                if (!TextUtils.isEmpty(msg)) {
                    controller.handleResolvePlayUrlFailed(msg);
                    return;
                }
                // 取流成功,手动选线标记完成使命,后续失败恢复走正常自动策略
                controller.setUserPickedLine(false);
                controller.setWebUserAgent(null);
                controller.setWebHeaderMap(null);
                HashMap<String, String> headers = PlaybackController.extractHeaders(info);
                if (headers != null) {
                    controller.setWebHeaderMap(headers);
                    String ua = PlaybackController.headerValue(headers, "user-agent");
                    controller.setWebUserAgent(ua == null ? null : ua.trim());
                }
                if (parse || jx) {
                    boolean userJxList = (playUrl.isEmpty() && ApiConfig.get().getVipParseFlags().contains(flag)) || jx;
                    controller.initParse(flag, userJxList, playUrl, url);
                } else {
                    if (view != null) view.showParse(false);
                    if (view != null) controller.playUrl(playUrl + url, headers);
                }
            } catch (Throwable th) {
                controller.handleResolvePlayUrlFailed(controller.str(R.string.player_get_info_error));
            }
        } else {
            // 获取播放信息错误后只需再重试一次
            controller.handleResolvePlayUrlFailed(controller.str(R.string.player_get_info_error));
        }
    }

    private String getSubtitleUrl(JSONObject object) {
        if (object == null) return "";
        String format = object.optString("format", "");
        String name = object.optString("name", controller.str(R.string.player_menu_subtitle));
        String ext = ".srt";
        if ("text/x-ssa".equals(format)) {
            ext = ".ass";
        } else if ("text/vtt".equals(format)) {
            ext = ".vtt";
        } else if ("text/lrc".equals(format)) {
            ext = ".lrc";
        }
        String filename = name + (name.toLowerCase(Locale.ROOT).endsWith(ext) ? "" : ext);
        String url = object.optString("url", "");
        String data = object.optString("data", "");
        // 本地代理 URL 要靠爬虫的内存态现取,拿不到就整段没有字幕/歌词;同一份内容已在 data 里时直接用
        if (!TextUtils.isEmpty(data) && (TextUtils.isEmpty(url) || PlayerHelper.isLocalProxyUrl(url))) {
            url = "data:text/plain;base64," + Base64.encodeToString(data.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            // data: URI 的文件名只能靠 fragment 带(内容里出现的点会让 hasExtension 误判)
            PlaybackViewBridge view = controller.viewBridge();
            return view == null ? url : url + "#" + view.encodeUrl(filename);
        }
        if (TextUtils.isEmpty(url) || FileUtils.hasExtension(url)) return url;
        PlaybackViewBridge view = controller.viewBridge();
        return view == null ? url : url + "#" + view.encodeUrl(filename);
    }

    private boolean isLyricSubtitle(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String value = name.toLowerCase(Locale.ROOT);
        return value.contains("lyric") || value.contains("lrc") || name.contains("歌词"); // i18n: keep
    }
}
