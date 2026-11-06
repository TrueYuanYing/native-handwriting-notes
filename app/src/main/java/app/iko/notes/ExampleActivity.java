package app.iko.notes;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 独立宿主示例：复用真实 InkView，不替换笔迹算法。 */
public class ExampleActivity extends Activity {
    private InkView ink;
    private DocumentStore store;
    private ExampleNotebookSession session;
    private boolean documentReady;
    // One process-wide queue preserves save/load ordering across Activity recreation.
    // The worker exits after 15 idle seconds and is recreated on demand.
    private static final ExecutorService io = new ThreadPoolExecutor(
        0,1,15,TimeUnit.SECONDS,new LinkedBlockingQueue<>());
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new DocumentStore(this);
        final android.content.SharedPreferences prefs=getSharedPreferences("example",MODE_PRIVATE);
        session=new ExampleNotebookSession(new ExampleNotebookSession.Storage() {
            public String lastId() {return prefs.getString("lastNoteId",null);}
            public NoteDocument load(String id) throws Exception {return store.load(id);}
            public void save(NoteDocument note) throws Exception {store.save(note);}
            public void remember(String id) throws Exception {
                if (!prefs.edit().putString("lastNoteId",id).commit()) throw new Exception("index_save_failed");
            }
        });
        ink = new InkView(this);
        ink.setDocument(NoteDocument.create("合成示例"));
        ink.setOnTouchListener((view,event) -> !documentReady);
        ink.setFingerDrawing(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        add(bar, "笔", () -> ink.setTool("pen"));
        add(bar, "橡皮", () -> ink.setTool("eraser"));
        add(bar, "撤销", () -> ink.undo());
        add(bar, "重做", () -> ink.redo());
        add(bar, "保存", this::save);
        root.addView(bar);
        LinearLayout pane=new LinearLayout(this);
        if (getIntent().getBooleanExtra("split",false)) {
            TextView transcript=new TextView(this);
            transcript.setText("宿主嵌入示例\n\n合成课堂文字：请说明矩阵对角化的条件。\n\n这是布局演示，没有启动ASR或录音。");
            transcript.setPadding(16,16,16,16);
            pane.addView(transcript,new LinearLayout.LayoutParams(0,-1,1));
        }
        pane.addView(ink,new LinearLayout.LayoutParams(0,-1,1));
        root.addView(pane,new LinearLayout.LayoutParams(-1,0,1));
        ink.setInkColor(Color.rgb(30, 40, 55));
        setContentView(root);
        io.execute(() -> {
            try {
                NoteDocument note=session.open();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    ink.setDocument(note);documentReady=true;
                });
            } catch (Exception error) {
                runOnUiThread(() -> {if (!isDestroyed()) Toast.makeText(this,"读取失败，保留原文件；请重开后重试",Toast.LENGTH_LONG).show();});
            }
        });
    }
    private void add(LinearLayout bar, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener(v -> action.run());
        bar.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }
    private void save() {
        if (!documentReady) return;
        NoteDocument snapshot = ink.snapshotDocument();
        io.execute(() -> {
            try {
                session.save(snapshot);
                runOnUiThread(() -> {if (!isDestroyed()) Toast.makeText(this, "已保存到示例应用私有目录", Toast.LENGTH_SHORT).show();});
            } catch (Exception error) {
                runOnUiThread(() -> {if (!isDestroyed()) Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_LONG).show();});
            }
        });
    }
    @Override protected void onPause() {save();super.onPause();}
}
