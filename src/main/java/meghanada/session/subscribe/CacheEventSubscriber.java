package meghanada.session.subscribe;

import com.google.common.base.Stopwatch;
import com.google.common.eventbus.Subscribe;
import meghanada.analyze.CompileResult;
import meghanada.project.Project;
import meghanada.reflect.asm.CachedASMReflector;
import meghanada.session.Session;
import meghanada.session.SessionEventBus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

import static meghanada.config.Config.timeItF;

public class CacheEventSubscriber extends AbstractSubscriber {

    private static Logger log = LogManager.getLogger(CacheEventSubscriber.class);

    public CacheEventSubscriber(final SessionEventBus sessionEventBus) {
        super(sessionEventBus);
        log.debug("subscribe cache");
    }

    @Subscribe
    public synchronized void on(final SessionEventBus.ClassCacheRequest request) throws IOException {
        if (request.onlyOutputDir) {
            final CachedASMReflector reflector = CachedASMReflector.getInstance();
            reflector.createClassIndexFromDir();
        } else {
            this.createFullIndex();
        }
    }

    private void createFullIndex() {
        final Session session = super.sessionEventBus.getSession();
        final Project project = session.getCurrentProject();
        final CachedASMReflector reflector = CachedASMReflector.getInstance();

        boolean result = timeItF("project analyze and compile elapsed:{}", () -> {
            try {
                final CompileResult compileResult = project.compileJava(false, true);
                if (!compileResult.isSuccess()) {
                    log.warn("Compile Error : {}", compileResult.getDiagnosticsSummary());
                }

                final CompileResult testCompileResult = project.compileTestJava(false, true);
                if (!testCompileResult.isSuccess()) {
                    log.warn("Test Compile Error : {}", testCompileResult.getDiagnosticsSummary());
                }

            } catch (Exception e) {
                log.catching(e);
            }
            return true;
        });

        final Stopwatch stopwatch = Stopwatch.createStarted();
        reflector.addClasspath(session.getDependentJars());
        reflector.addClasspath(project.getOutputDirectory());
        reflector.addClasspath(project.getTestOutputDirectory());
        for (final Project dependency : project.getDependencyProjects()) {
            reflector.addClasspath(dependency.getOutputDirectory());
            reflector.addClasspath(dependency.getTestOutputDirectory());
        }
        reflector.createClassIndexes();
        log.info("class index size:{} elapsed:{}", reflector.getGlobalClassIndex().size(), stopwatch.stop());
    }
}
