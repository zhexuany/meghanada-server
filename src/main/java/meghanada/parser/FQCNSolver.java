package meghanada.parser;

import com.google.common.collect.BiMap;
import meghanada.parser.source.*;
import meghanada.reflect.ClassIndex;
import meghanada.reflect.MemberDescriptor;
import meghanada.reflect.asm.CachedASMReflector;
import meghanada.utils.ClassName;
import meghanada.utils.ClassNameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.EntryMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

class FQCNSolver {

    private static final Pattern VALID_FQCN = Pattern
            .compile("(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*\\.)+\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*");

    private static final Logger log = LogManager.getLogger(FQCNSolver.class);

    private static FQCNSolver fqcnSolver;
    private final Map<String, String> globalClassSymbol;
    private final List<BiFunction<String, JavaSource, Optional<String>>> searchFunctions;

    private FQCNSolver(Map<String, String> globalClassSymbol) {
        this.globalClassSymbol = globalClassSymbol;
        this.searchFunctions = this.getSearchFunctions();

    }

    public static FQCNSolver getInstance() {
        if (fqcnSolver == null) {
            final Map<String, String> packageClasses = CachedASMReflector.getInstance().getPackageClasses("java.lang");
            fqcnSolver = new FQCNSolver(packageClasses);
        }

        return fqcnSolver;
    }

    private Optional<String> tryClassToFQCN(final String ownPkg, final String name, final BiMap<String, String> classes) {
        final EntryMessage entryMessage = log.traceEntry("ownPkg={} name={} classes={}", ownPkg, name, classes);
        final ClassName className = new ClassName(name);
        final Optional<String> result = Optional.ofNullable(className.toFQCN(ownPkg, classes));
        return log.traceExit(entryMessage, result);
    }

    Optional<String> solveThisScope(final String name, final JavaSource source) {
        final String searchName = ClassNameUtils.removeCapture(name);

        // Check FQCN
        final EntryMessage entryMessage = log.traceEntry("searchName={} name=name{}", searchName, name);

        {
            final Optional<String> typeParam = this.isTypeParameter(name, source);
            if (typeParam.isPresent()) {
                return log.traceExit(entryMessage, typeParam);
            }
            if (name.startsWith(ClassNameUtils.CAPTURE_OF)) {
                return log.traceExit(entryMessage, Optional.of(name));
            }
        }
        // field
        final Optional<String> result = source.getCurrentType()
                .map(typeScope -> typeScope.getFieldSymbol(searchName))
                .map(Variable::getFQCN);
        return log.traceExit(entryMessage, result);

    }

    Optional<String> solveSymbolFQCN(final String name, final JavaSource source, final int line) {
        final EntryMessage entryMessage = log.traceEntry("name={} line={}", name, line);
        final Optional<BlockScope> currentBlock = source.getCurrentBlock();
        final Optional<String> result = currentBlock.map(bs -> {

            // search current
            final Map<String, Variable> declaratorMap = bs.getDeclaratorMap();
            log.trace("declaratorMap={}", declaratorMap);
            if (declaratorMap.containsKey(name)) {
                final Variable variable = declaratorMap.get(name);
                return variable.getFQCN();
            }

            // search parent
            BlockScope parent = bs.getParent();
            while (parent != null) {
                final Map<String, Variable> parentDeclaratorMap = parent.getDeclaratorMap();
                if (parentDeclaratorMap.containsKey(name)) {
                    final Variable variable = parentDeclaratorMap.get(name);
                    return variable.getFQCN();
                }
                parent = parent.getParent();
            }

            // search field nam
            return source.getCurrentType().map(ts -> {
                final Variable fieldSymbol = ts.getFieldSymbol(name);
                if (fieldSymbol != null) {
                    return fieldSymbol.getFQCN();
                }
                return null;
            }).orElseGet(() -> solveFQCN(name, source).orElse(null));
        });
        return log.traceExit(entryMessage, result);
    }

    Optional<String> solveFQCN(final String name, final JavaSource source) {

        final String searchName = ClassNameUtils.removeCapture(name);

        // Check FQCN
        log.traceEntry("searchName={} name={}", searchName, name);

        {
            final Optional<String> typeParam = this.isTypeParameter(name, source);
            if (typeParam.isPresent()) {
                return log.traceExit(typeParam);
            }
            if (name.startsWith(ClassNameUtils.CAPTURE_OF)) {
                return log.traceExit(Optional.of(name));
            }
        }


        final Optional<String> result = this.searchFunctions
                .stream()
                .map(fn -> fn.apply(searchName, source))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();

        if (!result.isPresent()) {
            // final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            log.debug("can not solve name:{} file:{}", name, source.getFile());
        } else {
            log.debug("solved: {} -> FQCN:{}", searchName, result.get());
        }
        return log.traceExit(result);
    }

