package io.zerodreamcat.zako.flash;

import android.app.Application;
import android.content.Context;
import com.topjohnwu.superuser.Shell;

public class App extends Application {
    public static final String TAG = "ZakoFlash";
    public static Context application;
    public static IRemoteService server;
    public static Shell rootShell;

    public App() {
        application = this;
    }
}
