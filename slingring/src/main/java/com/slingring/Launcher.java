package com.slingring;

import javafx.application.Application;

/**
 * 
 * Launcher
 * 
 * starts the application, calls {@code start()} of {@link App}
 */
public class Launcher {
    public static void main(String[] args) {
        Application.launch(App.class,args);
    }
}