    private List<BiFunction<String, JavaSource, Optional<String>>> getSearchFunctions() {
        List<BiFunction<String, JavaSource, Optional<String>>> searchFunctions = new ArrayList<>(6);

        searchFunctions.add(this::solveThis);
        searchFunctions.add(this::solveSuper);
        searchFunctions.add(this::solveClassName);
        searchFunctions.add(this::solveSymbolName);
        searchFunctions.add(this::tryClassToFQCN);

        return searchFunctions;
    }

    private Optional<String> tryClassToFQCN(final String name, final JavaSource source) {
        return this.tryClassToFQCN(source.getPkg(), name, source.importClass);
    }


    private Optional<String> solveSuper(final String name, final JavaSource source) {
        final EntryMessage entryMessage = log.traceEntry("name={}", name);

        final Optional<TypeScope> currentType = source.getCurrentType();
        if (name.equals("super")
                && (currentType.isPresent() && currentType.get() instanceof ClassScope)) {

            final ClassScope classScope = (ClassScope) currentType.get();
            final List<String> supers = classScope.getExtendsClasses();
            if (supers.size() > 0) {
                final String s = supers.get(0);
                return log.traceExit(entryMessage, Optional.of(s));
            }
        }
        return log.traceExit(entryMessage, Optional.empty());
    }

    private Optional<String> solveThis(final String name, final JavaSource source) {
        final EntryMessage entryMessage = log.traceEntry("name={}", name);
        if (name.equals("this")) {
            final String result = source.getCurrentType()
                    .map(TypeScope::getFQCN)
                    .orElseGet(() -> {
                        final List<TypeScope> ts = source.getTypeScopes();
                        if (ts != null && ts.size() > 0) {
                            return ts.get(0).getFQCN();
                        }
                        return null;
                    });
            return log.traceExit(entryMessage, Optional.ofNullable(result));
        }
        return log.traceExit(entryMessage, Optional.empty());
    }

    private Optional<String> solveClassName(final String name, final JavaSource source) {

        final CachedASMReflector reflector = CachedASMReflector.getInstance();
        final ClassName className = new ClassName(name);
        String searchName = className.getName();
        final EntryMessage entryMessage = log.traceEntry("searchName={} name={}", searchName, name);

        String fqcn;

        // already fqcn ? try access index
        if (searchName.contains(".")) {
            final Optional<ClassIndex> classIndex = reflector.containsClassIndex(searchName);
            if (classIndex.isPresent()) {
                final Optional<String> result = Optional.of(className.replaceClassName(classIndex.get().getDeclaration()));
                log.trace("solved fqcn name={} result={}", searchName, result);
                return log.traceExit(entryMessage, result);
            }

            // and search inner class
            final Optional<String> result1 = searchInnerClassFromOwn(source, searchName);
            if (result1.isPresent()) {
                final Optional<String> result = Optional.of(className.replaceClassName(result1.get()));
                log.trace("solved fqcn name={} result={}", searchName, result);
                return log.traceExit(entryMessage, result);
            }
        }

        // 1. check primitive
        if (ClassNameUtils.isPrimitive(searchName)) {
            final Optional<String> result = Optional.ofNullable(className.replaceClassName(searchName));

            log.trace("solved primitive name={} result={}", searchName, result);
            return log.traceExit(entryMessage, result);
        }

        // 2. solve from imports
        fqcn = source.importClass.get(searchName);
        if (fqcn != null) {
            // TODO solve typeParameter
            final Optional<String> result = Optional.ofNullable(className.replaceClassName(fqcn));
            log.trace("solved import class name={} result={}", searchName, result);
            return log.traceExit(entryMessage, result);
        }

        // 3. solve from globals (java.lang.*)
        fqcn = this.globalClassSymbol.get(searchName);
        if (fqcn != null) {
            final Optional<String> result = Optional.ofNullable(className.replaceClassName(fqcn));
            log.trace("solved default package name={} result={}", searchName, result);
            return log.traceExit(entryMessage, result);
        }

        // 4. solve from package scope
        final Optional<String> packageResult = source.getCurrentType().map(typeScope -> {
            String s = searchName;
            final String typeScopePackage = typeScope.getPackage();

            if (typeScopePackage != null) {
                s = typeScopePackage + '.' + searchName;
            }

            final String result = reflector.containsClassIndex(s)
                    .map(classIndex -> className.replaceClassName(classIndex.getDeclaration()))
                    .orElse(null);
            return result;
        });

        if (packageResult.isPresent()) {
            log.trace("solved package or global scope class name={} result={}", searchName, packageResult);
            return log.traceExit(entryMessage, packageResult);
        }


        // 5. solve current source
        fqcn = source.getCurrentType().map(ts -> {
            // current?
            String type = ts.getType();
            if (type.equals(searchName)) {
                return ts.getFQCN();
            }
            return null;
        }).orElseGet(() -> {
            // solve from parsed source
            for (TypeScope ts : source.getTypeScopes()) {
                String type = ts.getType();
                if (type.equals(searchName)) {
                    return ts.getFQCN();
                }
            }
            return null;
        });

        if (fqcn != null && ClassNameUtils.getSimpleName(fqcn)
                .equals(ClassNameUtils.getSimpleName(searchName))) {
            final Optional<String> result = Optional.ofNullable(className.replaceClassName(fqcn));
            log.trace("solved current class name={} result={}", searchName, result);
            return log.traceExit(entryMessage, result);
        }

        // 6. search our class and inner class
        final Optional<String> result = this.searchInnerClass(source, className, searchName);

        return log.traceExit(entryMessage, result);
    }

