package meghanada;

import java.util.Map;

public class GenericInnerClass1<V> {

    public Map.Entry me1;
    public Map.Entry<String, V> me2;
    public String name;
    public V value;

    public GenericInnerClass1(String name, V value) {
        this.name = name;
        this.value = value;
        GenericInnerClass1.Entry<Integer> entry = new GenericInnerClass1.Entry<>(1);
        final Integer integer = entry.value;
    }

    static class Entry<V> {
        public V value;

        public Entry(V v) {
            this.value = value;
        }
    }
}
