package meghanada.analyze;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityId;
import jetbrains.exodus.entitystore.StoreTransaction;
import meghanada.cache.GlobalCache;
import meghanada.reflect.CandidateUnit;
import meghanada.reflect.ClassIndex;
import meghanada.reflect.MemberDescriptor;
import meghanada.reflect.asm.CachedASMReflector;
import meghanada.store.Storable;
import meghanada.utils.ClassNameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.EntryMessage;

public class Source implements Serializable, Storable {

  public static final String REPORT_UNKNOWN_TREE = "report-unknown-tree";
  public static final String ENTITY_TYPE = "Source";
  public static final String LINK_CLASS_REFERENCES = "references";
  public static final String LINK_REV_CLASS_REFERENCES = "_references";
  public static final String LINK_CLASS = "_class";
  public static final String LINK_VARIABLE = "variable";
  public static final String LINK_FIELD_ACCESS = "fieldAccess";
  public static final String LINK_METHOD_CALL = "methodCall";
  public static final String LINK_SOURCE = "source";

  private static final long serialVersionUID = 8712967042785424554L;
  private static final Logger log = LogManager.getLogger(Source.class);

  public final Set<String> importClasses = new HashSet<>(16);
  public final Map<String, String> staticImportClass = new HashMap<>(8);
  public final Set<String> unused = new HashSet<>(8);
  public final Set<String> unknown = new HashSet<>(8);
  public final List<ClassScope> classScopes = new ArrayList<>(1);
  public final Deque<ClassScope> currentClassScope = new ArrayDeque<>(1);
  public final Set<String> usingClasses = new HashSet<>(8);
  public final String filePath;
  // temp flag
  public boolean hasCompileError;
  private String packageName = "";
  private LinkedList<LineRange> lineRange;
  private int classStartLine;
  private Map<String, String> importMap;

  public Source(final String filePath) {
    this.filePath = filePath;
  }

  private static boolean includeInnerClass(final ClassScope cs, final String fqcn) {
    final String classScopeFQCN = cs.getFQCN();
    if (fqcn.equals(classScopeFQCN)) {
      return true;
    }

    final String replaced = ClassNameUtils.replaceInnerMark(classScopeFQCN);
    if (fqcn.equals(replaced)) {
      return true;
    }

    final List<ClassScope> children = cs.classScopes;
    for (final ClassScope child : children) {
      if (includeInnerClass(child, fqcn)) {
        return true;
      }
    }

    return false;
  }

  private static void addClassReference(
      StoreTransaction txn,
      Entity mainEntity,
      Map<String, ClassIndex> classIndex,
      Entity entity,
      String fqcn) {

    if (isNull(fqcn)) {
      return;
    }
    classIndex.computeIfPresent(
        fqcn,
        (key, index) -> {
          try {
            EntityId entityId = index.getEntityId();
            if (nonNull(entityId)) {
              Entity classEntity = txn.getEntity(entityId);
              classEntity.addLink(LINK_CLASS_REFERENCES, entity);

              entity.addLink(LINK_CLASS, classEntity);
              mainEntity.addLink(LINK_REV_CLASS_REFERENCES, entity);
            }
          } catch (Exception e) {
            log.warn(e.getMessage());
          }
          return index;
        });
  }

  private static void deleteLinks(StoreTransaction txn, Entity mainEntity) {
    Set<String> names = new HashSet<>(3);
    names.add(LINK_VARIABLE);
    names.add(LINK_FIELD_ACCESS);
    names.add(LINK_METHOD_CALL);

    // for (Entity entity : mainEntity.getLinks(LINK_REV_CLASS_REFERENCES)) {
    //   Entity classEntity = entity.getLink(LINK_CLASS);
    //   if (nonNull(classEntity)) {
    //     // reload
    //     classEntity.deleteLink(LINK_CLASS_REFERENCES, entity);
    //   }
    // }

    for (Entity entity : mainEntity.getLinks(names)) {
      entity.delete();
    }

    for (String name : names) {
      mainEntity.deleteLinks(name);
    }

    // mainEntity.deleteLinks(LINK_REV_CLASS_REFERENCES);
  }

