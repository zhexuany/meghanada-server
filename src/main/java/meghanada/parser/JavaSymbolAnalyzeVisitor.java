package meghanada.parser;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.imports.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import meghanada.parser.source.*;
import meghanada.reflect.*;
import meghanada.reflect.asm.CachedASMReflector;
import meghanada.utils.ClassNameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.EntryMessage;

import java.util.*;
import java.util.stream.Collectors;

class JavaSymbolAnalyzeVisitor extends VoidVisitorAdapter<JavaSource> {

    private static Logger log = LogManager.getLogger(JavaSymbolAnalyzeVisitor.class);
    private final FQCNSolver fqcnSolver;
    private final TypeAnalyzer typeAnalyzer;

    JavaSymbolAnalyzeVisitor() {
        this.fqcnSolver = FQCNSolver.getInstance();
        this.typeAnalyzer = new TypeAnalyzer(this);
    }

    @Override
    public void visit(final PackageDeclaration node, final JavaSource source) {
        final EntryMessage entryMessage = log.traceEntry("PackageDeclaration name={}", node.getName(), node.getRange());
        source.pkg = node.getName().toString();
        log.traceExit(entryMessage);
    }

    @Override
    public void visit(final SingleStaticImportDeclaration node, final JavaSource source) {
        final String member = node.getStaticMember();
        final String fqcn = node.getType().toString();
        log.trace("member={} fqcn={}", member, fqcn);
        source.staticImp.putIfAbsent(member, fqcn);
        super.visit(node, source);
    }

    @Override
    public void visit(final SingleTypeImportDeclaration node, final JavaSource source) {
        final ClassOrInterfaceType type = node.getType();
        final String fqcn = type.toString();
        final String name = ClassNameUtils.getSimpleName(type.getName());
        log.debug("add import name={} fqcn={}", name, fqcn);
        source.importClass.putIfAbsent(name, fqcn);
        CachedASMReflector.getInstance()
                .searchInnerClasses(fqcn)
                .forEach(classIndex -> {
                    final String innerName = ClassNameUtils.replaceInnerMark(classIndex.getName());
                    final String innerFqcn = classIndex.getRawDeclaration();
                    log.trace("add import name={} fqcn={}", innerName, innerFqcn);
                    source.importClass.putIfAbsent(innerName, innerFqcn);
                });
        source.addUnusedClass(name, fqcn);
        super.visit(node, source);
    }

    @Override
    public void visit(StaticImportOnDemandDeclaration node, JavaSource source) {
        final String fqcn = node.getType().toString();
        final CachedASMReflector reflector = CachedASMReflector.getInstance();
        reflector.reflectStaticStream(fqcn)
                .forEach(md -> {
                    source.staticImp.putIfAbsent(md.getName(), md.getDeclaringClass());
                });
        super.visit(node, source);
    }

    @Override
    public void visit(TypeImportOnDemandDeclaration node, JavaSource source) {
        final String pkg = node.getName().getQualifiedName();
        final CachedASMReflector reflector = CachedASMReflector.getInstance();
        Map<String, String> symbols = reflector.getPackageClasses(pkg);
        for (final Map.Entry<String, String> entry : symbols.entrySet()) {
            final String key = ClassNameUtils.replaceInnerMark(entry.getKey());
            final String val = entry.getValue();
            log.trace("add import name={} fqcn={}", key, val);
            source.importClass.putIfAbsent(key, val);
            reflector.searchInnerClasses(val)
                    .forEach(classIndex -> {
                        final String name1 = ClassNameUtils.replaceInnerMark(classIndex.getName());
                        final String fqcn1 = classIndex.getRawDeclaration();
                        log.trace("add import name={} fqcn={}", name1, fqcn1);
                        source.importClass.putIfAbsent(name1, fqcn1);
                    });
            source.addUnusedClass(key, val);
        }
        super.visit(node, source);
    }

    @Override
    public void visit(final EnumConstantDeclaration node, final JavaSource source) {
        log.traceEntry("EnumConstantDeclaration name={} range={}", node.getName(), node.getRange());

        source.getCurrentBlock().ifPresent(blockScope -> node.getArgs()
                .forEach(expression -> typeAnalyzer.analyzeExprClass(expression, blockScope, source)));
        final TypeScope current = source.currentType.peek();
        final String type = current.getType();
        if (node.getClassBody().size() > 0) {
            final EnumScope enumScope = (EnumScope) current;
            enumScope.incrIndex();

            final String className = type + ClassNameUtils.INNER_MARK + enumScope.getIndex();

            final ClassScope classScope = new ClassScope(source.pkg, className, node.getRange(), node.getRange(), false);
            source.currentType.push(classScope);

            log.trace("start EnumConstantDeclaration:{}, Range:{}", className, node.getRange());
            super.visit(node, source);
            log.trace("end   EnumConstantDeclaration:{}, Range:{}", className, node.getRange());

            final TypeScope typeScope = source.currentType.remove();
            source.typeScopes.add(typeScope);
        } else {
            final String className = type + ClassNameUtils.INNER_MARK + node.getName();
            final ClassScope classScope = new ClassScope(source.pkg, className, node.getRange(), node.getRange(), false);
            source.typeScopes.add(classScope);
        }
        log.traceExit();
    }

