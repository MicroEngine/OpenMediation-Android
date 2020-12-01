// Copyright 2019 ADTIMING TECHNOLOGY COMPANY LIMITED
// Licensed under the GNU Lesser General Public License Version 3

package com.openmediation.sdk.adn.core;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.widget.RelativeLayout;

import com.openmediation.sdk.adn.bean.AdBean;
import com.openmediation.sdk.adn.utils.GpUtil;
import com.openmediation.sdk.adn.utils.ResUtil;
import com.openmediation.sdk.adn.utils.error.Error;
import com.openmediation.sdk.adn.utils.error.ErrorBuilder;
import com.openmediation.sdk.adn.utils.error.ErrorCode;
import com.openmediation.sdk.adn.utils.webview.BaseWebView;
import com.openmediation.sdk.adn.utils.webview.AdsWebView;
import com.openmediation.sdk.adn.utils.webview.BaseWebViewClient;
import com.openmediation.sdk.utils.DeveloperLog;
import com.openmediation.sdk.utils.HandlerUtil;
import com.openmediation.sdk.utils.SdkUtil;
import com.openmediation.sdk.utils.crash.CrashUtil;

/**
 *
 */
public class BaseActivity extends Activity {
    protected RelativeLayout mLytAd;
    protected BaseWebView mAdView;
    protected AdBean mAdBean;
    protected String mPlacementId;
    protected String mSceneName;
    protected int mAbt;
    protected AbstractAdsManager mAdsManager;
    protected boolean isCloseCallbacked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            mLytAd = new RelativeLayout(this);
            setContentView(mLytAd);

            isCloseCallbacked = false;
            Intent intent = getIntent();
            mPlacementId = intent.getStringExtra("placementId");
            mSceneName = intent.getStringExtra("sceneName");
            mAbt = intent.getIntExtra("abt", 0);
            mAdsManager = CallbackBridge.getListener(mPlacementId);

            Bundle bundle = getIntent().getBundleExtra("bundle");
            if (bundle == null) {
                callbackAdShowFailedOnUIThread(ErrorBuilder.build(ErrorCode.CODE_SHOW_RESOURCE_ERROR));
                callbackAdCloseOnUIThread();
                finish();
                return;
            }
            bundle.setClassLoader(AdBean.class.getClassLoader());
            mAdBean = bundle.getParcelable("ad");
            bundle.clear();
            if (mAdBean == null || mAdBean.getResources() == null || mAdBean.getResources().isEmpty()) {
                callbackAdShowFailedOnUIThread(ErrorBuilder.build(ErrorCode.CODE_SHOW_RESOURCE_ERROR));
                callbackAdCloseOnUIThread();
                finish();
                return;
            }

            String impUrl = mAdBean.getResources().get(0);
            if (TextUtils.isEmpty(impUrl)) {
                callbackAdShowFailedOnUIThread(ErrorBuilder.build(ErrorCode.CODE_SHOW_RESOURCE_ERROR));
                callbackAdCloseOnUIThread();
                finish();
                return;
            }

            initViewAndLoad(impUrl);
        } catch (Throwable e) {
            DeveloperLog.LogD("BaseActivity", e);
            CrashUtil.getSingleton().saveException(e);
            callbackAdShowFailedOnUIThread(ErrorBuilder.build(ErrorCode.CODE_SHOW_UNKNOWN_EXCEPTION));
            callbackAdCloseOnUIThread();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        mAdBean = null;
        super.onDestroy();
    }

    protected void initViewAndLoad(String impUrl) {
        mAdView = AdsWebView.getInstance().getAdView();
        if (mAdView.getParent() != null) {
            ViewGroup group = (ViewGroup) mAdView.getParent();
            group.removeView(mAdView);
        }

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT);
        mLytAd.addView(mAdView, params);
        mAdView.setWebViewClient(new AdWebClient(this, mAdBean.getPkgName()));
    }

    /**
     * Ads close callback
     */
    protected void callbackAdCloseOnUIThread() {
        if (mAdsManager == null || isCloseCallbacked) {
            return;
        }
        HandlerUtil.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                callbackWhenClose();
            }
        });
    }

    /**
     * Ads click callback
     */
    protected void callbackAdClickOnUIThread() {
        if (mAdsManager == null) {
            return;
        }
        HandlerUtil.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                callbackWhenClick();
            }
        });
    }

    /**
     * Ads showing error callback
     *
     * @param error Error info
     */
    protected void callbackAdShowFailedOnUIThread(final Error error) {
        if (mAdsManager == null) {
            return;
        }
        HandlerUtil.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                callbackWhenShowedFailed(error);
            }
        });
    }

    protected void callbackWhenClose() {
        isCloseCallbacked = true;
        if (mAdsManager != null) {
            mAdsManager.onAdsClosed();
        }
    }

    protected void callbackWhenClick() {
        if (mAdsManager != null) {
            mAdsManager.onAdsClicked();
        }
    }

    protected void callbackWhenShowedFailed(Error error) {
        if (mAdsManager != null) {
            mAdsManager.onAdsShowFailed(error);
        }
    }

    protected static class AdWebClient extends BaseWebViewClient {
        private boolean isJumped = false;

        public AdWebClient(Activity activity, String pkgName) {
            super(activity, pkgName);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (isJumped) {
                isJumped = false;
                return true;
            }
            boolean result = super.shouldOverrideUrlLoading(view, url);
            if (!result) {
                try {
                    if (GpUtil.isGp(url)) {
                        GpUtil.goGp(view.getContext().getApplicationContext(), url);
                    } else {
                        if (SdkUtil.isAcceptedScheme(url)) {
                            view.loadUrl(url);
                        }
                    }
                } catch (Exception e) {
                    DeveloperLog.LogD("shouldOverrideUrlLoading error", e);
                    CrashUtil.getSingleton().saveException(e);
                }
            } else {
                isJumped = true;
                view.stopLoading();
            }
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            DeveloperLog.LogD("shouldInterceptRequest error", url);
            WebResourceResponse response = ResUtil.shouldInterceptRequest(view, url);
            if (response == null) {
                DeveloperLog.LogD("response null:" + url);
            }
            return response == null ? super.shouldInterceptRequest(view, url) : response;
        }
    }
}
