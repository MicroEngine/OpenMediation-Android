// Copyright 2019 ADTIMING TECHNOLOGY COMPANY LIMITED
// Licensed under the GNU Lesser General Public License Version 3

package com.openmediation.sdk.adn.interstitial;

import com.openmediation.sdk.adn.utils.error.Error;

public interface InterstitialAdListener {

    void onInterstitialAdEvent(String placementId, String event);

    void onInterstitialAdLoadSuccess(String placementId);

    void onInterstitialAdClosed(String placementId);

    void onInterstitialAdShowed(String placementId);

    void onInterstitialAdLoadFailed(String placementId, Error error);

    void onInterstitialAdClicked(String placementId);

    void onInterstitialAdShowFailed(String placementId, Error error);
}