  public void addImport(final String fqcn) {
    this.importClasses.add(fqcn);
    this.unused.add(fqcn);
    log.trace("unused class {}", fqcn);
  }

  public void addStaticImport(final String method, final String clazz) {
    this.staticImportClass.putIfAbsent(method, clazz);
    log.trace("static unused class {} {}", clazz, method);
  }

  public void startClass(final ClassScope classScope) {
    this.currentClassScope.push(classScope);
  }

  public Optional<ClassScope> getCurrentClass() {
    final ClassScope classScope = this.currentClassScope.peek();
    if (nonNull(classScope)) {
      return classScope.getCurrentClass();
    }
    return Optional.empty();
  }

  public Optional<ClassScope> endClass() {
    return this.getCurrentClass()
        .map(
            classScope -> {
              this.classScopes.add(classScope);
              return this.currentClassScope.remove();
            });
  }

  public Optional<BlockScope> getCurrentBlock() {
    return this.getCurrentClass().flatMap(TypeScope::getCurrentBlock);
  }

  public Optional<? extends Scope> getCurrentScope() {
    return this.getCurrentClass().flatMap(BlockScope::getCurrentScope);
  }

  public void addClassScope(final ClassScope classScope) {
    this.classScopes.add(classScope);
  }

  private LinkedList<LineRange> getRanges(final File file) throws IOException {

    if (nonNull(this.lineRange)) {
      return this.lineRange;
    }

    int last = 1;
    final LinkedList<LineRange> list = new LinkedList<>();
    try (final BufferedReader br =
        new BufferedReader(
            new InputStreamReader(new FileInputStream(file), Charset.forName("UTF-8")))) {
      String s;
      while ((s = br.readLine()) != null) {
        final int length = s.length();
        final LineRange range = new LineRange(last, last + length);
        list.add(range);
      }
    }
    this.lineRange = list;
    return this.lineRange;
  }

  Position getPos(int pos) throws IOException {
    int line = 1;
    final LinkedList<LineRange> ranges = getRanges(this.getFile());
    for (final LineRange r : ranges) {
      if (r.contains(pos)) {
        return new Position(line, pos + 1);
      }
      final Integer last = r.getEndPos();
      pos -= last;
      line++;
    }
    return new Position(-1, -1);
  }

  public File getFile() {
    return new File(this.filePath);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("file", this.filePath).toString();
  }

  public Optional<Variable> findVariable(final int pos) {
    for (final ClassScope cs : classScopes) {
      final Optional<Variable> variable = cs.findVariable(pos);
      if (variable.isPresent()) {
        return variable;
      }
    }
    log.warn("Missing element pos={}", pos);
    return Optional.empty();
  }

  public void dumpVariable() {
    final EntryMessage entryMessage = log.traceEntry("{}", Strings.repeat("*", 100));
    for (final ClassScope cs : classScopes) {
      cs.dumpVariable();
    }
    log.traceExit(entryMessage);
  }

  public void dumpFieldAccess() {
    final EntryMessage entryMessage = log.traceEntry("{}", Strings.repeat("*", 100));
    for (final ClassScope cs : classScopes) {
      cs.dumpFieldAccess();
    }
    log.traceExit(entryMessage);
  }

  public void dump() {
    final EntryMessage entryMessage = log.traceEntry("{}", Strings.repeat("*", 100));
    for (final ClassScope cs : classScopes) {
      cs.dump();
    }
    log.trace("unused={}", this.unused);
    log.trace("unknown={}", this.unknown);
    log.traceExit(entryMessage);
  }

  public Optional<AccessSymbol> getExpressionReturn(final int line) {
    final Scope scope = Scope.getScope(line, this.classScopes);
    if (nonNull(scope) && (scope instanceof TypeScope)) {
      final TypeScope typeScope = (TypeScope) scope;
      return Optional.ofNullable(typeScope.getExpressionReturn(line));
    }
    return Optional.empty();
  }

