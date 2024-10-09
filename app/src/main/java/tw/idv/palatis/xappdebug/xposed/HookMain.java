package tw.idv.palatis.xappdebug.xposed;

import static android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE;
import static android.util.Log.getStackTraceString;
import static tw.idv.palatis.xappdebug.Constants.CONFIG_PATH_FORMAT;
import static tw.idv.palatis.xappdebug.Constants.LOG_TAG;

import android.annotation.SuppressLint;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.os.StrictMode;
import android.os.UserHandle;

import androidx.annotation.Keep;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.SELinuxHelper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

@Keep
public class HookMain implements IXposedHookLoadPackage {

    // taken from Zygote.java
    // https://android.googlesource.com/platform/frameworks/base.git/+/master/core/java/com/android/internal/os/Zygote.java
    private static final int DEBUG_ENABLE_JDWP = 1;

    @SuppressLint("SdCardPath")
    private static boolean isDebuggable(final String packageName, int user) {
        final String path = String.format(
                Locale.getDefault(),
                CONFIG_PATH_FORMAT,
                user, packageName
        );

        final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
        boolean state = SELinuxHelper.getAppDataFileService().checkFileExists(path);
        StrictMode.setThreadPolicy(oldPolicy);
        return state;
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"android".equals(lpparam.packageName))
            return;
        hook(lpparam);
    }

    private void hook(final XC_LoadPackage.LoadPackageParam lpparam) {
        Class cls;
        try {
            cls = XposedHelpers.findClass("com.android.server.pm.ComputerEngine", lpparam.classLoader);
        } catch (XposedHelpers.ClassNotFoundError error) {
            XposedBridge.log(LOG_TAG + ": " + getStackTraceString(error));
            return;
        }
        Method getPackageInfoMethod = findBestMethod(cls, "getPackageInfo");
        if (getPackageInfoMethod == null) {
            return;
        }
        XposedBridge.hookMethod(getPackageInfoMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    PackageInfo packageInfo = (PackageInfo) param.getResult();
                    if (packageInfo != null && packageInfo.applicationInfo != null)
                        checkAndMakeDebuggable(packageInfo.applicationInfo, packageInfo.packageName, (int) param.args[2]);
                } catch (Exception e) {
                    XposedBridge.log(LOG_TAG + ": " + getStackTraceString(e));
                }
            }
        });
        XposedBridge.log(LOG_TAG + ": Hooked " + getPackageInfoMethod.toString());

        Method getApplicationInfoMethod = findBestMethod(cls, "getApplicationInfo");
        XposedBridge.hookMethod(getApplicationInfoMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    ApplicationInfo appInfo = (ApplicationInfo) param.getResult();
                    if (appInfo != null)
                        checkAndMakeDebuggable(appInfo, ((ApplicationInfo) param.getResult()).packageName, (int) param.args[2]);
                } catch (Exception e) {
                    XposedBridge.log(LOG_TAG + ": " + getStackTraceString(e));
                }
            }
        });
        XposedBridge.log(LOG_TAG + ": Hooked " + getApplicationInfoMethod.toString());

        Method getInstalledApplicationsMethod = findBestMethod(cls, "getInstalledApplications");
        XposedBridge.hookMethod(getInstalledApplicationsMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    List<ApplicationInfo> infos = (List<ApplicationInfo>) param.getResult();
                    if (infos != null) {
                        for (ApplicationInfo info : infos) {
                            checkAndMakeDebuggable(info, info.packageName, (int) param.args[1]);
                        }
                    }
                } catch (Exception e) {
                    XposedBridge.log(LOG_TAG + ": " + getStackTraceString(e));
                }
            }
        });
        XposedBridge.log(LOG_TAG + ": Hooked " + getInstalledApplicationsMethod.toString());

        Method startMethod = findBestMethod(Process.class, "start");
        XposedBridge.hookMethod(startMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                final String niceName = (String) param.args[1];
                final int uid = (int) param.args[2];
                final int runtimeFlags = (int) param.args[5];

                final int user = UserHandle.getUserHandleForUid(uid).hashCode();
                if (isDebuggable(niceName, user))
                    param.args[5] = runtimeFlags | DEBUG_ENABLE_JDWP;
            }
        });
        XposedBridge.log(LOG_TAG + ": Hooked " + startMethod.toString());

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Method getInstalledApplicationsListInternalMethod = findBestMethod(cls, "getInstalledApplicationsListInternal");
            XposedBridge.hookMethod(getInstalledApplicationsListInternalMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        List<ApplicationInfo> infos = (List<ApplicationInfo>) param.getResult();
                        if (infos != null) {
                            for (ApplicationInfo info : infos) {
                                checkAndMakeDebuggable(info, info.packageName, (int) param.args[1]);
                            }
                        }
                    } catch (Exception e) {
                        XposedBridge.log(LOG_TAG + ": " + getStackTraceString(e));
                    }
                }
            });
            XposedBridge.log(LOG_TAG + ": Hooked " + cls.getName() + "." + getInstalledApplicationsListInternalMethod.toString());
        }

        XposedBridge.log(LOG_TAG + ": All methods hooked");
    }

    private Method findBestMethod(Class cls, String methodName) {
        List<Method> getApplicationInfoList = new ArrayList<>();
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                getApplicationInfoList.add(m);
            }
        }
        if (getApplicationInfoList.size() == 0) {
            XposedBridge.log(LOG_TAG + ": " + getStackTraceString(new NoSuchMethodError(cls.getName() + "." + methodName)));
            return null;
        }
        getApplicationInfoList.sort((o1, o2) -> Integer.compare(o2.getParameterTypes().length, o1.getParameterTypes().length));
        return getApplicationInfoList.get(0);
    }

    private void checkAndMakeDebuggable(ApplicationInfo appInfo, String packageName, int user) {
        if (isDebuggable(packageName, user)) {
            appInfo.flags |= FLAG_DEBUGGABLE;
        }
    }

}
