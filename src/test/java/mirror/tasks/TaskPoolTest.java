package mirror.tasks;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class TaskPoolTest {

  private final TaskFactory factory = mock(TaskFactory.class);
  private final TaskLogic task = mock(TaskLogic.class);

  @Test
  public void shouldRunATask() {
    TaskPool p = new TaskPool(factory);
    p.runTask(task);
    verify(factory).runTask(eq(task), any((Runnable.class)));
  }

  @Test
  public void shouldStopATaskWhenAskedExplicitly() {
    TaskPool p = new TaskPool(factory);
    p.runTask(task);
    p.stopTask(task);
    verify(factory).runTask(eq(task), any((Runnable.class)));
    verify(factory).stopTask(task);
  }

  @Test
  public void shouldStopAllTasksWhenOneFails() throws Exception {
    TaskLogic task2 = mock(TaskLogic.class);
    TaskPool p = new TaskPool(factory);
    p.runTask(task);
    p.runTask(task2);

    // verify both tasks are started, and capture the onFailure hook
    ArgumentCaptor<Runnable> c = ArgumentCaptor.forClass(Runnable.class);
    verify(factory).runTask(eq(task), c.capture());
    verify(factory).runTask(eq(task2), any(Runnable.class));

    // run the onFailure hook and capture the StopTasksInPool task
    c.getValue().run();
    ArgumentCaptor<TaskLogic> t = ArgumentCaptor.forClass(TaskLogic.class);
    verify(factory).runTask(t.capture());
    t.getValue().runOneLoop();

    verify(factory).stopTask(task);
    verify(factory).stopTask(task2);
  }

  @Test
  public void shouldCallShutdownHookWhenTaskFails() throws Exception {
    TaskPool p = new TaskPool(factory);
    p.runTask(task);

    Runnable callback = mock(Runnable.class);
    p.addShutdownCallback(callback);

    // verify the is are started, and capture the onFailure hook
    ArgumentCaptor<Runnable> c = ArgumentCaptor.forClass(Runnable.class);
    verify(factory).runTask(eq(task), c.capture());

    // run the onFailure hook and capture the StopTasksInPool task
    c.getValue().run();
    ArgumentCaptor<TaskLogic> t = ArgumentCaptor.forClass(TaskLogic.class);
    verify(factory).runTask(t.capture());
    t.getValue().runOneLoop();

    verify(callback).run();
  }
}
