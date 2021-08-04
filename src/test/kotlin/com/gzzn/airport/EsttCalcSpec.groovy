package com.gzzn.airport

import io.micronaut.runtime.EmbeddedApplication
import spock.lang.Specification

import javax.inject.Inject


class EsttCalcSpec extends Specification {
    @Inject
    EmbeddedApplication<?> application

    void 'test it works'() {
        expect:
        application.running
    }
}