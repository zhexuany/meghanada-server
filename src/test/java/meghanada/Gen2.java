package meghanada;

import java.util.Map;

public class Gen2<V> {

    public String name;
    public V value;
    public Map.Entry me;

    public Gen2(String name, V value) {
        this.name = name;
        this.value = value;
        Gen2.Entry<Integer> entry = new Gen2.Entry<>(1);
        final Integer integer = entry.value;
    }

    static class Entry<V> {
        public V value;

        public Entry(V v) {
            this.value = value;
        }
    }
}
