package app.iko.notes;

import android.app.job.*;
import android.content.*;

/** Android schedules expiry while the app is closed; foreground scans cover delayed jobs. */
public final class NotesTrashJobService extends JobService {
  public static void schedule(Context context){
    JobScheduler scheduler=context.getSystemService(JobScheduler.class);
    if(scheduler!=null && scheduler.getPendingJob(70541)==null)scheduler.schedule(new JobInfo.Builder(70541,
        new ComponentName(context,NotesTrashJobService.class)).setPeriodic(24L*60*60*1000).build());
  }
  @Override public boolean onStartJob(JobParameters job){
    new Thread(()->{
      try {if(!NotesHost.isVisible()&&!NotesHost.recording()&&!Boolean.TRUE.equals(NotesHost.state.get("running")))NotesStorage.prune(this,System.currentTimeMillis());}
      catch(Exception ignored){/* Failed files are preserved for a later retry. */}
      finally {jobFinished(job,false);}
    },"iko-trash").start();return true;
  }
  @Override public boolean onStopJob(JobParameters job){return true;}
}
