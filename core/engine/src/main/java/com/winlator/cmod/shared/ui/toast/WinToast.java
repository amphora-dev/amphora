package com.winlator.cmod.shared.ui.toast;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public final class WinToast {

  private WinToast() {}

  public static void show(Context context, String text) {}

  public static void show(Context context, int textResId) {}

  public static void show(Context context, String text, Bitmap icon) {}

  public static void show(Context context, int textResId, Bitmap icon) {}

  public static void show(Context context, String text, int toastDuration) {}

  public static void show(Context context, String text, int toastDuration, View anchor) {}

  public static void show(Context context, int textResId, int toastDuration) {}

  public static void show(Context context, String text, Bitmap icon, int toastDuration) {}

  public static void show(Context context, String text, long durationMs) {}

  public static void show(Context context, int textResId, long durationMs) {}

  public static void show(Context context, int textResId, View anchor) {}

  public static void show(Context context, String text, View anchor) {}

  public static void show(
      Context context, String text, Bitmap icon, long durationMs, View anchor) {}
}