    private Optional<String> searchInnerClassFromOwn(final JavaSource source, final String name) {

        final Optional<String> innerClassName = ClassNameUtils.toInnerClassName(name);
        if (name.indexOf(".") > 0 && innerClassName.isPresent()) {
            CachedASMReflector reflector = CachedASMReflector.getInstance();
            final String className = innerClassName.get();
            final List<ClassIndex> list = reflector.searchClasses(className, false, false);
            if (list.size() > 0) {
                final ClassIndex index = list.get(0);
                final String declaration = index.getDeclaration();
                final String innerFQCN = source.getPkg() + "." + name;

                if (innerFQCN.equals(declaration)) {
                    return Optional.of(index.getRawDeclaration());
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> searchInnerClass(final JavaSource source, final ClassName className, final String searchName) {

        final Optional<String> fqcn = source.getCurrentType().map(ts -> {
            String parentClass = ts.getFQCN();

            String result = searchInnerClassInternal(ts, className, parentClass, searchName);
            log.trace("search parentClass:{} searchName:{}", parentClass, searchName);

            while (result == null && parentClass.contains(ClassNameUtils.INNER_MARK)) {
                final int idx = parentClass.lastIndexOf("$");
                parentClass = parentClass.substring(0, idx);
                log.trace("search parentClass:{} searchName:{}", parentClass, searchName);
                result = searchInnerClassInternal(ts, className, parentClass, searchName);
                log.trace("result:{} parentClass:{}", result, parentClass);
            }
            return result;
        });

        return fqcn;
    }

    private String searchInnerClassInternal(final TypeScope ts, final ClassName className, final String parentClass, final String searchName) {
        final EntryMessage entryMessage = log.traceEntry("className={} parentClass={} searchName={}", className, parentClass, searchName);

        final CachedASMReflector reflector = CachedASMReflector.getInstance();
        if (ts.getType().equals(searchName) && reflector.containsFQCN(parentClass)) {
            final String solved = className.replaceClassName(parentClass);
            return log.traceExit(entryMessage, solved);
        }

        final String innerName = parentClass + '$' + searchName;
        if (reflector.containsFQCN(innerName) && innerName.endsWith(searchName)) {
            final String solved = className.replaceClassName(innerName);
            return log.traceExit(entryMessage, solved);
        }

        if (reflector.getGlobalClassIndex().containsKey(parentClass)) {
            final Optional<String> result = reflector.getSuperClassStream(parentClass)
                    .map(superClass -> {
                        ClassName sc = new ClassName(superClass);
                        final String innerFQCN = sc.getName() + '$' + searchName;
                        log.trace("search inner name={}", innerFQCN);

                        if (reflector.containsFQCN(innerFQCN)) {
                            return className.replaceClassName(innerFQCN);
                        }
                        return null;
                    })
                    .filter(s -> s != null)
                    .findFirst();
            if (result.isPresent()) {
                final String solved = result.get();
                return log.traceExit(entryMessage, solved);
            }
        }
        log.traceExit(entryMessage);
        return null;
    }

    private Optional<String> isTypeParameter(final String name, final JavaSource source) {
        return source.getCurrentType().map(typeScope -> {
            if (typeScope instanceof ClassScope) {
                final ClassScope cs = (ClassScope) typeScope;
                final Map<String, String> typeParameterMap = cs.getTypeParameterMap();
                if (typeParameterMap != null && typeParameterMap.containsKey(name)) {
                    final String fqcn = typeParameterMap.get(name);
                    log.trace("match typeParameter name={} fqcn={}", name, fqcn);
                    return fqcn;
                }
            }
            return null;
        });
    }

    private Optional<String> solveSymbolName(final String name, final JavaSource source) {
        final ClassName className = new ClassName(name);
        final String symbolName = className.getName();
        final EntryMessage entryMessage = log.traceEntry("name={} symbolName={}", name, symbolName);

        {
            // check primitive
            if (ClassNameUtils.isPrimitive(symbolName)) {
                final Optional<String> result = Optional.ofNullable(className.replaceClassName(symbolName));
                return log.traceExit(entryMessage, result);
            }
        }

        // search from field
        final String resultFQCN = source.getCurrentType()
                .map(ts -> this.solveFromField(name, source, symbolName, ts))
                .orElseGet(() -> this.solveFromSource(name, source));

        if (resultFQCN != null) {
            final Optional<String> result = Optional.ofNullable(className.replaceClassName(resultFQCN));
            return log.traceExit(entryMessage, result);
        }
        final Optional<String> result = Optional.empty();
        return log.traceExit(entryMessage, result);
    }

    private String solveFromSource(final String name, final JavaSource source) {
        final EntryMessage em = log.traceEntry("name={}", name);
        final String res = source.getTypeScopes()
                .stream()
                .map(TypeScope::getFieldSymbols)
                .filter(map -> map != null && map.containsKey(name))
                .map(map -> {
                    Variable ns = map.get(name);
                    if (ns != null) {
                        return ns.getFQCN();
                    }
                    return null;
                })
                .filter(s -> s != null)
                .findFirst()
                .orElse(null);
        log.traceExit(em, res);
        return res;
    }

    private String solveFromField(final String name, final JavaSource source, final String symbolName, final TypeScope ts) {
        final EntryMessage em = log.traceEntry("name={} symbolName={}", name, symbolName);

        final CachedASMReflector reflector = CachedASMReflector.getInstance();
        if (!symbolName.startsWith("this.")) {
            // search from current block symbol
            BlockScope blockScope = this.getCurrentBlockFromTS(ts);
            if (blockScope != null) {
                while (blockScope != null) {
                    log.trace("block={}", blockScope);
                    final Variable solveNS = blockScope.getDeclaratorMap().get(name);
                    if (solveNS != null) {
                        final String fqcn = solveNS.getFQCN();
                        return log.traceExit(em, fqcn);
                    }
                    // from field
                    if (blockScope instanceof ClassScope) {
                        ClassScope classScope = (ClassScope) blockScope;
                        final Map<String, Variable> fieldSymbols = classScope.fieldSymbols;
                        final Variable variable = fieldSymbols.get(name);
                        if (variable != null) {
                            final String fqcn = variable.getFQCN();
                            return log.traceExit(em, fqcn);
                        }
                    }
                    blockScope = blockScope.getParent();
                }
            }
        }
        final String currentClass = ts.getFQCN();
        final String parentClass = ClassNameUtils.getParentClass(currentClass);

        final String searchField = symbolName.startsWith("this.") ? ClassNameUtils.replace(symbolName, "this.", "") : symbolName;

        for (TypeScope typeScope : source.getTypeScopes()) {
            final String fqcn = typeScope.getFQCN();
            if (fqcn.equals(currentClass) || fqcn.equals(parentClass)) {
                // search from class field
                Map<String, Variable> fieldMap = typeScope.getFieldSymbols();
                if (fieldMap != null && fieldMap.containsKey(searchField)) {
                    Variable ns = fieldMap.get(searchField);
                    if (ns != null) {
                        final String fqcn1 = ns.getFQCN();
                        return log.traceExit(em, fqcn1);
                    }
                }
            }
        }

        // search from class field
        final String res = reflector.reflectFieldStream(parentClass, searchField)
                .map(MemberDescriptor::getRawReturnType)
                .findFirst()
                .orElseGet(() -> reflector.reflectFieldStream(currentClass, searchField)
                        .map(MemberDescriptor::getRawReturnType)
                        .findFirst()
                        .orElse(null));
        log.traceExit(em, res);
        return res;
    }

    private BlockScope getCurrentBlockFromTS(TypeScope typeScope) {
        BlockScope blockScope = typeScope.currentBlock();
        if (blockScope == null) {
            return null;
        }
        return getCurrentBlock(blockScope);
    }

    private BlockScope getCurrentBlock(BlockScope blockScope) {
        if (blockScope.currentBlock() == null) {
            return blockScope;
        }
        blockScope = blockScope.currentBlock();
        return getCurrentBlock(blockScope);
    }

}
