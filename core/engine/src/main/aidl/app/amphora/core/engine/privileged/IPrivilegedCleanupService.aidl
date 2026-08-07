package app.amphora.core.engine.privileged;

interface IPrivilegedCleanupService {
    void scheduleForceStop(String packageName, int delayMillis);
    void destroy();
}
