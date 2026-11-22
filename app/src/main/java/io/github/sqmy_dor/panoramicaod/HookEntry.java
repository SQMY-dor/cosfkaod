package io.github.sqmy_dor.panoramicaod;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * CosFkAod: let ColorOS use 全景沉浸 (panoramic AOD) while a third-party theme
 * is applied.
 *
 * 1) com.oplus.aod (息屏 17.001.0021): Y2.a.k(Context) returns true when the
 *    applied theme is a theme-store "global" theme (applyType 3) or the lock is
 *    taken over by a custom unlock package. That one check makes the AOD app
 *    refuse panoramic ("三方主题不支持全景息屏显示模式"), hide it in settings, and
 *    reset Setting_AodState to 0 on start (AodApplication.p()). Forced false.
 *
 * 2) Theme store (com.heytap.themestore 17.22.1 / com.oplus.themestore 17.11.18):
 *    its AOD step after an apply resets Setting_AodState to 0 for themes without
 *    one-shot data. If panoramic (2) was selected before and the step dropped it
 *    to classic (0), put 2 back and tell the wallpaper side. An official one-shot
 *    theme that sets 1 (灵感接续) is left alone.
 *
 * Panoramic only dims the current lock wallpaper; nothing paid is touched.
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "CosFkAod";
    private static final boolean VERBOSE = true;

    private static final String AOD_PKG = "com.oplus.aod";
    private static final String KEY_STATE = "Setting_AodState";
    private static final int STATE_CLASSIC = 0;
    private static final int STATE_PANORAMIC = 2;

    /** Store builds: fingerprint class + method (only probed, never hooked), then the AOD step. */
    private static final String[][] STORE_PROFILES = {
        // label, fingerprint class, fingerprint method, AOD step class, AOD step method
        {"heytap-17.22.1", "ep.e", "A1", "com.nearme.themespace.resourcemanager.apply.b", "T"},
        {"oplus-17.11.18", "uc.d", "U0", "com.nearme.themespace.resourcemanager.apply.b", "R"},
    };

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        String pkg = lp.packageName;
        if (AOD_PKG.equals(pkg)) {
            XposedBridge.log(TAG + ": loaded into " + pkg);
            hookAodGate(lp.classLoader);
        } else if ("com.heytap.themestore".equals(pkg) || "com.oplus.themestore".equals(pkg)) {
            XposedBridge.log(TAG + ": loaded into " + pkg + " / " + lp.processName);
            hookStoreAodStep(lp.classLoader);
        }
    }

    // ------------------------------------------------------------ com.oplus.aod

    private static void hookAodGate(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists("Y2.a", cl);
        if (c == null || XposedHelpers.findClassIfExists("com.oplus.aod.util.AodProviderClientCallUtils", cl) == null) {
            XposedBridge.log(TAG + ": aod gate class not found (AOD app updated? re-map needed)");
            return;
        }
        Method m = XposedHelpers.findMethodExactIfExists(c, "k", Context.class);
        if (m == null || m.getReturnType() != boolean.class) {
            XposedBridge.log(TAG + ": Y2.a.k(Context) not found - nothing hooked");
            return;
        }
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (Boolean.TRUE.equals(p.getResult())) {
                    if (VERBOSE) log("aod: third-party gate true -> false");
                    p.setResult(Boolean.FALSE);
                }
            }
        });
        XposedBridge.log(TAG + ": hooked Y2.a.k (third-party panoramic gate)");
    }

    // ------------------------------------------------------------- theme store

    private static final ThreadLocal<Integer> STATE_BEFORE = new ThreadLocal<Integer>() {
        @Override protected Integer initialValue() { return -1; }
    };

    private static void hookStoreAodStep(ClassLoader cl) {
        for (String[] pr : STORE_PROFILES) {
            Class<?> fp = XposedHelpers.findClassIfExists(pr[1], cl);
            if (fp == null || !hasMethodNamed(fp, pr[2])) continue;
            Class<?> mgr = XposedHelpers.findClassIfExists(pr[3], cl);
            if (mgr == null) continue;
            Method step = XposedHelpers.findMethodExactIfExists(mgr, pr[4]);
            if (step == null) continue;
            XposedBridge.hookMethod(step, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    STATE_BEFORE.set(readState());
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    int before = STATE_BEFORE.get();
                    STATE_BEFORE.set(-1);
                    if (before != STATE_PANORAMIC) return;
                    int now = readState();
                    if (now != STATE_CLASSIC) return; // official one-shot (1) or unchanged
                    restorePanoramic();
                }
            });
            XposedBridge.log(TAG + ": profile " + pr[0] + ", hooked " + pr[3] + "." + pr[4] + " (AOD step)");
            return;
        }
        XposedBridge.log(TAG + ": no known store build - store side not hooked (panoramic may reset after applying a theme)");
    }

    private static boolean hasMethodNamed(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name)) return true;
        return false;
    }

    private static ContentResolver cr() {
        return android.app.AndroidAppHelper.currentApplication().getContentResolver();
    }

    private static int readState() {
        try { return Settings.Secure.getInt(cr(), KEY_STATE, -1); } catch (Throwable t) { return -1; }
    }

    private static void restorePanoramic() {
        try {
            ContentResolver r = cr();
            Settings.Secure.putInt(r, KEY_STATE, STATE_PANORAMIC);
            Settings.Secure.putInt(r, "Setting_Aod_ShowWallpaper", 0);
            log("store reset AOD to classic, restored panoramic");
            try {
                Bundle b = new Bundle();
                b.putInt("aodType", STATE_PANORAMIC);
                r.call(android.net.Uri.parse("content://com.oplus.wallpapers.InspirationThemeProvider"),
                        "aod_change_type", null, b);
                log("notified InspirationThemeProvider aod_change_type=2");
            } catch (Throwable t) {
                log("notify failed (re-pick 全景沉浸 once): " + t);
            }
        } catch (Throwable t) {
            log("restore FAILED: " + t);
        }
    }

    private static void log(String s) {
        XposedBridge.log(TAG + ": " + s);
    }
}
