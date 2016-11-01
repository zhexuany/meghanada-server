package meghanada;

public class HasInnerClass<K, V> {

    private A<K, V> a;

    public A<K, V> createA(K k, V v) {
        return new A<>(k, v);
    }

    class A<K, V> {
        K key;
        V value;

        public A(K k, V v) {
            this.key = k;
            this.value = v;
        }
    }

}
