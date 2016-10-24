package meghanada;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class GenericMethod1<T> {

    public T toString(T str) throws NullPointerException {
        return str;
    }

    public T[] toString(T[] str) throws IOException {
        return str;
    }

    public String[] toString(String[] str) {
        return str;
    }

    public <K, V> List<? extends K> test(K k, V v) {
        return new ArrayList<K>(8);
    }
}
