// Copyright 2019 ADTIMING TECHNOLOGY COMPANY LIMITED
// Licensed under the GNU Lesser General Public License Version 3

package com.openmediation.sdk.adn.interstitial;

import com.openmediation.sdk.adn.core.OmAdNetworkManager;

public final class InterstitialAd {

    /**
     * Returns default placement availability
     *
     * @return true or false
     */
    public static boolean isReady(String placementId) {
        return OmAdNetworkManager.getInstance().isInterstitialAdReady(placementId);
    }

    /**
     * Load ad.
     */
    public static void loadAd(String placementId) {
        OmAdNetworkManager.getInstance().loadInterstitialAd(placementId);
    }

    public static void loadAdWithPayload(String placementId, String payload) {
        OmAdNetworkManager.getInstance().loadInterstitialAd(placementId, payload);
    }

    /**
     * shows ad with default placement and default scene
     */
    public static void showAd(String placementId) {
        OmAdNetworkManager.getInstance().showInterstitialAd(placementId);
    }

    /**
     * Set the {@link InterstitialAdListener} to default placement that will receive events from the
     * rewarded video system. Set this to null to stop receiving event callbacks.
     *
     * @param listener the listener
     */
    public static void setAdListener(String placementId, InterstitialAdListener listener) {
        OmAdNetworkManager.getInstance().setInterstitialAdListener(placementId, listener);
    }
}
