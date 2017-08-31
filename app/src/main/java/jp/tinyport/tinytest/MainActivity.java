package jp.tinyport.tinytest;

import android.os.Bundle;

import javax.inject.Inject;

import jp.tinyport.tinytest.core.BaseActivity;

public class MainActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getComponent().inject(this);

        finish();
    }
}
