package meghanada.reflect.asm;

import com.google.common.base.Joiner;
import meghanada.utils.ClassNameUtils;

import java.util.List;

class TypeInfo {

    private final String fqcn;
    String name;
    List<TypeInfo> typeParameters;
    boolean isArray;
    boolean variableArguments;
    String paramName;
    //TypeInfo innerClass;
    private TypeInfo parent;
    private boolean isInner;

    TypeInfo(final String name, final String fqcn) {
        this.name = name;
        this.fqcn = fqcn;
        this.isArray = false;
        this.variableArguments = false;
    }

    TypeInfo(final String name, final String fqcn, final TypeInfo parent) {
        this(name, fqcn);
        this.parent = parent;
        this.isInner = true;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        if (this.isInner) {
            sb.append(this.parent);
            sb.append(ClassNameUtils.INNER_MARK);
        }

        sb.append(name);

        if (this.typeParameters != null && this.typeParameters.size() > 0) {
            sb.append("<");
            Joiner.on(", ").appendTo(sb, this.typeParameters).append(">");
        }
        if (isArray) {
            sb.append("[]");
        }
        if (variableArguments) {
            sb.append("...");
        }
        if (paramName != null) {
            sb.append(" ").append(paramName);
        }

        return sb.toString();
    }

    String getFQCN() {
        final StringBuilder sb = new StringBuilder();
        if (this.isInner) {
            sb.append(this.parent.getFQCN());
            sb.append(ClassNameUtils.INNER_MARK);
        }

        sb.append(fqcn);
        if (this.typeParameters != null && this.typeParameters.size() > 0) {
            sb.append("<");
            Joiner.on(", ").appendTo(sb, this.typeParameters).append(">");
        }
        if (isArray) {
            sb.append("[]");
        }
        return sb.toString();
    }
}
