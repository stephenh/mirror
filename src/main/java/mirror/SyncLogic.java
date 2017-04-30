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
    Update u;
    while ((u = queues.incomingQueue.poll()) != null) {
      handleUpdate(u);
    }
    diff();
  }

  private void handleUpdate(Update u) throws IOException, InterruptedException {
    if (u.getLocal()) {
      if (isStaleLocalUpdate(u)) {
        return;
      }
      tree.addLocal(ensureSettledAndReadModTime(u));
    } else {
      tree.addRemote(u);
    }
  }

  private void diff() throws InterruptedException {
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
   *
   * Also, without ensureSettled, even with watchman, we can see:
   * 
   * 1. .classpath file changes
   * 2. FS emits an inotify event
   * 3. FileWatcher reads the event, sends to queue
   * 4. SyncLogic does a diff, sends to SaveToRemote
   * 5. SaveToRemote reads the file and gets 0 bytes
   * 6. There are no more FS events, but we've now sent a 0 byte file to the remote
   *
   * This also handles getting 100s of inotify events when a 100mb+ file is written,
   * because the writes get flushed every ~100ms. (This happened in WatchService;
   * I'm not sure about watchman.)
   * 
   * If we block for a little bit here, we may still get a delouge of inotify events,
   * but they should all have the same mod time, and so effectively be no-ops and
   * not cause any new diff results to be emitted.
   */
  private Update ensureSettledAndReadModTime(Update local) throws InterruptedException {
    if (!local.getDelete()) {
      try {
        Path path = Paths.get(local.getPath());
        Utils.ensureSettled(fileAccess, path);
        local = Update.newBuilder(local).setModTime(fileAccess.getModifiedTime(path)).build();
        if (!local.getSymlink().isEmpty()) {
          local = Update.newBuilder(local).setSymlink(fileAccess.readSymlink(path).toString()).build();
        }
      } catch (IOException e) {
        // ignore as the path was probably deleted
        log.info("Exception in readLatestTimeAndSymlink: " + e.getMessage());
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
