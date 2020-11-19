package org.easydarwin;

import android.content.Context;

public class UnityHelper {

    private volatile static UnityHelper unityHelper;

    private UnityHelper() {
    }

    public static UnityHelper getUnityHelper() {
        if (unityHelper == null) {
            synchronized (UnityHelper.class) {
                if (unityHelper == null) {
                    unityHelper = new UnityHelper();
                }
            }
        }
        return unityHelper;
    }

    public void init(Context mComtext) { // to get the current Context for pusher instead of EasyApplication's Context

    }

    public void unInit() {

    }

    public void setPushURL(String url) {

    }

    public void startPush(byte[] rgb, int width, int height) {

    }

    public void stopPush() {

    }

    public native void convertRGB24ToI420();

    static {
        System.loadLibrary("CSUtil");
    }
}
