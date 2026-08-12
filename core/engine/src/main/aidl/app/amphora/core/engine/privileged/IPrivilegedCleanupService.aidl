package app.amphora.core.engine.privileged;

import android.os.ParcelFileDescriptor;

interface IPrivilegedCleanupService {
    void scheduleForceStop(String packageName, int delayMillis);
    String installPackage(in ParcelFileDescriptor apk, long apkSize, String packageName);
    String readPerformanceSnapshot();
    void destroy();
}
