package com.openmediation.sdk.adn.core;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.text.TextUtils;

import com.openmediation.sdk.adn.bean.AdBean;
import com.openmediation.sdk.adn.report.AdReport;
import com.openmediation.sdk.adn.utils.DownloadManager;
import com.openmediation.sdk.adn.utils.PUtils;
import com.openmediation.sdk.adn.utils.ResponseUtil;
import com.openmediation.sdk.adn.utils.error.Error;
import com.openmediation.sdk.adn.utils.error.ErrorBuilder;
import com.openmediation.sdk.adn.utils.error.ErrorCode;
import com.openmediation.sdk.adn.utils.helper.PayloadHelper;
import com.openmediation.sdk.adn.utils.helper.WaterFallHelper;
import com.openmediation.sdk.utils.AdRateUtil;
import com.openmediation.sdk.utils.AdtUtil;
import com.openmediation.sdk.utils.DeveloperLog;
import com.openmediation.sdk.utils.HandlerUtil;
import com.openmediation.sdk.utils.IOUtil;
import com.openmediation.sdk.utils.cache.DataCache;
import com.openmediation.sdk.utils.constant.CommonConstants;
import com.openmediation.sdk.utils.constant.KeyConstants;
import com.openmediation.sdk.utils.crash.CrashUtil;
import com.openmediation.sdk.utils.model.Placement;
import com.openmediation.sdk.utils.model.PlacementInfo;
import com.openmediation.sdk.utils.request.network.Request;
import com.openmediation.sdk.utils.request.network.Response;
import com.openmediation.sdk.utils.request.network.util.NetworkChecker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

public abstract class AbstractAdsManager implements Request.OnRequestCallback {
    protected Context mContext;
    protected AdBean mAdBean;
    protected String mPlacementId;
    protected ListenerWrapper mListenerWrapper;

    private boolean isInShowingProgress;
    private boolean isInLoadingProgress;
    private HandlerUtil.HandlerHolder mHandler;
    private TimeoutRunnable mTimeoutRunnable;
    private int mTimeout;

    public AbstractAdsManager(String placementId) {
        mPlacementId = placementId;
        mContext = AdtUtil.getApplication();
        mListenerWrapper = new ListenerWrapper();
        mHandler = new HandlerUtil.HandlerHolder(null, Looper.getMainLooper());
    }

    protected abstract int getAdType();

    protected boolean isReady() {
        return mAdBean != null && !mAdBean.isExpired();
    }

    protected boolean isIntervalLoadType() {
        return false;
    }

    protected void showAds() {
    }

    protected void destroy() {
        if (mHandler != null && mTimeoutRunnable != null) {
            mHandler.removeCallbacks(mTimeoutRunnable);
        }
        mTimeoutRunnable = null;
        mHandler = null;
    }

    public void loadAds() {
        loadAdsWithPayload(null);
    }

    public void loadAdsWithPayload(String payload) {
        delayLoad(payload);
    }

    private void delayLoad(String payload) {
        if (isInLoadingProgress) {
            return;
        }
        try {
            isInLoadingProgress = true;
            Error error = checkLoadAvailable();
            if (error != null) {
                onAdsLoadFailed(error);
                return;
            }
            if (TextUtils.isEmpty(payload)) {
                if (getAdType() != CommonConstants.PROMOTION && isCacheAdsType() && isReady()) {
                    onAdsLoadSuccess(mAdBean);
                    return;
                }

                //2:interval,4:manual
                WaterFallHelper.wfRequest(getPlacementInfo(), isIntervalLoadType() ? 2 : 4, this);
            } else {
                if (isCacheAdsType()) {
                    mAdBean = null;
                }
                try {
                    // parse payload
                    JSONObject jsonObject = new JSONObject(payload);
                    if (jsonObject.has("payload")) {
                        payload = jsonObject.optString("payload");
                    }
                } catch (Exception ignored) {
                }
                PayloadHelper.payloadRequest(getPlacementInfo(), payload, this);
            }

            if (mTimeout > 0) {
                if (mTimeoutRunnable != null) {
                    mHandler.removeCallbacks(mTimeoutRunnable);
                }
                mTimeoutRunnable = new TimeoutRunnable();
                mHandler.postDelayed(mTimeoutRunnable, mTimeout * 1000);
            }
        } catch (Exception e) {
            CrashUtil.getSingleton().saveException(e);
            onAdsLoadFailed(ErrorBuilder.build(ErrorCode.CODE_LOAD_BEFORE_UNKNOWN_ERROR));
        }
    }

