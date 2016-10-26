package meghanada.utils;

import com.google.common.base.MoreObjects;
import com.google.common.collect.BiMap;
import meghanada.reflect.asm.CachedASMReflector;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ClassName {

    private static final Logger log = LogManager.getLogger(ClassName.class);

    private final String rawName;
    private final int[] typeIndex;
    private final int[] typeLastIndex;
    private final int[] arrayIndex;
    private String[] classes;

    public ClassName(String name) {
        this.rawName = ClassNameUtils.vaArgsToArray(name);
        this.classes = StringUtils.split(this.rawName, ClassNameUtils.INNER_MARK);

        final int length = classes.length;
        this.typeIndex = new int[length];
        this.typeLastIndex = new int[length];
        this.arrayIndex = new int[length];

        for (int i = 0; i < length; i++) {
            final String aClass = classes[i];
            this.typeIndex[i] = aClass.indexOf("<");
            this.typeLastIndex[i] = aClass.lastIndexOf(">");
            this.arrayIndex[i] = aClass.indexOf("[");
        }

    }

    public boolean hasTypeParameter() {
        for (int i : this.typeIndex) {
            if (i > 0) {
                return true;
            }
        }
        return false;
    }

    public String toFQCN(String ownPkg, BiMap<String, String> classes) {

        CachedASMReflector cachedASMReflector = CachedASMReflector.getInstance();
        final String name = this.getName();

        if (cachedASMReflector.containsFQCN(name)) {
            return rawName;
        }

        if (classes != null && classes.containsKey(name)) {
            return this.replaceClassName(classes.get(name));
        }

        {
            if (ownPkg != null) {
                final String result = cachedASMReflector.classNameToFQCN(ownPkg + '.' + name);
                if (result != null) {
                    return this.replaceClassName(result);
                }
            }
        }

        // full search
        final String result = cachedASMReflector.classNameToFQCN(name);
        if (result != null) {
            return this.replaceClassName(result);
        }
        return null;
    }

    public String getName() {
        final int length = this.classes.length;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            final String aClass = this.classes[i];

            String name = ClassNameUtils.removeCapture(aClass);
            if (typeIndex[i] >= 0) {
                String fst = name.substring(0, typeIndex[i]);
                String sec = name.substring(typeLastIndex[i] + 1, name.length());
                name = fst + sec;
            }
            final int ai = name.lastIndexOf("[");
            if (ai > 0) {
                name = name.substring(0, ai);
            }
            sb.append(name);
            if (i != length - 1) {
                sb.append(ClassNameUtils.INNER_MARK);
            }
        }
        return sb.toString();
    }

    public String replaceClassName(final String name) {
        final String[] tmpClasses = StringUtils.split(name, ClassNameUtils.INNER_MARK);
        final int length = tmpClasses.length;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            final String tmpClass = tmpClasses[i];
            if (this.typeIndex[i] >= 0) {
                final String aClass = this.classes[i];
                final String s = tmpClass + aClass.substring(this.typeIndex[i]);
                sb.append(s);
            } else if (arrayIndex[i] >= 0) {
                final String aClass = this.classes[i];
                final String s = tmpClass + aClass.substring(this.arrayIndex[i]);
                sb.append(s);
            } else {
                sb.append(tmpClass);
            }
            if (i != length - 1) {
                sb.append(ClassNameUtils.INNER_MARK);
            }
        }
        return sb.toString();

//        //TODO more pattern
//        final int inner = name.indexOf("$");
//        if (inner >= 0) {
//            final String fst = name.substring(0, inner);
//            if (typeIndex >= 0) {
//                return fst + this.rawName.substring(typeIndex, typeLastIndex + 1) + name.substring(inner);
//            }
//            return name;
//        }
//
//        if (typeIndex >= 0) {
//            return name + this.rawName.substring(typeIndex);
//        }
//        if (arrayIndex >= 0) {
//            return name + this.rawName.substring(arrayIndex);
//        }
//        return name;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("rawName", rawName)
                .toString();
    }
}
