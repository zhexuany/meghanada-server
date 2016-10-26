package meghanada;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.EntryMessage;

import java.util.HashMap;
import java.util.Map;

public class GenericMethodCall1 {
    private static Logger log = LogManager.getLogger(GenericMethodCall1.class);

    public void test1(String name) {
        final EntryMessage entryMessage = log.traceEntry("className={}", name);
        Map<String, String> map = new HashMap<>();
        if (map.containsKey(name)) {
            final String s = map.get(name).concat("-A");
            System.out.println(s);
        }
        final String s = log.traceExit(entryMessage, "");
        System.out.println(s);
    }
}