    @Override
    public void visit(final EnumDeclaration node, final JavaSource source) {
        log.traceEntry("EnumDeclaration name={} range={}", node.getName(), node.getRange());

        final NodeList<ClassOrInterfaceType> nImplements = node.getImplements();
        String className = node.getName();

        final List<String> implClasses = new ArrayList<>(4);
        if (nImplements != null) {
            for (final ClassOrInterfaceType clazz : nImplements) {
                final String name = clazz.getName();
                fqcnSolver.solveFQCN(name, source).ifPresent(implClasses::add);
            }
        }
        final TypeScope current = source.currentType.peek();
        if (current != null) {
            String type = current.getType();
            className = type + ClassNameUtils.INNER_MARK + className;
        }
        final NameExpr nameExpr = node.getNameExpr();

        final EnumScope enumScope = new EnumScope(source.pkg, className, node.getRange(), nameExpr.getRange());
        enumScope.setImplClasses(implClasses);
        source.currentType.push(enumScope);

        log.trace("start Enum:{}, Range:{}", className, node.getRange());
        super.visit(node, source);
        log.trace("end   Enum:{}, Range:{}", className, node.getRange());

        final TypeScope typeScope = source.currentType.remove();
        source.typeScopes.add(typeScope);

        log.traceExit();
    }


    @Override
    public void visit(final ClassOrInterfaceType node, final JavaSource source) {
        final String name = node.getName();
        final EntryMessage entryMessage = log.traceEntry("ClassOrInterfaceType name={}", name);

        node.getTypeArguments().ifPresent(list -> {
            list.forEach(type -> {
                if (type instanceof ClassOrInterfaceType) {
                    this.visit((ClassOrInterfaceType) type, source);
                }
            });
        });

        super.visit(node, source);
        if (source.resolveHints.containsKey(name)) {
            final String fqcn = source.resolveHints.get(name);
            node.setName(fqcn);
            log.trace("solve {} to {}", name, fqcn);
        } else {
            final String fqcn = this.toFQCN(node.getName(), source);
            node.setName(fqcn);
            final Node parentNode = node.getParentNode();
            if (parentNode == null || !(parentNode instanceof ImportDeclaration)) {
                this.markUsedClass(fqcn, source);
            }
            source.resolveHints.putIfAbsent(name, fqcn);
            log.trace("solve {} to {}", name, fqcn);
        }
        log.traceExit(entryMessage);
    }

    @Override
    public void visit(final ClassOrInterfaceDeclaration node, final JavaSource source) {
        final EntryMessage entryMessage = log.traceEntry("ClassOrInterfaceDeclaration name={} range={}",
                node.getName(),
                node.getRange());

        final NodeList<TypeParameter> typeParameters = node.getTypeParameters();

        final List<String> typeParams = typeParameters
                .stream()
                .map(typeParameter -> {
                    this.visit(typeParameter, source);
                    return typeParameter.toString();
                })
                .collect(Collectors.toList());

        String className = node.getName();

        final Optional<TypeScope> current = source.getCurrentType();
        if (current.isPresent()) {
            final String type = current.get().getType();
            className = type + ClassNameUtils.INNER_MARK + className;
        }
        final CachedASMReflector reflector = CachedASMReflector.getInstance();

        final ClassScope classScope = new ClassScope(source.pkg, className,
                node.getRange(),
                node.getNameExpr().getRange(),
                node.isInterface());

        final List<String> extendsClasses = node.getExtends()
                .stream()
                .map(type -> {
                    this.visit(type, source);
                    final String name = type.getName();
                    reflector.reflectFieldStream(name).forEach(md -> {
                        final String declaration = md.getDeclaration();
                        if (declaration.contains("public") || declaration.contains("protected")) {
                            final String returnType = md.getReturnType();
                            final String solved = this.fqcnSolver.solveFQCN(returnType, source)
                                    .orElse(returnType);
                            final Variable ns = new Variable(
                                    md.getDeclaringClass(),
                                    md.getName(),
                                    node.getRange(),
                                    solved,
                                    true);
                            classScope.addFieldSymbol(ns);
                        }
                    });
                    return name;
                }).collect(Collectors.toList());

        final List<String> implClasses = node.getImplements()
                .stream()
                .map(type -> {
                    this.visit(type, source);
                    return type.getName();
                }).collect(Collectors.toList());

        classScope.setExtendsClasses(extendsClasses);
        classScope.setImplClasses(implClasses);
        classScope.setTypeParameters(typeParams);

        source.currentType.push(classScope);
        this.setClassTypeParameter(typeParameters, classScope);
        log.trace("start ClassOrInterface:{}, Range:{}", className, node.getRange());
        super.visit(node, source);
        log.trace("end   ClassOrInterface:{}, Range:{}", className, node.getRange());

        source.typeScopes.add(source.currentType.remove());
        log.traceExit(entryMessage);
    }

