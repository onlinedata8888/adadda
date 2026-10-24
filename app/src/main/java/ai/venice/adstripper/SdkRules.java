package ai.venice.adstripper;

import java.util.HashMap;
import java.util.Map;

/** Registry of ad networks, keyed by smali class descriptor. */
final class SdkRules {

    enum Action { HIDE, NOOP }

    private static final Map<String, Map<String, Action>> BANNERS = new HashMap<>();
    private static final Map<String, Map<String, Action>> INTERS  = new HashMap<>();

    private static void put(Map<String, Map<String, Action>> m, String cls, String... methods) {
        Map<String, Action> mm = m.computeIfAbsent("L" + cls + ";", k -> new HashMap<>());
        for (String s : methods) mm.put(s, m == BANNERS ? Action.HIDE : Action.NOOP);
    }

    static {
        put(BANNERS, "com/google/android/gms/ads/BaseAdView", "loadAd");
        put(BANNERS, "com/google/android/gms/ads/AdView", "loadAd");
        put(BANNERS, "com/google/android/gms/ads/admanager/AdManagerAdView", "loadAd");
        put(BANNERS, "com/facebook/ads/AdView", "loadAd");
        put(BANNERS, "com/facebook/ads/AdViewBase", "loadAd");
        put(BANNERS, "com/unity3d/ads/banner/BannerView", "load", "loadBanner");
        put(BANNERS, "com/applovin/adview/AppLovinAdView", "loadNextAd", "render");
        put(BANNERS, "com/mopub/mobileads/MoPubView", "loadAd");
        put(BANNERS, "com/inmobi/ads/InMobiBanner", "load");
        put(BANNERS, "com/ironsource/mediationsdk/ISBannerView", "loadBanner");
        put(BANNERS, "com/amazon/device/ads/AdLayout", "loadAd");
        put(BANNERS, "com/smaato/soma/BannerView", "loadAd", "asyncLoadAd");

        put(INTERS, "com/google/android/gms/ads/BaseInterstitialAd", "loadAd", "load", "show", "showInterstitial");
        put(INTERS, "com/google/android/gms/ads/interstitial/InterstitialAd", "load", "loadAd", "show");
        put(INTERS, "com/facebook/ads/InterstitialAd", "loadAd", "show");
        put(INTERS, "com/unity3d/ads/UnityAds", "show");
        put(INTERS, "com/applovin/adview/AppLovinInterstitialAd", "show");
        put(INTERS, "com/applovin/adview/AppLovinInterstitialAdDialog", "show");
        put(INTERS, "com/mopub/mobileads/MoPubInterstitial", "load", "show");
        put(INTERS, "com/inmobi/ads/InMobiInterstitial", "load", "show");
        put(INTERS, "com/vungle/ads/VungleInterstitial", "loadAd", "playAd");
        put(INTERS, "com/vungle/ads/VungleAdvert", "loadAd", "playAd");
        put(INTERS, "com/chartboost/sdk/Chartboost", "showInterstitial");
        put(INTERS, "com/ironsource/mediationsdk/IronSource", "showInterstitial");
        put(INTERS, "com/amazon/device/ads/InterstitialAd", "loadAd", "show");
        put(INTERS, "com/adcolony/sdk/AdColony", "show");
        put(INTERS, "com/startapp/sdk/adsbase/StartAppAd", "loadAd", "showAd");
        put(INTERS, "com/smaato/soma/InterstitialAd", "loadAd", "show");
    }

    static Action actionFor(String descriptor, String method, boolean aggressive) {
        Map<String, Action> m = BANNERS.get(descriptor);
        if (m != null) { Action a = m.get(method); if (a != null) return a; }
        if (aggressive) {
            m = INTERS.get(descriptor);
            if (m != null) { Action a = m.get(method); if (a != null) return a; }
        }
        return null;
    }

    private SdkRules() { }
}
