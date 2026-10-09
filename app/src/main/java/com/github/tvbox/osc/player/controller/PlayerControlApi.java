package com.github.tvbox.osc.player.controller;

import android.webkit.WebView;
import android.widget.TextView;

import androidx.media3.ui.SubtitleView;

import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.state.PlayerUiState;

import org.json.JSONObject;

import java.util.HashMap;

public interface PlayerControlApi {

    interface KernelProvider {
        MyVideoView get();
    }

    void setKernelProvider(KernelProvider provider);

    SubtitleView getExoSubtitleView();

    PlayerUiState getUiState();


    void setListener(VodControlListener listener);


    void setPlayerConfig(JSONObject playerCfg);

    void showParse(boolean userJxList);

    void setPreviewMode(boolean previewMode);

    void setTitle(String playTitleInfo);

    void setUrlTitle(String playTitleInfo);

    void setHasDanmu(boolean hasDanmu);

    void setCanChangePosition(boolean canChangePosition);

    void setEnableInNormal(boolean enableInNormal);

    void setGestureEnabled(boolean gestureEnabled);

    void toggleControlBar();

    void hidePauseRoot();

    void onNewPlayStarted();

    void setLifecyclePaused(boolean paused);

    void resetSpeed();

    boolean onBackPressed();

    boolean switchPlayer();

    void stopOther();


    void playM3u8(String url, HashMap<String, String> headers);

    String encodeUrl(String url);

    String firstUrlByArray(String url);

    void evaluateScript(SourceBean sourceBean, String url, WebView view);

    String getWebPlayUrlIfNeeded(String webPlayUrl);
}
