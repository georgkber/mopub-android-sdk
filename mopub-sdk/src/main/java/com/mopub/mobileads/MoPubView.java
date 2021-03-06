/*
 * Copyright (c) 2010, MoPub Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'MoPub Inc.' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.mopub.mobileads;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.webkit.WebViewDatabase;
import android.widget.FrameLayout;
import com.mopub.mobileads.factories.AdViewControllerFactory;
import com.mopub.mobileads.factories.CustomEventBannerAdapterFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.Map;

import static com.mopub.mobileads.AdFetcher.CUSTOM_EVENT_DATA_HEADER;
import static com.mopub.mobileads.AdFetcher.CUSTOM_EVENT_NAME_HEADER;
import static com.mopub.mobileads.MoPubErrorCode.ADAPTER_NOT_FOUND;

public class MoPubView extends FrameLayout {
    
    public interface BannerAdListener {
        public void onBannerLoaded(MoPubView banner);
        public void onBannerFailed(MoPubView banner, MoPubErrorCode errorCode);
        public void onBannerClicked(MoPubView banner);
        public void onBannerExpanded(MoPubView banner);
        public void onBannerCollapsed(MoPubView banner);
    }
    
    public enum LocationAwareness {
        LOCATION_AWARENESS_NORMAL, LOCATION_AWARENESS_TRUNCATED, LOCATION_AWARENESS_DISABLED
    }

    public static final String HOST = "ads.mopub.com";
    public static final String HOST_FOR_TESTING = "testing.ads.mopub.com";
    public static final String AD_HANDLER = "/m/ad";
    public static final int DEFAULT_LOCATION_PRECISION = 6;

    protected AdViewController mAdViewController;
    protected CustomEventBannerAdapter mCustomEventBannerAdapter;
    
    private Context mContext;
    private BroadcastReceiver mScreenStateReceiver;
    private boolean mIsInForeground;
    private LocationAwareness mLocationAwareness;
    private int mLocationPrecision;
    private boolean mPreviousAutorefreshSetting = false;
    
    private BannerAdListener mBannerAdListener;
    
    private OnAdWillLoadListener mOnAdWillLoadListener;
    private OnAdLoadedListener mOnAdLoadedListener;
    private OnAdFailedListener mOnAdFailedListener;
    private OnAdPresentedOverlayListener mOnAdPresentedOverlayListener;
    private OnAdClosedListener mOnAdClosedListener;
    private OnAdClickedListener mOnAdClickedListener;

    public MoPubView(Context context) {
        this(context, null);
    }

    public MoPubView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContext = context;
        mIsInForeground = (getVisibility() == VISIBLE);
        mLocationAwareness = LocationAwareness.LOCATION_AWARENESS_NORMAL;
        mLocationPrecision = DEFAULT_LOCATION_PRECISION;
        
        setHorizontalScrollBarEnabled(false);
        setVerticalScrollBarEnabled(false);
        
        // There is a rare bug in Froyo/2.2 where creation of a WebView causes a
        // NullPointerException. (http://code.google.com/p/android/issues/detail?id=10789)
        // It happens when the WebView can't access the local file store to make a cache file.
        // Here, we'll work around it by trying to create a file store and then just go inert
        // if it's not accessible.
        if (WebViewDatabase.getInstance(context) == null) {
            Log.e("MoPub", "Disabling MoPub. Local cache file is inaccessible so MoPub will " +
                    "fail if we try to create a WebView. Details of this Android bug found at:" +
                    "http://code.google.com/p/android/issues/detail?id=10789");
            return;
        }
        
        initVersionDependentAdView(context);
        registerScreenStateBroadcastReceiver();
    }
    
    private void initVersionDependentAdView(Context context) {
        int sdkVersion = (new Integer(Build.VERSION.SDK)).intValue();
        if (sdkVersion < 7) {
        	mAdViewController = AdViewControllerFactory.create(context, this);
        } else {
            // On Android 2.1 (Eclair) and up, try to load our HTML5-enabled AdViewController class.
            Class<?> HTML5AdViewClass = null;
            try {
                HTML5AdViewClass = (Class<?>) Class.forName("com.mopub.mobileads.HTML5AdView");
            } catch (ClassNotFoundException e) {
                mAdViewController = AdViewControllerFactory.create(context, this);
                return;
            } 

            Class<?>[] parameterTypes = new Class[2];
            parameterTypes[0] = Context.class;
            parameterTypes[1] = MoPubView.class;

            Object[] args = new Object[2];
            args[0] = context;
            args[1] = this;

            try {
                Constructor<?> constructor = HTML5AdViewClass.getConstructor(parameterTypes);
                mAdViewController = (AdViewController) constructor.newInstance(args);
            } catch (SecurityException e) {
                Log.e("MoPub", "Could not load HTML5AdView.");
            } catch (NoSuchMethodException e) {
                Log.e("MoPub", "Could not load HTML5AdView.");
            } catch (IllegalArgumentException e) {
                Log.e("MoPub", "Could not load HTML5AdView.");
            } catch (InstantiationException e) {
                Log.e("MoPub", "Could not load HTML5AdView.");
            } catch (IllegalAccessException e) {
                Log.e("MoPub", "Could not load HTML5AdView.");
            } catch (InvocationTargetException e) {
                Log.e("MoPub", "Could not load HTML5AdView.");
            }

            if (mAdViewController == null) mAdViewController = AdViewControllerFactory.create(context, this);
        }
    }

    private void registerScreenStateBroadcastReceiver() {
        if (mAdViewController == null) return;
        
        mScreenStateReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                    if (mIsInForeground) {
                        Log.d("MoPub", "Screen sleep with ad in foreground, disable refresh");
                        if (mAdViewController != null) {
                            mPreviousAutorefreshSetting = mAdViewController.getAutorefreshEnabled();
                            mAdViewController.setAutorefreshEnabled(false);
                        }
                    } else {
                        Log.d("MoPub", "Screen sleep but ad in background; " + 
                                "refresh should already be disabled");
                    }
                } else if (intent.getAction().equals(Intent.ACTION_USER_PRESENT)) {
                    if (mIsInForeground) {
                        Log.d("MoPub", "Screen wake / ad in foreground, reset refresh");
                        if (mAdViewController != null) {
                            mAdViewController.setAutorefreshEnabled(mPreviousAutorefreshSetting);
                        }
                    } else {
                        Log.d("MoPub", "Screen wake but ad in background; don't enable refresh");
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        mContext.registerReceiver(mScreenStateReceiver, filter);
    }
    
    private void unregisterScreenStateBroadcastReceiver() {
        try {
            mContext.unregisterReceiver(mScreenStateReceiver);
        } catch (Exception IllegalArgumentException) {
            Log.d("MoPub", "Failed to unregister screen state broadcast receiver (never registered).");
        }
    }
    
    public void loadAd() {
        if (mAdViewController != null) mAdViewController.loadAd();
    }
    
    /*
     * Tears down the ad view: no ads will be shown once this method executes. The parent
     * Activity's onDestroy implementation must include a call to this method.
     */
    public void destroy() {
        unregisterScreenStateBroadcastReceiver();
        removeAllViews();
        
        if (mAdViewController != null) {
            mAdViewController.cleanup();
            mAdViewController = null;
        }
        
        if (mCustomEventBannerAdapter != null) {
            mCustomEventBannerAdapter.invalidate();
            mCustomEventBannerAdapter = null;
        }
    }

    protected void loadFailUrl(MoPubErrorCode errorCode) {
        if (mAdViewController != null) mAdViewController.loadFailUrl(errorCode);
    }

    protected void loadCustomEvent(Map<String, String> paramsMap) {
        if (paramsMap == null) {
            Log.d("MoPub", "Couldn't invoke custom event because the server did not specify one.");
            loadFailUrl(ADAPTER_NOT_FOUND);
            return;
        }

        if (mCustomEventBannerAdapter != null) {
            mCustomEventBannerAdapter.invalidate();
        }

        Log.d("MoPub", "Loading custom event adapter.");

        mCustomEventBannerAdapter = CustomEventBannerAdapterFactory.create(
                this,
                paramsMap.get(CUSTOM_EVENT_NAME_HEADER),
                paramsMap.get(CUSTOM_EVENT_DATA_HEADER));
        mCustomEventBannerAdapter.loadAd();
    }

    protected void registerClick() {
        if (mAdViewController != null) {
            mAdViewController.registerClick();

            // Let any listeners know that an ad was clicked
            adClicked();
        }
    }

    protected void trackNativeImpression() {
        Log.d("MoPub", "Tracking impression for native adapter.");
        if (mAdViewController != null) mAdViewController.trackImpression();
    }
    
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        if (mAdViewController == null) return;
        
        if (visibility == VISIBLE) {
            Log.d("MoPub", "Ad Unit ("+ mAdViewController.getAdUnitId()+") going visible: enabling refresh");
            mIsInForeground = true;
            mAdViewController.setAutorefreshEnabled(true);
        }
        else {
            Log.d("MoPub", "Ad Unit ("+ mAdViewController.getAdUnitId()+") going invisible: disabling refresh");
            mIsInForeground = false;
            mAdViewController.setAutorefreshEnabled(false);
        }
    }

    protected void adLoaded() {
        Log.d("MoPub", "adLoaded");
        
        if (mBannerAdListener != null) {
            mBannerAdListener.onBannerLoaded(this);
        } else if (mOnAdLoadedListener != null) {
            mOnAdLoadedListener.OnAdLoaded(this);
        }
    }

    protected void adFailed(MoPubErrorCode errorCode) {
        if (mBannerAdListener != null) {
            mBannerAdListener.onBannerFailed(this, errorCode);
        } else if (mOnAdFailedListener != null) {
            mOnAdFailedListener.OnAdFailed(this);
        }
    }

    protected void adPresentedOverlay() {
        if (mBannerAdListener != null) {
            mBannerAdListener.onBannerExpanded(this);
        } else if (mOnAdPresentedOverlayListener != null) {
            mOnAdPresentedOverlayListener.OnAdPresentedOverlay(this);
        }
    }
    
    protected void adClosed() {
        if (mBannerAdListener != null) {
            mBannerAdListener.onBannerCollapsed(this);
        } else if (mOnAdClosedListener != null) {
            mOnAdClosedListener.OnAdClosed(this);
        }
    }

    protected void adClicked() {
        if (mBannerAdListener != null) {
            mBannerAdListener.onBannerClicked(this);
        } else if (mOnAdClickedListener != null) {
            mOnAdClickedListener.OnAdClicked(this);
        }
    }
    
    protected void nativeAdLoaded() {
        if (mAdViewController != null) mAdViewController.scheduleRefreshTimerIfEnabled();
        adLoaded();
    }
    
    ////////////////////////////////////////////////////////////////////////////////////////////////

    public void setAdUnitId(String adUnitId) {
        if (mAdViewController != null) mAdViewController.setAdUnitId(adUnitId);
    }
    
    public void setKeywords(String keywords) {
        if (mAdViewController != null) mAdViewController.setKeywords(keywords);
    }

    public String getKeywords() {
        return (mAdViewController != null) ? mAdViewController.getKeywords() : null;
    }

    public void setLocation(Location location) {
        if (mAdViewController != null) mAdViewController.setLocation(location);
    }

    public Location getLocation() {
        return (mAdViewController != null) ? mAdViewController.getLocation() : null;
    }

    public void setTimeout(int milliseconds) {
        if (mAdViewController != null) mAdViewController.setTimeout(milliseconds);
    }

    public int getAdWidth() {
        return (mAdViewController != null) ? mAdViewController.getAdWidth() : 0;
    }

    public int getAdHeight() {
        return (mAdViewController != null) ? mAdViewController.getAdHeight() : 0;
    }

    public String getResponseString() {
        return (mAdViewController != null) ? mAdViewController.getResponseString() : null;
    }
    
    public void setClickthroughUrl(String url) {
        if (mAdViewController != null) mAdViewController.setClickthroughUrl(url);
    }
    
    public String getClickthroughUrl() {
        return (mAdViewController != null) ? mAdViewController.getClickthroughUrl() : null;
    }

    public Activity getActivity() {
        return (Activity) mContext;
    }
    
    public void setBannerAdListener(BannerAdListener listener) {
        mBannerAdListener = listener;
    }
    
    public BannerAdListener getBannerAdListener() {
        return mBannerAdListener;
    }
    
    public void setLocationAwareness(LocationAwareness awareness) {
        mLocationAwareness = awareness;
    }

    public LocationAwareness getLocationAwareness() {
        return mLocationAwareness;
    }

    public void setLocationPrecision(int precision) {
        mLocationPrecision = (precision >= 0) ? precision : 0;
    }

    public int getLocationPrecision() {
        return mLocationPrecision;
    }
    
    public void setLocalExtras(Map<String, Object> localExtras) {
        if (mAdViewController != null) mAdViewController.setLocalExtras(localExtras);
    }
    
    public Map<String, Object> getLocalExtras() {
        if (mAdViewController != null) return mAdViewController.getLocalExtras();
        return Collections.emptyMap();
    }
    
    public void setAutorefreshEnabled(boolean enabled) {
        if (mAdViewController != null) mAdViewController.setAutorefreshEnabled(enabled);
    }
    
    public boolean getAutorefreshEnabled() {
        if (mAdViewController != null) return mAdViewController.getAutorefreshEnabled();
        else {
            Log.d("MoPub", "Can't get autorefresh status for destroyed MoPubView. " + 
                    "Returning false.");
            return false;
        }
    }
    
    public void setAdContentView(View view) {
        if (mAdViewController != null) mAdViewController.setAdContentView(view);
    }
    
    public void setTesting(boolean testing) {
        if (mAdViewController != null) mAdViewController.setTesting(testing);
    }
    
    public boolean getTesting() {
        if (mAdViewController != null) return mAdViewController.getTesting();
        else {
            Log.d("MoPub", "Can't get testing status for destroyed MoPubView. " + 
                    "Returning false.");
            return false;
        }
    }
    
    public void forceRefresh() {
        if (mCustomEventBannerAdapter != null) {
            mCustomEventBannerAdapter.invalidate();
            mCustomEventBannerAdapter = null;
        }
        
        if (mAdViewController != null) mAdViewController.forceRefresh();
    }
    
    @Deprecated
    public interface OnAdWillLoadListener {
        public void OnAdWillLoad(MoPubView m, String url);
    }
    
    @Deprecated
    public interface OnAdLoadedListener {
        public void OnAdLoaded(MoPubView m);
    }
    
    @Deprecated
    public interface OnAdFailedListener {
        public void OnAdFailed(MoPubView m);
    }
    
    @Deprecated
    public interface OnAdClosedListener {
        public void OnAdClosed(MoPubView m);
    }
    
    @Deprecated
    public interface OnAdClickedListener {
        public void OnAdClicked(MoPubView m);
    }
    
    @Deprecated
    public interface OnAdPresentedOverlayListener {
        public void OnAdPresentedOverlay(MoPubView m);
    }
    
    @Deprecated
    public void setOnAdWillLoadListener(OnAdWillLoadListener listener) {
        mOnAdWillLoadListener = listener;
    }
    
    @Deprecated
    public void setOnAdLoadedListener(OnAdLoadedListener listener) {
        mOnAdLoadedListener = listener;
    }
    
    @Deprecated
    public void setOnAdFailedListener(OnAdFailedListener listener) {
        mOnAdFailedListener = listener;
    }
    
    @Deprecated
    public void setOnAdPresentedOverlayListener(OnAdPresentedOverlayListener listener) {
        mOnAdPresentedOverlayListener = listener;
    }
    
    @Deprecated
    public void setOnAdClosedListener(OnAdClosedListener listener) {
        mOnAdClosedListener = listener;
    }
    
    @Deprecated
    public void setOnAdClickedListener(OnAdClickedListener listener) {
        mOnAdClickedListener = listener;
    }
    
    @Deprecated
    protected void adWillLoad(String url) {
        Log.d("MoPub", "adWillLoad: " + url);
        if (mOnAdWillLoadListener != null) mOnAdWillLoadListener.OnAdWillLoad(this, url);
    }
    
    @Deprecated
    public void customEventDidLoadAd() {
        if (mAdViewController != null) mAdViewController.customEventDidLoadAd();
    }
    
    @Deprecated
    public void customEventDidFailToLoadAd() {
        if (mAdViewController != null) mAdViewController.customEventDidFailToLoadAd();
    }
    
    @Deprecated
    public void customEventActionWillBegin() {
        if (mAdViewController != null) mAdViewController.customEventActionWillBegin();
    }
}