    /**
     * @param clazz Interactive Interstitial Video
     */
    public void show(Class clazz) {
        if (!isReady()) {
            onAdsShowFailed(ErrorBuilder.build(ErrorCode.CODE_SHOW_FAIL_NOT_READY));
            return;
        }

        Intent intent = new Intent(mContext, clazz);
        Bundle bundle = new Bundle();
        bundle.putParcelable("ad", mAdBean);
        intent.putExtra("bundle", bundle);
        intent.putExtra("placementId", mPlacementId);
        intent.putExtra("adType", getAdType());
        intent.putExtra("sceneName",
                DataCache.getInstance().getFromMem(mPlacementId + KeyConstants.KEY_DISPLAY_SCENE, String.class));
        intent.putExtra("abt",
                DataCache.getInstance().getFromMem(mPlacementId + KeyConstants.KEY_DISPLAY_ABT, Integer.class));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    @Override
    public void onRequestSuccess(Response response) {
        try {
            if (response == null || response.code() != HttpsURLConnection.HTTP_OK) {
                Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_SERVER_ERROR);
                onAdsLoadFailed(error);
                return;
            }
            JSONObject jsonObject = new JSONObject(response.body().string());
//            int code = jsonObject.optInt("code");
            JSONArray campaigns = jsonObject.optJSONArray("campaigns");
            if (campaigns == null || campaigns.length() <= 0) {
                Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_NO_FILL);
                onAdsLoadFailed(error);
                return;
            }
            List<AdBean> adBeanList = ResponseUtil.transformResponse(campaigns);
            if (adBeanList == null || adBeanList.isEmpty()) {
                onAdsLoadFailed(ErrorBuilder.build(ErrorCode.CODE_LOAD_PARSE_FAILED));
            } else {
                preLoadRes(adBeanList);
            }
        } catch (Exception e) {
            CrashUtil.getSingleton().saveException(e);
            onAdsLoadFailed(ErrorBuilder.build(ErrorCode.CODE_LOAD_UNKNOWN_EXCEPTION));
        } finally {
            IOUtil.closeQuietly(response);
        }
    }

    @Override
    public void onRequestFailed(String error) {
        DeveloperLog.LogD("onRequestFailed : " + error);
        Error e = ErrorBuilder.build(ErrorCode.CODE_LOAD_SERVER_ERROR);
        onAdsLoadFailed(e);
    }

    /**
     * 仅手动加载可以回，Banner和Native需要交给子类再处理
     */
    protected void onAdsLoadSuccess(AdBean adBean) {
        DeveloperLog.LogD("onAdsLoadSuccess : " + mPlacementId);
        if (!isInLoadingProgress) {
            return;
        }
        onLoadFinish();
        if (isCacheAdsType()) {
            callbackAdsReady();
        }
    }

    /**
     * Banner广告需要判断是否是自刷新，自刷新的加载失败不回调用户
     * 其它类型需要判断是否是手动加载
     */
    protected void onAdsLoadFailed(Error error) {
        DeveloperLog.LogD("onAdsLoadFailed : " + mPlacementId);
        if (!isInLoadingProgress) {
            return;
        }
        onLoadFinish();
        if (getAdType() != CommonConstants.BANNER) {
            callbackAdsFailed(error);
        }
    }

    public void onAdsShowed() {
        DeveloperLog.LogD("onAdsShowed : " + mPlacementId);
        isInShowingProgress = true;
        // TODO
//        AdRateUtil.onPlacementShowed(mPlacementId);
        AdReport.impReport(mContext, mPlacementId, mAdBean);
        callbackAdsShowed();
    }

    protected void onAdsShowFailed(Error error) {
        DeveloperLog.LogD("onAdsShowFailed : " + mPlacementId);
        isInShowingProgress = false;
        callbackAdsShowFailed(error);
    }

    public void onAdsClicked() {
        DeveloperLog.LogD("onAdsClicked : " + mPlacementId);
        callbackAdsClicked();
    }

    /**
     * Video,Interstitial,Interactive在广告关闭之后，需要做下一次的预加载
     */
    protected void onAdsClosed() {
        DeveloperLog.LogD("onAdsClosed : " + mPlacementId);
        isInShowingProgress = false;
        mAdBean = null;
        callbackAdsClosed();
    }

    public void onAddEvents(String event) {
        if (mListenerWrapper == null) {
            return;
        }
        mListenerWrapper.onAdsEvent(mPlacementId, event);
    }

    protected void callbackAdsReady() {
        if (mListenerWrapper == null) {
            return;
        }
        if (isCacheAdsType()) {
            mListenerWrapper.onAdsLoadSuccess(mPlacementId);
        }
    }

    private void onLoadFinish() {
        isInLoadingProgress = false;
        if (mHandler != null && mTimeoutRunnable != null) {
            mHandler.removeCallbacks(mTimeoutRunnable);
        }
    }

    protected void callbackAdsFailed(Error error) {
        if (mListenerWrapper == null) {
            return;
        }
        mListenerWrapper.onAdsLoadFailed(mPlacementId, error);
    }

    private void callbackAdsShowed() {
        if (mListenerWrapper == null) {
            return;
        }
        mListenerWrapper.onAdOpened(mPlacementId);
    }

    private void callbackAdsShowFailed(Error error) {
        if (mListenerWrapper == null) {
            return;
        }
        mListenerWrapper.onAdOpenFailed(mPlacementId, error);
    }

    private void callbackAdsClicked() {
        if (mListenerWrapper == null) {
            return;
        }
        mListenerWrapper.onAdClicked(mPlacementId);
    }

    private void callbackAdsClosed() {
        if (mListenerWrapper == null) {
            return;
        }
        mListenerWrapper.onAdClosed(mPlacementId);
    }

    private boolean isCacheAdsType() {
        switch (getAdType()) {
            case CommonConstants.BANNER:
            case CommonConstants.NATIVE:
                return false;
            default:
                return true;
        }
    }

    protected void preLoadRes(final List<AdBean> adBeanList) {
        preLoadResImpl(adBeanList.get(0));
    }

    protected void preLoadResImpl(AdBean adBean) {
        final List<String> res = new ArrayList<>();
        final int adType = getAdType();
        switch (adType) {
            case CommonConstants.BANNER:
                if (adBean.getResources() == null || adBean.getResources().isEmpty()) {
                    onAdsLoadFailed(ErrorBuilder.build(ErrorCode.CODE_LOAD_SERVER_ERROR));
                    return;
                } else {
                    res.add(adBean.getResources().get(0));
                }
                break;
            case CommonConstants.NATIVE:
                if (!TextUtils.isEmpty(adBean.getIconUrl())) {
                    res.add(adBean.getIconUrl());
                }
                List<String> imgUrls = adBean.getMainimgUrl();
                if (imgUrls != null && imgUrls.size() > 0) {
                    res.addAll(imgUrls);
                }
                break;
            case CommonConstants.INTERSTITIAL:
            case CommonConstants.VIDEO:
            case CommonConstants.PROMOTION:
                if (adBean.getResources() != null) {
                    if (!adBean.getResources().isEmpty()) {
                        res.addAll(adBean.getResources());
                    }
                    if (!TextUtils.isEmpty(adBean.getIconUrl())) {
                        res.add(adBean.getIconUrl());
                    }
                    if (!TextUtils.isEmpty(adBean.getVideoUrl())) {
                        res.add(adBean.getVideoUrl());
                    }
                    List<String> imgUrlList = adBean.getMainimgUrl();
                    if (imgUrlList != null && imgUrlList.size() > 0) {
                        res.addAll(imgUrlList);
                    }
                } else {
                    onAdsLoadFailed(ErrorBuilder.build(ErrorCode.CODE_LOAD_SERVER_ERROR));
                    return;
                }
                break;
            default:
                break;
        }

        try {
            adBean.getSuccess().set(0);
            adBean.getFailed().set(0);
            for (String s : res) {
                DownloadManager.getInstance().downloadFile(s, new DownloadResCallback(res.size(), adType, adBean));
            }
        } catch (Exception e) {
            DeveloperLog.LogD("AdManager loadAd res exception : ", e);
            CrashUtil.getSingleton().saveException(e);
            onAdsLoadFailed(ErrorBuilder.build(ErrorCode.CODE_LOAD_DOWNLOAD_EXCEPTION));
        }
    }

    protected PlacementInfo getPlacementInfo() {
        return new PlacementInfo(mPlacementId).getPlacementInfo(getAdType());
    }

    /**
     * loadAd can start if
     * 1.Activity available
     * 2.network available
     */
    private Error checkLoadAvailable() {

        if (TextUtils.isEmpty(mPlacementId)) {
            Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_PLACEMENT_EMPTY);
            DeveloperLog.LogE("loadAd ad placement is null");
            return error;
        }

        //does nothing if Showing in Progress
        if (isInShowingProgress && getAdType() != CommonConstants.BANNER && getAdType() != CommonConstants.NATIVE) {
            Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_PLACEMENT_IS_SHOWING);
            DeveloperLog.LogD("loadAdWithAction: " + mPlacementId +
                    " cause current is in loading/showing progress");
            return error;
        }

        //network available?
        if (!NetworkChecker.isAvailable(mContext)) {
            Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_NETWORK_ERROR);
            DeveloperLog.LogE("loadAd ad network not available");
            return error;
        }

        Placement placement = PUtils.getPlacement(mPlacementId);
        if (placement == null) {
            Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_PLACEMENT_NOT_FOUND);
            DeveloperLog.LogE(error.toString() + ", placement not found");
            return error;
        }
        mTimeout = placement.getPt();
        if (AdRateUtil.shouldBlockPlacement(placement)) {
            Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_FREQUENCY_ERROR);
            DeveloperLog.LogD(error.toString() + ", Placement :" + mPlacementId + " is blocked");
            return error;
        }

        if (placement.getT() != getAdType()) {
            Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_PLACEMENT_AD_TYPE_INCORRECT);
            DeveloperLog.LogE("placement wrong type, Placement :" + mPlacementId);
            return error;
        }

        // 仅限Banner&Native有效
        if ((getAdType() == CommonConstants.BANNER || getAdType() == CommonConstants.NATIVE) && AdRateUtil.isPlacementCapped(placement)) {
            Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_FREQUENCY_ERROR);
            DeveloperLog.LogD(error.toString() + ", Placement :" + mPlacementId + " is blocked");
            return error;
        }

        return null;
    }

    private void replaceOnlineResToLocal(AdBean adBean, String url, String path) {
        if (TextUtils.equals(url, adBean.getIconUrl())) {
            adBean.setIconUrl(path);
        } else if (adBean.getMainimgUrl() != null && adBean.getMainimgUrl().contains(url)) {
            List<String> imgUrls = adBean.getMainimgUrl();
            int size = imgUrls.size();
            List<String> localImgUrl = adBean.getLocalImgUrl();
            if (localImgUrl == null) {
                localImgUrl = new ArrayList<>(size);
            }
            for (int i = 0; i < size; i ++ ) {
                if (TextUtils.equals(url, imgUrls.get(i))) {
                    localImgUrl.add(i, path);
                    break;
                }
            }
            adBean.setLocalImgUrl(localImgUrl);
        } else if (TextUtils.equals(url, adBean.getVideoUrl())) {
            adBean.setVideoUrl(path);
        } else {
            List<String> resList = adBean.getResources();
            if (resList == null) {
                return;
            }
            if (resList.contains(url)) {
                List<String> localRes = adBean.getLocalResources();
                int size = resList.size();
                if (localRes == null) {
                    localRes = new ArrayList<>(size);
                }
                for (int i = 0; i < size; i ++ ) {
                    if (TextUtils.equals(url, resList.get(i))) {
                        localRes.add(i, path);
                        break;
                    }
                }
                adBean.setLocalResources(localRes);
            }
        }
    }

    private class TimeoutRunnable implements Runnable {

        @Override
        public void run() {
            Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_TIMEOUT);
            onAdsLoadFailed(error);
        }
    }

    private class DownloadResCallback implements DownloadManager.OnResDownloaded {

        private int total;
        private int adType;
        private AdBean adBean;

        DownloadResCallback(int total, int adType, AdBean adBean) {
            this.total = total;
            this.adType = adType;
            this.adBean = adBean;
        }

        @Override
        public void onCompleted(String url, File file) {
            if (file == null) {
                adBean.getFailed().incrementAndGet();
            } else {
                adBean.getSuccess().incrementAndGet();
                replaceOnlineResToLocal(adBean, url, "file://".concat(file.getPath()));
            }
            if (total == (adBean.getSuccess().get() + adBean.getFailed().get())) {
                if (adBean.getFailed().get() > 0) {
                    onAdsLoadFailed(ErrorBuilder.build(ErrorCode.CODE_LOAD_DOWNLOAD_FAILED));
                } else {
                    mAdBean = adBean;
                    mAdBean.setFillTime(System.currentTimeMillis());
                    onAdsLoadSuccess(mAdBean);
                }
            }
        }
    }
}
