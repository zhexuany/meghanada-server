package meghanada.parser.source;

import com.esotericsoftware.kryo.DefaultSerializer;
import com.github.javaparser.Range;
import com.google.common.base.MoreObjects;
import meghanada.reflect.MethodParameter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@DefaultSerializer(MethodScopeSerializer.class)
public class MethodScope extends BlockScope {

    private static Logger log = LogManager.getLogger(MethodScope.class);
    final Range nameRange;
    Map<String, String> typeParameterMap;
    List<MethodParameter> parameters = new ArrayList<>(4);

    public MethodScope(final String name, final Range range, final Range nameRange) {
        super(name, range);
        this.nameRange = nameRange;
    }

    public MethodScope startBlock(final String name, final Range range, final Range nameRange, final Map<String, String> typeParameterMap) {
        // add method
        MethodScope scope = new MethodScope(name, range, nameRange);
        scope.typeParameterMap = typeParameterMap;
        super.startBlock(scope);
        return scope;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("scope", name)
                .add("nameRange", nameRange)
                .toString();
    }

    public Range getNameRange() {
        return nameRange;
    }

    public Map<String, String> getTypeParameterMap() {
        return typeParameterMap;
    }

    public List<MethodParameter> getParameters() {
        return parameters;
    }
}
