// Copyright 2019 ADTIMING TECHNOLOGY COMPANY LIMITED
// Licensed under the GNU Lesser General Public License Version 3

package com.openmediation.sdk.adn.core.imp.video;

import com.openmediation.sdk.adn.AdsActivity;
import com.openmediation.sdk.adn.core.AbstractAdsManager;
import com.openmediation.sdk.adn.core.CallbackBridge;
import com.openmediation.sdk.adn.utils.Cache;
import com.openmediation.sdk.adn.video.RewardedVideoListener;
import com.openmediation.sdk.utils.DeveloperLog;
import com.openmediation.sdk.utils.constant.CommonConstants;

import java.io.File;

public final class VideoAdImp extends AbstractAdsManager {

    public VideoAdImp(String placementId) {
        super(placementId);
    }

    @Override
    protected int getAdType() {
        return CommonConstants.VIDEO;
    }

    public void setListener(RewardedVideoListener adListener) {
        mListenerWrapper.setVideoListener(adListener);
    }

    @Override
    public boolean isReady() {
        try {
            boolean result = super.isReady();
            if (!result) {
                return false;
            }
            File video = Cache.getCacheFile(mContext, mAdBean.getVideoUrl(), null);
            return video != null && video.exists() && video.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void showAds() {
        super.showAds();
        CallbackBridge.addListenerToMap(mPlacementId, this);
        show(AdsActivity.class);
    }

    @Override
    public void destroy() {
        super.destroy();
        CallbackBridge.removeListenerFromMap(mPlacementId);
    }

    public void onRewardedVideoStarted() {
        DeveloperLog.LogD("onRewardedVideoAdStarted : " + mPlacementId);
        mListenerWrapper.onRewardAdStarted(mPlacementId);
    }

    public void onRewardedVideoEnded() {
        DeveloperLog.LogD("onRewardedVideoEnded : " + mPlacementId);
        mListenerWrapper.onRewardAdEnded(mPlacementId);
    }

    public void onRewardedRewarded() {
        DeveloperLog.LogD("onRewardedRewarded : " + mPlacementId);
        mListenerWrapper.onRewardAdRewarded(mPlacementId);
    }
}
