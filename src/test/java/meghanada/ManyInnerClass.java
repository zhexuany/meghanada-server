package meghanada;

public class ManyInnerClass<K, V> {

    K key;
    V value;

    @Override
    public int hashCode() {
        A<K, V> a = new A<K, V>(key, value);
        return a.key.hashCode() ^ a.value.hashCode();
    }

    public A<K, V> createA() {
        return new A<>(key, value);
    }

    public B createB() {
        return new B(new Exception("E"));
    }

    public <T> C createC(T t) {
        return new C(key, value, t);
    }

    @Override
    public boolean equals(Object o) {
        return true;
    }

    static class D<T, R> {
        T key;
        R value;

        public D(T key, R value) {
            this.key = key;
            this.value = value;
        }
    }

    static class E {
        Exception ex;

        public E(Exception ex) {
            this.ex = ex;
        }

    }

    class A<K, V> {
        K key;
        V value;

        public A(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    class B {
        Exception ex;
        // return parent class V
        V value;

        public B(Exception ex) {
            this.ex = ex;
        }

        public K getK() {
            // return parent class K
            return key;
        }

        public <T> T getT(T t) {
            return t;
        }

    }

    class C<T> {
        K key;
        V value;
        T t;

        public C(K key, V value, T t) {
            this.key = key;
            this.value = value;
            this.t = t;
        }
    }

}