  public List<ClassScope> getClassScopes() {
    return this.classScopes;
  }

  public Optional<TypeScope> getTypeScope(final int line) {
    final Scope scope = Scope.getScope(line, this.classScopes);
    if (nonNull(scope)) {
      return Optional.of((TypeScope) scope);
    }
    return Optional.empty();
  }

  public Optional<MethodCall> getMethodCall(
      final int line, final int column, final boolean onlyName) {
    final EntryMessage entryMessage = log.traceEntry("line={} column={}", line, column);
    int col = column;
    final Scope scope = Scope.getInnerScope(line, this.classScopes);
    if (nonNull(scope)) {
      final Collection<MethodCall> symbols = scope.getMethodCall(line);
      final int size = symbols.size();
      log.trace("variables:{}", symbols);
      if (onlyName) {
        for (final MethodCall methodCall : symbols) {
          if (methodCall.nameContains(col)) {
            final Optional<MethodCall> result = Optional.of(methodCall);
            return log.traceExit(entryMessage, result);
          }
        }
      } else {
        while (size > 0 && col-- > 0) {
          for (final MethodCall methodCallSymbol : symbols) {
            if (methodCallSymbol.containsColumn(col)) {
              final Optional<MethodCall> result = Optional.of(methodCallSymbol);
              return log.traceExit(entryMessage, result);
            }
          }
        }
      }
    }
    final Optional<MethodCall> empty = Optional.empty();
    return log.traceExit(entryMessage, empty);
  }

  public List<MethodCall> getMethodCall(final int line) {
    log.traceEntry("line={}", line);
    Scope scope = Scope.getScope(line, this.classScopes);
    if (nonNull(scope)) {
      if (scope instanceof TypeScope) {
        TypeScope typeScope = (TypeScope) scope;
        List<MethodCall> symbols = typeScope.getMethodCall(line);
        if (symbols.size() > 0) {
          return log.traceExit(symbols);
        }
      }
      final List<MethodCall> callSymbols = scope.getMethodCall(line);
      return log.traceExit(callSymbols);
    }
    return log.traceExit(Collections.emptyList());
  }

  public List<FieldAccess> getFieldAccess(final int line) {
    Scope scope = Scope.getScope(line, this.classScopes);
    if (nonNull(scope)) {
      if (scope instanceof TypeScope) {
        TypeScope typeScope = (TypeScope) scope;
        List<FieldAccess> symbols = typeScope.getFieldAccess(line);
        if (symbols.size() > 0) {
          return symbols;
        }
      }
      return scope.getFieldAccess(line);
    }
    return Collections.emptyList();
  }

  public Map<String, Variable> getDeclaratorMap(final int line) {
    final Scope scope = Scope.getInnerScope(line, this.classScopes);
    if (nonNull(scope)) {
      return scope.getDeclaratorMap();
    }
    return Collections.emptyMap();
  }

  public Map<String, Variable> getVariableMap(final int line) {
    final Scope scope = Scope.getInnerScope(line, this.classScopes);
    if (nonNull(scope)) {
      return scope.getVariableMap();
    }
    return Collections.emptyMap();
  }

  public Optional<Variable> getVariable(final int line, final int col) {
    final Scope scope = Scope.getInnerScope(line, this.classScopes);
    if (nonNull(scope)) {
      return scope
          .getVariables()
          .stream()
          .filter(
              variable -> variable.range.begin.line == line && variable.range.containsColumn(col))
          .findFirst();
    }
    return Optional.empty();
  }

  public List<MemberDescriptor> getAllMember() {
    final List<MemberDescriptor> memberDescriptors = new ArrayList<>(8);
    for (final TypeScope typeScope : this.classScopes) {
      final List<MemberDescriptor> result = typeScope.getMemberDescriptors();
      if (nonNull(result)) {
        memberDescriptors.addAll(result);
      }
    }
    return memberDescriptors;
  }