    private void setClassTypeParameter(final NodeList<TypeParameter> typeParameters, final ClassScope scope) {
        typeParameters.forEach(tp -> {
            final String key = tp.getName();
            final NodeList<ClassOrInterfaceType> typeBounds = tp.getTypeBound();
            if (typeBounds != null && typeBounds.size() > 0) {
                typeBounds.forEach(typeBound -> {
                    final String tb = typeBound.getName();
                    scope.getTypeParameterMap().put(key, tb);
                    log.trace("put class typeParameterMap name={} typeBound={} fqcn={}", key, tb, tb);
                });
            } else {
                scope.getTypeParameterMap().put(key, ClassNameUtils.OBJECT_CLASS);
                log.trace("put class typeParameterMap name={} typeBound=Object fqcn={}", key, ClassNameUtils.OBJECT_CLASS);
            }
        });
    }

    @Override
    public void visit(final ConstructorDeclaration node, final JavaSource source) {
        final EntryMessage entryMessage = log.traceEntry("ConstructorDeclaration:{}, Range:{}", node.getDeclarationAsString(), node.getRange());
        source.getCurrentType().ifPresent(typeScope -> {
            final NameExpr nameExpr = node.getNameExpr();
            final String constructorName = nameExpr.getName();

            // start method
            typeScope.startMethod(constructorName, node.getRange(), nameExpr.getRange(), new HashMap<>(0));

            super.visit(node, source);

            final BlockScope scope = typeScope.endMethod();
            this.processAfterConstructor(node, source, typeScope, scope);

        });
        log.traceExit(entryMessage);
    }

    @Override
    public void visit(final FieldDeclaration node, final JavaSource source) {

        final EntryMessage entryMessage = log.traceEntry("FieldDeclaration:{}, Range:{}", node, node.getRange());
        source.getCurrentType().ifPresent(ts -> {

            final String typeStr = node.getElementType().toString();
            final ClassOrInterfaceType cot = new ClassOrInterfaceType(typeStr);
            this.visit(cot, source);
            final String fqcn = cot.toString();
            for (final VariableDeclarator v : node.getVariables()) {
                final VariableDeclaratorId declaratorId = v.getId();
                String fqcnAndArray = fqcn + node.getArrayBracketPairsAfterElementType().toString();
                log.trace("field fqcn={}", fqcnAndArray);
                final String name = declaratorId.getName();
                final String declaringClass = ts.getFQCN();
                final Variable ns = new Variable(
                        declaringClass,
                        name,
                        declaratorId.getRange(),
                        fqcnAndArray,
                        true);
                ts.addFieldSymbol(ns);
                final String modifier = toModifierString(node.getModifiers());
                final FieldDescriptor fd = new FieldDescriptor(
                        declaringClass,
                        name,
                        modifier,
                        fqcnAndArray);
                ts.addMemberDescriptor(fd);
            }
        });
        log.traceExit(entryMessage);
    }

    @Override
    public void visit(final LambdaExpr node, final JavaSource arg) {
        log.trace("LambdaExpr:{} {}", node, node.isParametersEnclosed());
        log.trace("LambdaExpr Parent:{}", node.getParentNode().getClass());
        super.visit(node, arg);
    }

