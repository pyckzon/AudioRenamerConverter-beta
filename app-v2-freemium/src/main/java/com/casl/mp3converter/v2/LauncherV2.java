package com.casl.mp3converter.v2;

import javafx.application.Application;

/** Same launcher pattern as v1: non-module-friendly entry point for FX. */
public class LauncherV2 {
    public static void main(String[] args) {
        Application.launch(ApplicationV2.class, args);
    }
}
