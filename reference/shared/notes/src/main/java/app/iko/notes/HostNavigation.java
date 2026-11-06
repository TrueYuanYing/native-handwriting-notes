package app.iko.notes;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;

/** The same five IKO destinations, retained on the native notebook home. */
final class HostNavigation {
  static View create(Activity activity) {
    LinearLayout bar=Ui.row(activity);
    bar.setBackgroundColor(Ui.SURFACE);
    String[] labels={"听写","课表","笔记","专注","设置"};
    String[] fallback={"microphone","pages","library","focus","settings"};
    JSONArray icons=null;Typeface font=null;
    try {
      icons=new JSONObject(activity.getIntent().getStringExtra("hostStyle")).getJSONArray("navIcons");
      font=Typeface.createFromAsset(activity.getAssets(),"flutter_assets/fonts/MaterialIcons-Regular.otf");
    } catch(Exception ignored) { /* Native vector fallback keeps all destinations usable. */ }
    for(int i=0;i<labels.length;i++) {
      final int index=i;
      boolean selected=i==2;
      LinearLayout item=Ui.column(activity);item.setGravity(Gravity.CENTER);
      item.setContentDescription(labels[i]);item.setSelected(selected);item.setFocusable(true);
      android.widget.FrameLayout indicator=new android.widget.FrameLayout(activity);
      if(selected)indicator.setBackground(Ui.bg(activity,Ui.ACCENT,100));
      View icon;
      if(font!=null && icons!=null) {
        TextView glyph=Ui.text(activity,new String(Character.toChars(icons.optInt(i))),23,selected?Ui.ON_ACCENT:Ui.MUTED);
        glyph.setTypeface(font);glyph.setGravity(Gravity.CENTER);icon=glyph;
      } else {
        IconView glyph=new IconView(activity,fallback[i]);glyph.tint(selected?Ui.ON_ACCENT:Ui.MUTED);icon=glyph;
      }
      icon.setFocusable(false);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      indicator.addView(icon,new android.widget.FrameLayout.LayoutParams(-1,-1));
      item.addView(indicator,new LinearLayout.LayoutParams(Ui.dp(activity,64),Ui.dp(activity,32)));
      TextView label=Ui.text(activity,labels[i],11,Ui.TEXT);label.setGravity(Gravity.CENTER);
      label.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
      label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      item.addView(label,new LinearLayout.LayoutParams(-1,Ui.dp(activity,24)));
      item.setOnClickListener(v->{
        if(index==2)return;
        NotesHost.Client client=NotesHost.client;
        if(client==null){activity.finish();return;}
        client.command(activity,"selectTab",Collections.singletonMap("index",index),result->{
          if(result.get("error")==null)activity.finish();
          else android.widget.Toast.makeText(activity,result.get("error").toString(),android.widget.Toast.LENGTH_SHORT).show();
        });
      });
      Ui.press(item);
      bar.addView(item,new LinearLayout.LayoutParams(0,Ui.dp(activity,68),1));
    }
    return bar;
  }
}
