package app.iko.notes;

import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/** Native subtree: Android delivers complete MotionEvents directly to the original ink views. */
final class NotesWorkspaceRoot extends FrameLayout {
  private final NotesWorkspace workspace;
  private boolean palmContact, twoFingerNavigation;
  private int palmPenId=-1;
  NotesWorkspaceRoot(NotesWorkspace workspace) {
    super(workspace);
    this.workspace = workspace;
    setFocusableInTouchMode(true);
    setClipChildren(true);
    setClipToPadding(true);
  }
  @Override public boolean dispatchTouchEvent(MotionEvent event) {
    final int action=event.getActionMasked();
    if(action==MotionEvent.ACTION_DOWN){palmContact=event.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER&&workspace.protectPalmAt(event);twoFingerNavigation=false;palmPenId=-1;}
    if(palmContact){
      int stylus=-1;
      for(int i=0;i<event.getPointerCount();i++)if(event.getToolType(i)==MotionEvent.TOOL_TYPE_STYLUS||event.getToolType(i)==MotionEvent.TOOL_TYPE_ERASER){stylus=i;break;}
      if(stylus>=0){
        boolean beginning=palmPenId!=event.getPointerId(stylus);
        if(beginning&&twoFingerNavigation){MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);try{super.dispatchTouchEvent(cancel);}finally{cancel.recycle();}twoFingerNavigation=false;}
        palmPenId=event.getPointerId(stylus);
        int penAction=beginning?MotionEvent.ACTION_DOWN:action==MotionEvent.ACTION_CANCEL?MotionEvent.ACTION_CANCEL:
            (action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP&&event.getActionIndex()==stylus)?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;
        MotionEvent pen=singlePointer(event,stylus,penAction);
        try{super.dispatchTouchEvent(pen);}finally{pen.recycle();}
        if(penAction==MotionEvent.ACTION_UP||penAction==MotionEvent.ACTION_CANCEL)palmPenId=-1;
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)palmContact=false;
        return true;
      }
      // Do not forward a lone palm DOWN to InkView. Explicit two-finger navigation gets a proper stream.
      if(action==MotionEvent.ACTION_POINTER_DOWN&&event.getPointerCount()==2&&!twoFingerNavigation&&event.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER&&event.getToolType(1)==MotionEvent.TOOL_TYPE_FINGER){
        MotionEvent.PointerProperties properties=new MotionEvent.PointerProperties();event.getPointerProperties(0,properties);
        MotionEvent.PointerCoords coordinates=new MotionEvent.PointerCoords();event.getPointerCoords(0,coordinates);
        MotionEvent down=MotionEvent.obtain(event.getDownTime(),event.getEventTime(),MotionEvent.ACTION_DOWN,1,new MotionEvent.PointerProperties[]{properties},new MotionEvent.PointerCoords[]{coordinates},event.getMetaState(),event.getButtonState(),event.getXPrecision(),event.getYPrecision(),event.getDeviceId(),event.getEdgeFlags(),event.getSource(),event.getFlags());
        try{super.dispatchTouchEvent(down);}finally{down.recycle();}
        twoFingerNavigation=true;
      }
      if(twoFingerNavigation){
        boolean handled=super.dispatchTouchEvent(event);
        if(action==MotionEvent.ACTION_POINTER_UP&&event.getPointerCount()==2){
          MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);
          try{super.dispatchTouchEvent(cancel);}finally{cancel.recycle();}twoFingerNavigation=false;
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)palmContact=false;
        return handled;
      }
      if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)palmContact=false;
      return true;
    }
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN && findFocus() == null) requestFocus();
    return workspace.preDispatchTouchEvent(event) || super.dispatchTouchEvent(event);
  }
  private static MotionEvent singlePointer(MotionEvent source,int index,int action){
    MotionEvent.PointerProperties properties=new MotionEvent.PointerProperties();source.getPointerProperties(index,properties);
    MotionEvent.PointerCoords coordinates=new MotionEvent.PointerCoords();
    int history=action==MotionEvent.ACTION_MOVE?source.getHistorySize():0;
    if(history>0)source.getHistoricalPointerCoords(index,0,coordinates);else source.getPointerCoords(index,coordinates);
    MotionEvent result=MotionEvent.obtain(source.getDownTime(),history>0?source.getHistoricalEventTime(0):source.getEventTime(),action,1,new MotionEvent.PointerProperties[]{properties},new MotionEvent.PointerCoords[]{coordinates},source.getMetaState(),source.getButtonState(),source.getXPrecision(),source.getYPrecision(),source.getDeviceId(),source.getEdgeFlags(),source.getSource(),source.getFlags());
    if(history>0){
      for(int h=1;h<history;h++){source.getHistoricalPointerCoords(index,h,coordinates);result.addBatch(source.getHistoricalEventTime(h),new MotionEvent.PointerCoords[]{coordinates},source.getMetaState());}
      source.getPointerCoords(index,coordinates);result.addBatch(source.getEventTime(),new MotionEvent.PointerCoords[]{coordinates},source.getMetaState());
    }
    return result;
  }
  @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
    return workspace.preDispatchGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event);
  }
  @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    if (w > 0 && h > 0) workspace.onContainerSizeChanged(w, h);
  }
  @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); workspace.updateVisibility(); }
  @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); workspace.updateVisibility(); }
  @Override protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    if (workspace != null) workspace.updateVisibility();
  }
  @Override protected void onVisibilityChanged(View changed, int visibility) {
    super.onVisibilityChanged(changed, visibility);
    if (workspace != null) workspace.updateVisibility();
  }
}
