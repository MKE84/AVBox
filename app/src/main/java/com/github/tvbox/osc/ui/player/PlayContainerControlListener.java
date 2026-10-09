package com.github.tvbox.osc.ui.player;

import android.text.TextUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.controller.VodControlListener;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.WatchProgressStore;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONObject;
import java.util.HashMap;

final class PlayContainerControlListener implements VodControlListener {

    private final PlayContainer container;

    PlayContainerControlListener(PlayContainer container) {
        this.container = container;
    }

    @Override
    public void showEpisodes() {
        if (container.pageHost != null) container.pageHost.showEpisodeSheet();
    }

    @Override
    public void playNext(boolean rmProgress) {
        String preProgressKey = container.scheduler.progressKey();
        String preOwner = container.scheduler.progressOwner();
        container.playNext(rmProgress);
        if (rmProgress && preProgressKey != null)
            WatchProgressStore.clear(preOwner, preProgressKey);
    }

    @Override
    public void playPre() {
        container.playPrevious();
    }

    @Override
    public void changeParse(ParseBean pb) {
        container.scheduler.resetAutoRetryState();
        container.scheduler.clearTriedLines();
        container.scheduler.doParse(pb);
    }

    @Override
    public void updatePlayerCfg() {
        JSONObject persistCfg = container.scheduler.playerCfgForPersist();
        if (persistCfg == null) return;
        container.scheduler.vod().playerCfg = persistCfg.toString();
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, persistCfg));
    }

    @Override
    public void replay(boolean replay) {
        container.reviveEngineIfReleased();
        container.scheduler.resetAutoRetryState();
        container.scheduler.clearTriedLines();
        container.scheduler.setPlaybackStarted(false);
        if(replay){
            container.playViaScheduler(true);
        }else {
            container.replayCurrentAddress();
        }
    }

    @Override
    public void errReplay() {
        container.errorWithRetry(container.getContext().getString(R.string.player_error_play), false);
    }

    @Override
    public void closeSubtitles() {
        container.closeSubtitles();
    }

    @Override
    public void selectSubtitle() {
        try {
            container.selectMySubtitle();
        } catch (Exception e) {
            LOG.e("PlayContainer", e);
        }
    }

    @Override
    public void selectAudioTrack() {
        container.selectMyAudioTrack();
    }

    @Override
    public void selectVideoTrack() {
        container.selectMyVideoTrack();
    }

    @Override
    public void prepared() {
        container.initSubtitleView();
    }
    @Override
    public void startPlayUrl(String url, HashMap<String, String> headers) {
        if (!TextUtils.isEmpty(container.scheduler.m3u8SourceUrl()) && !container.scheduler.isM3u8ProxyUrl(url)) container.scheduler.clearM3u8ProxyUrl();
        container.scheduler.goPlayUrl(url, headers);
    }

    @Override
    public void onM3u8ProxyUrl(String proxyUrl, String sourceUrl) {
        container.scheduler.setM3u8Urls(proxyUrl, sourceUrl);
    }

    @Override
    public void clickCast() {
        container.showCastDialog();
    }

    @Override
    public void setAllowSwitchPlayer(boolean isAllow){container.scheduler.setAllowSwitchPlayer(isAllow);}

    @Override
    public void setAllowDecodeFallback(boolean isAllow){container.scheduler.setAllowDecodeFallback(isAllow);}
}
