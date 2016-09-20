package mirror;

import static org.apache.commons.lang3.StringUtils.defaultIfEmpty;
import static org.apache.commons.lang3.StringUtils.substringAfterLast;
import static org.jooq.lambda.Seq.seq;
import static org.jooq.lambda.tuple.Tuple.tuple;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jooq.lambda.tuple.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import mirror.UpdateTreeDiff.DiffResults;
import mirror.tasks.TaskLogic;

/**
 * Implements the steady-state (post-initial sync) two-way sync logic.
 *
 * We poll for changes, either the remote host or our local disk, and
 * either persist it locally or send it out remotely, while also considering
 * whether we've since had a newer/conflicting change.
 */
public class SyncLogic implements TaskLogic {

  private static final Logger log = LoggerFactory.getLogger(SyncLogic.class);
  private final Queues queues;
  private final FileAccess fileAccess;
  private final UpdateTree tree;
  private boolean diffPaused;

  public SyncLogic(Queues queues, FileAccess fileAccess, UpdateTree tree) {
    this.queues = queues;
    this.fileAccess = fileAccess;
    this.tree = tree;
  }

  @Override
  public void onStart() throws InterruptedException {
    diff(); // do an initial diff
  }

  @Override
  public Duration runOneLoop() throws InterruptedException {
    try {
      List<Update> batch = getNextBatchOrBlock();
      logLocalUpdates(batch);
      for (Update u : batch) {
        handleUpdate(u);
      }
      diff();
    } catch (IOException | RuntimeException e) {
      log.error("Exception", e);
    }
    return null;
  }

  // see if we have up to N more updates
  private List<Update> getNextBatchOrBlock() throws InterruptedException {
    List<Update> updates = new ArrayList<>();
    // block for at least one
    updates.add(queues.incomingQueue.take());
    // now go ahead and drain the rest while we're here
    queues.incomingQueue.drainTo(updates);
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
      if (u.getData().equals(UpdateTree.localOverflowMarker)) {
        tree.markAllLocalNodesDeleted();
        diffPaused = true;
      } else if (u.getData().equals(UpdateTree.overflowRecoveredMarker)) {
        diffPaused = false;
      } else if (isStaleLocalUpdate(u)) {
        return;
      } else {
        tree.addLocal(readLatestTimeAndSymlink(u));
      }
    } else {
      tree.addRemote(u);
    }
  }

  private void diff() throws InterruptedException {
    if (diffPaused) {
      return;
    }
    DiffResults r = new UpdateTreeDiff(tree).diff();
    for (Update u : r.saveLocally) {
      queues.saveToLocal.put(u);
    }
    for (Update u : r.sendToRemote) {
      queues.saveToRemote.put(u);
    }
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
    if (local.getPath().equals("")) {
      return false;
    }
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
    if (!log.isDebugEnabled()) {
      return;
    }
    // print out what came in locally
    Map<String, List<Tuple2<String, Update>>> byExt = seq(batch) //
      .filter(u -> u.getLocal())
      .map(u -> tuple(defaultIfEmpty(substringAfterLast(u.getPath(), "."), "<dir>"), u))
      .groupBy(t -> t.v1());
    String exts = seq(byExt).map(t -> t.v1() + "=" + t.v2().size()).toString(", ");
    if (!exts.isEmpty()) {
      log.debug("Local updates: " + exts);
    }
  }

}
