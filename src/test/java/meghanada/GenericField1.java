package meghanada;

import java.io.IOException;
import java.util.List;

public class GenericField1<K extends String, V> extends SuperClass {

    public java.util.List[] list;
    public List<List<? extends V>> values;
    public String name;
    public K key;
    public V value;
    public List[] valuesArray;

    public GenericField1(String name, K key, V value) throws IOException {
        this.name = name;
        this.key = key;
        this.value = value;
    }

    public List<List<? extends V>> getValues() {
        return values;
    }
}