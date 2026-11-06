package app.iko.notes;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import java.util.concurrent.ExecutorService;

/** Compatibility entry for external imports and the standalone native test surface. */
public final class MainActivity extends Activity {
  private NotesWorkspace workspace;
  private android.window.OnBackInvokedCallback backCallback;
  // Existing native instrumentation observes these aliases; ownership stays in the workspace.
  InkView ink;
  NoteDocument document;
  DocumentStore store;
  ExecutorService io;
  FrameLayout root;
  boolean libraryLoaded;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    workspace = new NotesWorkspace(this, getIntent(), state, false, this::finish);
    workspace.observeState(() -> workspace.copyLegacyState(this));
    setContentView(workspace.getView());
    if (android.os.Build.VERSION.SDK_INT >= 33) {
      backCallback = () -> workspace.handleBack();
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
          android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
    }
  }
  public NotesWorkspace getWorkspace() { return workspace; }
  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent); setIntent(intent); workspace.acceptIntent(intent);
  }
  @Override public void onConfigurationChanged(Configuration config) {
    super.onConfigurationChanged(config); workspace.onHostConfigurationChanged(config);
  }
  @Override protected void onSaveInstanceState(Bundle state) {
    workspace.saveState(state); super.onSaveInstanceState(state);
  }
  @Override protected void onResume() { super.onResume(); if (workspace != null) workspace.onHostResume(); }
  @Override protected void onPause() { if (workspace != null) workspace.onHostPause(); super.onPause(); }
  @Override protected void onStop() { if (workspace != null) workspace.onHostStop(); super.onStop(); }
  @Override protected void onDestroy() {
    if (workspace != null) workspace.dispose();
    if (android.os.Build.VERSION.SDK_INT >= 33 && backCallback != null)
      getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
    super.onDestroy();
  }
  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data); if (workspace != null) workspace.onActivityResult(request, result, data);
  }
  @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (workspace != null) workspace.onRequestPermissionsResult(request, permissions, results);
  }
  @Override public boolean dispatchKeyEvent(KeyEvent event) {
    return workspace != null && workspace.preDispatchKeyEvent(event) || super.dispatchKeyEvent(event);
  }
  @SuppressWarnings("deprecation")
  @Override public void onBackPressed() { if (workspace == null || !workspace.handleBack()) super.onBackPressed(); }
  @SuppressWarnings("deprecation")
  @Override public void finish() {
    super.finish();
    if (getIntent().hasExtra("hostStyle") && android.os.Build.VERSION.SDK_INT < 34) overridePendingTransition(0, 0);
  }
}
