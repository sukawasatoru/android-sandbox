package jp.tinyport.tinytest.core;

import android.app.Activity;

import jp.tinyport.tinytest.core.di.ActivityComponent;
import jp.tinyport.tinytest.core.di.ActivityModule;

public class BaseActivity extends Activity {
    private ActivityComponent sComponent;

    protected ActivityComponent getComponent() {
        if (sComponent == null) {
            sComponent = ((App) getApplication()).getComponent()
                    .plus(new ActivityModule());
        }

        return sComponent;
    }
}
