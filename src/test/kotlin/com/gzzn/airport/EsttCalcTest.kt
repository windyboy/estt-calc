package com.gzzn.airport

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.micronaut.runtime.EmbeddedApplication
import io.micronaut.test.extensions.kotest5.annotation.MicronautTest
import jakarta.inject.Inject

@MicronautTest(environments = ["test"])
class EsttCalcTest : DescribeSpec() {

    @Inject
    lateinit var application: EmbeddedApplication<*>

    init {
        xdescribe("Application - disabled") {
            it("should be running") {
                application.isRunning.shouldBeTrue()
            }
        }
    }
}

