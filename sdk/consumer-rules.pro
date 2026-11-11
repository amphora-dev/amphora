# wineandroid.so registers its natives on this class by name (JNI_OnLoad) and
# calls its callbacks by name and signature (createDesktopView, createWindow,
# destroyWindow, windowPosChanged, setParent, setCursor).
-keep class app.amphora.sdk.internal.driver.WineDriver {
    native <methods>;
    public void createDesktopView();
    public void createWindow(int, boolean, boolean, int);
    public void destroyWindow(int);
    public void windowPosChanged(int, int, int, int, int, int, int, int, int, int, int, int, int, int, int, int, int);
    public void setParent(int, int);
    public void setCursor(int, int, int, int, int, int[]);
}
