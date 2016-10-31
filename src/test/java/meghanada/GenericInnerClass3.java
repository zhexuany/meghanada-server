package meghanada;

public class GenericInnerClass3 {

    public void test() {
        final ManyInnerClass<String, Long> manyInnerClass = new ManyInnerClass<>();
        manyInnerClass.key = "A";
        manyInnerClass.value = 1L;

        final ManyInnerClass<String, Long>.A<String, Long> a = manyInnerClass.createA();
        final ManyInnerClass<String, Long>.B b = manyInnerClass.createB();
        final ManyInnerClass<String, Long>.C<Integer> c = manyInnerClass.createC(new Integer(9));
        final ManyInnerClass.D<String, String> d = new ManyInnerClass.D<>("", "");
        final ManyInnerClass.E e = new ManyInnerClass.E(new Exception("E"));

        final String ak = a.key;
        final Long av = a.value;

        final Exception bex = b.ex;
        final String bk = b.getK();

        final String ck = c.key;
        final Long cv = c.value;
        final Integer ct = c.t;

        final String dk = d.key;
        final String dv = d.value;

        final Exception eex = e.ex;

    }

}
