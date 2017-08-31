package jp.tinyport.tinytest.core;

import android.app.Application;

import com.squareup.leakcanary.LeakCanary;

import jp.tinyport.tinytest.core.di.AppComponent;
import jp.tinyport.tinytest.core.di.AppModule;
import jp.tinyport.tinytest.core.di.DaggerAppComponent;

public class App extends Application {
    private AppComponent mComponent;

    @Override
    public void onCreate() {
        super.onCreate();

        mComponent = DaggerAppComponent.builder()
                .appModule(new AppModule(this))
                .build();

        if (LeakCanary.isInAnalyzerProcess(this)) return;

        LeakCanary.install(this);
    }

    public AppComponent getComponent() {
        return mComponent;
    }
}
