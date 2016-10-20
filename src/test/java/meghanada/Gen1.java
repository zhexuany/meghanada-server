package meghanada;

import java.io.IOException;
import java.util.List;

public class Gen1<K extends String, V> {

    public String name;
    public K key;
    public V value;
    public List<List<? extends V>> values;
    public List[] valuesArray;

    public Gen1(String name, K key, V value) throws IOException {
        this.name = name;
        this.key = key;
        this.value = value;
    }

}