    @Override
    public void visit(final MethodDeclaration node, final JavaSource source) {
        final EntryMessage entryMessage = log.traceEntry("MethodDeclaration name={} range={}", node.getName(), node.getRange());
        final NameExpr nameExpr = node.getNameExpr();

        source.getCurrentType().ifPresent(typeScope -> {
            final String methodName = node.getName();

            // mark returnType
            this.markUsedClass(node.getType().toString(), source);

            log.trace("start Method:{}, Range:{}", methodName, node.getRange());
            Map<String, String> typeParameterMap = new HashMap<>();
            node.getTypeParameters().forEach(tp -> {
                final String name = tp.getName();
                final NodeList<ClassOrInterfaceType> typeBounds = tp.getTypeBound();
                if (typeBounds != null && typeBounds.size() > 0) {
                    typeBounds.forEach(clazz -> {
                        final String tb = clazz.getName();
                        final String fqcn = this.toFQCN(tb, source);
                        typeParameterMap.put(name, fqcn);
                        log.trace("put typeParameterMap name={} typeBound={} fqcn={}", name, tb, fqcn);
                    });
                } else {
                    typeParameterMap.put(name, ClassNameUtils.OBJECT_CLASS);
                    log.trace("put typeParameterMap name={} typeBound=Object fqcn={}", name, ClassNameUtils.OBJECT_CLASS);
                }
            });

            typeScope.startMethod(methodName, node.getRange(), nameExpr.getRange(), typeParameterMap);
            super.visit(node, source);
            final BlockScope scope = typeScope.endMethod();
            this.processAfterMethod(node, source, typeScope, scope);
            log.trace("end Method:{} Range:{}", methodName, node.getRange());

        });
        log.traceExit(entryMessage);
    }

    private void processAfterConstructor(final ConstructorDeclaration node, final JavaSource source, final TypeScope typeScope, final BlockScope scope) {
        List<MethodParameter> paramList = Collections.emptyList();
        if (scope instanceof MethodScope) {
            final MethodScope ms = (MethodScope) scope;
            paramList = ms.getParameters();
        }

        final List<String> exList = node.getThrows()
                .stream()
                .map(ref -> {
                    final ClassOrInterfaceType wrap = new ClassOrInterfaceType(ref.toString());
                    this.visit(wrap, source);
                    return wrap.getName();
                }).collect(Collectors.toList());


        final String modifier = toModifierString(node.getModifiers());
        final String fqcn = typeScope.getFQCN();

        // TODO check hasDefault
        final MemberDescriptor memberDescriptor = new MethodDescriptor(fqcn,
                node.getName(),
                CandidateUnit.MemberType.CONSTRUCTOR,
                modifier,
                paramList,
                exList.toArray(new String[exList.size()]),
                fqcn,
                false);
        typeScope.addMemberDescriptor(memberDescriptor);
    }

    private void processAfterMethod(final MethodDeclaration node, final JavaSource source, final TypeScope typeScope, final BlockScope scope) {
        List<MethodParameter> paramList = Collections.emptyList();
        if (scope instanceof MethodScope) {
            final MethodScope ms = (MethodScope) scope;
            paramList = ms.getParameters();
        }

        final List<String> exList = node.getThrows()
                .stream()
                .map(ref -> {
                    final ClassOrInterfaceType wrap = new ClassOrInterfaceType(ref.toString());
                    this.visit(wrap, source);
                    return wrap.getName();
                }).collect(Collectors.toList());

        final String returnTypeFQCN = node.getType().toString();
        final String modifier = toModifierString(node.getModifiers());
        final String fqcn = typeScope.getFQCN();

        // TODO check hasDefault
        final MemberDescriptor memberDescriptor = new MethodDescriptor(fqcn,
                node.getName(),
                CandidateUnit.MemberType.METHOD,
                modifier,
                paramList,
                exList.toArray(new String[exList.size()]),
                returnTypeFQCN,
                false);

        typeScope.addMemberDescriptor(memberDescriptor);
    }

    @Override
    public void visit(FieldAccessExpr node, JavaSource source) {
        final EntryMessage entryMessage = log.traceEntry("FieldAccessExpr node={} range={}", node, node.getRange());
        source.getCurrentBlock().ifPresent(bs -> {
            this.fieldAccess(node, source, bs);
        });
        // super.visit(node, source);
        log.traceExit(entryMessage);
    }

    Optional<FieldAccessSymbol> fieldAccess(final FieldAccessExpr node, final JavaSource source, final BlockScope blockScope) {
        final EntryMessage entryMessage = log.traceEntry("field={} range={}", node.getFieldExpr(), node.getRange());
        final NameExpr nameExpr = node.getFieldExpr();
        final String fieldName = node.getField();
        final Expression scope = node.getScope();
        if (scope instanceof NameExpr) {
            final String type = ((NameExpr) scope).getName();
            if (Character.isUpperCase(type.charAt(0))) {
                this.markUsedClass(type, source);
            }
        }

        Optional<Expression> optional = Optional.ofNullable(scope);
        if (!optional.isPresent()) {
            Expression expr = new ThisExpr();
            optional = Optional.of(expr);
        }

        final Optional<FieldAccessSymbol> result = optional.flatMap(scopeExpr -> {
            final String scopeString = scopeExpr.toString();
            return this.typeAnalyzer.analyzeExprClass(scopeExpr, blockScope, source)
                    .flatMap(exprClass -> {
                        final String declaringClass = this.toFQCN(exprClass, source);
                        return this.createFieldAccessSymbol(fieldName,
                                scopeString,
                                declaringClass,
                                nameExpr.getRange(),
                                source)
                                .map(blockScope::addFieldAccess);
                    });
        });

        return log.traceExit(entryMessage, result);
    }

