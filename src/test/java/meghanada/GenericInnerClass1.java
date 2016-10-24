package meghanada;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GenericInnerClass1<V> {

    public Map.Entry me1;
    public Map.Entry<String, V> me2;
    public String name;
    public V value;

    public GenericInnerClass1(String name, V value) {
        this.name = name;
        this.value = value;
        GenericInnerClass1.Entry<Integer> entry1 = new GenericInnerClass1.Entry<>(1);
        final Integer integer1 = entry1.value;
        Entry<String> entry2 = new Entry<>("TEST");
        final String value1 = entry2.value;

        Entry<List<String>> entry3 = new Entry<>(new ArrayList<>());
        final List<String> value2 = entry3.value;
    }

    static class Entry<V> {
        public V value;

        public Entry(V v) {
            this.value = value;
        }
    }
}
