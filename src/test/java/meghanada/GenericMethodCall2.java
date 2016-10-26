package meghanada;

public class GenericMethodCall2 {

    public void test1() {
        Class[] params1 = null;
        Class[] params2 = null;
        TargetClass.subsumes(params1, params2);
    }

    public void test2() {
        String name = "";
        Compiler.munge(name);
    }
}
