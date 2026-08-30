package com.example.rumiologia;

import android.app.Application;

public class RumiologiaApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        TemaApp.aplicarGuardado(this);
    }
}
