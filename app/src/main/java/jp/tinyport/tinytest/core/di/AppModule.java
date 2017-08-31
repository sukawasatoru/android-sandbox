package jp.tinyport.tinytest.core.di;

import android.app.Application;
import android.content.Context;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class AppModule {
    private final Context mContext;

    public AppModule(Application app) {
        mContext = app;
    }

    @Provides
    public Context provideContext() {
        return mContext;
    }
}
