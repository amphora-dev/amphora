package com.winlator.cmod.runtime.display.environment;

import android.content.Context;
import android.util.Log;
import com.winlator.cmod.runtime.audio.alsaserver.ALSAClient;
import com.winlator.cmod.runtime.display.environment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.runtime.display.environment.components.PulseAudioComponent;
import com.winlator.cmod.shared.io.FileUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class XEnvironment implements Iterable<EnvironmentComponent> {
  private static final String TAG = "XEnvironment";
  private Context context;
  private final ImageFs imageFs;
  private final ArrayList<EnvironmentComponent> components = new ArrayList<>();
  private final Set<EnvironmentComponent> startedComponents = ConcurrentHashMap.newKeySet();

  public XEnvironment(Context context, ImageFs imageFs) {
    this.context = context;
    this.imageFs = imageFs;
  }

  public Context getContext() {
    return context;
  }

  public void setContext(Context context) {
    this.context = context;
  }

  public ImageFs getImageFs() {
    return imageFs;
  }

  public void addComponent(EnvironmentComponent environmentComponent) {
    environmentComponent.environment = this;
    components.add(environmentComponent);
  }

  public <T extends EnvironmentComponent> T getComponent(Class<T> componentClass) {
    for (EnvironmentComponent component : components) {
      if (component.getClass() == componentClass) return (T) component;
    }
    return null;
  }

  @Override
  public Iterator<EnvironmentComponent> iterator() {
    return components.iterator();
  }

  public File getTmpDir() {
    File tmpDir = new File(context.getFilesDir(), "tmp");
    if (!tmpDir.isDirectory()) {
      tmpDir.mkdirs();
      FileUtils.chmod(tmpDir, 0771);
    }
    return tmpDir;
  }

  public void startEnvironmentComponents() {
    FileUtils.clear(getTmpDir());
    Log.d(TAG, "Starting " + components.size() + " environment component(s)");

    // GuestProgramLauncherComponent forks the game process and reads every
    // other component's sockets/state — it must start LAST and remain serial.
    // Every preceding component (XServer, audio, shm, network info, Steam
    // client) is a self-contained service; start them in parallel.
    ArrayList<EnvironmentComponent> parallelStart = new ArrayList<>();
    EnvironmentComponent launcher = null;
    for (EnvironmentComponent c : components) {
      if (c instanceof GuestProgramLauncherComponent) {
        launcher = c;
      } else {
        parallelStart.add(c);
      }
    }

    Set<EnvironmentComponent> attempted = ConcurrentHashMap.newKeySet();
    Throwable startFailure = null;
    if (parallelStart.size() <= 1) {
      for (EnvironmentComponent c : parallelStart) {
        try {
          attempted.add(c);
          startedComponents.add(c);
          Log.d(TAG, "Starting component " + c.getClass().getSimpleName());
          c.start();
        } catch (Throwable t) {
          startFailure = t;
          break;
        }
      }
    } else {
      ExecutorService pool = Executors.newFixedThreadPool(parallelStart.size());
      ArrayList<Future<?>> futures = new ArrayList<>(parallelStart.size());
      for (EnvironmentComponent c : parallelStart) {
        final EnvironmentComponent comp = c;
        futures.add(
            pool.submit(
                () -> {
                  attempted.add(comp);
                  startedComponents.add(comp);
                  Log.d(TAG, "Starting component " + comp.getClass().getSimpleName());
                  comp.start();
                }));
      }
      pool.shutdown();
      boolean interrupted = false;
      for (Future<?> f : futures) {
        boolean complete = false;
        while (!complete) {
          try {
            f.get();
            complete = true;
          } catch (InterruptedException e) {
            interrupted = true;
            if (startFailure == null) startFailure = e;
          } catch (ExecutionException e) {
            complete = true;
            if (startFailure == null) startFailure = e.getCause();
          }
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
    }

    if (startFailure != null) {
      rollbackStartedComponents(attempted, startFailure);
      throwStartFailure(startFailure);
    }

    if (launcher != null) {
      try {
        attempted.add(launcher);
        startedComponents.add(launcher);
        Log.d(TAG, "Starting component " + launcher.getClass().getSimpleName());
        launcher.start();
      } catch (Throwable t) {
        rollbackStartedComponents(attempted, t);
        throwStartFailure(t);
      }
    }
    Log.d(TAG, "Environment component startup finished");
  }

  private void rollbackStartedComponents(
      Set<EnvironmentComponent> attempted, Throwable startFailure) {
    Log.e(TAG, "Environment startup failed; rolling back started components", startFailure);
    for (int i = components.size() - 1; i >= 0; i--) {
      EnvironmentComponent component = components.get(i);
      if (!attempted.contains(component)) continue;
      try {
        component.stop();
        startedComponents.remove(component);
      } catch (Throwable stopFailure) {
        startFailure.addSuppressed(stopFailure);
        Log.e(
            TAG,
            "Component rollback failed for " + component.getClass().getSimpleName(),
            stopFailure);
      }
    }
  }

  private static void throwStartFailure(Throwable failure) {
    if (failure instanceof RuntimeException) throw (RuntimeException) failure;
    if (failure instanceof Error) throw (Error) failure;
    throw new IllegalStateException("Environment component startup failed", failure);
  }

  public void stopEnvironmentComponents() {
    // Stop in reverse order so dependent components (guest launcher) tear down before
    // their underlying services (audio sockets, XServer, shm).
    Log.d(TAG, "Stopping " + components.size() + " environment component(s)");
    RuntimeException firstFailure = null;
    for (int i = components.size() - 1; i >= 0; i--) {
      EnvironmentComponent component = components.get(i);
      if (!startedComponents.contains(component)) continue;
      String name = component.getClass().getSimpleName();
      try {
        Log.d(TAG, "Stopping component " + name);
        component.stop();
        startedComponents.remove(component);
        Log.d(TAG, "Stopped component " + name);
      } catch (RuntimeException e) {
        Log.e(TAG, "Component stop failed for " + name, e);
        if (firstFailure == null) firstFailure = e;
      }
    }
    if (firstFailure != null) {
      Log.e(TAG, "Environment component shutdown finished with failure(s)", firstFailure);
    }
    Log.d(TAG, "Environment component shutdown finished");
  }

  public void onPause() {
    ALSAClient.setEnvironmentPaused(true);
    PulseAudioComponent pulseAudio = getComponent(PulseAudioComponent.class);
    if (pulseAudio != null) pulseAudio.suspend();
  }

  public void onResume() {
    PulseAudioComponent pulseAudio = getComponent(PulseAudioComponent.class);
    if (pulseAudio != null) pulseAudio.resume();
    ALSAClient.setEnvironmentPaused(false);
  }
}