    @Override
    public void visit(final MethodCallExpr node, final JavaSource source) {
        final EntryMessage entryMessage = log.traceEntry("MethodCallExpr name={} range={}", node.getName(), node.getRange());

        source.getCurrentBlock().ifPresent(blockScope -> {
            this.methodCall(node, source, blockScope);
        });
        // super.visit(node, source);
        log.traceExit(entryMessage);
    }

    Optional<MethodCallSymbol> methodCall(final MethodCallExpr node, final JavaSource src, final BlockScope bs) {
        final NameExpr methodNameExpr = node.getNameExpr();
        final String methodName = methodNameExpr.getName();
        final NodeList<Expression> args = node.getArgs();
        Optional<Expression> scopeExpression = node.getScope();

        final EntryMessage entryMessage = log.traceEntry("name={} range={}", node.getName(), node.getRange());

        if (!scopeExpression.isPresent()) {
            if (src.staticImp.containsKey(methodName)) {
                final String dec = src.staticImp.get(methodName);
                final Expression expr = new NameExpr(dec);
                expr.setRange(node.getRange());
                scopeExpression = Optional.of(expr);
            } else {
                final Expression expr = new ThisExpr();
                expr.setRange(node.getRange());
                scopeExpression = Optional.of(expr);
            }
        } else {
            final Expression expression = scopeExpression.get();
            if (expression instanceof NameExpr) {
                final String type = ((NameExpr) expression).getName();
                // TODO needs?
                if (Character.isUpperCase(type.charAt(0))) {
                    this.markUsedClass(type, src);
                }
            }
        }
        final Optional<MethodCallSymbol> result = scopeExpression.flatMap(scopeExpr -> {
            final String scopeString = scopeExpr.toString();

            return this.typeAnalyzer.analyzeExprClass(scopeExpr, bs, src)
                    .flatMap(declaringClass -> {
                        final String maybeReturn = this.typeAnalyzer.
                                getReturnType(src, bs, declaringClass, methodName, args).
                                orElse(null);

                        return this.createMethodCallSymbol(node,
                                scopeString,
                                declaringClass,
                                src,
                                maybeReturn)
                                .map(bs::addMethodCall);
                    });
        });

        return log.traceExit(entryMessage, result);
    }

    private Optional<MethodCallSymbol> createMethodCallSymbol(final MethodCallExpr node, final String scope, final String declaringClass, final JavaSource source, final String returnType) {
        final NameExpr methodNameExpr = node.getNameExpr();
        final String methodName = methodNameExpr.getName();
        // GET method scope
        final MethodCallSymbol symbol = new MethodCallSymbol(scope,
                methodName,
                node.getRange(),
                methodNameExpr.getRange(),
                declaringClass);

        boolean isLocal = scope.equals("this") || scope.equals("super");

        final Optional<MethodCallSymbol> result = Optional.ofNullable(returnType).map(type -> {
            final String returnFQCN = this.toFQCN(type, source);
            symbol.returnType = returnFQCN;
            return Optional.of(symbol);
        }).orElseGet(() -> {
            final Optional<MethodCallSymbol> mcs = typeAnalyzer.analyzeReturnType(methodName, declaringClass, isLocal, false, source).map(resolved -> {
                final String returnFQCN = this.toFQCN(resolved, source);
                symbol.returnType = returnFQCN;
                return symbol;
            });
            return mcs;
        });

        if (!result.isPresent()) {
            log.warn("MISSING ReturnType:{} :{}", symbol, source.getFile());
        }
        return result;
    }

