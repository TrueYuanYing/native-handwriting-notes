package app.iko.notes;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

/** Original IKO assets, decoded only at the size needed by the navigation rail. */
final class IkoLogoView extends ImageView {
  IkoLogoView(Context context, String palette, boolean dark) {
    super(context);
    int resource;
    String name;
    switch (palette) {
      case "forest":
        resource = R.drawable.iko_logo_forest;
        name = "森林";
        break;
      case "ocean":
        resource = R.drawable.iko_logo_ocean;
        name = "海洋";
        break;
      case "plum":
        resource = R.drawable.iko_logo_plum;
        name = "梅子";
        break;
      default:
        resource = dark ? R.drawable.iko_logo_dark : R.drawable.iko_logo_light;
        name = dark ? "柔和黑" : "明亮白";
    }
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = 4;
    setImageBitmap(BitmapFactory.decodeResource(getResources(), resource, options));
    setScaleType(ScaleType.FIT_CENTER);
    setBackground(Ui.bg(context, Ui.SURFACE, 16));
    setClipToOutline(true);
    setContentDescription("IKO 标识：" + name);
  }
}
