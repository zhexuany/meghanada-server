package meghanada;

public class GenericInnerClass2 {

    public static class ABC {

        public void test() {
            DEF def = null;
            def.innerClass1 = new GenericInnerClass1("", null);
        }

        public static class DEF {

            GenericInnerClass1 innerClass1;

        }
    }

}