    private Optional<FieldAccessSymbol> createFieldAccessSymbol(final String name, final String scope, final String declaringClass, final Range range, final JavaSource source) {
        final EntryMessage entryMessage = log.traceEntry("name={} scope={} declaringClass={} range={}", name, scope, declaringClass, range);

        final FieldAccessSymbol symbol = new FieldAccessSymbol(scope, name, range, declaringClass);

        boolean isLocal = scope.equals("this") || scope.equals("super");

        // 1. normal field access instanceA.name
        final Optional<FieldAccessSymbol> fas = this.typeAnalyzer.getReturnFromReflect(name, declaringClass, isLocal, true, source)
                .map(type -> {
                    symbol.returnType = this.toFQCN(type, source);
                    return symbol;
                });
        if (fas.isPresent()) {
            // fields
            return log.traceExit(entryMessage, fas);
        }

        // 2. access inner class instanceA.innerClassB or ClassA.innerClassB
        final Optional<FieldAccessSymbol> fas2 = typeAnalyzer.analyzeReturnType(name, declaringClass, isLocal, true, source)
                .map(type -> {
                    final String returnFQCN = this.toFQCN(type, source);
                    symbol.returnType = returnFQCN;
                    return symbol;
                });
        if (!fas2.isPresent()) {
            log.warn("MISSING ReturnType:{} File:{}", symbol, source.getFile());
        }
        return log.traceExit(entryMessage, fas2);
    }

    private boolean isLocal(String scope, String declaringClass, JavaSource source) {
        final Optional<TypeScope> currentType = source.getCurrentType();
        if (currentType.isPresent()) {
            final String fqcn = currentType.get().getFQCN();
            if (declaringClass.contains(fqcn)) {
                // inner or self
                return true;
            }
        }
        return scope.equals("this") || scope.equals("super");
    }

    @Override
    public void visit(final Parameter node, final JavaSource source) {
        final EntryMessage entryMessage = log.traceEntry("Parameter node='{}' range={}", node, node.getRange());
        final String type = node.isVarArgs() ? node.getType().toString() + ClassNameUtils.VA_ARGS : node.getType().toString();
        final VariableDeclaratorId declaratorId = node.getId();

        source.getCurrentType().ifPresent(ts -> source.getCurrentBlock(ts).ifPresent(bs -> {
            String fqcn = null;
            if (bs instanceof MethodScope) {
                final MethodScope methodScope = (MethodScope) bs;
                final Map<String, String> typeParameterMap = methodScope.getTypeParameterMap();
                if (typeParameterMap.containsKey(type)) {
                    fqcn = typeParameterMap.get(type);
                }
            }
            if (fqcn == null) {
                fqcn = fqcnSolver.solveFQCN(type, source).orElse(type);
            }
            final String name = declaratorId.getName();
            final Variable ns = new Variable(
                    ts.getFQCN(),
                    name,
                    declaratorId.getRange(),
                    fqcn,
                    true);
            bs.addNameSymbol(ns);
            if (bs instanceof MethodScope) {
                final MethodScope methodScope = (MethodScope) bs;
                methodScope.getParameters().add(new MethodParameter(fqcn, name));
            }
        }));

        super.visit(node, source);
        log.traceExit(entryMessage);
    }

    @Override
    public void visit(final CatchClause node, final JavaSource source) {
        source.getCurrentType().ifPresent(typeScope -> {
            final Parameter except = node.getParam();
            final VariableDeclaratorId variableDeclaratorId = except.getId();
            if (variableDeclaratorId != null) {
                final String name = variableDeclaratorId.getName();
                // sampling first
                final Type exType = except.getType();
                final String exTypeFQCN = fqcnSolver.solveFQCN(exType.toString(), source).orElse(exType.toString());
                final Variable ns = new Variable(
                        typeScope.getFQCN(),
                        name,
                        variableDeclaratorId.getRange(),
                        exTypeFQCN);
                final BlockScope blockScope = source.getCurrentBlock(typeScope).orElse(typeScope);
                blockScope.addNameSymbol(ns);
            }
        });
        super.visit(node, source);
    }

    @Override
    public void visit(final ObjectCreationExpr node, final JavaSource source) {
        final EntryMessage entryMessage = log.traceEntry("ObjectCreationExpr node={} range={}", node, node.getRange());
        // new statement
        final String className = node.getType().getName();
        final String createClassName = fqcnSolver.solveFQCN(className, source).orElse(className);
        this.markUsedClass(createClassName, source);
        if (source.isImported(createClassName)) {
            source.getCurrentBlock().ifPresent(bs -> {
                this.constructorCall(createClassName, node, source, bs);
            });
        }
        log.traceExit(entryMessage);
    }

    private void markUsedClass(final String type, final JavaSource source) {
        final String nm = ClassNameUtils.getSimpleName(type);
        log.debug("mark className:{}", nm);

        if (!source.isImported(nm)) {
            log.debug("add unknown class:{}", nm);
            source.addUnknownClass(nm);
        } else {
            log.debug("remove unused class:{}", nm);
            source.removeUnusedClass(nm);
        }
    }

