package jp.tinyport.tinytest.core.di;

import dagger.Subcomponent;
import jp.tinyport.tinytest.MainActivity;
import jp.tinyport.tinytest.core.di.scope.ActivityScope;

@ActivityScope
@Subcomponent(modules = ActivityModule.class)
public interface ActivityComponent {
    void inject(MainActivity activity);
}
