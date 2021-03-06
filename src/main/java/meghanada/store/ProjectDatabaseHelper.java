package meghanada.store;

import static java.util.Objects.isNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityId;
import jetbrains.exodus.entitystore.EntityIterable;
import meghanada.analyze.CompileResult;
import meghanada.analyze.Source;
import meghanada.project.Project;
import meghanada.reflect.ClassIndex;
import meghanada.reflect.MemberDescriptor;
import meghanada.reflect.asm.CachedASMReflector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ProjectDatabaseHelper {

  private static final String PROP_DECLARATION = "declaration";
  private static final String PROP_FILE_PATH = "filePath";
  private static final String BLOB_PROP_MEMBERS = "members";
  private static final String BLOB_PROP_CHECKSUM = "checksum";
  private static final String BLOB_PROP_CALLER = "caller";

  private static final Logger log = LogManager.getLogger(ProjectDatabaseHelper.class);

  public static void saveClassIndexes(Collection<ClassIndex> indexes, boolean allowUpdate) {
    ProjectDatabase projectDatabase = ProjectDatabase.getInstance();
    if (!indexes.isEmpty()) {
      projectDatabase.asyncStoreObjects(indexes, allowUpdate);
    }
  }

  public static File getClassFile(String fqcn) {
    ProjectDatabase database = ProjectDatabase.getInstance();
    Optional<String> res =
        database.findOne(
            ClassIndex.ENTITY_TYPE,
            PROP_DECLARATION,
            fqcn,
            entity -> (String) entity.getProperty(PROP_FILE_PATH));

    return res.map(File::new).orElse(null);
  }

  public static ClassIndex getClassIndex(String fqcn) throws Exception {
    ProjectDatabase database = ProjectDatabase.getInstance();
    return database.loadObject(ClassIndex.ENTITY_TYPE, fqcn, ClassIndex.class);
  }

  public static void getClassIndexLinks(String fqcn, String linkName, Consumer<EntityIterable> fn)
      throws Exception {
    Map<String, ClassIndex> globalClassIndex =
        CachedASMReflector.getInstance().getGlobalClassIndex();
    if (!globalClassIndex.containsKey(fqcn)) {
      return;
    }
    ClassIndex index = globalClassIndex.get(fqcn);
    EntityId entityId = index.getEntityId();
    ProjectDatabase database = ProjectDatabase.getInstance();

    boolean result =
        database.execute(
            txn -> {
              Entity classEntity = txn.getEntity(entityId);
              EntityIterable iterable = classEntity.getLinks(linkName);
              fn.accept(iterable);
              return true;
            });
  }

  public static boolean saveMemberDescriptors(
      final String fqcn, final List<MemberDescriptor> members) {

    ProjectDatabase database = ProjectDatabase.getInstance();
    return database.execute(
        txn -> {
          EntityIterable it = txn.find(ClassIndex.ENTITY_TYPE, ProjectDatabase.ID, fqcn);
          Entity entity = it.getFirst();

          if (isNull(entity)) {
            return false;
          }

          try {
            ProjectDatabase.setSerializeBlobData(entity, BLOB_PROP_MEMBERS, members);
          } catch (IOException e) {
            log.catching(e);
            txn.abort();
            return false;
          }

          //txn.saveEntity(entity);
          return true;
        });
  }

  public static Optional<List<MemberDescriptor>> getMemberDescriptors(String fqcn) {

    ProjectDatabase database = ProjectDatabase.getInstance();
    return database.computeInReadonly(
        txn -> {
          EntityIterable it =
              txn.find(ClassIndex.ENTITY_TYPE, ProjectDatabase.ID, fqcn)
                  .intersect(txn.findWithBlob(ClassIndex.ENTITY_TYPE, BLOB_PROP_MEMBERS));
          Entity entity = it.getFirst();

          if (isNull(entity)) {
            return Optional.empty();
          }

          try (InputStream in = entity.getBlob(BLOB_PROP_MEMBERS)) {
            @SuppressWarnings("unchecked")
            List<MemberDescriptor> res = Serializer.readObject(in, ArrayList.class);
            return Optional.ofNullable(res);
          } catch (Exception e) {
            log.catching(e);
            return Optional.empty();
          }
        });
  }

  public static boolean deleteMemberDescriptors(String fqcn) {

    ProjectDatabase database = ProjectDatabase.getInstance();
    return database.execute(
        txn -> {
          EntityIterable it = txn.find(ClassIndex.ENTITY_TYPE, ProjectDatabase.ID, fqcn);
          Entity entity = it.getFirst();

          if (isNull(entity)) {
            return false;
          }
          boolean result = entity.deleteBlob(BLOB_PROP_MEMBERS);
          //txn.saveEntity(entity);
          return result;
        });
  }

  public static void saveProject(Project project, boolean async) {
    ProjectDatabase database = ProjectDatabase.getInstance();
    if (async) {
      database.asyncStoreObject(project, true);
      return;
    }
    database.storeObject(project, true);
  }

  public static Project loadProject(String projectRoot) throws Exception {
    ProjectDatabase database = ProjectDatabase.getInstance();
    return database.loadObject(Project.ENTITY_TYPE, projectRoot, Project.class);
  }

  public static void saveSource(Source source) {
    ProjectDatabase database = ProjectDatabase.getInstance();
    database.asyncStoreObject(source, true);
  }

  public static void saveSources(Collection<Source> sources, boolean async) {
    ProjectDatabase database = ProjectDatabase.getInstance();
    if (async) {
      database.asyncStoreObjects(sources, true);
      return;
    }
    database.storeObjects(sources, true);
  }

  public static Source loadSource(String filePath) throws Exception {
    ProjectDatabase database = ProjectDatabase.getInstance();
    return database.loadObject(Source.ENTITY_TYPE, filePath, Source.class);
  }

  public static boolean deleteSource(String filePath) throws Exception {
    ProjectDatabase database = ProjectDatabase.getInstance();
    return database.deleteObject(Source.ENTITY_TYPE, filePath);
  }

  @Nonnull
  @SuppressWarnings("unchecked")
  public static Map<String, String> getChecksumMap(String projectRoot) {
    ProjectDatabase database = ProjectDatabase.getInstance();

    Optional<Map<String, String>> result =
        database.computeInReadonly(
            txn -> {
              EntityIterable entities =
                  txn.find(Project.ENTITY_TYPE, ProjectDatabase.ID, projectRoot)
                      .intersect(txn.findWithBlob(Project.ENTITY_TYPE, BLOB_PROP_CHECKSUM));
              Entity entity = entities.getFirst();
              if (isNull(entity)) {
                return Optional.empty();
              }
              try (InputStream in = entity.getBlob(BLOB_PROP_CHECKSUM)) {
                return Optional.ofNullable(Serializer.readObject(in, ConcurrentHashMap.class));
              } catch (Exception e) {
                log.catching(e);
                return Optional.empty();
              }
            });
    return result.orElse(new ConcurrentHashMap<>(32));
  }

  public static boolean saveChecksumMap(String projectRoot, Map<String, String> map) {
    ProjectDatabase database = ProjectDatabase.getInstance();

    return database.execute(
        txn -> {
          EntityIterable entities = txn.find(Project.ENTITY_TYPE, ProjectDatabase.ID, projectRoot);

          Entity entity = entities.getFirst();
          if (isNull(entity)) {
            return false;
          }
          try {
            ProjectDatabase.setSerializeBlobData(entity, BLOB_PROP_CHECKSUM, map);
          } catch (IOException e) {
            log.catching(e);
            txn.abort();
            return false;
          }
          //txn.saveEntity(entity);
          return true;
        });
  }

  @Nonnull
  @SuppressWarnings("unchecked")
  public static Map<String, Set<String>> getCallerMap(String projectRoot) {
    ProjectDatabase database = ProjectDatabase.getInstance();

    Optional<Map<String, Set<String>>> result =
        database.computeInReadonly(
            txn -> {
              EntityIterable entities =
                  txn.find(Project.ENTITY_TYPE, ProjectDatabase.ID, projectRoot)
                      .intersect(txn.findWithBlob(Project.ENTITY_TYPE, BLOB_PROP_CALLER));
              Entity entity = entities.getFirst();
              if (isNull(entity)) {
                return Optional.empty();
              }
              try (InputStream in = entity.getBlob(BLOB_PROP_CALLER)) {
                return Optional.ofNullable(Serializer.readObject(in, ConcurrentHashMap.class));
              } catch (Exception e) {
                log.warn(e.getMessage());
                return Optional.empty();
              }
            });

    return result.orElse(new ConcurrentHashMap<>(32));
  }

  public static boolean saveCallerMap(String projectRoot, Map<String, Set<String>> map) {
    ProjectDatabase database = ProjectDatabase.getInstance();

    return database.execute(
        txn -> {
          EntityIterable entities = txn.find(Project.ENTITY_TYPE, ProjectDatabase.ID, projectRoot);

          Entity entity = entities.getFirst();
          if (isNull(entity)) {
            return false;
          }
          try {
            ProjectDatabase.setSerializeBlobData(entity, BLOB_PROP_CALLER, map);
          } catch (IOException e) {
            log.catching(e);
            txn.abort();
            return false;
          }
          //txn.saveEntity(entity);
          return true;
        });
  }

  public static void saveCompileResult(CompileResult result) {
    ProjectDatabase database = ProjectDatabase.getInstance();
    database.asyncStoreObject(result, false);
  }

  public static void reset() {
    ProjectDatabase.reset();
  }

  public static void shutdown() {
    ProjectDatabase database = ProjectDatabase.getInstance();
    database.shutdown();
  }
}
