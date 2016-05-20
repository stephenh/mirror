package mirror;

import static mirror.Utils.handleInterrupt;
import static org.apache.commons.lang3.StringUtils.defaultIfEmpty;
import static org.apache.commons.lang3.StringUtils.substringAfterLast;
import static org.jooq.lambda.Seq.seq;
import static org.jooq.lambda.tuple.Tuple.tuple;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jooq.lambda.tuple.Tuple2;

import com.google.common.annotations.VisibleForTesting;

import mirror.UpdateTreeDiff.DiffResults;

/**
 * Implements the steady-state (post-initial sync) two-way sync logic.
 *
 * We poll for changes, either the remote host or our local disk, and
 * either persist it locally or send it out remotely, while also considering
 * whether we've since had a newer/conflicting change.
 */
public class SyncLogic extends AbstractThreaded {

  private static final Update shutdownUpdate = Update.newBuilder().build();
  private final Queues queues;
  private final FileAccess fileAccess;
  private final UpdateTree tree;

  public SyncLogic(MirrorSessionState state, Queues queues, FileAccess fileAccess, UpdateTree tree) {
    super(state);
    this.queues = queues;
    this.fileAccess = fileAccess;
    this.tree = tree;
  }

  @Override
  protected void pollLoop() throws InterruptedException {
    diff(); // do an initial diff
    while (!shutdown) {
      try {
        List<Update> batch = getNextBatchOrBlock();
        if (seq(batch).anyMatch(u -> u == shutdownUpdate)) {
          break;
        }
        logLocalUpdates(batch);
        for (Update u : batch) {
          handleUpdate(u);
        }
        diff();
      } catch (Exception e) {
        log.error("Exception", e);
      }
    }
  }

  @Override
  protected void doStop() {
    queues.incomingQueue.clear();
    queues.incomingQueue.add(shutdownUpdate);
  }

  // see if we have up to N more updates
  private List<Update> getNextBatchOrBlock() throws InterruptedException {
    List<Update> updates = new ArrayList<>();
    // block for at least one
    Update update = queues.incomingQueue.take();
    // then try to grab more if they size
    do {
      if (update != null) {
        updates.add(update);
      }
      update = queues.incomingQueue.poll();
    } while (update != null && updates.size() <= 1000);
    return updates;
  }

  @VisibleForTesting
  void poll() throws IOException, InterruptedException {
    Update u = queues.incomingQueue.poll();
    if (u != null) {
      handleUpdate(u);
      diff();
    }
  }

  private void handleUpdate(Update u) throws IOException, InterruptedException {
    if (u.getLocal()) {
      if (isStaleLocalUpdate(u)) {
        return;
      }
      tree.addLocal(readLatestTimeAndSymlink(u));
    } else {
      tree.addRemote(u);
    }
  }

  private void diff() throws InterruptedException {
    DiffResults r = new UpdateTreeDiff(tree).diff();
    r.saveLocally.forEach(handleInterrupt(u -> queues.saveToLocal.put(u)));
    r.sendToRemote.forEach(handleInterrupt(u -> queues.saveToRemote.put(u)));
  }

  /**
   * If we're changing the type of a node, e.g. from a file to a directory,
   * we'll delete the file, which will create a delete event in FileWatcher,
   * but we'll have already created the directory, so we can ignore this
   * update. (Files could also disappear in general between FileWatcher
   * putting the update in the queue, and us picking it up, but that should
   * be rarer.)
   */
  private boolean isStaleLocalUpdate(Update local) throws IOException {
    Path path = Paths.get(local.getPath());
    boolean stillDeleted = local.getDelete() && !fileAccess.exists(path);
    boolean stillExists = !local.getDelete() && fileAccess.exists(path);
    return !(stillDeleted || stillExists);
  }

  /**
   * Even though FileWatcher sets mod times, we need to re-read the mod time here,
   * because if we saved a file, we technically have to a) first write the file, then
   * b) set the mod time back in time to what matches the remote (to prevent the local
   * file from looking newer than the remote that it's actually a copy of).
   * 
   * The FileWatcher is fast enough that it could actually read a "too new" mod time
   * in between a) and b).
   */
  private Update readLatestTimeAndSymlink(Update local) {
    if (!local.getDelete()) {
      try {
        Path path = Paths.get(local.getPath());
        local = Update.newBuilder(local).setModTime(fileAccess.getModifiedTime(path)).build();
        if (!local.getSymlink().isEmpty()) {
          local = Update.newBuilder(local).setSymlink(fileAccess.readSymlink(path).toString()).build();
        }
      } catch (IOException e) {
        // ignore as the path was probably deleted
      }
    }
    return local;
  }

  private void logLocalUpdates(List<Update> batch) {
    // print out what came in locally
    Map<String, List<Tuple2<String, Update>>> byExt = seq(batch) //
      .filter(u -> u.getLocal())
      .map(u -> tuple(defaultIfEmpty(substringAfterLast(u.getPath(), "."), "<dir>"), u))
      .groupBy(t -> t.v1());
    String exts = seq(byExt).map(t -> t.v1() + "=" + t.v2().size()).toString(", ");
    if (!exts.isEmpty()) {
      log.info("Local updates: " + exts);
    }
  }

}