  public Optional<FieldAccess> searchFieldAccess(final int line, final int col, final String name) {
    final Scope scope = Scope.getScope(line, this.classScopes);
    if (nonNull(scope) && (scope instanceof TypeScope)) {
      final TypeScope ts = (TypeScope) scope;
      final Collection<FieldAccess> fieldAccesses = ts.getFieldAccess(line);

      for (final FieldAccess fa : fieldAccesses) {
        final Range range = fa.range;
        final Position begin = fa.range.begin;
        if (range.begin.line == line && range.containsColumn(col) && fa.name.equals(name)) {
          return Optional.of(fa);
        }
      }
    }
    return Optional.empty();
  }

  public Map<String, List<String>> searchMissingImport() {
    return this.searchMissingImport(true);
  }

  private Map<String, List<String>> searchMissingImport(boolean addAll) {
    final CachedASMReflector reflector = CachedASMReflector.getInstance();

    // search missing imports
    final Map<String, List<String>> ask = new HashMap<>(4);

    log.debug("unknown class size:{} classes:{}", this.unknown.size(), this.unknown);
    final Map<String, String> importedClassMap = this.getImportedClassMap();
    for (final String clazzName : this.unknown) {
      String searchWord = ClassNameUtils.removeTypeAndArray(clazzName);
      final int i = searchWord.indexOf('.');
      if (i > 0) {
        searchWord = searchWord.substring(0, i);
      }

      if (searchWord.isEmpty()) {
        continue;
      }

      if (importedClassMap.containsKey(searchWord)) {
        continue;
      }

      log.debug("search unknown class : '{}' ...", searchWord);
      final Collection<? extends CandidateUnit> findUnits =
          reflector.searchClasses(searchWord, false, false);
      log.debug("find candidate units : {}", findUnits);

      if (findUnits.size() == 0) {
        continue;
      }
      if (findUnits.size() == 1) {

        final CandidateUnit[] candidateUnits = findUnits.toArray(new CandidateUnit[1]);
        final String declaration = candidateUnits[0].getDeclaration();
        if (declaration.startsWith("java.lang")) {
          continue;
        }
        final String pa = ClassNameUtils.getPackage(declaration);
        if (nonNull(this.packageName) && pa.equals(this.packageName)) {
          // remove same package
          continue;
        }

        if (addAll) {
          ask.put(clazzName, Collections.singletonList(candidateUnits[0].getDeclaration()));
        }
      } else {
        final List<String> imports =
            findUnits.stream().map(CandidateUnit::getDeclaration).collect(Collectors.toList());
        if (!imports.isEmpty()) {
          ask.put(clazzName, imports);
        }
      }
    }
    return ask;
  }

  public void invalidateCache() {
    this.invalidateCache(this.classScopes);
  }

  private void invalidateCache(final List<ClassScope> classScopes) {
    final GlobalCache globalCache = GlobalCache.getInstance();
    for (final ClassScope classScope : classScopes) {
      globalCache.invalidateMemberDescriptors(classScope.getFQCN());
      this.invalidateCache(classScope.classScopes);
    }
  }

  public List<String> optimizeImports() {
    // shallow copy
    final Map<String, String> importMap = new HashMap<>(this.getImportedClassMap());

    log.debug("unused:{}", this.unused);
    // remove unused
    this.unused.forEach(k -> importMap.values().remove(k));
    log.debug("importMap:{}", importMap);

    final Map<String, List<String>> missingImport = this.searchMissingImport(false);
    log.debug("missingImport:{}", missingImport);

    if (missingImport.size() > 0) {
      // fail
      return Collections.emptyList();
    }

    // create optimize import
    // 1. count import pkg
    // 2. sort
    final Map<String, List<String>> optimizeMap = new HashMap<>(32);
    importMap
        .values()
        .forEach(
            fqcn -> {
              final String packageName = ClassNameUtils.getPackage(fqcn);
              if (packageName.startsWith("java.lang")) {
                return;
              }
              if (optimizeMap.containsKey(packageName)) {
                final List<String> list = optimizeMap.get(packageName);
                list.add(fqcn);
              } else {
                final List<String> list = new ArrayList<>(1);
                list.add(fqcn);
                optimizeMap.put(packageName, list);
              }
            });

    final List<String> optimized =
        optimizeMap
            .values()
            .stream()
            .map(
                imports -> {
                  if (imports.size() >= 100000) {
                    final String sample = imports.get(0);
                    final String pkg = ClassNameUtils.getPackage(sample);
                    final List<String> result = new ArrayList<>(4);
                    result.add(pkg + ".*");
                    return result;
                  }
                  return imports;
                })
            .flatMap(Collection::stream)
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.toList());