    private void constructorCall(final String createClass, final ObjectCreationExpr node, final JavaSource src, final BlockScope bs) {
        final EntryMessage entryMessage = log.traceEntry("constructorCall class={}", createClass);
        final NodeList<Expression> args = node.getArgs();
        if (args != null) {
            final Optional<TypeAnalyzer.MethodSignature> signature = typeAnalyzer.getMethodSignature(src, bs, createClass, args);

            final String maybeReturn = signature.flatMap(ms ->
                    this.typeAnalyzer.getConstructor(src, createClass, args.size(), ms.signature).
                            map(CandidateUnit::getReturnType)).orElse(createClass);

            final String clazz = ClassNameUtils.getSimpleName(createClass);

            final MethodCallSymbol mcs = new MethodCallSymbol("",
                    clazz,
                    node.getRange(),
                    node.getType().getRange(),
                    createClass);
            mcs.returnType = maybeReturn;
            bs.addMethodCall(mcs);
        }
        log.traceExit(entryMessage);
    }

    @Override
    public void visit(final NameExpr node, final JavaSource source) {

        final EntryMessage entryMessage = log.traceEntry("NameExpr name={} range={}", node.getName(), node.getRange());
        source.getCurrentType().ifPresent(ts -> source.getCurrentBlock(ts).ifPresent(bs -> {
            final String name = node.getName();
            final Variable fieldSymbol = ts.getFieldSymbol(name);
            if (fieldSymbol != null) {
                final Variable ns = new Variable(
                        ts.getFQCN(),
                        name,
                        node.getRange(),
                        fieldSymbol.getFQCN());
                bs.addNameSymbol(ns);
            } else {
                final Map<String, Variable> map = bs.getDeclaratorMap();
                if (map.containsKey(name)) {
                    final String fqcn = map.get(name).getFQCN();
                    final Variable v = new Variable(
                            ts.getFQCN(),
                            name,
                            node.getRange(),
                            fqcn);
                    bs.addNameSymbol(v);
                }
            }
        }));
        super.visit(node, source);
        log.traceExit(entryMessage);
    }

    @Override
    public void visit(final VariableDeclarationExpr node, final JavaSource source) {
        final EntryMessage entryMessage = log.traceEntry("VariableDeclarationExpr node='{}' range={}", node, node.getRange());

        source.getCurrentType().ifPresent(typeScope -> source.getCurrentBlock(typeScope).ifPresent(blockScope -> {
            for (final VariableDeclarator v : node.getVariables()) {
                String type = v.getType().toString();
                final VariableDeclaratorId declaratorId = v.getId();
                final String name = declaratorId.getName();
                type = type + node.getArrayBracketPairsAfterElementType().toString();

                fqcnSolver.solveFQCN(type, source).ifPresent(fqcn -> {
                    log.trace("variableDeclarationExpr fqcn={}", fqcn);
                    this.markUsedClass(fqcn, source);
                    final Variable ns = new Variable(
                            typeScope.getFQCN(),
                            name,
                            declaratorId.getRange(),
                            fqcn,
                            true);
                    blockScope.addNameSymbol(ns);
                });
            }
        }));
        super.visit(node, source);
        log.traceExit(entryMessage);
    }

    @Override
    public void visit(final ReturnStmt node, final JavaSource source) {
        // log.debug("ReturnStmt Line:{}", node.getBeginLine(), node);
        source.getCurrentBlock().ifPresent(blockScope -> {
            if (blockScope.isLambdaBlock) {
                // lambda return
                final Optional<Expression> expression = node.getExpr();
                expression.ifPresent(expr -> {
                    typeAnalyzer.analyzeExprClass(expr, blockScope, source).ifPresent(fqcn -> {
                        final String s = ClassNameUtils.boxing(fqcn);
                        log.trace("Lambda Block ReturnFQCN:{} Expr:{}", s, expression);
                        source.typeHint.addLambdaReturnType(s);
                    });
                });
            }
        });
        super.visit(node, source);
    }

    @Override
    public void visit(final BlockStmt node, final JavaSource source) {
        final EntryMessage entryMessage = log.traceEntry("BlockStmt range={}", node.getRange());

        final boolean called = source.getCurrentType().flatMap(typeScope -> source.getCurrentBlock(typeScope).map(blockScope -> {
            final Node parentNode = node.getParentNode();
            final Class<? extends Node> parentNodeClass = parentNode.getClass();
            if (parentNodeClass.equals(BlockStmt.class)
                    || parentNodeClass.equals(IfStmt.class)
                    || parentNodeClass.equals(TryStmt.class)) {
                blockScope.startBlock("", node.getRange(), blockScope.getDeclaratorMap());
                super.visit(node, source);
                blockScope.endBlock();
                return true;
            }

            super.visit(node, source);
            // called
            return true;
        })).orElse(false);

        if (!called) {
            super.visit(node, source);
        }
        log.traceExit(entryMessage);
    }

