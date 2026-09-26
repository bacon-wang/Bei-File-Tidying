package com.example.tidying;

import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class GuiSmokeTest {
  @Test void guiClassLoadsInHeadlessBuild() {
    if (GraphicsEnvironment.isHeadless()) return;
    assertDoesNotThrow(() -> Class.forName("com.example.tidying.FileTidyingGui"));
  }
}
