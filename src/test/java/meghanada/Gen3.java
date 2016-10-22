package meghanada;

import java.util.ArrayList;
import java.util.List;

public class Gen3<T> {

    public T toString(T str) {
        return str;
    }

    public T[] toString(T[] str) {
        return str;
    }

    public String[] toString(String[] str) {
        return str;
    }

    public <K, V> List<? extends K> test(K k, V v) {
        return new ArrayList<K>(8);
    }
}