    @Override
    public void visit(final ExpressionStmt node, final JavaSource source) {
        final Optional<BlockScope> currentBlock = source.getCurrentBlock();
        if (currentBlock.isPresent()) {
            final BlockScope blockScope = currentBlock.get();
            final ExpressionScope expr = new ExpressionScope(blockScope.getName(), node.getRange());
            blockScope.startExpression(expr);
            super.visit(node, source);
            blockScope.endExpression();
        } else {
            super.visit(node, source);
        }
    }

    private String toModifierString(final EnumSet<Modifier> m) {
        final StringBuilder sb = new StringBuilder();
        if (m.contains(Modifier.PRIVATE)) {
            sb.append("private ");
        }
        if (m.contains(Modifier.PUBLIC)) {
            sb.append("public ");
        }
        if (m.contains(Modifier.PROTECTED)) {
            sb.append("protected ");
        }
        if (m.contains(Modifier.STATIC)) {
            sb.append("static ");
        }
        if (m.contains(Modifier.ABSTRACT)) {
            sb.append("abstract ");
        }
        if (m.contains(Modifier.FINAL)) {
            sb.append("final ");
        }
        if (m.contains(Modifier.NATIVE)) {
            sb.append("native ");
        }
        if (m.contains(Modifier.STRICTFP)) {
            sb.append("strict ");
        }
        if (m.contains(Modifier.SYNCHRONIZED)) {
            sb.append("synchronized ");
        }
        if (m.contains(Modifier.TRANSIENT)) {
            sb.append("transient ");
        }
        if (m.contains(Modifier.VOLATILE)) {
            sb.append("volatile ");
        }
        if (sb.length() > 0) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

//    private String toModifierString(final int modifier) {
//        final StringBuilder sb = new StringBuilder();
//        if (Modifier.isPrivate(modifier)) {
//            sb.append("private ");
//        }
//        if (Modifier.isPublic(modifier)) {
//            sb.append("public ");
//        }
//        if (Modifier.isProtected(modifier)) {
//            sb.append("protected ");
//        }
//        if (Modifier.isStatic(modifier)) {
//            sb.append("static ");
//        }
//        if (Modifier.isAbstract(modifier)) {
//            sb.append("abstract ");
//        }
//        if (Modifier.isFinal(modifier)) {
//            sb.append("final ");
//        }
//        if (Modifier.isInterface(modifier)) {
//            sb.append("interface ");
//        }
//        if (Modifier.isNative(modifier)) {
//            sb.append("native ");
//        }
//        if (Modifier.isStrict(modifier)) {
//            sb.append("strict ");
//        }
//        if (Modifier.isSynchronized(modifier)) {
//            sb.append("synchronized ");
//        }
//        if (Modifier.isTransient(modifier)) {
//            sb.append("transient ");
//        }
//        if (Modifier.isVolatile(modifier)) {
//            sb.append("volatile ");
//        }
//        return sb.toString();
//    }

    private String toFQCN(final String type, final JavaSource source) {
        String returnFQCN = ClassNameUtils.removeCapture(type);

        final Optional<TypeScope> currentType = source.getCurrentType();
        if (currentType.isPresent()) {
            final TypeScope typeScope = currentType.get();
            if (typeScope instanceof ClassScope) {
                final ClassScope classScope = (ClassScope) typeScope;
                final Map<String, String> map = classScope.getTypeParameterMap();
                if (map.containsKey(returnFQCN)) {
                    returnFQCN = ClassNameUtils.removeCapture(map.get(returnFQCN));
                }
            }
        }

        return this.fqcnSolver.solveFQCN(returnFQCN, source).orElse(returnFQCN);
    }

    private Optional<String> resolveFQCN(final String type, final JavaSource source) {
        String returnFQCN = ClassNameUtils.removeCapture(type);

        final Optional<TypeScope> currentType = source.getCurrentType();
        if (currentType.isPresent()) {
            final TypeScope typeScope = currentType.get();
            if (typeScope instanceof ClassScope) {
                final ClassScope classScope = (ClassScope) typeScope;
                final Map<String, String> map = classScope.getTypeParameterMap();
                if (map.containsKey(returnFQCN)) {
                    returnFQCN = ClassNameUtils.removeCapture(map.get(returnFQCN));
                }
            }
        }

        return this.fqcnSolver.solveFQCN(returnFQCN, source);
    }
}
