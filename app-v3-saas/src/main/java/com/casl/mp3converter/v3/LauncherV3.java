package com.casl.mp3converter.v3;

import javafx.application.Application;

/** Same launcher pattern as v1/v2: non-module-friendly entry point for FX. */
public class LauncherV3 {
    public static void main(String[] args) {
        Application.launch(ApplicationV3.class, args);
    }
}
