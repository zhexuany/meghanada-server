package meghanada;

import java.util.ArrayList;
import java.util.List;

public class Gen4 extends GenericInnerClass1<Long> {

    public Gen4(String name, Long value) {
        super(name, value);
        List<Long> longList = new ArrayList<>();
        longList.add(0, 10L);
    }

    public void sameNameVar(Gen4 gen4) {
        final float v = gen4.value.floatValue();

        if (v > 0) {
            Long s = 0L;
        } else {
            String s = "";
            if (s.isEmpty()) {
                s.toString();
            }
        }
    }

    public List sameNameVar(Gen4 gen4, boolean b) {
        return null;
    }

    public ArrayList sameNameVar(Gen4 gen4, int b) {
        return null;
    }

    public void doReceive(Gen4 gen4) {
        boolean a = false;
        // return List
        sameNameVar(gen4, a);
    }


}
