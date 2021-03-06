package meghanada.store;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Stopwatch;
import com.google.common.hash.Hashing;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import jetbrains.exodus.ExodusException;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityId;
import jetbrains.exodus.entitystore.EntityIterable;
import jetbrains.exodus.entitystore.EntityStoreException;
import jetbrains.exodus.entitystore.PersistentEntityStore;
import jetbrains.exodus.entitystore.PersistentEntityStores;
import jetbrains.exodus.entitystore.StoreTransaction;
import jetbrains.exodus.env.Environment;
import jetbrains.exodus.env.EnvironmentImpl;
import jetbrains.exodus.env.Environments;
import meghanada.Main;
import meghanada.config.Config;
import meghanada.project.Project;
import meghanada.utils.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class ProjectDatabase {

  public static final String ID = "_id";
  public static final String SERIALIZE_KEY = "_serialize";

  private static final String STORE_NAME = "meghanadaStore";
  private static final Logger log = LogManager.getLogger(ProjectDatabase.class);

  private static final int MERGE_SIZE = 10;
  private static final int BURST_LIMIT = MERGE_SIZE;

  private static ProjectDatabase projectDatabase;
  private static AtomicLong seq = new AtomicLong(1);

  private final BlockingQueue<StoreRequest> blockingQueue = new LinkedBlockingDeque<>();
  private ExecutorService executorService = null;
  private Environment environment = null;
  private PersistentEntityStore entityStore = null;
  private String projectRoot;
  private boolean isTerminated;
  private boolean ioBurstMode;

  private ProjectDatabase() {
    open();
    initWorker();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    shutdown();
                  } catch (Throwable t) {
                    log.catching(t);
                  }
                }));
  }

  public static ProjectDatabase getInstance() {
    checkChangeProject();
    if (projectDatabase != null) {
      projectDatabase.open();
      projectDatabase.initWorker();

      return projectDatabase;
    }
    projectDatabase = new ProjectDatabase();
    return projectDatabase;
  }

  private static void checkChangeProject() {

    if (projectDatabase == null) {
      return;
    }

    String currentProject = System.getProperty(Project.PROJECT_ROOT_KEY);
    String projectRoot = projectDatabase.projectRoot;
    if (!currentProject.equals(projectRoot)) {
      projectDatabase.shutdown();
    }
  }

  public static void reset() {
    if (projectDatabase != null) {
      if (nonNull(projectDatabase.entityStore)) {
        try {
          EnvironmentImpl environment =
              (EnvironmentImpl) projectDatabase.entityStore.getEnvironment();
          environment.flushAndSync();
          projectDatabase.entityStore.close();
        } catch (ExodusException e) {
          // wait transaction
          try {
            Thread.sleep(1000 * 3);
          } catch (InterruptedException e1) {
            log.catching(e1);
          }
          projectDatabase.entityStore.close();
        }
        projectDatabase.entityStore = null;
      }
      if (nonNull(projectDatabase.environment)) {
        projectDatabase.environment.close();
        projectDatabase.environment = null;
      }
      projectDatabase.open();
    }
  }

  @SuppressWarnings("rawtypes")
  private static long putObject(Storable s, boolean allowUpdate, StoreTransaction txn) {

    String entityType = s.getEntityType();
    EntityId entityId = s.getEntityId();
    String id = s.getStoreId();
    Entity entity = null;

    if (nonNull(entityId)) {
      try {
        entity = txn.getEntity(entityId);
      } catch (EntityStoreException e) {
        // re-create
      }
    } else {
      EntityIterable it = txn.find(entityType, ID, id);
      entity = it.getFirst();
    }

    if (!allowUpdate && nonNull(entity)) {
      // find update entry
      s.onSuccess(entity);
      return entity.getId().getLocalId();
    }

    if (isNull(entity)) {
      entity = txn.newEntity(entityType);
      entity.setProperty(ID, id);
    }

    Map<String, Comparable> p = s.getSaveProperties();
    if (nonNull(p)) {
      p.forEach(entity::setProperty);
    }
    s.storeExtraData(txn, entity);

    if (s instanceof Serializable) {
      try {
        setSerializeBlobData(entity, SERIALIZE_KEY, s);
      } catch (IOException e) {
        log.catching(e);
        txn.abort();
        return -1;
      }
    }
    // txn.saveEntity(entity);
    s.onSuccess(entity);
    return entity.getId().getLocalId();
  }

  public static void setSerializeBlobData(Entity entity, String prop, Object obj)
      throws IOException {

    requireNonNull(entity, "require entity");
    requireNonNull(obj, "require obj");
    requireNonNull(prop, "require prop");

    byte[] bytes = Serializer.asByte(obj);
    requireNonNull(bytes);

    try (InputStream in = new ByteArrayInputStream(bytes)) {
      requireNonNull(in);
      entity.setBlob(prop, in);
    }
  }

  private void runGC() {
    if (nonNull(this.environment)) {
      this.environment.gc();
    }
  }

  private void initWorker() {

    if (isNull(this.executorService) || this.executorService.isTerminated()) {

      this.executorService = Executors.newCachedThreadPool();
      this.isTerminated = false;
      log.debug("start async database worker");

      this.executorService.execute(
          () -> {
            while (!this.isTerminated) {
              try {

                StoreRequest req = blockingQueue.take();
                Stopwatch stopwatch = Stopwatch.createStarted();
                if (nonNull(req) && !req.isShutdown()) {
                  mergeAndStore(req);
                }
                log.debug("!remain {} elapsed {}", blockingQueue.size(), stopwatch.stop());
                if (blockingQueue.isEmpty()) {
                  log.debug("store queue is empty");
                }
              } catch (Exception e) {
                log.catching(e);
              }
            }

            log.debug("shutdown async database worker");
          });
    }
  }

  private void mergeAndStore(StoreRequest req) {

    if (req.isMergable()) {
      Set<Storable> buf = new HashSet<>(5);
      buf.add(req.getStorable());

      for (int i = 0; i < MERGE_SIZE - 1; i++) {
        StoreRequest nextReq = this.blockingQueue.poll();
        if (isNull(nextReq) || nextReq.isShutdown()) {
          break;
        }

        if (nextReq.isMergable()) {
          buf.add(nextReq.getStorable());
        } else {
          this.mergeAndStore(nextReq);
        }
      }

      if (!buf.isEmpty()) {
        storeObjects(buf, true);
      }
    } else {

      Storable storable = req.getStorable();
      if (nonNull(storable)) {
        storeObject(storable, req.isAllowUpdate());
      }
      Collection<? extends Storable> storables = req.getStorables();
      if (nonNull(storables)) {
        storeObjects(storables, req.isAllowUpdate());
      }
    }
  }

  private synchronized void close() {

    if (nonNull(this.entityStore)) {
      try {
        EnvironmentImpl environment = (EnvironmentImpl) this.entityStore.getEnvironment();
        environment.flushAndSync();
        this.entityStore.close();
      } catch (ExodusException e) {
        // wait transaction
        try {
          Thread.sleep(1000 * 3);
        } catch (InterruptedException e1) {
          log.catching(e1);
        }
        this.entityStore.close();
      }
      this.entityStore = null;
    }

    if (nonNull(this.environment)) {
      this.environment.close();
      this.environment = null;
    }
  }

  private void open() {
    try {
      if (isNull(this.environment)) {
        Config config = Config.load();
        String dir = config.getProjectSettingDir();

        File root = new File(dir);
        if (root.exists() && root.isFile()) {
          root = root.getParentFile();
        }

        if (!root.exists() && !root.mkdirs()) {
          log.warn("{} mkdirs fail", root);
        }

        String rootDir = System.getProperty(Project.PROJECT_ROOT_KEY);
        String name = new File(rootDir).getName();

        String hash =
            Hashing.sha256()
                .newHasher()
                .putString(Main.getVersion(), StandardCharsets.UTF_8)
                .hash()
                .toString();
        File base = new File(root, name + '_' + hash.substring(0, 8));

        File[] files = root.listFiles();
        if (nonNull(files)) {
          for (File file : files) {
            if (file.isDirectory() && file.getName().startsWith(name) && !file.equals(base)) {
              FileUtils.deleteFiles(file, true);
            }
          }
        }

        try {
          this.environment = Environments.newInstance(base);
        } catch (ExodusException ex) {
          // try re-create
          FileUtils.deleteFiles(base, true);
          this.environment = Environments.newInstance(base);
        }
        this.entityStore = PersistentEntityStores.newInstance(environment, STORE_NAME);
        String location = this.environment.getLocation();
        String projectRoot = System.getProperty(Project.PROJECT_ROOT_KEY);
        this.projectRoot = projectRoot;
        log.debug("open project database {}", location);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public String getProjectRoot() {
    return projectRoot;
  }

  public <R> boolean execute(Function<StoreTransaction, Boolean> fn) {
    return this.entityStore.computeInTransaction(fn::apply);
  }

  public <R> R computeInReadonly(Function<StoreTransaction, R> fn) {
    return this.entityStore.computeInReadonlyTransaction(fn::apply);
  }

  public long storeObject(Storable s) {
    return storeObject(s, true);
  }

  public long storeObject(Storable s, boolean allowUpdate) {
    return this.entityStore.computeInTransaction(txn -> putObject(s, allowUpdate, txn));
  }

  public void asyncStoreObject(Storable s, boolean allowUpdate) {
    StoreRequest req = new StoreRequest();
    req.setAllowUpdate(allowUpdate);
    req.setStorable(s);
    requestAsyncStore(req);
  }

  public void asyncStoreObjects(Collection<? extends Storable> storables, boolean allowUpdate) {
    StoreRequest req = new StoreRequest();
    req.setAllowUpdate(allowUpdate);
    req.setStorables(storables);
    requestAsyncStore(req);
  }

  private void requestAsyncStore(StoreRequest req) {
    if (isNull(req.getStorable()) && isNull(req.getStorables())) {
      throw new IllegalArgumentException("require obj or objects");
    }

    try {
      this.blockingQueue.put(req);
      this.runWorker();
    } catch (InterruptedException e) {
      log.catching(e);
    }
  }

  private void runWorker() {

    if (!this.ioBurstMode && this.blockingQueue.size() > BURST_LIMIT) {
      this.ioBurstMode = true;

      this.executorService.execute(
          () -> {
            boolean readyShutdown = false;
            while (!this.isTerminated && this.ioBurstMode) {
              try {

                StoreRequest req = blockingQueue.take();

                Stopwatch stopwatch = Stopwatch.createStarted();
                if (nonNull(req) && !req.isShutdown()) {
                  mergeAndStore(req);
                  readyShutdown = false;
                }
                log.debug("@remain {} elapsed {}", blockingQueue.size(), stopwatch.stop());

                if (this.blockingQueue.isEmpty()) {
                  if (readyShutdown) {
                    this.ioBurstMode = false;
                  } else {
                    readyShutdown = true;
                  }
                }
              } catch (Exception e) {
                log.catching(e);
              }
            }
          });
    }
  }

  public int storeObjects(Collection<? extends Storable> storables, boolean allowUpdate) {

    return this.entityStore.computeInTransaction(
        txn -> {
          int success = 0;
          for (Storable s : storables) {
            if (putObject(s, allowUpdate, txn) != -1) {
              success++;
            }
          }
          return success;
        });
  }

  public <T> T loadObject(String entityType, String id, Class<T> clazz) throws Exception {

    return this.entityStore.computeInReadonlyTransaction(
        txn -> {
          EntityIterable it = txn.find(entityType, ID, id);
          Entity entity = it.getFirst();
          if (nonNull(entity)) {
            try (InputStream in = entity.getBlob(SERIALIZE_KEY)) {
              return Serializer.readObject(in, clazz);
            } catch (Exception e) {
              log.warn(e.getMessage());
              return null;
            }
          }
          return null;
        });
  }

  public boolean deleteObject(String entityType, String id) throws Exception {

    return this.entityStore.computeInTransaction(
        txn -> {
          EntityIterable it = txn.find(entityType, ID, id);
          Entity entity = it.getFirst();
          if (isNull(entity)) {
            return false;
          }
          return entity.delete();
        });
  }

  public <U> List<U> find(
      String entityType,
      String propName,
      String value,
      Function<? super Entity, ? extends U> mapper) {

    return this.entityStore.computeInReadonlyTransaction(
        txn -> {
          EntityIterable iterable = txn.find(entityType, propName, value);
          List<U> result = new ArrayList<>(8);
          for (Entity entity : iterable) {
            result.add(mapper.apply(entity));
          }
          return result;
        });
  }

  public <U> Optional<U> findOne(
      String entityType,
      String propName,
      String value,
      Function<? super Entity, ? extends U> mapper) {

    return this.entityStore.computeInReadonlyTransaction(
        txn -> {
          EntityIterable iterable = txn.find(entityType, propName, value);
          for (Entity entity : iterable) {
            U apply = mapper.apply(entity);
            if (nonNull(apply)) {
              return Optional.of(apply);
            }
          }
          return Optional.empty();
        });
  }

  public <U> List<U> findStartingWith(
      String entityType,
      String propName,
      String value,
      Function<? super Entity, ? extends U> mapper) {

    return this.entityStore.computeInReadonlyTransaction(
        txn -> {
          EntityIterable iterable = txn.findStartingWith(entityType, propName, value);
          List<U> result = new ArrayList<>(8);
          for (Entity entity : iterable) {
            result.add(mapper.apply(entity));
          }
          return result;
        });
  }

  @SuppressWarnings("rawtypes")
  public <U> List<U> findRange(
      String entityType,
      String propName,
      Comparable min,
      Comparable max,
      Function<? super Entity, ? extends U> mapper) {

    return this.entityStore.computeInReadonlyTransaction(
        txn -> {
          EntityIterable iterable = txn.find(entityType, propName, min, max);
          List<U> result = new ArrayList<>(8);
          for (Entity entity : iterable) {
            result.add(mapper.apply(entity));
          }
          return result;
        });
  }

  public void findAll(String entityType, Consumer<Entity> consumer) {
    this.entityStore.executeInReadonlyTransaction(
        txn -> {
          EntityIterable all = txn.getAll(entityType);
          for (Entity entity : all) {
            consumer.accept(entity);
          }
        });
  }

  public <T> void findAllObject(String entityType, Class<T> clazz, Consumer<T> consumer)
      throws Exception {
    this.entityStore.executeInReadonlyTransaction(
        txn -> {
          EntityIterable all = txn.getAll(entityType);
          for (Entity entity : all) {
            try (InputStream in = entity.getBlob(SERIALIZE_KEY)) {
              T t = Serializer.readObject(in, clazz);
              consumer.accept(t);
            } catch (Exception e) {
              log.warn(e.getMessage());
            }
          }
        });
  }

  public long size(String entityName) {
    return this.entityStore.computeInReadonlyTransaction(txn -> txn.getAll(entityName).size());
  }

  public void shutdown() {
    if (this.isTerminated) {
      return;
    }

    this.isTerminated = true;

    StoreRequest req = new StoreRequest();
    req.setShutdown(true);
    try {
      this.blockingQueue.put(req);
      if (this.ioBurstMode) {
        this.blockingQueue.put(req);
      }
    } catch (InterruptedException e) {
      log.catching(e);
    }

    this.executorService.shutdown();
    try {
      this.executorService.awaitTermination(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      log.catching(e);
    }
    this.close();
  }

  static class StoreRequest {

    private final long id;
    private boolean shutdown;
    private boolean allowUpdate;
    private Storable storable;
    private Collection<? extends Storable> storables;

    public StoreRequest() {
      this.id = seq.incrementAndGet();
    }

    public boolean isShutdown() {
      return shutdown;
    }

    public void setShutdown(boolean shutdown) {
      this.shutdown = shutdown;
    }

    public boolean isAllowUpdate() {
      return allowUpdate;
    }

    public void setAllowUpdate(boolean allowUpdate) {
      this.allowUpdate = allowUpdate;
    }

    public Storable getStorable() {
      return storable;
    }

    public void setStorable(Storable storable) {
      this.storable = storable;
    }

    public Collection<? extends Storable> getStorables() {
      return storables;
    }

    public void setStorables(Collection<? extends Storable> storables) {
      this.storables = storables;
    }

    public boolean isMergable() {
      return nonNull(storable) && allowUpdate;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("id", id)
          .add("shutdown", shutdown)
          .add("allowUpdate", allowUpdate)
          .add("storable", storable)
          .add("storables", storables)
          .toString();
    }
  }
}
