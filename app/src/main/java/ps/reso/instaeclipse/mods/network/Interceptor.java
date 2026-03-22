package ps.reso.instaeclipse.mods.network;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker;

public class Interceptor {

    public void handleInterceptor(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader classLoader = lpparam.classLoader;

            // Locate the TigonServiceLayer class dynamically
            Class<?> tigonClass = classLoader.loadClass("com.instagram.api.tigon.TigonServiceLayer");
            Method[] methods = tigonClass.getDeclaredMethods();

            Class<?> random_param_1 = null;
            Class<?> random_param_2 = null;
            Class<?> random_param_3 = null;
            String uriFieldName = null;

            // Analyze methods in TigonServiceLayer
            for (Method method : methods) {
                if (method.getName().equals("startRequest") && method.getParameterCount() == 3) {
                    Class<?>[] paramTypes = method.getParameterTypes();
                    random_param_1 = paramTypes[0];
                    random_param_2 = paramTypes[1];
                    random_param_3 = paramTypes[2];
                    break;
                }
            }

            // Dynamically identify the URI field in c5aE
            if (random_param_1 != null) {
                for (Field field : random_param_1.getDeclaredFields()) {
                    if (field.getType().equals(URI.class)) {
                        uriFieldName = field.getName();
                        break;
                    }
                }
            }

            // If classes and fields are resolved, hook the method
            if (random_param_1 != null && random_param_2 != null && random_param_3 != null && uriFieldName != null) {
                String finalUriFieldName = uriFieldName;
                XposedHelpers.findAndHookMethod("com.instagram.api.tigon.TigonServiceLayer", classLoader, "startRequest",
                        random_param_1, random_param_2, random_param_3, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    Object requestObj = param.args[0]; // Dynamic object
                                    URI uri = (URI) XposedHelpers.getObjectField(requestObj, finalUriFieldName);

                                    if (uri != null && uri.getPath() != null) {
                                        String path = uri.getPath();
                                        String host = uri.getHost();
                                        String query = uri.getQuery();
                                        // Check all conditions passed in as predicates
                                        boolean shouldDrop = false;

                                        // Ghost Mode URIs
                                        if (FeatureFlags.isGhostScreenshot) {
                                            shouldDrop |= path.endsWith("/screenshot/") || path.endsWith("/ephemeral_screenshot/");
                                        }
                                        if (FeatureFlags.isGhostViewOnce) {
                                            shouldDrop |= path.endsWith("/item_replayed/");
                                            shouldDrop |= (path.contains("/direct") && path.endsWith("/item_seen/"));
                                        }
                                        if (FeatureFlags.isGhostStory) {
                                            shouldDrop |= path.contains("/api/v2/media/seen/");
                                        }
                                        if (FeatureFlags.isGhostLive) {
                                            shouldDrop |= path.contains("/heartbeat_and_get_viewer_count/");
                                            FeatureStatusTracker.setHooked("GhostLive");
                                        }

                                        // Distraction Free
                                        if (FeatureFlags.disableStories) {
                                            shouldDrop |= path.contains("/feed/reels_tray/")
                                                    || path.contains("feed/get_latest_reel_media/")
                                                    || path.contains("direct_v2/pending_inbox/?visual_message")
                                                    || path.contains("stories/hallpass/")
                                                    || path.contains("/api/v1/feed/reels_media_stream/");
                                        }
                                        if (FeatureFlags.disableFeed) {
                                            shouldDrop |= path.endsWith("/feed/timeline/");
                                        }
                                        if (FeatureFlags.disableReels && !FeatureFlags.disableReelsExceptDM) {
                                            shouldDrop |= path.endsWith("/qp/batch_fetch/")
                                                    || path.contains("api/v1/clips")
                                                    || path.contains("clips")
                                                    || path.contains("mixed_media")
                                                    || path.contains("mixed_media/discover/stream/");
                                        }
                                        if (FeatureFlags.disableReelsExceptDM) {
                                            if (path.startsWith("/api/v1/direct_v2/")) {
                                                return;
                                            }
                                            shouldDrop |= (path.startsWith("/api/v1/clips/") && query != null
                                                    && (query.contains("next_media_ids=")
                                                    || query.contains("max_id=")))
                                                    || path.contains("/clips/discover/")
                                                    || path.contains("/mixed_media/discover/stream/");
                                        }
                                        if (FeatureFlags.disableExplore) {
                                            shouldDrop |= path.contains("/discover/topical_explore")
                                                    || path.contains("/discover/topical_explore_stream")
                                                    || (host != null && host.contains("i.instagram.com") && path.contains("/api/v1/fbsearch/top_serp/"));
                                        }
                                        if (FeatureFlags.disableComments) {
                                            shouldDrop |= path.contains("/api/v1/media/") && path.contains("comments/");
                                        }

                                        // Ads
                                        if (FeatureFlags.isAdBlockEnabled) {
                                            shouldDrop |= path.contains("profile_ads/get_profile_ads/")
                                                    || path.contains("/async_ads/")
                                                    || path.contains("/feed/injected_reels_media/")
                                                    || path.equals("/api/v1/ads/graphql/");
                                        }

                                        // Analytics
                                        if (FeatureFlags.isAnalyticsBlocked) {
                                            shouldDrop |= (host != null && host.contains("graph.instagram.com"))
                                                    || (host != null && host.contains("graph.facebook.com"))
                                                    || path.contains("/logging_client_events");
                                        }

                                        // Misc
                                        if (FeatureFlags.disableRepost) {
                                            shouldDrop |= path.contains("/media/create_note/");
                                        }

                                        if (shouldDrop) {
                                            // XposedBridge.log("the URI was blocked: " + path);
                                            // Modify the URI to divert the request to a harmless endpoint
                                            try {
                                                URI fakeUri = new URI("https", "127.0.0.1", "/404", null);
                                                XposedHelpers.setObjectField(requestObj, finalUriFieldName, fakeUri);
                                                // XposedBridge.log("🚫 [InstaEclipse] Changed URI to: " + fakeUri);
                                            } catch (Exception e) {
                                                // XposedBridge.log("❌ [InstaEclipse] Failed to modify URI: " + e.getMessage());
                                            }
                                        }
                                        /*
                                         DEV Purposes
                                        else {
                                            XposedBridge.log("Logging: " + host + path);
                                        }
                                         */

                                        if (FeatureFlags.showFollowerToast && path.startsWith("/api/v1/friendships/show/")) {
                                            String[] parts = path.split("/");
                                            if (parts.length >= 6) {
                                                // Extracted ID from /api/v1/friendships/show/{id}
                                                FollowIndicatorTracker.currentlyViewedUserId = parts[5];
                                            }
                                        }

                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log("(InstaEclipse | Interceptor): ❌ Hook error: " + t.getMessage());
                                }
                            }
                        }
                );
            } else {
                XposedBridge.log("Could not resolve required classes or fields.");
            }

        } catch (Exception e) {
            XposedBridge.log("Error in interceptor: " + e.getMessage());
        }
    }
}
