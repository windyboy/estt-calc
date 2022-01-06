package com.gzzn.airport

import io.micronaut.context.ApplicationContext
import io.micronaut.context.env.Environment
import io.micronaut.runtime.EmbeddedApplication
import jakarta.inject.Inject
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification


class EsttCalcSpec extends Specification {
    @Inject
    EmbeddedApplication<?> application

    @AutoCleanup
    @Shared
    ApplicationContext ctx = ApplicationContext.run()

    void 'test it works'() {
        expect:
        application.running
    }

    void 'test env is detected'() {
//        given:
//        ApplicationContext ctx = ApplicationContext.run()

        expect:
        ctx.environment.getActiveNames().contains(Environment.TEST)

//        cleanup:
//        ctx.close()
    }
}