    log.debug("optimize imports:{}", optimized);

    return optimized;
  }

  public boolean isReportUnknown() {
    if (this.hasCompileError) {
      return false;
    }
    final String key = System.getProperty(REPORT_UNKNOWN_TREE);
    return nonNull(key) && key.equals("true");
  }

  public boolean addImportIfAbsent(final String fqcn) {

    if (this.importClasses.contains(fqcn)) {
      return false;
    }

    for (final ClassScope classScope : this.classScopes) {
      if (Source.includeInnerClass(classScope, fqcn)) {
        return false;
      }
    }

    final CachedASMReflector reflector = CachedASMReflector.getInstance();
    if (fqcn.startsWith(this.packageName)) {
      final Map<String, String> packageClasses = reflector.getPackageClasses(this.packageName);
      final boolean find =
          packageClasses
              .values()
              .stream()
              .filter(s -> s.equals(fqcn))
              .map(s -> true)
              .findFirst()
              .orElseGet(
                  () -> {
                    // TODO search inner class
                    return false;
                  });

      if (find) {
        return false;
      }
    }
    this.importClasses.add(fqcn);
    return true;
  }

  public void resetLineRange() {
    this.lineRange = null;
  }

  public String getImportedClassFQCN(final String shortName, @Nullable final String defaultValue) {
    return this.importClasses
        .stream()
        .filter(
            s -> {
              if (s.indexOf('.') > 0) {
                return s.endsWith('.' + shortName);
              }
              return s.endsWith(shortName);
            })
        .findFirst()
        .orElseGet(
            () ->
                CachedASMReflector.getInstance()
                    .getStandardClasses()
                    .getOrDefault(shortName, defaultValue));
  }

  public Map<String, String> getImportedClassMap() {

    if (nonNull(this.importMap)) {
      return this.importMap;
    }

    final Map<String, String> map = new HashMap<>(32);
    for (final String s : this.importClasses) {
      final String key = ClassNameUtils.getSimpleName(s);
      map.putIfAbsent(key, s);
    }

    final Map<String, String> standardClasses =
        CachedASMReflector.getInstance().getStandardClasses();
    map.putAll(standardClasses);

    this.importMap = map;
    return map;
  }

  public void addUnknown(@Nullable final String unknown) {
    if (isNull(unknown)) {
      return;
    }
    final String trimed = unknown.trim();
    if (!trimed.isEmpty()) {
      this.unknown.add(trimed);
    }
  }

  @Override
  public String getStoreId() {
    return this.filePath;
  }

  @Override
  public String getEntityType() {
    return ENTITY_TYPE;
  }

  @Override
  @SuppressWarnings("rawtypes")
  public Map<String, Comparable> getSaveProperties() {
    Map<String, Comparable> map = new HashMap<>(3);
    map.put("filePath", this.filePath);
    if (isNull(this.packageName)) {
      map.put("packageName", "");
    } else {
      map.put("packageName", this.packageName);
    }
    map.put("fqcn", this.getFQCN());

    return map;
  }

  // @Override
  // public void storeExtraData(StoreTransaction txn, Entity mainEntity) {

  //   Map<String, ClassIndex> classIndex = CachedASMReflector.getInstance().getGlobalClassIndex();
  //   deleteLinks(txn, mainEntity);

  //   for (ClassScope cs : this.getAllClassScopes()) {
  //     Entity entity = txn.newEntity(ClassScope.ENTITY_TYPE);
  //     cs.setEntityProps(entity);
  //     entity.setLink(LINK_SOURCE, mainEntity);
  //     // addClassReference(txn, mainEntity, classIndex, entity, cs.getFQCN());
  //     // txn.saveEntity(entity);
  //   }

  //   for (Variable variable : getVariables()) {
  //     Entity entity = txn.newEntity(Variable.ENTITY_TYPE);
  //     variable.setEntityProps(entity);
  //     mainEntity.addLink(LINK_VARIABLE, entity);
  //     entity.setLink(LINK_SOURCE, mainEntity);
  //     // addClassReference(txn, mainEntity, classIndex, entity, variable.fqcn);
  //     // txn.saveEntity(entity);
  //   }

  //   for (FieldAccess fieldAccess : getFieldAccesses()) {

  //     Entity entity = txn.newEntity(FieldAccess.ENTITY_TYPE);
  //     fieldAccess.setEntityProps(entity);
  //     mainEntity.addLink(LINK_FIELD_ACCESS, entity);
  //     entity.setLink(LINK_SOURCE, mainEntity);
  //     // addClassReference(txn, mainEntity, classIndex, entity, fieldAccess.declaringClass);
  //     // txn.saveEntity(entity);
  //   }

  //   for (MethodCall methodCall : getMethodCalls()) {

  //     Entity entity = txn.newEntity(MethodCall.ENTITY_TYPE);
  //     methodCall.setEntityProps(entity);
  //     // TODO arguments
  //     mainEntity.addLink(LINK_METHOD_CALL, entity);
  //     entity.setLink(LINK_SOURCE, mainEntity);
  //     // addClassReference(txn, mainEntity, classIndex, entity, methodCall.declaringClass);
  //     // txn.saveEntity(entity);
  //   }
  // }

  private Set<ClassScope> getAllClassScopes(ClassScope classScope) {
    Set<ClassScope> set = new LinkedHashSet<>(8);
    for (ClassScope cs : classScope.getClassScopes()) {
      set.add(cs);
      set.addAll(getAllClassScopes(cs));
    }
    return set;
  }

  private Set<ClassScope> getAllClassScopes() {
    Set<ClassScope> set = new LinkedHashSet<>(8);
    for (ClassScope cs : this.classScopes) {
      set.addAll(getAllClassScopes(cs));
    }
    return set;
  }

  public String getFQCN() {
    if (this.classScopes.isEmpty()) {
      return "";
    }
    return this.classScopes.get(0).getFQCN();
  }

  public Collection<Variable> getVariables() {

    final Set<Variable> result = new HashSet<>(8);
    for (final ClassScope c : this.classScopes) {
      result.addAll(c.getVariables());
    }

    return result;
  }

  public Collection<FieldAccess> getFieldAccesses() {

    final List<FieldAccess> result = new ArrayList<>(8);
    for (final ClassScope c : this.classScopes) {
      result.addAll(c.getFieldAccesses());
    }

    return result;
  }

  public Collection<MethodCall> getMethodCalls() {

    final List<MethodCall> result = new ArrayList<>(8);
    for (final ClassScope c : this.classScopes) {
      result.addAll(c.getMethodCalls());
    }

    return result;
  }

  public Collection<AccessSymbol> getAccessSymbols() {

    final List<AccessSymbol> result = new ArrayList<>(8);
    for (final ClassScope c : this.classScopes) {
      result.addAll(c.getAccessSymbols());
    }

    return result;
  }

  @Nonnull
  public String getPackageName() {
    if (isNull(packageName)) {
      return "";
    }
    return packageName;
  }

  public void setPackageName(String packageName) {
    this.packageName = packageName;
  }

  public int getClassStartLine() {
    return classStartLine;
  }

  public void setClassStartLine(int classStartLine) {
    this.classStartLine = classStartLine;
  }
}
