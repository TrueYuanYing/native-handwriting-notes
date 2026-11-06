package app.iko.notes;

import android.app.Application;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

public final class IkoApplication extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    PDFBoxResourceLoader.init(this);
  }
}
