package com.gitee.connect_screen.shizuku;

interface IUserService {

    void destroy() = 16777114; // Destroy method defined by Shizuku server

    void exit() = 1; // Exit method defined by user

    String fetchLogs() = 2;

    String executeCommand(String command) = 3;

    void setScreenPower(int powerMode) = 4;

    void startListenVolumeKey() = 5;

    void stopListenVolumeKey() = 6;

    boolean isLoopActive() = 7;

    void startTouchRotation(String devicePath, int rotation, int targetWidth,
                           int targetHeight, int displayId) = 8;

    void stopTouchRotation() = 9;

    boolean isTouchRotationActive() = 10;

    void attachTouchRotationClient(IBinder appToken, IBinder shizukuToken) = 11;

    void recoverDefaultTouch() = 12;

    long[] getLastTouchDownRaw() = 13;

    void startTouchRotationAffine(String devicePath, int rotation, int targetWidth,
                                 int targetHeight, int displayId, in double[] coefficients) = 14;
}
